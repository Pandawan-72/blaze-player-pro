package fr.retrospare.blazeplayer.player

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    @Inject lateinit var dataStore: DataStore<Preferences>
    @Inject lateinit var mediaRepository: MediaRepository

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var audioManager: AudioManager

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideUI() }
    private var uiVisible = true
    private var mediaPath = ""
    private var mediaName = ""
    private var resumeHandled = false
    private var playNextCalled = false
    private var seekBarDragging = false
    private var zoneTouching = false
    private var gestureStartY = 0f
    private var initialBrightness = 0.5f
    private var initialVolume = 0
    private var maxVolume = 0
    private var ratioIndex = 0
    private val ratios = listOf(MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, MediaPlayer.ScaleType.SURFACE_FILL, MediaPlayer.ScaleType.SURFACE_16_9, MediaPlayer.ScaleType.SURFACE_4_3)

    private var videoNotifManager: VideoNotificationManager? = null
    private var videoThumbnail: android.graphics.Bitmap? = null

    private fun pref(key: Preferences.Key<Int>, def: Int) = runBlocking { dataStore.data.first()[key] ?: def }
    private fun prefBool(key: Preferences.Key<Boolean>, def: Boolean) = runBlocking { dataStore.data.first()[key] ?: def }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaPath = intent.getStringExtra("mediaPath") ?: return finish()
        mediaName = intent.getStringExtra("mediaName") ?: File(mediaPath).name

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        requestedOrientation = when (pref(intPreferencesKey("orientation"), 0)) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }

        AudioPlaybackService.instance?.pause()
        AudioPlaybackService.instance?.stop()
        binding.tvTitle.text = mediaName
        binding.tvCurrentTime.text = "0:00:00"
        binding.tvTotalTime.text = "0:00:00"

        // Init libVLC
        val vlcOptions = arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--rtsp-tcp",
            "--avcodec-hw=any",
            "--audio-resampler=soxr",
            "--smb-user=",
            "--smb-pwd=",
            "--network-caching=1500"
        )
        // Si SMB avec credentials dans l'URL, les passe à VLC
        if (mediaPath.startsWith("smb://") && mediaPath.contains("@")) {
            try {
                val userInfo = mediaPath.substringAfter("smb://").substringBefore("@")
                val user = userInfo.substringBefore(":").let { java.net.URLDecoder.decode(it, "UTF-8") }
                val pass = if (userInfo.contains(":")) userInfo.substringAfter(":").let { java.net.URLDecoder.decode(it, "UTF-8") } else ""
                vlcOptions.add("--smb-user=$user")
                vlcOptions.add("--smb-pwd=$pass")
            } catch (e: Exception) {}
        }
        libVLC = LibVLC(this, vlcOptions)
        mediaPlayer = MediaPlayer(libVLC)

        // MÉTHODE OFFICIELLE : attachViews avec VLCVideoLayout
        mediaPlayer.attachViews(binding.playerView, null, false, false)

        // Listener
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> runOnUiThread {
                    updatePlayPauseBtn(true); scheduleHide(); handleResume()
                    videoNotifManager?.showNotification(mediaName, true, mediaPlayer.time, mediaPlayer.length, videoThumbnail, PlayerActivity::class.java)
                }
                MediaPlayer.Event.Paused -> runOnUiThread {
                    updatePlayPauseBtn(false); cancelHide(); showUI()
                    videoNotifManager?.showNotification(mediaName, false, mediaPlayer.time, mediaPlayer.length, videoThumbnail, PlayerActivity::class.java)
                }
                MediaPlayer.Event.Stopped -> runOnUiThread { updatePlayPauseBtn(false) }
                MediaPlayer.Event.EndReached -> runOnUiThread {
                    updatePlayPauseBtn(false)
                    cancelHide()
                    showUI()
                    if (!playNextCalled && prefBool(booleanPreferencesKey("auto_play"), true)) {
                        playNextCalled = true; playNext()
                    }
                }
                else -> {}
            }
        }

        playMedia(mediaPath)
        binding.root.setOnClickListener { if (uiVisible) hideUI() else showUI() }
        setupControls()
        setupProgressBar()
        setupGestures()
        startProgressLoop()
        saveHistory()
        setupNotification()
        scheduleHide()
    }

    private fun playMedia(path: String) {
        val media = when {
            path.startsWith("content://") -> {
                val fd = contentResolver.openFileDescriptor(Uri.parse(path), "r")?.fileDescriptor ?: return
                Media(libVLC, fd)
            }
            path.startsWith("smb://") || path.startsWith("ftp://") || path.startsWith("http://") || path.startsWith("https://") -> {
                Media(libVLC, Uri.parse(path))
            }
            else -> Media(libVLC, Uri.parse(path))
        }
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        mediaPlayer.rate = speeds.getOrElse(pref(intPreferencesKey("speed_index"), 3)) { 1.0f }
    }

    private fun handleResume() {
        if (resumeHandled) return
        resumeHandled = true
        val mode = pref(intPreferencesKey("resume_mode"), 1)
        if (mode == 2) return
        val savedMs = getSharedPreferences("blaze_positions", MODE_PRIVATE).getLong(mediaPath, 0L)
        if (savedMs <= 3000L || mediaPlayer.time > 1000L) return
        when (mode) {
            0 -> mediaPlayer.time = savedMs
            1 -> android.app.AlertDialog.Builder(this)
                .setTitle("Reprendre la lecture")
                .setMessage("Reprendre depuis %d:%02d ?".format(savedMs / 60000, (savedMs / 1000) % 60))
                .setPositiveButton("Reprendre") { _, _ -> mediaPlayer.time = savedMs }
                .setNegativeButton("Depuis le début") { _, _ -> mediaPlayer.time = 0 }
                .show()
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener {
            when {
                mediaPlayer.isPlaying -> mediaPlayer.pause()
                mediaPlayer.playerState == 6 -> playMedia(mediaPath)
                else -> mediaPlayer.play()
            }
            scheduleHide()
        }
        binding.btnRewind.setOnClickListener {
            mediaPlayer.time = (mediaPlayer.time - seekMs()).coerceAtLeast(0); scheduleHide()
        }
        binding.btnForward.setOnClickListener {
            val dur = mediaPlayer.length.takeIf { it > 0 } ?: return@setOnClickListener
            mediaPlayer.time = (mediaPlayer.time + seekMs()).coerceAtMost(dur); scheduleHide()
        }
        binding.btnRatio.setOnClickListener {
            ratioIndex = (ratioIndex + 1) % ratios.size
            mediaPlayer.setVideoScale(ratios[ratioIndex]); scheduleHide()
        }
        binding.btnAudio.setOnClickListener { scheduleHide() }
        binding.btnSubtitles.setOnClickListener { scheduleHide() }
        binding.uiOverlay.setOnClickListener { if (uiVisible) hideUI() else showUI() }
        binding.playerView.setOnClickListener { if (uiVisible) hideUI() else showUI() }

        val labels = listOf("5s", "10s", "15s", "30s", "60s")
        val idx = pref(intPreferencesKey("seek_time_index"), 1)
        binding.tvRewindLabel.text = "−${labels.getOrElse(idx) { "10s" }}"
        binding.tvForwardLabel.text = "+${labels.getOrElse(idx) { "10s" }}"
    }

    private fun seekMs() = when (pref(intPreferencesKey("seek_time_index"), 1)) {
        0 -> 5_000L; 1 -> 10_000L; 2 -> 15_000L; 3 -> 30_000L; 4 -> 60_000L; else -> 10_000L
    }

    private fun setupProgressBar() {
        binding.progressContainer.setOnTouchListener { _, ev ->
            val dur = mediaPlayer.length.takeIf { it > 0 } ?: run {
                // Vidéo terminée : permet quand même de naviguer
                if (mediaPlayer.playerState != 6) return@setOnTouchListener true
                // Relance pour pouvoir seeker
                playMedia(mediaPath)
                uiHandler.postDelayed({
                    val w2 = binding.progressContainer.width.toFloat().coerceAtLeast(1f)
                    val ms2 = (ev.x / w2 * (mediaPlayer.length.takeIf { it > 0 } ?: return@postDelayed)).toLong()
                    mediaPlayer.time = ms2
                }, 600)
                return@setOnTouchListener true
            }
            val w = binding.progressContainer.width.toFloat().coerceAtLeast(1f)
            val ms = (ev.x / w * dur).toLong().coerceIn(0, dur)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    seekBarDragging = true
                    cancelHide()
                    mediaPlayer.time = ms
                    updateProgressUI(ms, dur)
                }
                MotionEvent.ACTION_MOVE -> {
                    val msMove = (ev.x / w * dur).toLong().coerceIn(0, dur)
                    mediaPlayer.time = msMove
                    updateProgressUI(msMove, dur)
                    binding.seekIndicator.visibility = View.VISIBLE
                    binding.seekIndicator.text = formatTime(msMove)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    seekBarDragging = false
                    binding.seekIndicator.visibility = View.GONE
                    mediaPlayer.play()
                    scheduleHide()
                }
            }
            true
        }
    }

    private fun setupGestures() {
        binding.touchZoneLeft.setOnTouchListener { _, ev ->
            val h = binding.touchZoneLeft.height.toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { zoneTouching = true; cancelHide(); gestureStartY = ev.y; initialBrightness = getBrightness() }
                MotionEvent.ACTION_MOVE -> {
                    val b = (initialBrightness - (ev.y - gestureStartY) / h * 1.5f).coerceIn(0.01f, 1f)
                    setBrightness(b)
                    binding.touchZoneLeftFill.layoutParams.height = (h * b).toInt()
                    binding.touchZoneLeftFill.requestLayout()
                    binding.tvBrightnessZone.text = "${(b * 100).toInt()}%"
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { zoneTouching = false; scheduleHide() }
            }
            true
        }
        binding.touchZoneRight.setOnTouchListener { _, ev ->
            val h = binding.touchZoneRight.height.toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { zoneTouching = true; cancelHide(); gestureStartY = ev.y; initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }
                MotionEvent.ACTION_MOVE -> {
                    val v = (initialVolume - ((ev.y - gestureStartY) / h * maxVolume * 1.5f).toInt()).coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    val pct = v.toFloat() / maxVolume
                    binding.touchZoneRightFill.layoutParams.height = (h * pct).toInt()
                    binding.touchZoneRightFill.requestLayout()
                    binding.tvVolumeZone.text = "${(pct * 100).toInt()}%"
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { zoneTouching = false; scheduleHide() }
            }
            true
        }
    }

    private fun startProgressLoop() {
        lifecycleScope.launch {
            while (true) {
                delay(500)
                if (!mediaPlayer.isPlaying) continue
                val dur = mediaPlayer.length.takeIf { it > 0 } ?: continue
                val pos = mediaPlayer.time
                if (!seekBarDragging) runOnUiThread { updateProgressUI(pos, dur) }
                if (pos % 5000 < 600 && pos > 0) {
                    getSharedPreferences("blaze_positions", MODE_PRIVATE).edit().putLong(mediaPath, pos).apply()
                    mediaRepository.updateProgress(mediaPath, pos / 1000)
                    runOnUiThread {
                        videoNotifManager?.showNotification(mediaName, mediaPlayer.isPlaying, pos, dur, videoThumbnail, PlayerActivity::class.java)
                    }
                }
            }
        }
    }

    private fun updateProgressUI(pos: Long, dur: Long) {
        binding.tvCurrentTime.text = formatTime(pos)
        binding.tvTotalTime.text = formatTime(dur)
        val pct = (pos.toFloat() / dur).coerceIn(0f, 1f)
        val w = binding.progressContainer.width.toFloat()
        val thumbHalf = 7f * resources.displayMetrics.density
        val fillW = ((w - thumbHalf * 2) * pct).coerceIn(0f, w - thumbHalf * 2)
        binding.progressFill.layoutParams.width = fillW.toInt()
        binding.progressFill.requestLayout()
        binding.progressThumb.translationX = fillW
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun showUI() {
        uiVisible = true
        binding.uiOverlay.visibility = View.VISIBLE
        binding.uiOverlay.animate().alpha(1f).setDuration(200).start()
        binding.touchZoneLeft.visibility = View.VISIBLE
        binding.touchZoneLeft.animate().alpha(1f).setDuration(200).start()
        binding.touchZoneRight.visibility = View.VISIBLE
        binding.touchZoneRight.animate().alpha(1f).setDuration(200).start()
        scheduleHide()
    }

    private fun hideUI() {
        if (zoneTouching || seekBarDragging) return
        uiVisible = false
        binding.uiOverlay.animate().alpha(0f).setDuration(200).withEndAction { binding.uiOverlay.visibility = View.GONE }.start()
        binding.touchZoneLeft.animate().alpha(0f).setDuration(200).withEndAction { binding.touchZoneLeft.visibility = View.GONE }.start()
        binding.touchZoneRight.animate().alpha(0f).setDuration(200).withEndAction { binding.touchZoneRight.visibility = View.GONE }.start()
    }

    private fun scheduleHide() {
        cancelHide()
        if (mediaPlayer.isPlaying) uiHandler.postDelayed(hideRunnable, 3000)
    }

    private fun cancelHide() = uiHandler.removeCallbacks(hideRunnable)
    private fun updatePlayPauseBtn(playing: Boolean) {
        binding.ivPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun getBrightness(): Float { val b = window.attributes.screenBrightness; return if (b < 0) 0.5f else b }
    private fun setBrightness(v: Float) { val lp = window.attributes; lp.screenBrightness = v; window.attributes = lp }

    private fun playNext() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val col = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(android.provider.MediaStore.Video.Media._ID, android.provider.MediaStore.Video.Media.DISPLAY_NAME, android.provider.MediaStore.Video.Media.DATA)
                var curData = ""; var curName = ""
                contentResolver.query(col, proj, "${android.provider.MediaStore.Video.Media.DATA} = ?", arrayOf(Uri.parse(mediaPath).path ?: mediaPath), null)?.use {
                    if (it.moveToFirst()) { curData = it.getString(it.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATA)) ?: ""; curName = it.getString(it.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)) ?: "" }
                }
                if (curData.isEmpty()) return@launch
                val parent = File(curData).parent ?: return@launch
                val list = mutableListOf<Pair<String, String>>()
                contentResolver.query(col, proj, "${android.provider.MediaStore.Video.Media.DATA} LIKE ? AND ${android.provider.MediaStore.Video.Media.DATA} NOT LIKE ?", arrayOf("$parent/%", "$parent/%/%"), android.provider.MediaStore.Video.Media.DISPLAY_NAME)?.use { c ->
                    while (c.moveToNext()) { val id = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)); val name = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)) ?: continue; list.add(android.content.ContentUris.withAppendedId(col, id).toString() to name) }
                }
                val idx = list.indexOfFirst { it.second == curName }
                if (idx >= 0 && idx < list.size - 1) {
                    val next = list[idx + 1]
                    withContext(Dispatchers.Main) { mediaPath = next.first; mediaName = next.second; binding.tvTitle.text = mediaName; resumeHandled = true; playNextCalled = false; playMedia(next.first) }
                }
            } catch (e: Exception) {}
        }
    }

    private fun saveHistory() {
        val ext = mediaName.substringAfterLast('.', "").lowercase()
        lifecycleScope.launch { mediaRepository.saveRecentItem(MediaItem(id = mediaPath, name = mediaName, path = mediaPath, extension = ext, mimeType = "video/$ext", isNetwork = false, lastPlayedAt = System.currentTimeMillis())) }
    }

    private fun setupNotification() {
        videoNotifManager = VideoNotificationManager(this)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val r = android.media.MediaMetadataRetriever()
                if (mediaPath.startsWith("content://")) r.setDataSource(this@PlayerActivity, Uri.parse(mediaPath)) else r.setDataSource(mediaPath)
                videoThumbnail = r.getFrameAtTime(1_000_000); r.release()
            } catch (e: Exception) {}
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && prefBool(booleanPreferencesKey("pip"), false) && mediaPlayer.isPlaying)
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
    }

    override fun onStop() {
        super.onStop()
        val pos = mediaPlayer.time; mediaPlayer.pause()
        if (mediaPath.isNotEmpty() && pos > 0) { getSharedPreferences("blaze_positions", MODE_PRIVATE).edit().putLong(mediaPath, pos).apply(); lifecycleScope.launch { mediaRepository.updateProgress(mediaPath, pos / 1000) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        videoNotifManager?.cancel()
        mediaPlayer.detachViews()
        mediaPlayer.release()
        libVLC.release()
    }
}
