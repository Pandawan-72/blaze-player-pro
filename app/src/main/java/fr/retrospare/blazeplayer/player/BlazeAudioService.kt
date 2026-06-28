package fr.retrospare.blazeplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fr.retrospare.blazeplayer.MainActivity
import fr.retrospare.blazeplayer.R
import org.json.JSONArray
import org.json.JSONObject

class BlazeAudioService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): BlazeAudioService = this@BlazeAudioService
    }

    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    val isPlaying: Boolean get() = mediaPlayer?.isPlaying ?: false
    val currentPosition: Long get() = mediaPlayer?.currentPosition?.toLong() ?: 0L
    val duration: Long get() = mediaPlayer?.duration?.toLong() ?: 0L

    var onCompletion: (() -> Unit)? = null
    var onPlaybackChanged: ((Boolean) -> Unit)? = null

    companion object {
        var instance: BlazeAudioService? = null
        private const val CHANNEL_ID = "blaze_audio_channel"
        private const val NOTIF_ID = 2001
        private const val PREFS = "blaze_playlist_v2"
        private const val KEY_ITEMS = "items"
        private const val KEY_INDEX = "index"

        fun save(context: Context, items: List<PlaylistItem>, index: Int) {
            val json = JSONArray().apply {
                items.forEach { put(JSONObject().put("path", it.path).put("name", it.name)) }
            }
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS, json.toString())
                .putInt(KEY_INDEX, index)
                .commit()
        }

        fun load(context: Context): Pair<List<PlaylistItem>, Int> {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_ITEMS, null) ?: return Pair(emptyList(), 0)
            val index = prefs.getInt(KEY_INDEX, 0)
            return try {
                val arr = JSONArray(json)
                val items = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    PlaylistItem(obj.getString("path"), obj.getString("name"))
                }
                Pair(items, index)
            } catch (e: Exception) { Pair(emptyList(), 0) }
        }

        fun clear(context: Context) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Blaze Audio", "En attente..."))
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        instance = null
        mediaPlayer?.release()
        mediaPlayer = null
        abandonAudioFocus()
        super.onDestroy()
    }

    fun play(path: String, title: String) {
        requestAudioFocus()
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(applicationContext, android.net.Uri.parse(path))
            prepare()
            start()
            setOnCompletionListener { onCompletion?.invoke() }
        }
        updateNotification(title, "")
        onPlaybackChanged?.invoke(true)
    }

    fun pause() {
        mediaPlayer?.pause()
        onPlaybackChanged?.invoke(false)
    }

    fun resume() {
        mediaPlayer?.start()
        onPlaybackChanged?.invoke(true)
    }

    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        onPlaybackChanged?.invoke(false)
    }

    fun seekTo(ms: Long) {
        mediaPlayer?.seekTo(ms.toInt())
    }

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { focus ->
                when (focus) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                    AudioManager.AUDIOFOCUS_GAIN -> resume()
                }
            }
            .build()
        focusRequest = request
        audioManager?.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Blaze Audio", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, artist: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_music_note_large)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, artist: String) {
        val notif = buildNotification(title, artist)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }
}
