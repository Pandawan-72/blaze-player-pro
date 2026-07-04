package fr.retrospare.blazeplayer.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Equalizer
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import fr.retrospare.blazeplayer.cast.BlazeCastMediaItemConverter

/**
 * Service de lecture VIDEO basé sur [MediaSessionService], suivant le pattern officiel Media3
 * 1.9.0 pour Cast : https://developer.android.com/media/media3/cast/create-castplayer
 *
 *   val exoPlayer = ExoPlayer.Builder(context).build()
 *   val castPlayer = CastPlayer.Builder(context).setLocalPlayer(exoPlayer).build()
 *   val session = MediaSession.Builder(context, castPlayer).build()
 *
 * CastPlayer.Builder encapsule l'ExoPlayer local et bascule TOUT SEUL entre lecture locale et
 * Chromecast (position, playlist, transitions) dès qu'un MediaItem valide lui est transmis via
 * l'API Player standard (setMediaItems/prepare/play) — c'est la seule API utilisée pour piloter
 * la lecture dans toute l'app, jamais d'accès direct à RemoteMediaClient. Le MediaItem doit être
 * valide dans les deux contextes : son URI pointe donc toujours vers notre propre relais HTTP
 * (LocalStreamServer, piloté par VideoStreamServerManager), jamais directement vers smb:// ou
 * content://, que le Chromecast ne peut de toute façon pas lire.
 */
@UnstableApi
class VideoPlaybackService : MediaSessionService() {

    companion object {
        // Distinct de BlazePlayerService (audio) pour que les deux notifications ne se marchent
        // pas dessus.
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "blaze_video_channel"

        // Commande personnalisée : met à jour la miniature du média en cours dans la
        // notification. Passe obligatoirement par le service (plutôt que d'appeler
        // replaceMediaItem() côté MediaController dans PlayerActivity) car ce dernier ne répercute
        // pas fiablement la mise à jour sur DefaultMediaNotificationProvider — un problème connu
        // de Media3 quand replaceMediaItem() n'est pas appelé directement sur l'instance qui sert
        // la session.
        const val CUSTOM_COMMAND_SET_ARTWORK = "fr.retrospare.blazeplayer.SET_ARTWORK"
        const val CUSTOM_COMMAND_SET_VIDEO_METADATA = "fr.retrospare.blazeplayer.SET_VIDEO_METADATA"
        const val EXTRA_ARTWORK_MEDIA_ID = "media_id"
        const val EXTRA_ARTWORK_DATA = "artwork_data"
        const val EXTRA_METADATA_TITLE = "title"
        const val CUSTOM_COMMAND_SET_VOLUME_BOOST = "fr.retrospare.blazeplayer.SET_VOLUME_BOOST"
        const val EXTRA_VOLUME_BOOST_PERCENT = "volume_boost_percent"
        const val EXTRA_DIALOGUE_MODE_PERCENT = "dialogue_mode_percent"
    }

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var videoPreampEqualizer: Equalizer? = null
    private var lastVolumeBoostPercent: Int = 0
    private var lastDialogueModePercent: Int = 0

    // Même modèle 10 bandes que l'equalizer audio. Le mode Dialogue vidéo projette cette
    // courbe de type preset "Vocal" sur les bandes natives disponibles du téléphone.
    private val videoDialogueFreqsHz = intArrayOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    private val videoDialogueVocalCurveMb = intArrayOf(-800, -600, -400, -200, 100, 350, 550, 450, 200, 100)

    override fun onCreate() {
        super.onCreate()

        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(fr.retrospare.blazeplayer.R.string.notif_channel_video)
                .build()
        )

        // Verrou Wi-Fi + CPU tenu pendant toute la durée de vie du service (pas seulement pendant
        // la lecture locale) : sans ça, une fois que CastPlayer bascule sur le Chromecast, plus
        // rien ne maintenait le Wi-Fi actif, et un écran verrouillé pouvait laisser le Wi-Fi
        // retomber en veille, coupant la connexion Cast en cours de route.
        try {
            val wifiManager = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            wifiLock = wifiManager?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BlazePlayer:videoWifiLock")
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()
            val powerManager = applicationContext.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "BlazePlayer:videoWakeLock")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(6 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            android.util.Log.w("VideoPlaybackService", "Failed to acquire wifi/wake lock", e)
        }

