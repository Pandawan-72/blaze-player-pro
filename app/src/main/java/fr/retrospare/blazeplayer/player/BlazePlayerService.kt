package fr.retrospare.blazeplayer.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
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

        // Cache disque partagé (avec la vidéo) pour les flux réseau (SMB) : Media3 route
        // automatiquement les schémas connus (file/content/asset) vers leurs DataSource dédiées et
        // ne passe par notre factory "base" que pour smb:// — donc seul l'audio réseau est mis en
        // cache, la lecture locale (MediaStore) n'est pas affectée.
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(MediaCacheManager.getCache(this))
            .setUpstreamDataSourceFactory(SmbDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSourceFactory = DefaultDataSource.Factory(this, cacheDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
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
        player = exoPlayer

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("openBlazeAudio", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(openIntent)
            .setCallback(SessionCallback())
            .build()
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
     * Best practice Media3 : si l'utilisateur swipe l'app hors des tâches récentes alors qu'il n'y
     * a rien en lecture (ou pas de média chargé), on arrête le service au lieu de laisser un
     * service/notification fantôme tourner indéfiniment.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || p.mediaItemCount == 0 || !p.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }
}
