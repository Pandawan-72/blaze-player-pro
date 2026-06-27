package fr.retrospare.blazeplayer.player

import android.content.Context
import android.media.AudioManager
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.cast.CastManager
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.databinding.ActivityPlayerBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {
    private var castManager: CastManager? = null
    private var videoNotifManager: VideoNotificationManager? = null
    private var videoThumbnail: android.graphics.Bitmap? = null
    private var playNextCalled = false
    private var resumeShown = false

    companion object {
        const val EXTRA_MEDIA_URI   = "mediaPath"
        const val EXTRA_MEDIA_TITLE = "mediaName"
        private const val UI_HIDE_DELAY = 3000L
        private const val ZONE_SIDE_PCT = 0.28f
        private const val ZONE_SEEK_PCT = 0.78f
        private const val SAVE_PROGRESS_INTERVAL = 5000L
    }

    private val viewModel: PlayerViewModel by viewModels()
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetector

    @Inject lateinit var mediaRepository: MediaRepository
    @Inject lateinit var dataStore: DataStore<Preferences>

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideUI() }

    private var uiVisible = true
    private var isDragging = false
    private var dragZone = DragZone.NONE
    private var dragStartY = 0f
    private var dragStartX = 0f
    private var initialBrightness = 0.5f
    private var initialVolume = 0
    private var maxVolume = 1
    private var seekStartPct = 0f

    private var mediaPath = ""
    private var mediaName = ""

    enum class DragZone { NONE, LEFT, RIGHT, SEEK }


    private fun <T> getPref(key: Preferences.Key<T>, default: T): T {
        return kotlinx.coroutines.runBlocking {
            dataStore.data.first()[key] ?: default
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        mediaPath = intent.getStringExtra(EXTRA_MEDIA_URI) ?: return finish()
        mediaName = intent.getStringExtra(EXTRA_MEDIA_TITLE) ?: ""

        binding.tvTitle.text = mediaName
        binding.tvSubtitle.text = ""

        setupPlayer(mediaPath)
        setupControls()
        setupTouchHandler()
        scheduleHideUI()
        // Sauvegarde immédiatement dans l'historique pour que updateProgress puisse trouver l'item
        saveToHistory()
        // Stoppe l'audio si en cours
        AudioPlaybackService.instance?.pause()
        videoNotifManager = VideoNotificationManager(this)
        // Extrait une miniature de la vidéo
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                if (mediaPath.startsWith("content://")) retriever.setDataSource(this@PlayerActivity, android.net.Uri.parse(mediaPath))
                else retriever.setDataSource(mediaPath)
                videoThumbnail = retriever.getFrameAtTime(1_000_000)
                retriever.release()
            } catch (e: Exception) { videoThumbnail = null }
        }
        startSavingProgress()
    }

    private fun setupPlayer(uri: String) {
        viewModel.initPlayer(this)
        binding.playerView.player = viewModel.player

        // Labels avance/recul selon réglage
        lifecycleScope.launch {
            kotlinx.coroutines.delay(200)
            val seekLabels = listOf("5", "10", "15", "30", "60")
            val seekLabel = seekLabels.getOrElse(getPref(intPreferencesKey("seek_time_index"), 1)) { "10" }
            binding.tvRewindLabel.text = "-${seekLabel}s"
            binding.tvForwardLabel.text = "+${seekLabel}s"
        }

        // Met à jour les labels des boutons selon le réglage
        val seekLabels = listOf("5", "10", "15", "30", "60")
        val seekLabel = seekLabels.getOrElse(getPref(intPreferencesKey("seek_time_index"), 1)) { "10" }
        binding.btnRewind.contentDescription = "-${seekLabel}s"
        binding.btnForward.contentDescription = "+${seekLabel}s"

        // Orientation
        requestedOrientation = when (getPref(intPreferencesKey("orientation"), 0)) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        val speedMap = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        viewModel.player?.setPlaybackSpeed(speedMap.getOrElse(getPref(intPreferencesKey("speed_index"), 3)) { 1.0f })

        // Langue et sous-titres
        val langCodes = listOf("", "fr", "en", "es", "de", "it", "ja")
        val audioLang = langCodes.getOrElse(getPref(intPreferencesKey("audio_lang"), 0)) { "" }
        val subLang = langCodes.getOrElse(getPref(intPreferencesKey("subtitle_lang"), 0)) { "" }
        val showSubs = getPref(booleanPreferencesKey("subtitles_default"), true)

        val trackParams = viewModel.player?.trackSelectionParameters?.buildUpon()
            ?.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !showSubs)
            ?.apply { if (audioLang.isNotEmpty()) setPreferredAudioLanguage(audioLang) }
            ?.apply { if (subLang.isNotEmpty()) setPreferredTextLanguage(subLang) }
            ?.build()
        trackParams?.let { viewModel.player?.trackSelectionParameters = it }

        // Reprendre la lecture
        val resumeMode = getPref(intPreferencesKey("resume_mode"), 1)
        if (resumeMode != 2) {
            val posPrefs = getSharedPreferences("blaze_positions", MODE_PRIVATE)
            val savedPositionMs = posPrefs.getLong(mediaPath, 0L)
            if (savedPositionMs > 3000L) {
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(800)
                    when (resumeMode) {
                        0 -> viewModel.player?.seekTo(savedPositionMs)
                        1 -> {
                            val min = savedPositionMs / 60000
                            val sec = (savedPositionMs / 1000) % 60
                            runOnUiThread {
                                android.app.AlertDialog.Builder(this@PlayerActivity)
                                    .setTitle("Reprendre la lecture")
                                    .setMessage("Reprendre depuis %d:%02d ?".format(min, sec))
                                    .setPositiveButton("Reprendre") { _, _ -> viewModel.player?.seekTo(savedPositionMs) }
                                    .setNegativeButton("Depuis le début", null)
                                    .show()
                            }
                        }
                    }
                }
            }
        }




        // Init Chromecast
        try {
            viewModel.player?.let { exo ->
                castManager = CastManager(this, exo) { newPlayer ->
                    binding.playerView.player = newPlayer
                }.also { it.init() }
            }
            binding.btnCast.setOnClickListener {
                try {
                    androidx.mediarouter.app.MediaRouteChooserDialog(this).show()
                } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            binding.btnCast.visibility = android.view.View.GONE
        }
        binding.playerView.useController = false
        viewModel.playUri(uri)

        viewModel.player?.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon()
                if (isPlaying) scheduleHideUI() else cancelHideUI()
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    androidx.media3.common.Player.STATE_READY -> {
                        val resumeMode = getPref(intPreferencesKey("resume_mode"), 1)
                        if (resumeMode != 2 && !resumeShown) {
                            val savedMs = getSharedPreferences("blaze_positions", MODE_PRIVATE).getLong(mediaPath, 0L)
                            if (savedMs > 3000L && (viewModel.player?.currentPosition ?: 0L) < 1000L) {
                                resumeShown = true
                                when (resumeMode) {
                                    0 -> viewModel.player?.seekTo(savedMs)
                                    1 -> runOnUiThread {
                                        android.app.AlertDialog.Builder(this@PlayerActivity)
                                            .setTitle("Reprendre la lecture")
                                            .setMessage("Reprendre depuis %d:%02d ?".format(savedMs/60000, (savedMs/1000)%60))
                                            .setPositiveButton("Reprendre") { _, _ -> viewModel.player?.seekTo(savedMs) }
                                            .setNegativeButton("Depuis le début", null)
                                            .show()
                                    }
                                }
                            }
                        }
                    }
                    androidx.media3.common.Player.STATE_ENDED -> {
                        val autoPlay = getPref(booleanPreferencesKey("auto_play"), true)
                        if (autoPlay && !playNextCalled) {
                            playNextCalled = true
                            playNextFile()
                        }
                    }
                }
            }


        })

        Handler(Looper.getMainLooper()).also { h ->
            val r = object : Runnable {
                override fun run() {
                    updateProgress()
                    h.postDelayed(this, 500)
                }
            }
            h.post(r)
        }
    }

    private fun saveToHistory() {
        val duration = viewModel.player?.duration?.div(1000) ?: 0
        val ext = mediaPath.substringAfterLast('.', "").lowercase()
        val isNetwork = mediaPath.startsWith("smb://") || mediaPath.startsWith("ftp://")
        val item = MediaItem(
            id = mediaPath,
            name = mediaName.ifEmpty { File(mediaPath).name },
            path = mediaPath,
            duration = duration,
            extension = ext,
            isNetwork = isNetwork,
            lastPlayedAt = System.currentTimeMillis()
        )
        lifecycleScope.launch {
            mediaRepository.saveRecentItem(item)
        }
    }

    private fun startSavingProgress() {
        lifecycleScope.launch {
            while (true) {
                delay(SAVE_PROGRESS_INTERVAL)
                val positionMs = viewModel.player?.currentPosition ?: continue
                val positionSec = positionMs / 1000
                if (mediaPath.isNotEmpty() && positionMs > 0) {
                    getSharedPreferences("blaze_positions", MODE_PRIVATE)
                        .edit().putLong(mediaPath, positionMs).apply()
                    mediaRepository.updateProgress(mediaPath, positionSec)
                    // Mise à jour notification vidéo
                    val dur = viewModel.player?.duration ?: 0
                    val playing = viewModel.player?.isPlaying == true
                    videoNotifManager?.showNotification(mediaName, playing, positionMs, dur, videoThumbnail, PlayerActivity::class.java)
                }
            }
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener {
            viewModel.player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
            scheduleHideUI()
        }
        binding.btnRewind.setOnClickListener {
            viewModel.player?.let { p ->
                val seekMs = listOf(5_000L, 10_000L, 15_000L, 30_000L, 60_000L).getOrElse(getPref(intPreferencesKey("seek_time_index"), 1)) { 10_000L }
                p.seekTo((p.currentPosition - seekMs).coerceAtLeast(0))
            }
            scheduleHideUI()
        }
        binding.btnForward.setOnClickListener {
            viewModel.player?.let { p ->
                val dur = p.duration
                val seekMs = listOf(5_000L, 10_000L, 15_000L, 30_000L, 60_000L).getOrElse(getPref(intPreferencesKey("seek_time_index"), 1)) { 10_000L }
                if (dur > 0) p.seekTo((p.currentPosition + seekMs).coerceAtMost(dur))
            }
            scheduleHideUI()
        }
        binding.btnSubtitles.setOnClickListener {
            scheduleHideUI()
            showSubtitleSelector()
        }
        binding.btnAudio.setOnClickListener {
            scheduleHideUI()
            showAudioSelector()
        }
        binding.btnRatio.setOnClickListener {
            viewModel.cycleAspectRatio(binding.playerView)
            scheduleHideUI()
        }
    }

    private fun setupTouchHandler() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (uiVisible) hideUI() else showUI()
                return true
            }
        })

        binding.root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            val w = binding.root.width.toFloat()
            val h = binding.root.height.toFloat()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.x; dragStartY = event.y
                    isDragging = false
                    val relX = event.x / w; val relY = event.y / h
                    dragZone = when {
                        relY > ZONE_SEEK_PCT -> DragZone.SEEK
                        relX < ZONE_SIDE_PCT -> DragZone.LEFT
                        relX > (1 - ZONE_SIDE_PCT) -> DragZone.RIGHT
                        else -> DragZone.NONE
                    }
                    if (dragZone == DragZone.LEFT) initialBrightness = getCurrentBrightness()
                    if (dragZone == DragZone.RIGHT) initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (dragZone == DragZone.SEEK) {
                        val dur = viewModel.player?.duration ?: 0
                        val pos = viewModel.player?.currentPosition ?: 0
                        seekStartPct = if (dur > 0) pos.toFloat() / dur else 0f
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - dragStartX; val dy = event.y - dragStartY
                    if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) isDragging = true
                    if (!isDragging) return@setOnTouchListener true
                    when (dragZone) {
                        DragZone.LEFT -> {
                            val newB = (initialBrightness + (-dy / h)).coerceIn(0f, 1f)
                            setBrightness(newB)
                            showBrightnessIndicator(newB)
                        }
                        DragZone.RIGHT -> {
                            val newVol = (initialVolume + (-dy / h * maxVolume).toInt()).coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            showVolumeIndicator(newVol.toFloat() / maxVolume)
                        }
                        DragZone.SEEK -> {
                            val dur = viewModel.player?.duration ?: 0L
                            if (dur > 0) {
                                val newPct = (seekStartPct + dx / w).coerceIn(0f, 1f)
                                viewModel.player?.seekTo((newPct * dur).toLong())
                                showSeekIndicator((newPct * dur).toLong())
                                dragStartX = event.x
                                seekStartPct = newPct
                            }
                        }
                        DragZone.NONE -> {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideSideIndicators()
                    binding.seekIndicator.visibility = View.GONE
                    isDragging = false
                }
            }
            true
        }
    }

    private fun showUI() {
        uiVisible = true
        binding.uiOverlay.visibility = View.VISIBLE
        binding.uiOverlay.animate().alpha(1f).setDuration(200).start()
        scheduleHideUI()
    }

    private fun hideUI() {
        uiVisible = false
        binding.uiOverlay.animate().alpha(0f).setDuration(300)
            .withEndAction { binding.uiOverlay.visibility = View.GONE }.start()
    }

    private fun scheduleHideUI() {
        cancelHideUI()
        if (viewModel.player?.isPlaying == true) hideHandler.postDelayed(hideRunnable, UI_HIDE_DELAY)
    }

    private fun cancelHideUI() = hideHandler.removeCallbacks(hideRunnable)

    private fun updatePlayPauseIcon() {
        val isPlaying = viewModel.player?.isPlaying == true
        binding.ivPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateProgress() {
        val player = viewModel.player ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val position = player.currentPosition
        binding.tvCurrentTime.text = formatTime(position)
        binding.tvTotalTime.text = formatTime(duration)
        val pct = position.toFloat() / duration
        val trackW = binding.progressContainer.width
        binding.progressFill.layoutParams.width = (trackW * pct).toInt()
        binding.progressThumb.translationX = (trackW * pct) - 6f
        binding.progressFill.requestLayout()
        binding.progressBuffer.layoutParams.width = (trackW * (player.bufferedPosition.toFloat() / duration)).toInt()
        binding.progressBuffer.requestLayout()
    }

    private fun showBrightnessIndicator(value: Float) {
        binding.brightnessIndicator.visibility = View.VISIBLE
        binding.tvBrightness.text = "${(value * 100).toInt()}%"
        val bar = binding.brightnessIndicator.findViewById<View>(R.id.brightnessBar)
        bar.layoutParams.height = (52.dpToPx() * value).toInt()
        bar.requestLayout()
    }

    private fun showVolumeIndicator(value: Float) {
        binding.volumeIndicator.visibility = View.VISIBLE
        binding.tvVolume.text = "${(value * 100).toInt()}%"
        val bar = binding.volumeIndicator.findViewById<View>(R.id.volumeBar)
        bar.layoutParams.height = (52.dpToPx() * value).toInt()
        bar.requestLayout()
    }

    private fun hideSideIndicators() {
        binding.brightnessIndicator.visibility = View.GONE
        binding.volumeIndicator.visibility = View.GONE
    }

    private fun showSeekIndicator(posMs: Long) {
        binding.seekIndicator.visibility = View.VISIBLE
        binding.seekIndicator.text = formatTime(posMs)
    }

    private fun getCurrentBrightness(): Float {
        val lp = window.attributes
        return if (lp.screenBrightness < 0) {
            try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }
            catch (e: Exception) { 0.5f }
        } else lp.screenBrightness
    }

    private fun setBrightness(value: Float) {
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        viewModel.player?.pause()
        val pos = viewModel.player?.currentPosition?.div(1000) ?: 0
        if (mediaPath.isNotEmpty() && pos > 0) {
            lifecycleScope.launch { mediaRepository.updateProgress(mediaPath, pos) }
        }
    }

    private fun showAudioSelector() {
        val player = viewModel.player ?: return
        val tracks = player.currentTracks
        val audioGroups = mutableListOf<Pair<String, androidx.media3.common.Tracks.Group>>()

        for (group in tracks.groups) {
            if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                val format = group.getTrackFormat(0)
                val label = format.language?.let { java.util.Locale(it).displayLanguage }
                    ?: format.label
                    ?: "Audio ${audioGroups.size + 1}"
                audioGroups.add(label to group)
            }
        }

        if (audioGroups.isEmpty()) {
            android.widget.Toast.makeText(this, "Aucune piste audio disponible", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val items = audioGroups.map { (label, group) ->
            "${if (group.isSelected) "✓  " else "    "}$label"
        }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Piste audio")
            .setItems(items) { _, which ->
                val group = audioGroups[which].second
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(
                        androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0)
                    ).build()
            }
            .show()
    }

    private fun showSubtitleSelector() {
        val player = viewModel.player ?: return
        val tracks = player.currentTracks
        val subGroups = mutableListOf<Pair<String, androidx.media3.common.Tracks.Group>>()

        for (group in tracks.groups) {
            if (group.type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                val format = group.getTrackFormat(0)
                val label = format.language?.let { java.util.Locale(it).displayLanguage }
                    ?: format.label
                    ?: "Sous-titres ${subGroups.size + 1}"
                subGroups.add(label to group)
            }
        }

        val disabled = player.trackSelectionParameters.disabledTrackTypes
            .contains(androidx.media3.common.C.TRACK_TYPE_TEXT)

        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        items.add("${if (disabled) "✓  " else "    "}Désactivés")
        actions.add {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                .build()
        }

        subGroups.forEach { (label, group) ->
            items.add("${if (group.isSelected && !disabled) "✓  " else "    "}$label")
            actions.add {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(
                        androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0)
                    ).build()
            }
        }

        if (subGroups.isEmpty()) {
            android.widget.Toast.makeText(this, "Aucun sous-titre disponible", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Sous-titres")
            .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    private fun playNextFile() {
        if (mediaPath.isEmpty()) return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(
                    android.provider.MediaStore.Video.Media._ID,
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Video.Media.DATA
                )

                // Trouve le DATA path via l'ID dans l'URI
                val currentId = android.content.ContentUris.parseId(android.net.Uri.parse(mediaPath))
                var currentDataPath = ""
                var currentName = ""
                contentResolver.query(
                    collection, proj,
                    "${android.provider.MediaStore.Video.Media._ID} = ?",
                    arrayOf(currentId.toString()), null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        currentDataPath = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATA)) ?: ""
                        currentName = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)) ?: ""
                    }
                }

                if (currentDataPath.isEmpty()) return@launch

                val parentPath = java.io.File(currentDataPath).parent ?: return@launch

                contentResolver.query(
                    collection, proj,
                    "${android.provider.MediaStore.Video.Media.DATA} LIKE ? AND ${android.provider.MediaStore.Video.Media.DATA} NOT LIKE ?",
                    arrayOf("$parentPath/%", "$parentPath/%/%"),
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME
                )?.use { c ->
                    val list = mutableListOf<Pair<String, String>>()
                    val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val name = c.getString(nameCol) ?: continue
                        val uri = android.content.ContentUris.withAppendedId(collection, id).toString()
                        list.add(uri to name)
                    }
                    val idx = list.indexOfFirst { it.second == currentName }
                    if (idx >= 0 && idx < list.size - 1) {
                        val next = list[idx + 1]
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            mediaPath = next.first
                            mediaName = next.second
                            resumeShown = true // Pas de popup reprendre pour lecture auto
                            viewModel.playUri(next.first)
                            saveToHistory()
                            playNextCalled = false
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val pip = getPref(booleanPreferencesKey("pip"), false)
            if (pip && viewModel.player?.isPlaying == true) {
                val params = android.app.PictureInPictureParams.Builder().build()
                enterPictureInPictureMode(params)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        videoNotifManager?.cancel()
        cancelHideUI()
        binding.playerView.player = null
        viewModel.releasePlayer()
    }
}
