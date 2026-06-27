package fr.retrospare.blazeplayer.player

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    @Inject lateinit var dataStore: DataStore<Preferences>
    @Inject lateinit var mediaRepository: MediaRepository

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var player: ExoPlayer
    private lateinit var audioManager: AudioManager

    private var prefSpeedIndex = 3
    private var prefResumeMode = 1
    private var prefAutoPlay = true
    private var prefSeekIndex = 1
    private var prefPip = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // Orientation : lit depuis DataStore de façon bloquante avant le layout
        val orientIdx = runBlocking { dataStore.data.first()[intPreferencesKey("orientation")] ?: 0 }
        requestedOrientation = when (orientIdx) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Applique les insets pour éviter la barre nav en portrait
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            binding.uiOverlay.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        mediaPath = intent.getStringExtra("mediaPath") ?: return finish()
        mediaName = intent.getStringExtra("mediaName") ?: File(mediaPath).name

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Charge prefs async
        lifecycleScope.launch {
            val prefs = dataStore.data.first()
            prefSpeedIndex = prefs[intPreferencesKey("speed_index")] ?: 3
            prefResumeMode = prefs[intPreferencesKey("resume_mode")] ?: 1
            prefAutoPlay = prefs[booleanPreferencesKey("auto_play")] ?: true
            prefSeekIndex = prefs[intPreferencesKey("seek_time_index")] ?: 1
            prefPip = prefs[booleanPreferencesKey("pip")] ?: false
            // orientation gérée en synchrone au démarrage
            // Applique vitesse de lecture
            val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            player.setPlaybackSpeed(speeds.getOrElse(prefSpeedIndex) { 1.0f })

            val labels = listOf("5s", "10s", "15s", "30s", "60s")
            binding.tvRewindLabel.text = "−${labels.getOrElse(prefSeekIndex) { "10s" }}"
            binding.tvForwardLabel.text = "+${labels.getOrElse(prefSeekIndex) { "10s" }}"
        }

        // Stop audio si actif
        try { AudioPlaybackService.instance?.pause() } catch (e: Exception) {}

        // Init ExoPlayer
        player = ExoPlayer.Builder(this).build()

        // Attache la surface ExoPlayer
        val surfaceView = binding.playerView as android.view.SurfaceView
        player.setVideoSurfaceView(surfaceView)

        // Gestion ratio automatique
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height
                    binding.aspectRatioFrame.setAspectRatio(ratio)
                }
            }
        })

        binding.tvTitle.text = mediaName
        binding.tvCurrentTime.text = "0:00:00"
        binding.tvTotalTime.text = "0:00:00"

        // Listener
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                runOnUiThread {
                    updatePlayPauseBtn(isPlaying)
                    if (isPlaying) {
                        scheduleHide()
                        if (!resumeHandled) handleResume()
                    } else {
                        cancelHide()
                        showUI()
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    runOnUiThread {
                        updatePlayPauseBtn(false)
                        cancelHide()
                        showUI()
                        if (!playNextCalled) {
                            playNextCalled = true
                            // Relit la pref au moment réel de la fin
                            lifecycleScope.launch {
                                val prefs = dataStore.data.first()
                                val autoPlay = prefs[booleanPreferencesKey("auto_play")] ?: true
                                if (autoPlay) playNext()
                            }
                        }
                    }
                }
            }
        })

        // Charge le media
        val mediaItem = MediaItem.fromUri(Uri.parse(mediaPath))
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        setupControls()
        setupProgressBar()
        setupGestures()
        startProgressLoop()
        saveHistory()
        scheduleHide()
    }

    private var currentRatioIndex = 0
    private val ratioLabels = listOf("Auto", "16:9", "4:3", "Plein")

    private fun cycleAspectRatio() {
        currentRatioIndex = (currentRatioIndex + 1) % ratioLabels.size
        when (currentRatioIndex) {
            0 -> { // Auto - ratio naturel de la vidéo
                val size = player.videoSize
                if (size.width > 0 && size.height > 0) {
                    val ratio = size.width.toFloat() * size.pixelWidthHeightRatio / size.height
                    binding.aspectRatioFrame.setAspectRatio(ratio)
                }
                binding.aspectRatioFrame.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            1 -> { // 16:9
                binding.aspectRatioFrame.setAspectRatio(16f / 9f)
                binding.aspectRatioFrame.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            2 -> { // 4:3
                binding.aspectRatioFrame.setAspectRatio(4f / 3f)
                binding.aspectRatioFrame.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            3 -> { // Plein écran
                binding.aspectRatioFrame.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        }
        // Affiche le ratio au centre pendant 2 secondes
        binding.seekIndicator.text = ratioLabels[currentRatioIndex]
        binding.seekIndicator.visibility = android.view.View.VISIBLE
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({ binding.seekIndicator.visibility = android.view.View.GONE }, 2000)
    }

    private fun showAudioTracks() {
        val tracks = player.currentTracks
        val audioGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) {
            android.widget.Toast.makeText(this, "Aucune piste audio", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val items = audioGroups.mapIndexed { i, group ->
            val format = group.getTrackFormat(0)
            val lang = format.language ?: "Piste ${i + 1}"
            val selected = group.isSelected
            "${if (selected) "✓  " else "    "}$lang"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Piste audio")
            .setItems(items.toTypedArray()) { _, i ->
                val params = player.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(
                        audioGroups[i].getTrackFormat(0).language
                    ).build()
                player.trackSelectionParameters = params
            }.show()
    }

    private fun showSubtitles() {
        val tracks = player.currentTracks
        val subGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
        val items = mutableListOf("${if (subGroups.none { it.isSelected }) "✓  " else "    "}Désactivés")
        subGroups.forEachIndexed { i, group ->
            val format = group.getTrackFormat(0)
            val lang = format.language ?: "ST ${i + 1}"
            items.add("${if (group.isSelected) "✓  " else "    "}$lang")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Sous-titres")
            .setItems(items.toTypedArray()) { _, i ->
                if (i == 0) {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                        .build()
                } else {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                        .setPreferredTextLanguage(subGroups[i - 1].getTrackFormat(0).language)
                        .build()
                }
            }.show()
    }


    override fun onStop() {
        super.onStop()
        val pos = player.currentPosition
        if (mediaPath.isNotEmpty() && pos > 0) {
            getSharedPreferences("blaze_positions", MODE_PRIVATE)
                .edit().putLong(mediaPath, pos).apply()
            lifecycleScope.launch {
                mediaRepository.updateProgress(mediaPath, pos / 1000)
            }
        }
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        player.release()
    }

    private fun handleResume() {
        resumeHandled = true
        if (prefResumeMode == 2) return
        val savedMs = getSharedPreferences("blaze_positions", MODE_PRIVATE)
            .getLong(mediaPath, 0L)
        if (savedMs <= 3000L) return
        when (prefResumeMode) {
            0 -> player.seekTo(savedMs)
            1 -> android.app.AlertDialog.Builder(this)
                .setTitle("Reprendre la lecture")
                .setMessage("Reprendre depuis %d:%02d ?".format(savedMs / 60000, (savedMs / 1000) % 60))
                .setPositiveButton("Reprendre") { _, _ -> player.seekTo(savedMs) }
                .setNegativeButton("Depuis le début") { _, _ -> player.seekTo(0) }
                .show()
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnPlayPause.setOnClickListener {
            if (player.isPlaying) player.pause() else player.play()
            scheduleHide()
        }

        binding.btnRewind.setOnClickListener {
            player.seekTo((player.currentPosition - seekMs()).coerceAtLeast(0))
            scheduleHide()
        }

        binding.btnForward.setOnClickListener {
            val dur = player.duration.takeIf { it > 0 } ?: return@setOnClickListener
            player.seekTo((player.currentPosition + seekMs()).coerceAtMost(dur))
            scheduleHide()
        }

        binding.btnRatio.setOnClickListener {
            scheduleHide()
            cycleAspectRatio()
        }
        binding.btnAudio.setOnClickListener {
            scheduleHide()
            showAudioTracks()
        }
        binding.btnSubtitles.setOnClickListener {
            scheduleHide()
            showSubtitles()
        }
        binding.uiOverlay.setOnClickListener { if (uiVisible) hideUI() else showUI() }
        (binding.playerView as android.view.SurfaceView).setOnClickListener { if (uiVisible) hideUI() else showUI() }
    }

    private fun seekMs() = when (prefSeekIndex) {
        0 -> 5_000L; 1 -> 10_000L; 2 -> 15_000L; 3 -> 30_000L; 4 -> 60_000L; else -> 10_000L
    }

    private fun setupProgressBar() {
        binding.progressContainer.setOnTouchListener { _, ev ->
            val dur = player.duration.takeIf { it > 0 } ?: return@setOnTouchListener true
            val w = binding.progressContainer.width.toFloat().coerceAtLeast(1f)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    seekBarDragging = true; cancelHide()
                    player.seekTo((ev.x / w * dur).toLong().coerceIn(0, dur))
                    updateProgressUI(player.currentPosition, dur)
                }
                MotionEvent.ACTION_MOVE -> {
                    val ms = (ev.x / w * dur).toLong().coerceIn(0, dur)
                    player.seekTo(ms)
                    updateProgressUI(ms, dur)
                    binding.seekIndicator.visibility = View.VISIBLE
                    binding.seekIndicator.text = formatTime(ms)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    seekBarDragging = false
                    binding.seekIndicator.visibility = View.GONE
                    player.play()
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
                MotionEvent.ACTION_DOWN -> {
                    zoneTouching = true; cancelHide()
                    gestureStartY = ev.y; initialBrightness = getBrightness()
                }
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
                MotionEvent.ACTION_DOWN -> {
                    zoneTouching = true; cancelHide()
                    gestureStartY = ev.y
                    initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
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
            while (!isDestroyed && !isFinishing) {
                delay(500)
                if (isDestroyed || isFinishing) break
                try {
                    val dur = player.duration.takeIf { it > 0 } ?: continue
                    val pos = player.currentPosition
                    if (!seekBarDragging) withContext(Dispatchers.Main) { updateProgressUI(pos, dur) }
                } catch (e: Exception) {}
            }
        }
    }

    private fun updateProgressUI(pos: Long, dur: Long) {
        if (isDestroyed || isFinishing) return
        binding.tvCurrentTime.text = formatTime(pos)
        binding.tvTotalTime.text = formatTime(dur)
        val pct = (pos.toFloat() / dur).coerceIn(0f, 1f)
        val w = binding.progressContainer.width.toFloat()
        val thumbHalf = 7f * resources.displayMetrics.density
        binding.progressFill.layoutParams.width = ((w - thumbHalf * 2) * pct).toInt()
        binding.progressFill.requestLayout()
        binding.progressThumb.translationX = (w - thumbHalf * 2) * pct
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
        if (player.isPlaying) uiHandler.postDelayed(hideRunnable, 3000)
    }

    private fun cancelHide() = uiHandler.removeCallbacks(hideRunnable)

    private fun updatePlayPauseBtn(playing: Boolean) {
        if (isDestroyed || isFinishing) return
        binding.ivPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun getBrightness(): Float { val b = window.attributes.screenBrightness; return if (b < 0) 0.5f else b }
    private fun setBrightness(v: Float) { val lp = window.attributes; lp.screenBrightness = v; window.attributes = lp }

    private fun playNext() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val col = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(
                    android.provider.MediaStore.Video.Media._ID,
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Video.Media.DATA,
                    android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME
                )
                // Charge tous les fichiers du même dossier que la vidéo courante
                var curBucket = ""
                var curName = ""
                // Trouve le dossier de la vidéo courante via son ID (extrait du content URI)
                contentResolver.query(Uri.parse(mediaPath), arrayOf(
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME
                ), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        curName = c.getString(0) ?: ""
                        curBucket = c.getString(1) ?: ""
                    }
                }
                if (curBucket.isEmpty() && curName.isEmpty()) return@launch

                // Charge tous les fichiers du même bucket (dossier)
                val list = mutableListOf<Pair<String, String>>()
                val where = if (curBucket.isNotEmpty())
                    "${android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME} = ?"
                else null
                val args = if (curBucket.isNotEmpty()) arrayOf(curBucket) else null

                contentResolver.query(col, proj, where, args,
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID))
                        val name = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)) ?: continue
                        list.add(android.content.ContentUris.withAppendedId(col, id).toString() to name)
                    }
                }

                val idx = list.indexOfFirst { it.second == curName }
                if (idx >= 0 && idx < list.size - 1) {
                    val next = list[idx + 1]
                    withContext(Dispatchers.Main) {
                        if (isDestroyed || isFinishing) return@withContext
                        // Fondu vers noir
                        binding.root.animate().alpha(0f).setDuration(400).withEndAction {
                            mediaPath = next.first
                            mediaName = next.second
                            binding.tvTitle.text = mediaName
                            resumeHandled = true
                            playNextCalled = false
                            player.setMediaItem(MediaItem.fromUri(Uri.parse(next.first)))
                            player.prepare()
                            player.play()
                            // Fondu retour après 1.5s
                            uiHandler.postDelayed({
                                binding.root.animate().alpha(1f).setDuration(400).start()
                            }, 1500)
                        }.start()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun saveHistory() {
        val ext = mediaName.substringAfterLast('.', "").lowercase()
        lifecycleScope.launch {
            mediaRepository.saveRecentItem(fr.retrospare.blazeplayer.data.model.MediaItem(
                id = mediaPath, name = mediaName, path = mediaPath,
                extension = ext, mimeType = "video/$ext",
                isNetwork = false, lastPlayedAt = System.currentTimeMillis()
            ))
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
            && prefPip && player.isPlaying) {
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
        }
    }
}
