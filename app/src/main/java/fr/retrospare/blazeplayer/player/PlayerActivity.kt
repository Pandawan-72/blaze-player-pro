package fr.retrospare.blazeplayer.player

import android.content.Context
import android.media.AudioManager
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
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.cast.CastManager
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.databinding.ActivityPlayerBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {
    private var castManager: CastManager? = null

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
        startSavingProgress()
    }

    private fun setupPlayer(uri: String) {
        viewModel.initPlayer(this)
        binding.playerView.player = viewModel.player

        // Init Chromecast - protégé contre l'absence de Google Play Services
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
                if (state == androidx.media3.common.Player.STATE_READY) {
                    saveToHistory()
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
                val position = viewModel.player?.currentPosition?.div(1000) ?: continue
                if (mediaPath.isNotEmpty() && position > 0) {
                    mediaRepository.updateProgress(mediaPath, position)
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
                p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
            }
            scheduleHideUI()
        }
        binding.btnForward.setOnClickListener {
            viewModel.player?.let { p ->
                val dur = p.duration
                if (dur > 0) p.seekTo((p.currentPosition + 10_000).coerceAtMost(dur))
            }
            scheduleHideUI()
        }
        binding.btnSubtitles.setOnClickListener { scheduleHideUI() }
        binding.btnAudio.setOnClickListener { scheduleHideUI() }
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

    override fun onStop() {
        super.onStop()
        viewModel.player?.pause()
        val pos = viewModel.player?.currentPosition?.div(1000) ?: 0
        if (mediaPath.isNotEmpty() && pos > 0) {
            lifecycleScope.launch { mediaRepository.updateProgress(mediaPath, pos) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelHideUI()
        binding.playerView.player = null
        viewModel.releasePlayer()
    }
}
