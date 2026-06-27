package fr.retrospare.blazeplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.net.Uri
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

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
    var player: MediaPlayer? = null
        private set
    private var libVLC: LibVLC? = null
    private var currentArtwork: Bitmap? = null
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var currentTitle = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        libVLC = LibVLC(this, arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--audio-resampler=soxr"
        ))
        player = MediaPlayer(libVLC)

        mediaSession = MediaSessionCompat(this, "BlazeAudio").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { player?.play() }
                override fun onPause() { player?.pause() }
                override fun onStop() { stopSelf() }
            })
            isActive = true
        }

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        player?.setEventListener { event ->
            when (event.type) {
                org.videolan.libvlc.MediaPlayer.Event.Playing -> {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onPlaybackChanged?.invoke(true)
                        updateNotification()
                    }
                }
                org.videolan.libvlc.MediaPlayer.Event.Paused,
                org.videolan.libvlc.MediaPlayer.Event.Stopped -> {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onPlaybackChanged?.invoke(false)
                        updateNotification()
                    }
                }
                org.videolan.libvlc.MediaPlayer.Event.EndReached -> {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onNext?.invoke()
                    }
                }
                else -> {}
            }
        }
    }

    fun play(path: String, name: String) {
        android.util.Log.d("BlazeAudio", "play() called: $path")
        currentTitle = name.substringBeforeLast(".")
        requestAudioFocus()
        val media = if (path.startsWith("content://")) {
            Media(libVLC, contentResolver.openFileDescriptor(Uri.parse(path), "r")?.fileDescriptor)
        } else {
            Media(libVLC, Uri.parse(path))
        }
        if (media == null) {
            android.util.Log.e("BlazeAudio", "media is null for $path")
            return
        }
        player?.media = media
        media.release()
        player?.play()
        android.util.Log.d("BlazeAudio", "player.isPlaying=${player?.isPlaying} state=${player?.playerState}")
        // Charge la cover en arrière-plan
        Thread {
            try {
                val retriever = MediaMetadataRetriever()
                if (path.startsWith("content://")) retriever.setDataSource(this, Uri.parse(path))
                else retriever.setDataSource(path)
                val art = retriever.embeddedPicture
                retriever.release()
                currentArtwork = art?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                updateMediaSession()
                updateNotification()
            } catch (e: Exception) { currentArtwork = null }
        }.start()
        updateNotification()
        updateMediaSession()
    }

    fun pause() {
        player?.pause()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateNotification() }, 100)
    }
    fun resume() {
        player?.play()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateNotification() }, 100)
    }
    fun stop() { player?.stop() }
    val isPlaying get() = player?.isPlaying == true
    val currentPosition get() = player?.time ?: 0L
    val duration get() = player?.length ?: 0L

    fun seekTo(ms: Long) { player?.time = ms }

    private fun requestAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .build()
            audioManager?.requestAudioFocus(focusRequest!!)
        }
    }

    private fun updateMediaSession() {
        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Blaze Player")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        currentArtwork?.let { metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        mediaSession?.setMetadata(metaBuilder.build())
        mediaSession?.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP)
            .setState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                currentPosition, 1f)
            .build())
    }

    private fun updateNotification() {
        val prevIntent = PendingIntent.getService(this, 2,
            Intent(this, AudioPlaybackService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val playPauseIntent = PendingIntent.getService(this, 0,
            Intent(this, AudioPlaybackService::class.java).apply {
                action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val nextIntent = PendingIntent.getService(this, 3,
            Intent(this, AudioPlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, AudioPlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText("Blaze Player")
            .setSmallIcon(fr.retrospare.blazeplayer.R.drawable.ic_audio)
            .setContentIntent(openIntent)
            .addAction(fr.retrospare.blazeplayer.R.drawable.ic_skip_previous, "Précédent", prevIntent)
            .addAction(
                if (isPlaying) fr.retrospare.blazeplayer.R.drawable.ic_pause else fr.retrospare.blazeplayer.R.drawable.ic_play,
                if (isPlaying) "Pause" else "Play",
                playPauseIntent
            )
            .addAction(fr.retrospare.blazeplayer.R.drawable.ic_skip_next, "Suivant", nextIntent)
            .addAction(fr.retrospare.blazeplayer.R.drawable.ic_close, "Stop", stopIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        currentArtwork?.let { builder.setLargeIcon(it) }
        val notification = builder.build()

        startForeground(NOTIF_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pause()
            ACTION_PLAY -> resume()
            ACTION_STOP -> { stop(); stopForeground(true); stopSelf() }
            ACTION_PREV -> onPrev?.invoke()
            ACTION_NEXT -> onNext?.invoke()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Lecture audio",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Blaze Player audio"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        mediaSession?.release()
        player?.release()
        libVLC?.release()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        }
        super.onDestroy()
    }
}
