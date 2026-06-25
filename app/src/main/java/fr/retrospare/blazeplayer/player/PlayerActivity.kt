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
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.databinding.ActivityPlayerBinding
import kotlin.math.abs

@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_URI   = "mediaPath"
        const val EXTRA_MEDIA_TITLE = "mediaName"
        private const val UI_HIDE_DELAY = 3000L
        private const val ZONE_SIDE_PCT = 0.28f
        private const val ZONE_SEEK_PCT = 0.78f
    }

    private val viewModel: PlayerViewModel by viewModels()
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetector

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

        val uri   = intent.getStringExtra(EXTRA_MEDIA_URI)   ?: return finish()
        val title = intent.getStringExtra(EXTRA_MEDIA_TITLE) ?: ""

        binding.tvTitle.text    = title
        binding.tvSubtitle.text = ""

        setupPlayer(uri)
        setupControls()
        setupTouchHandler()
        scheduleHideUI()
    }

    private fun setupPlayer(uri: String) {
        viewModel.initPlayer(this)
        binding.playerView.player         = viewModel.player
        binding.playerView.useController  = false
        viewModel.playUri(uri)

        viewModel.player?.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                updatePlayPauseIcon()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon()
                if (isPlaying) scheduleHideUI() else cancelHideUI()
            }
        })

        // Mise à jour de la barre de progression
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

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnPlayPause.setOnClickListener {
            viewModel.player?.let { p ->
                if (p.isPlaying) p.pause() else p.play()
            }
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

        binding.btnSubtitles.setOnClickListener {
            // TODO: sélecteur sous-titres
            scheduleHideUI()
        }

        binding.btnAudio.setOnClickListener {
            // TODO: sélecteur piste audio
            scheduleHideUI()
        }

        binding.btnRatio.setOnClickListener {
            viewModel.cycleAspectRatio(binding.playerView)
            scheduleHideUI()
        }

        binding.btnCast.setOnClickListener {
            // TODO: Chromecast
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
                    dragStartX = event.x
                    dragStartY = event.y
                    isDragging = false
                    dragZone = DragZone.NONE

                    val relX = event.x / w
                    val relY = event.y / h
                    dragZone = when {
                        relY > ZONE_SEEK_PCT  -> DragZone.SEEK
                        relX < ZONE_SIDE_PCT  -> DragZone.LEFT
                        relX > (1 - ZONE_SIDE_PCT) -> DragZone.RIGHT
                        else -> DragZone.NONE
                    }

                    if (dragZone == DragZone.LEFT) {
                        initialBrightness = getCurrentBrightness()
                    }
                    if (dragZone == DragZone.RIGHT) {
                        initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    }
                    if (dragZone == DragZone.SEEK) {
                        val dur = viewModel.player?.duration ?: 0
                        val pos = viewModel.player?.currentPosition ?: 0
                        seekStartPct = if (dur > 0) pos.toFloat() / dur else 0f
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - dragStartX
                    val dy = event.y - dragStartY

                    if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) isDragging = true
                    if (!isDragging) return@setOnTouchListener true

                    when (dragZone) {
                        DragZone.LEFT -> {
                            val delta = -dy / h
                            val newBrightness = (initialBrightness + delta).coerceIn(0f, 1f)
                            setBrightness(newBrightness)
                            showBrightnessIndicator(newBrightness)
                        }
                        DragZone.RIGHT -> {
                            val delta = -dy / h
                            val newVol = (initialVolume + (delta * maxVolume).toInt())
                                .coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            showVolumeIndicator(newVol.toFloat() / maxVolume)
                        }
                        DragZone.SEEK -> {
                            val dur = viewModel.player?.duration ?: 0L
                            if (dur > 0) {
                                val delta = dx / w
                                val newPct = (seekStartPct + delta).coerceIn(0f, 1f)
                                val newPos = (newPct * dur).toLong()
                                viewModel.player?.seekTo(newPos)
                                showSeekIndicator(newPos)
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
            .withEndAction { binding.uiOverlay.visibility = View.GONE }
            .start()
    }

    private fun scheduleHideUI() {
        cancelHideUI()
        if (viewModel.player?.isPlaying == true) {
            hideHandler.postDelayed(hideRunnable, UI_HIDE_DELAY)
        }
    }

    private fun cancelHideUI() = hideHandler.removeCallbacks(hideRunnable)

    private fun updatePlayPauseIcon() {
        val isPlaying = viewModel.player?.isPlaying == true
        binding.ivPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateProgress() {
        val player = viewModel.player ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val position = player.currentPosition

        binding.tvCurrentTime.text = formatTime(position)
        binding.tvTotalTime.text   = formatTime(duration)

        val pct = position.toFloat() / duration
        val trackW = binding.progressContainer.width
        binding.progressFill.layoutParams.width  = (trackW * pct).toInt()
        binding.progressThumb.translationX = (trackW * pct) - 6f
        binding.progressFill.requestLayout()

        val buffered = player.bufferedPosition
        binding.progressBuffer.layoutParams.width = (trackW * (buffered.toFloat() / duration)).toInt()
        binding.progressBuffer.requestLayout()
    }

    private fun showBrightnessIndicator(value: Float) {
        binding.brightnessIndicator.visibility = View.VISIBLE
        val pct = (value * 100).toInt()
        binding.tvBrightness.text = "$pct%"
        val barH = binding.brightnessIndicator
            .findViewById<View>(R.id.brightnessBar)
        val maxH = 52.dpToPx()
        barH.layoutParams.height = (maxH * value).toInt()
        barH.requestLayout()
    }

    private fun showVolumeIndicator(value: Float) {
        binding.volumeIndicator.visibility = View.VISIBLE
        val pct = (value * 100).toInt()
        binding.tvVolume.text = "$pct%"
        val barV = binding.volumeIndicator
            .findViewById<View>(R.id.volumeBar)
        val maxH = 52.dpToPx()
        barV.layoutParams.height = (maxH * value).toInt()
        barV.requestLayout()
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
            try {
                Settings.System.getInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                ) / 255f
            } catch (e: Exception) { 0.5f }
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
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%d:%02d".format(m, s)
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    override fun onStop() {
        super.onStop()
        viewModel.player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelHideUI()
        binding.playerView.player = null
        viewModel.releasePlayer()
    }
}
