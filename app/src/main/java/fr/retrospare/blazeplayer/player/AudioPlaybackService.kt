package fr.retrospare.blazeplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import fr.retrospare.blazeplayer.R

class AudioPlaybackService : Service() {

    companion object {
        var instance: AudioPlaybackService? = null
        const val CHANNEL_ID = "blaze_audio"
        const val NOTIF_ID = 1001
        const val ACTION_PLAY = "PLAY"
        const val ACTION_PAUSE = "PAUSE"
        const val ACTION_STOP = "STOP"
        const val ACTION_PREV = "PREV"
        const val ACTION_NEXT = "NEXT"
    }

    var onPrev: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPlaybackChanged: ((Boolean) -> Unit)? = null

    var exoPlayer: ExoPlayer? = null
    private var currentArtwork: Bitmap? = null
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var currentTitle = ""
    private var currentPath = ""

    // Expose isPlaying pour compatibilité
    val isPlaying: Boolean get() = exoPlayer?.isPlaying ?: false
    val currentPosition: Long get() = exoPlayer?.currentPosition ?: 0L
    val duration: Long get() = exoPlayer?.duration ?: 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onPlaybackChanged?.invoke(isPlaying)
                        updateNotification()
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onNext?.invoke()
                        }
                    }
                }
            })
        }

        mediaSession = MediaSessionCompat(this, "BlazeAudio").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onStop() { stopSelf() }
                override fun onSkipToPrevious() { onPrev?.invoke() }
                override fun onSkipToNext() { onNext?.invoke() }
            })
            isActive = true
        }

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_STOP -> { stop(); stopSelf() }
            ACTION_PREV -> onPrev?.invoke()
            ACTION_NEXT -> onNext?.invoke()
        }
        return START_STICKY
    }

    fun play(path: String, title: String) {
        currentPath = path
        currentTitle = title
        loadArtwork(path)

        val uri = Uri.parse(path)
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }

        requestAudioFocus()
        updateNotification()
    }

    fun play() {
        exoPlayer?.play()
        requestAudioFocus()
    }

    fun pause() {
        exoPlayer?.pause()
        abandonAudioFocus()
    }

    fun stop() {
        exoPlayer?.stop()
        abandonAudioFocus()
        mediaSession?.isActive = false
    }

    fun seekTo(ms: Long) {
        exoPlayer?.seekTo(ms)
    }

    fun resume() = play()

    private fun requestAudioFocus() {
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                }
            }
        }.build()
        audioManager?.requestAudioFocus(focusRequest!!)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }

    private fun loadArtwork(path: String) {
        Thread {
            try {
                val retriever = MediaMetadataRetriever()
                if (path.startsWith("content://")) {
                    retriever.setDataSource(this, Uri.parse(path))
                } else {
                    retriever.setDataSource(path)
                }
                val art = retriever.embeddedPicture
                currentArtwork = art?.let {
                    android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                }
                retriever.release()
                // Force refresh notif avec la cover
                android.os.Handler(android.os.Looper.getMainLooper()).post { updateNotification() }
            } catch (e: Exception) {}
        }.start()
    }

    private fun updateNotification() {
        val isPlaying = exoPlayer?.isPlaying ?: false

        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, AudioPlayerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun actionIntent(action: String, reqCode: Int) = PendingIntent.getService(
            this, reqCode, Intent(this, AudioPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val state = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                exoPlayer?.currentPosition ?: 0L, 1f)
            .build()
        mediaSession?.setPlaybackState(state)

        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .apply { currentArtwork?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) } }
            .build()
        mediaSession?.setMetadata(meta)

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText("Blaze Player")
            .setSmallIcon(R.drawable.ic_music_note_large)
            .setContentIntent(openIntent)
            .setLargeIcon(currentArtwork)
            .addAction(R.drawable.ic_skip_previous, "Précédent", actionIntent(ACTION_PREV, 1))
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Pause" else "Lecture",
                actionIntent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY, 2)
            )
            .addAction(R.drawable.ic_skip_next, "Suivant", actionIntent(ACTION_NEXT, 3))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        startForeground(NOTIF_ID, notif)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Lecture audio",
            NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stop()
        exoPlayer?.release()
        exoPlayer = null
        mediaSession?.release()
        abandonAudioFocus()
    }
}
