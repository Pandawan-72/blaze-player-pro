package fr.retrospare.blazeplayer.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class VideoNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val activity = PlayerActivity.instance?.get() ?: return
        when (intent.action) {
            "PLAY" -> activity.player.play()
            "PAUSE" -> activity.player.pause()
            "STOP" -> activity.finish()
        }
    }
}