        // Le MediaItem est TOUJOURS une URL HTTP (notre propre relais local) : un DataSource HTTP
        // standard suffit dans tous les cas, plus besoin de SmbDataSource ici.
        // Le cache disque Media3 (SimpleCache) a été retiré ici : son initialisation peut
        // scanner/verrouiller le cache sur le thread principal (onCreate), ce qui provoquait un
        // ANR au clic sur une vidéo — le service vidéo démarre de façon synchrone au clic.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(240_000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 64 * 1024))
            .setBufferDurationsMs(180_000, 720_000, 8_000, 45_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(120_000, true)
            .build()

        val localPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exoPlayer = localPlayer
        localPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                recreateVideoAudioEffects(audioSessionId)
                applyVideoAudioEffects(lastVolumeBoostPercent, lastDialogueModePercent)
            }
        })

        // Pattern officiel Media3 1.9.0 : CastPlayer.Builder(local+remote). Le RemoteCastPlayer
        // reçoit un MediaItemConverter dédié qui transforme les SubtitleConfiguration en vrais
        // MediaTrack Cast. Aucun RemoteMediaClient.load() manuel dans l'app.
        val sessionPlayer: Player = try {
            val remotePlayer = RemoteCastPlayer.Builder(this)
                .setMediaItemConverter(BlazeCastMediaItemConverter())
                .build()

            val cp = CastPlayer.Builder(this)
                .setLocalPlayer(localPlayer)
                .setRemotePlayer(remotePlayer)
                .setTransferCallback { sourcePlayer, targetPlayer ->
                    androidx.media3.common.PlayerTransferState.fromPlayer(sourcePlayer)
                        .setToPlayer(targetPlayer)

                    val goingRemote = targetPlayer.deviceInfo.playbackType ==
                        androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE
                    val returningLocal = sourcePlayer.deviceInfo.playbackType ==
                        androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE && !goingRemote

                    // Quand le Cast prend la main, on retire toute Surface du décodeur local :
                    // l'ancien crash était "The surface has been released" après destruction de
                    // PlayerActivity pendant un Cast. Le CastPlayer stoppera ensuite l'inactif.
                    if (goingRemote) {
                        try { localPlayer.clearVideoSurface() } catch (_: Exception) {}
                    }

                    // Quand la session Cast se termine alors que l'activité n'a plus de Surface
                    // valide, on ne redémarre pas automatiquement le décodage local. L'utilisateur
                    // peut relancer la lecture depuis l'écran, avec une Surface fraîche.
                    if (returningLocal) {
                        try { localPlayer.clearVideoSurface() } catch (_: Exception) {}
                        targetPlayer.playWhenReady = false
                    }
                }
                .build()
            castPlayer = cp
            cp
        } catch (e: Exception) {
            android.util.Log.w("VideoPlaybackService", "CastPlayer unavailable, falling back to local ExoPlayer", e)
            localPlayer
        }

        fun buildOpenIntent(path: String?, name: String?): PendingIntent {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!path.isNullOrEmpty()) putExtra("mediaPath", path)
                if (!name.isNullOrEmpty()) putExtra("mediaName", name)
            }
            return PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setId("BlazeVideo")
            .setSessionActivity(buildOpenIntent(null, null))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val defaultResult = super.onConnect(session, controller)
                    val sessionCommands = defaultResult.availableSessionCommands.buildUpon()
                        .add(SessionCommand(CUSTOM_COMMAND_SET_ARTWORK, Bundle.EMPTY))
                        .add(SessionCommand(CUSTOM_COMMAND_SET_VIDEO_METADATA, Bundle.EMPTY))
                        .add(SessionCommand(CUSTOM_COMMAND_SET_VOLUME_BOOST, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.accept(sessionCommands, defaultResult.availablePlayerCommands)
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == CUSTOM_COMMAND_SET_VOLUME_BOOST) {
                        applyVideoAudioEffects(args.getInt(EXTRA_VOLUME_BOOST_PERCENT, 0), args.getInt(EXTRA_DIALOGUE_MODE_PERCENT, lastDialogueModePercent))
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (customCommand.customAction == CUSTOM_COMMAND_SET_VIDEO_METADATA) {
                        val mediaId = args.getString(EXTRA_ARTWORK_MEDIA_ID)
                        val title = args.getString(EXTRA_METADATA_TITLE).orEmpty()
                        val artworkData = args.getByteArray(EXTRA_ARTWORK_DATA)
                        val current = session.player.currentMediaItem
                        if (current != null && current.mediaId == mediaId) {
                            val metaBuilder = current.mediaMetadata.buildUpon()
                            if (title.isNotBlank()) metaBuilder.setTitle(title)
                            if (artworkData != null) {
                                try { fr.retrospare.blazeplayer.ui.ThumbnailUtils.cacheArtworkData(applicationContext, mediaId, artworkData) } catch (_: Exception) {}
                                metaBuilder.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            }
                            val updated = current.buildUpon().setMediaMetadata(metaBuilder.build()).build()
                            session.player.replaceMediaItem(session.player.currentMediaItemIndex, updated)
                            mediaSession?.setSessionActivity(buildOpenIntent(mediaId, title.ifBlank { current.mediaMetadata.title?.toString() }))
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (customCommand.customAction == CUSTOM_COMMAND_SET_ARTWORK) {
                        val mediaId = args.getString(EXTRA_ARTWORK_MEDIA_ID)
                        val artworkData = args.getByteArray(EXTRA_ARTWORK_DATA)
                        val current = session.player.currentMediaItem
                        if (current != null && artworkData != null && current.mediaId == mediaId) {
                            try { fr.retrospare.blazeplayer.ui.ThumbnailUtils.cacheArtworkData(applicationContext, mediaId, artworkData) } catch (_: Exception) {}
                            val updated = current.buildUpon()
                                .setMediaMetadata(
                                    current.mediaMetadata.buildUpon()
                                        .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                        .build()
                                )
                                .build()
                            // Appelé directement sur session.player (l'instance qui sert
                            // effectivement la session), pas via le MediaController appelant —
                            // c'est ce qui garantit que DefaultMediaNotificationProvider reflète
                            // bien le changement.
                            session.player.replaceMediaItem(session.player.currentMediaItemIndex, updated)
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }

                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int {
                    if (playerCommand == Player.COMMAND_STOP) {
                        // Ne coupe plus le service vidéo depuis une commande STOP externe. Sur certains
                        // flux réseau 4K, Media3/Android peut émettre STOP après une coupure de source
                        // sans vraie action utilisateur, ce qui supprimait la notification et laissait
                        // PlayerActivity revenir à l'accueil. Le bouton Stop in-app garde son chemin
                        // explicite via PlayerActivity.stopVideoPlaybackFromUi().
                        android.util.Log.w("VideoPlaybackService", "Ignoring external video STOP command to keep network playback recoverable")
                        return SessionResult.RESULT_SUCCESS
                    }
                    return SessionResult.RESULT_SUCCESS
                }
            })
            .build()

        sessionPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                fr.retrospare.blazeplayer.debug.CrashReporter.log(
                    applicationContext,
                    "VideoPlaybackService player error " + androidx.media3.common.PlaybackException.getErrorCodeName(error.errorCode) +
                        " remote=" + (sessionPlayer.deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE),
                    error
                )
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val path = mediaItem?.mediaId
                val name = mediaItem?.mediaMetadata?.title?.toString()
                mediaSession?.setSessionActivity(buildOpenIntent(path, name))
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                recreateVideoAudioEffects(audioSessionId)
                applyVideoAudioEffects(lastVolumeBoostPercent, lastDialogueModePercent)
            }
        })
    }

    fun getExoPlayer(): ExoPlayer? = exoPlayer
    fun getCastPlayer(): CastPlayer? = castPlayer
    fun getMediaSession(): MediaSession? = mediaSession

    private fun recreateVideoAudioEffects(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) return
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        try { videoPreampEqualizer?.release() } catch (_: Exception) {}
        loudnessEnhancer = try {
            LoudnessEnhancer(audioSessionId).apply { enabled = false }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Video loudness enhancer unavailable", e)
            null
        }
        videoPreampEqualizer = try {
            Equalizer(0, audioSessionId).apply { enabled = false }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Video preamp equalizer unavailable", e)
            null
        }
    }

    private fun applyVideoAudioEffects(volumeBoostPercent: Int, dialogueModePercent: Int) {
        lastVolumeBoostPercent = volumeBoostPercent.coerceIn(0, 20)
        lastDialogueModePercent = dialogueModePercent.coerceIn(0, 100)
        val player = exoPlayer ?: return
        if ((loudnessEnhancer == null || videoPreampEqualizer == null) && player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            recreateVideoAudioEffects(player.audioSessionId)
        }

        // Même base que le préampli audio : un gain logiciel uniforme sur l'Equalizer de la
        // session vidéo. Le mode Dialogue ajoute maintenant une vraie courbe 10 bandes inspirée
        // du préréglage Vocal : graves fortement atténués, zone 1-4 kHz mise en avant, aigus
        // gardés mais sans agressivité. La courbe est interpolée sur les bandes natives Android
        // disponibles, exactement comme l'equalizer audio 10 bandes le fait déjà.
        val preampMillibels = (lastVolumeBoostPercent * 50).coerceIn(0, 1000)
        val dialogue = lastDialogueModePercent / 100f
        try {
            videoPreampEqualizer?.let { eq ->
                val range = eq.bandLevelRange
                val min = range.getOrNull(0)?.toInt() ?: -1500
                val max = range.getOrNull(1)?.toInt() ?: 1500
                val nativeBands = eq.numberOfBands.toInt()
                for (band in 0 until nativeBands) {
                    val centerHz = try { eq.getCenterFreq(band.toShort()) / 1000 } catch (_: Exception) { 1000 }
                    val dialogueGain = (dialogueCurveForNativeBand(centerHz, band, nativeBands) * dialogue).toInt()

                    // Petite compression statique façon "vocal preset" : quand le mode dialogue
                    // est actif, les boosts positifs sont moins amplifiés par le préampli afin de
                    // ne pas remettre les explosions/bruits forts devant les voix.
                    val protectedPreamp = if (dialogue > 0f && dialogueGain > 0) {
                        (preampMillibels * (1f - 0.35f * dialogue)).toInt()
                    } else {
                        preampMillibels
                    }
                    val level = (protectedPreamp + dialogueGain).coerceIn(min, max).toShort()
                    try { eq.setBandLevel(band.toShort(), level) } catch (_: Exception) {}
                }
                eq.enabled = preampMillibels != 0 || lastDialogueModePercent != 0
            }

            // Le boost volume reste indépendant, comme le préampli audio. Le mode Dialogue ne
            // pousse pas LoudnessEnhancer : il travaille surtout par égalisation pour clarifier les
            // voix sans augmenter les pics sonores.
            loudnessEnhancer?.setTargetGain(preampMillibels)
            loudnessEnhancer?.enabled = preampMillibels > 0
            player.volume = 1f
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Video audio effects failed", e)
        }
    }


    private fun dialogueCurveForNativeBand(centerHz: Int, nativeIndex: Int, nativeBandCount: Int): Int {
        if (centerHz <= 0) {
            val mapped = ((nativeIndex * videoDialogueVocalCurveMb.size) / nativeBandCount).coerceIn(0, videoDialogueVocalCurveMb.lastIndex)
            return videoDialogueVocalCurveMb[mapped]
        }
        var first = 0
        var second = 0
        var firstDistance = Int.MAX_VALUE
        var secondDistance = Int.MAX_VALUE
        for (i in videoDialogueFreqsHz.indices) {
            val distance = kotlin.math.abs(videoDialogueFreqsHz[i] - centerHz)
            if (distance < firstDistance) {
                second = first
                secondDistance = firstDistance
                first = i
                firstDistance = distance
            } else if (distance < secondDistance) {
                second = i
                secondDistance = distance
            }
        }
        return (videoDialogueVocalCurveMb[first] * 0.7f + videoDialogueVocalCurveMb[second] * 0.3f).toInt()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun stopPlaybackAndSelf(reason: String) {
        try {
            mediaSession?.player?.pause()
            mediaSession?.player?.stop()
            mediaSession?.player?.clearMediaItems()
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Video $reason session stop failed", e)
        }
        try { castPlayer?.stop() } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Video $reason cast stop failed", e)
        }
        try {
            exoPlayer?.clearVideoSurface()
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Video $reason exo stop failed", e)
        }
        try { fr.retrospare.blazeplayer.cast.VideoStreamServerManager.stopServer() } catch (_: Exception) {}
        stopSelf()
    }

    /**
     * Home / retour launcher : l'activité passe en arrière-plan ou en PiP et le service continue.
     * Swipe de l'app depuis les tâches récentes : fermeture explicite, donc on coupe aussi bien la
     * lecture locale que Cast et on laisse MediaSessionService retirer sa notification.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlaybackAndSelf("onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        try { videoPreampEqualizer?.release() } catch (_: Exception) {}
        loudnessEnhancer = null
        videoPreampEqualizer = null
        try { wifiLock?.release() } catch (e: Exception) {}
        try { wakeLock?.release() } catch (e: Exception) {}
        wifiLock = null
        wakeLock = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        try { exoPlayer?.clearVideoSurface() } catch (_: Exception) {}
        exoPlayer = null
        castPlayer = null
        super.onDestroy()
    }
}
