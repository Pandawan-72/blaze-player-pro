package fr.retrospare.blazeplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import fr.retrospare.blazeplayer.R

class AudioService : Service() {

    companion object {
        const val CHANNEL_ID = "blaze_audio"
        const val NOTIF_ID = 1001
        const val ACTION_PLAY = "fr.retrospare.blazeplayer.PLAY"
        const val ACTION_PAUSE = "fr.retrospare.blazeplayer.PAUSE"
        const val ACTION_NEXT = "fr.retrospare.blazeplayer.NEXT"
        const val ACTION_PREV = "fr.retrospare.blazeplayer.PREV"
        const val ACTION_STOP = "fr.retrospare.blazeplayer.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_PLAYING = "playing"
    }

    inner class AudioBinder : Binder() {
        fun getService() = this@AudioService
    }

    private val binder = AudioBinder()
    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "BlazeAudio").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { sendBroadcast(Intent(ACTION_PLAY).setPackage(packageName)) }
                override fun onPause() { sendBroadcast(Intent(ACTION_PAUSE).setPackage(packageName)) }
                override fun onSkipToNext() { sendBroadcast(Intent(ACTION_NEXT).setPackage(packageName)) }
                override fun onSkipToPrevious() { sendBroadcast(Intent(ACTION_PREV).setPackage(packageName)) }
                override fun onStop() { sendBroadcast(Intent(ACTION_STOP).setPackage(packageName)) }
            })
            isActive = true
        }
        // Démarre en foreground immédiatement
        startForeground(NOTIF_ID, buildNotification("Blaze Player", "", true))
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: ""
        val artist = intent?.getStringExtra(EXTRA_ARTIST) ?: ""
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true

        when (intent?.action) {
            ACTION_PLAY -> sendBroadcast(Intent(ACTION_PLAY).setPackage(packageName))
            ACTION_PAUSE -> sendBroadcast(Intent(ACTION_PAUSE).setPackage(packageName))
            ACTION_NEXT -> sendBroadcast(Intent(ACTION_NEXT).setPackage(packageName))
            ACTION_PREV -> sendBroadcast(Intent(ACTION_PREV).setPackage(packageName))
            ACTION_STOP -> { stopForeground(true); stopSelf(); return START_NOT_STICKY }
            else -> if (title.isNotEmpty()) updateMetadata(title, artist, playing)
        }
        return START_STICKY
    }

    fun updateMetadata(title: String, artist: String, playing: Boolean) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .build()
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f
                )
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                ).build()
        )
        startForeground(NOTIF_ID, buildNotification(title, artist, playing))
    }

    private fun buildNotification(title: String, artist: String, playing: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, AudioPlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun action(a: String, icon: Int, label: String): NotificationCompat.Action {
            val pi = PendingIntent.getService(
                this, a.hashCode(),
                Intent(this, AudioService::class.java).apply { action = a },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Action(icon, label, pi)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_audio)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(openIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .addAction(action(ACTION_PREV, R.drawable.ic_skip_previous, "Précédent"))
            .addAction(
                if (playing) action(ACTION_PAUSE, R.drawable.ic_pause, "Pause")
                else action(ACTION_PLAY, R.drawable.ic_play, "Lecture")
            )
            .addAction(action(ACTION_NEXT, R.drawable.ic_skip_next, "Suivant"))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Lecture audio", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Contrôles Blaze Player"
                setShowBadge(false)
                getSystemService(NotificationManager::class.java).createNotificationChannel(this)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
    }
}
