package fr.retrospare.blazeplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.cast.CastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Service de lecture VIDEO basé sur [MediaSessionService]. Encapsule un [ExoPlayer] local dans un
 * [CastPlayer] : Media3 bascule alors automatiquement entre lecture locale et Chromecast sans que
 * le code appelant (PlayerActivity) n'ait à gérer deux players différents — il manipule toujours
 * la même interface [Player].
 */
@UnstableApi
class VideoPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null

    override fun onCreate() {
        super.onCreate()

        // Cache disque partagé (cf. MediaCacheManager) : seuls les flux non-locaux (smb://, http(s)://)
        // passent par cette factory "base" de DefaultDataSource, donc seule la vidéo réseau profite
        // du cache — le contenu local (content://, MediaStore) continue d'être lu directement.
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(MediaCacheManager.getCache(this))
            .setUpstreamDataSourceFactory(SmbDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSourceFactory = DefaultDataSource.Factory(this, cacheDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 64 * 1024))
            .setBufferDurationsMs(60_000, 300_000, 5_000, 10_000)
            .setPrioritizeTimeOverSizeThresholds(true)
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
            // Garde le Wi-Fi actif pendant la lecture réseau (SMB) en arrière-plan/écran éteint.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exoPlayer = localPlayer

        // CastPlayer encapsule ExoPlayer - Media3 bascule automatiquement local <-> Chromecast.
        // Si l'API Cast n'est pas disponible sur l'appareil (pas de Play Services), on retombe
        // simplement sur le player local : c'est le seul cas où l'exception est avalée.
        val sessionPlayer: Player = try {
            val cp = CastPlayer.Builder(this).setLocalPlayer(localPlayer).build()
            castPlayer = cp
            cp
        } catch (e: Exception) {
            android.util.Log.w("VideoPlaybackService", "CastPlayer unavailable, falling back to local ExoPlayer", e)
            localPlayer
        }

        // sessionActivity pointe vers PlayerActivity, pas vers MainActivity/audio.
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(openIntent)
            .build()
    }

    fun getExoPlayer(): ExoPlayer? = exoPlayer
    fun getCastPlayer(): CastPlayer? = castPlayer
    fun getMediaSession(): MediaSession? = mediaSession

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Best practice Media3 : si l'utilisateur swipe l'app hors des tâches récentes alors que rien
     * ne joue (et qu'on ne caste pas, où la lecture doit continuer côté Chromecast), on arrête le
     * service au lieu de laisser une notification fantôme persister.
     */
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
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayer = null
        castPlayer = null
        super.onDestroy()
    }
}
