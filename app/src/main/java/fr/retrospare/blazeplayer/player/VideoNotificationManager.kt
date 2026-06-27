package fr.retrospare.blazeplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.core.app.NotificationCompat
import fr.retrospare.blazeplayer.R

class VideoNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "blaze_video"
        const val NOTIF_ID = 1002
        const val ACTION_PLAY = "VIDEO_PLAY"
        const val ACTION_PAUSE = "VIDEO_PAUSE"
        const val ACTION_STOP = "VIDEO_STOP"
    }

    private var mediaSession: MediaSessionCompat? = null
    private val notifManager = context.getSystemService(NotificationManager::class.java)

    init {
        createChannel()
        mediaSession = MediaSessionCompat(context, "BlazeVideo").apply { isActive = true }
    }

    fun showNotification(
        title: String,
        isPlaying: Boolean,
        position: Long,
        duration: Long,
        thumbnail: Bitmap? = null,
        activityClass: Class<*>
    ) {
        val openIntent = PendingIntent.getActivity(context, 0,
            Intent(context, activityClass).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val playPauseIntent = PendingIntent.getBroadcast(context, 0,
            Intent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = PendingIntent.getBroadcast(context, 1,
            Intent(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        mediaSession?.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .apply { thumbnail?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) } }
            .build())

        mediaSession?.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO)
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                position, 1f
            ).build())

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Blaze Player")
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(openIntent)
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Pause" else "Lecture",
                playPauseIntent
            )
            .addAction(R.drawable.ic_close, "Fermer", stopIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0))
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        thumbnail?.let { builder.setLargeIcon(it) }
        notifManager.notify(NOTIF_ID, builder.build())
    }

    fun cancel() {
        notifManager.cancel(NOTIF_ID)
        mediaSession?.release()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Lecture vidéo",
            NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        notifManager.createNotificationChannel(channel)
    }
}
