package fr.retrospare.blazeplayer.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Service de lecture AUDIO basé sur [MediaSessionService] : c'est l'unique source de vérité pour
 * la lecture audio de l'application. Toute UI (AudioPlayerFragment, MiniPlayer, notification
 * système, Android Auto...) doit communiquer avec le player exclusivement via un
 * [androidx.media3.session.MediaController] connecté à ce service.
 *
 * On évite délibérément toute référence statique directe au player ou au service (pattern
 * `companion object { var instance }`) : c'est un anti-pattern Media3 qui casse l'encapsulation
 * de la session et ne fonctionne pas si le contrôleur tourne dans un autre process. Le seul état
 * interne qui n'est pas exposé par l'API [androidx.media3.common.Player] standard (l'audioSessionId,
 * nécessaire pour brancher l'égaliseur système) est exposé via une commande de session personnalisée.
 *
 * La notification média (lockscreen, barre de notif, contrôles Bluetooth/casque) est entièrement
 * gérée automatiquement par [MediaSessionService] via son [androidx.media3.session.MediaNotification.Provider]
 * par défaut — il ne faut surtout pas la dupliquer manuellement avec une notification "maison".
 */
@UnstableApi
class BlazePlayerService : MediaSessionService() {

    companion object {
        /** Commande de session permettant à un [androidx.media3.session.MediaController] de
         *  récupérer l'audioSessionId courant du player, utilisé pour brancher l'égaliseur système
         *  (android.media.audiofx). Non exposé par l'API Player standard. */
        const val COMMAND_GET_AUDIO_SESSION_ID = "fr.retrospare.blazeplayer.GET_AUDIO_SESSION_ID"
        const val EXTRA_AUDIO_SESSION_ID = "audioSessionId"

        // ID de notification et channel dédiés : Media3 utilise le même ID par défaut pour tous
        // les MediaSessionService de l'app si non personnalisé, ce qui faisait que la notification
        // vidéo écrasait purement et simplement la notification audio (même slot de notification).
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "blaze_audio_channel"
    }

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var sessionPlayer: Player? = null
    private var eqManager: EqualizerManager? = null
    private val eqApplyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())


    override fun onCreate() {
        super.onCreate()

        // Notification/channel dédiés à l'audio, distincts de ceux de la vidéo (cf. constantes
        // ci-dessus) pour que les deux notifications coexistent sans se remplacer l'une l'autre.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(fr.retrospare.blazeplayer.R.string.notif_channel_audio)
                .build()
        )

        // Niveau 1 anti-ANR : ne pas initialiser SimpleCache dans onCreate(). SimpleCache peut
        // scanner/verrouiller le cache disque sur le thread principal au démarrage du service.
        // Pour la fiabilité debug, on privilégie un DataSource direct ; le cache pourra revenir plus
        // tard avec une initialisation lazy hors thread principal.
        val dataSourceFactory = DefaultDataSource.Factory(this, SmbDataSource.Factory())
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        // Lecture audio réseau : on privilégie la stabilité sur SMB/Wi-Fi plutôt qu'un démarrage
        // ultra agressif. Les valeurs ci-dessous gardent un démarrage raisonnable, mais exigent
        // un tampon plus confortable après rebuffer afin d'éviter les micro-coupures et les arrêts
        // silencieux sur NAS/Wi-Fi instables.
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 64 * 1024))
            .setBufferDurationsMs(
                /* minBufferMs = */ 45_000,
                /* maxBufferMs = */ 180_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 10_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15_000, true)
            .build()

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            // Garde le Wi-Fi actif pendant la lecture réseau (SMB) en arrière-plan/écran éteint.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        restoreEqualizerForPlayer(exoPlayer)
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                fr.retrospare.blazeplayer.debug.CrashReporter.log(
                    applicationContext,
                    "Audio player error: code=${error.errorCodeName}",
                    error
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                    android.util.Log.i("BlazePlayerService", "Audio buffering…")
                }
            }
        })
        player = exoPlayer

        // Isolation stricte audio/vidéo : la MediaSession audio expose UNIQUEMENT ExoPlayer local.
        // Aucun composant Chromecast côté audio : le lecteur audio et le
        // mini-player ne peuvent plus récupérer ni piloter un média vidéo casté.
        val audioSessionPlayer: Player = exoPlayer
        sessionPlayer = audioSessionPlayer

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("openBlazeAudio", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        audioSessionPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                persistAudioQueue()
            }

            override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                if (events.contains(androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(androidx.media3.common.Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                    events.contains(androidx.media3.common.Player.EVENT_REPEAT_MODE_CHANGED) ||
                    events.contains(androidx.media3.common.Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                    persistAudioQueue()
                }
            }
        })

        mediaSession = MediaSession.Builder(this, audioSessionPlayer)
            .setId("BlazeAudio")
            .setSessionActivity(openIntent)
            .setCallback(SessionCallback())
            .build()
    }

    private fun restoreEqualizerForPlayer(exoPlayer: ExoPlayer, attempt: Int = 0) {
        val sessionId = exoPlayer.audioSessionId
        if (sessionId <= 0 && attempt < 10) {
            eqApplyHandler.postDelayed({ restoreEqualizerForPlayer(exoPlayer, attempt + 1) }, 250L)
            return
        }
        if (sessionId <= 0) return
        try { eqManager?.release() } catch (_: Exception) {}
        eqManager = try {
            EqualizerManager(sessionId, applicationContext).also { it.restoreLastSession() }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Restore equalizer in audio service failed", e)
            null
        }
    }


    private fun isObsoleteLocalRelayUrl(value: String): Boolean =
        value.startsWith("http://") && value.contains(":8928/")

    private fun originalPathFromItem(mi: androidx.media3.common.MediaItem): String {
        val extras = mi.mediaMetadata.extras
        val fromExtras = extras?.getString("blaze_original_path")
            ?.takeIf { it.isNotBlank() && !isObsoleteLocalRelayUrl(it) && AudioRepository.isAudioExtension(it) }
        if (fromExtras != null) return fromExtras
        val fromMediaId = mi.mediaId.takeIf { it.isNotBlank() && !isObsoleteLocalRelayUrl(it) && AudioRepository.isAudioExtension(it) }
        if (fromMediaId != null) return fromMediaId
        return mi.localConfiguration?.uri?.toString()
            ?.takeIf { it.isNotBlank() && !isObsoleteLocalRelayUrl(it) && AudioRepository.isAudioExtension(it) }
            .orEmpty()
    }

    private fun persistAudioQueue() {
        val p = sessionPlayer ?: player ?: return
        try {
            if (p.mediaItemCount <= 0) return
            val items = (0 until p.mediaItemCount).map { i ->
                val mi = p.getMediaItemAt(i)
                val path = originalPathFromItem(mi)
                val name = mi.mediaMetadata.title?.toString()?.ifBlank { null }
                    ?: mi.localConfiguration?.uri?.lastPathSegment
                    ?: path.substringAfterLast('/')
                PlaylistItem(path, name)
            }.filter { it.path.isNotBlank() && !isObsoleteLocalRelayUrl(it.path) && AudioRepository.isAudioExtension(it.path) }
            if (items.isNotEmpty()) {
                AudioRepository.save(
                    applicationContext,
                    items,
                    p.currentMediaItemIndex.coerceAtLeast(0),
                    p.currentPosition.coerceAtLeast(0L),
                    p.repeatMode,
                    p.shuffleModeEnabled
                )
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Persist audio queue failed", e)
        }
    }

    private inner class SessionCallback : MediaSession.Callback {

        // Balayer la notification déclenche automatiquement COMMAND_STOP côté Media3 (géré en
        // interne par DefaultMediaNotificationProvider), mais ça n'arrête PAS le service lui-même
        // (limitation connue de Media3) : la lecture pouvait donc continuer en arrière-plan alors
        // que la notification avait disparu. On intercepte précisément COMMAND_STOP ici plutôt que
        // de deviner via les transitions d'état du player (IDLE arrive aussi normalement pendant
        // le chargement, ce qui coupait le service à tort).
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            if (playerCommand == androidx.media3.common.Player.COMMAND_STOP) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { stopSelf() }
            }
            return SessionResult.RESULT_SUCCESS
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(COMMAND_GET_AUDIO_SESSION_ID, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(
                sessionCommands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_GET_AUDIO_SESSION_ID) {
                val resultExtras = Bundle().apply {
                    putInt(EXTRA_AUDIO_SESSION_ID, player?.audioSessionId ?: 0)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, resultExtras))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Comportement attendu : bouton Home = lecture audio en arrière-plan avec notification ;
     * swipe de l'app dans les tâches récentes = fermeture explicite, donc arrêt immédiat de la
     * lecture et suppression de la notification Media3.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        persistAudioQueue()
        try {
            sessionPlayer?.stop()
            sessionPlayer?.clearMediaItems()
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Audio onTaskRemoved stop failed", e)
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistAudioQueue()
        try { eqApplyHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { mainHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { mediaSession?.release() } catch (_: Exception) {}
        try { eqManager?.release() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        mediaSession = null
        eqManager = null
        sessionPlayer = null
        player = null
        super.onDestroy()
    }
}
