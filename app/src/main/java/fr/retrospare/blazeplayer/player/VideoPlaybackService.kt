package fr.retrospare.blazeplayer.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import fr.retrospare.blazeplayer.R

object VideoPlaybackService {

    private const val CHANNEL_ID = "blaze_video"
    private const val NOTIF_ID = 1002

    fun showNotification(context: Context, player: Player, title: String, session: MediaSession, thumbnail: android.graphics.Bitmap? = null) {
        createChannel(context)

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, PlayerActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPlaying = player.isPlaying
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(R.drawable.ic_pause, "Pause", buildActionIntent(context, "PAUSE"))
        } else {
            NotificationCompat.Action(R.drawable.ic_play, "Lecture", buildActionIntent(context, "PLAY"))
        }
        val stopAction = NotificationCompat.Action(R.drawable.ic_close, "Fermer", buildActionIntent(context, "STOP"))

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Blaze Player")
            .setSmallIcon(R.drawable.ic_play)
            .setLargeIcon(thumbnail)
            .setContentIntent(openIntent)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(android.support.v4.media.session.MediaSessionCompat.Token.fromToken(session.platformToken))
                .setShowActionsInCompactView(0))
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notif)
    }

    fun cancelNotification(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIF_ID)
    }

    private fun buildActionIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, VideoNotificationReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Lecture vidéo", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
