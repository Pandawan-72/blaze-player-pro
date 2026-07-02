package fr.retrospare.blazeplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
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
    }

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

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
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(MediaCacheManager.getCache(this))
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(cacheDataSourceFactory)

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 64 * 1024))
            .setBufferDurationsMs(60_000, 300_000, 5_000, 10_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(30_000, true)
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
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int {
                    if (playerCommand == Player.COMMAND_STOP) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { stopSelf() }
                    }
                    return SessionResult.RESULT_SUCCESS
                }
            })
            .build()

        sessionPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val path = mediaItem?.mediaId
                val name = mediaItem?.mediaMetadata?.title?.toString()
                mediaSession?.setSessionActivity(buildOpenIntent(path, name))
            }
        })
    }

    fun getExoPlayer(): ExoPlayer? = exoPlayer
    fun getCastPlayer(): CastPlayer? = castPlayer
    fun getMediaSession(): MediaSession? = mediaSession

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaSession
        val isCasting = session?.player?.deviceInfo?.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE
        val isActivelyPlaying = session?.player?.playWhenReady == true
        if (!isCasting && !isActivelyPlaying) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
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
