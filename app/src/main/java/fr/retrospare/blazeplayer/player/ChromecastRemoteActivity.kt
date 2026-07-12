package fr.retrospare.blazeplayer.player

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.cast.BlazeMediaRouteDialogFactory
import fr.retrospare.blazeplayer.debug.CrashReporter
import fr.retrospare.blazeplayer.databinding.ActivityChromecastRemoteBinding

/**
 * Télécommande plein écran dédiée au Chromecast vidéo.
 *
 * Elle privilégie le relais vers PlayerActivity afin de réutiliser exactement la logique du
 * lecteur (file d'attente, changement de source, stop et seeks). Si le player a été fermé pendant
 * que le Cast continue, elle pilote directement le MediaController conservé par
 * VideoPlaybackService et l'état de file persisté.
 */
class ChromecastRemoteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChromecastRemoteBinding
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private var routeButtonReady = false
    private var isUserSeeking = false
    private var timelineDurationMs = 0L
    private var seekCommitUntilMs = 0L

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 600L)
        }
    }

    private val castControlViews: List<View>
        get() = listOf(
            binding.btnVolumeUp,
            binding.btnVolumeDown,
            binding.btnMute,
            binding.btnRewind,
            binding.btnForward,
            binding.btnPlayPause,
            binding.btnPrevious,
            binding.btnNext,
            binding.btnStop
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChromecastRemoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = ContextCompat.getColor(this, R.color.background)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.background)

        // L'activité peut être affichée en edge-to-edge selon le thème global. La coque de la
        // télécommande reste toujours sous les barres système, avec une marge régulière.
        val safeMargin = dp(10)
        ViewCompat.setOnApplyWindowInsetsListener(binding.remoteRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                bars.left + safeMargin,
                bars.top + safeMargin,
                bars.right + safeMargin,
                bars.bottom + safeMargin
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.remoteRoot)

        binding.tvLedStatus.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPower.setOnClickListener { openCastPanel() }

        binding.btnPlayPause.setOnClickListener {
            executePlayerCommand(ChromecastRemoteCommandBridge.Command.PLAY_PAUSE)
        }
        binding.btnPrevious.setOnClickListener {
            executePlayerCommand(ChromecastRemoteCommandBridge.Command.PREVIOUS)
        }
        binding.btnNext.setOnClickListener {
            executePlayerCommand(ChromecastRemoteCommandBridge.Command.NEXT)
        }
        binding.btnRewind.setOnClickListener {
            executePlayerCommand(ChromecastRemoteCommandBridge.Command.SEEK_BACK)
        }
        binding.btnForward.setOnClickListener {
            executePlayerCommand(ChromecastRemoteCommandBridge.Command.SEEK_FORWARD)
        }
        binding.btnStop.setOnClickListener {
            executePlayerCommand(ChromecastRemoteCommandBridge.Command.STOP)
        }
        binding.btnVolumeUp.setOnClickListener { adjustCastVolume(+0.05) }
        binding.btnVolumeDown.setOnClickListener { adjustCastVolume(-0.05) }
        binding.btnMute.setOnClickListener { toggleCastMute() }
        binding.seekPosition.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = true
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || timelineDurationMs <= 0L) return
                val target = timelineDurationMs * progress.toLong() / seekBar.max.coerceAtLeast(1)
                binding.tvPosition.text = formatTime(target)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val duration = timelineDurationMs
                if (duration > 0L) {
                    val target = duration * seekBar.progress.toLong() / seekBar.max.coerceAtLeast(1)
                    seekToPosition(target)
                    seekCommitUntilMs = SystemClock.uptimeMillis() + 900L
                }
                isUserSeeking = false
                handler.postDelayed({ updateUi() }, 180L)
            }
        })

        setupRouteButton()
        setControlsEnabled(false)
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onDestroy()
    }

    private fun setupRouteButton() {
        binding.hiddenCastRouteButton.post {
            try {
                binding.hiddenCastRouteButton.setDialogFactory(BlazeMediaRouteDialogFactory())
                CastButtonFactory.setUpMediaRouteButton(this, binding.hiddenCastRouteButton)
                routeButtonReady = true
            } catch (e: Exception) {
                routeButtonReady = false
                CrashReporter.log(applicationContext, "Cast remote route button setup failed", e)
            }
        }
    }

    private fun openCastPanel() {
        if (routeButtonReady) {
            binding.hiddenCastRouteButton.performClick()
        } else {
            setupRouteButton()
            binding.hiddenCastRouteButton.postDelayed({
                if (routeButtonReady) binding.hiddenCastRouteButton.performClick()
            }, 180L)
        }
    }

    private fun connectToVideoService() {
        if (controller != null || controllerFuture != null) return
        try {
            startService(Intent(this, VideoPlaybackService::class.java))
            val token = SessionToken(this, ComponentName(this, VideoPlaybackService::class.java))
            controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
                future.addListener({
                    try {
                        controller = future.get()
                        runOnUiThread { updateUi() }
                    } catch (e: Exception) {
                        controllerFuture = null
                        CrashReporter.log(applicationContext, "Cast remote MediaController connection failed", e)
                    }
                }, MoreExecutors.directExecutor())
            }
        } catch (e: Exception) {
            CrashReporter.log(applicationContext, "Cast remote could not start video service", e)
        }
    }

    private fun currentCastSession(): CastSession? {
        return try {
            CastContext.getSharedInstance(applicationContext)
                .sessionManager
                .currentCastSession
        } catch (_: Exception) {
            null
        }
    }

    private fun executePlayerCommand(command: ChromecastRemoteCommandBridge.Command) {
        val session = currentCastSession()
        if (session?.isConnected != true) return

        if (ChromecastRemoteCommandBridge.dispatch(command)) {
            handler.postDelayed({ updateUi() }, 120L)
            return
        }

        val player = controller
        try {
            when (command) {
                ChromecastRemoteCommandBridge.Command.PLAY_PAUSE -> {
                    val remote = session.remoteMediaClient
                    when {
                        remote?.isPlaying == true -> remote.pause()
                        remote != null -> remote.play()
                        player?.isPlaying == true -> player.pause()
                        player != null -> player.play()
                    }
                }
                ChromecastRemoteCommandBridge.Command.SEEK_BACK -> seekBy(-10_000L)
                ChromecastRemoteCommandBridge.Command.SEEK_FORWARD -> seekBy(10_000L)
                ChromecastRemoteCommandBridge.Command.PREVIOUS -> playAdjacent(-1)
                ChromecastRemoteCommandBridge.Command.NEXT -> playAdjacent(1)
                ChromecastRemoteCommandBridge.Command.STOP -> {
                    // Garde la session Cast disponible, mais reproduit visuellement un vrai stop.
                    session.remoteMediaClient?.pause()
                    session.remoteMediaClient?.let { seekRemote(it, 0L) }
                    player?.pause()
                    player?.seekTo(0L)
                }
            }
        } catch (e: Exception) {
            CrashReporter.log(applicationContext, "Cast remote command failed: $command", e)
        }
        handler.postDelayed({ updateUi() }, 180L)
    }

    private fun seekBy(deltaMs: Long) {
        val session = currentCastSession() ?: return
        val remote = session.remoteMediaClient
        if (remote != null) {
            val duration = remote.streamDuration.takeIf { it > 0L } ?: Long.MAX_VALUE
            val target = (remote.approximateStreamPosition + deltaMs).coerceIn(0L, duration)
            seekRemote(remote, target)
            return
        }
        controller?.let { player ->
            val duration = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, duration))
        }
    }

    private fun seekRemote(remote: RemoteMediaClient, positionMs: Long) {
        remote.seek(
            MediaSeekOptions.Builder()
                .setPosition(positionMs.coerceAtLeast(0L))
                .build()
        )
    }

    private fun seekToPosition(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        try {
            val remote = currentCastSession()?.remoteMediaClient
            if (remote != null) {
                seekRemote(remote, target)
            } else {
                controller?.seekTo(target)
            }
        } catch (e: Exception) {
            CrashReporter.log(applicationContext, "Cast remote precise seek failed", e)
        }
    }

    private fun playAdjacent(offset: Int) {
        val state = VideoRemoteQueueState.load(this)
        val player = controller
        if (state != null && player != null) {
            val targetIndex = state.index + offset
            if (targetIndex in state.paths.indices) {
                val path = state.paths[targetIndex]
                val name = state.names.getOrElse(targetIndex) { path.substringAfterLast('/') }
                val item = VideoMediaItemFactory.build(this, path, name)
                player.setMediaItem(item, 0L)
                player.playWhenReady = true
                player.prepare()
                player.play()
                VideoRemoteQueueState.updateIndex(this, targetIndex)
                return
            }
            if (offset < 0 && state.index == 0) {
                player.seekTo(0L)
                return
            }
        }

        val remote = currentCastSession()?.remoteMediaClient ?: return
        if (offset > 0) remote.queueNext(null) else remote.queuePrev(null)
    }

    private fun adjustCastVolume(delta: Double) {
        val session = currentCastSession() ?: return
        try {
            if (session.isMute && delta > 0) session.isMute = false
            session.volume = (session.volume + delta).coerceIn(0.0, 1.0)
        } catch (e: Exception) {
            CrashReporter.log(applicationContext, "Cast remote volume command failed", e)
        }
        handler.postDelayed({ updateUi() }, 120L)
    }

    private fun toggleCastMute() {
        val session = currentCastSession() ?: return
        try {
            session.isMute = !session.isMute
        } catch (e: Exception) {
            CrashReporter.log(applicationContext, "Cast remote mute command failed", e)
        }
        handler.postDelayed({ updateUi() }, 120L)
    }

    private fun updateUi() {
        if (isFinishing || isDestroyed) return
        val session = currentCastSession()
        val connected = session?.isConnected == true
        if (connected && controller == null && controllerFuture == null) connectToVideoService()
        setControlsEnabled(connected)

        val ledColor = ContextCompat.getColor(this, if (connected) R.color.green_accent else R.color.red_accent)
        binding.tvLedStatus.text = getString(
            if (connected) R.string.cast_remote_led_on else R.string.cast_remote_led_off
        )
        binding.tvLedStatus.setTextColor(ledColor)
        binding.tvLedStatus.setShadowLayer(10f, 0f, 0f, ledColor)
        binding.tvDevice.text = session?.castDevice?.friendlyName
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.casting_chromecast)

        val remoteClient = session?.remoteMediaClient
        val mediaTitle = remoteClient?.mediaInfo?.metadata
            ?.getString(MediaMetadata.KEY_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: controller?.currentMediaItem?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.cast_remote_no_media)
        binding.tvMediaTitle.text = mediaTitle

        val playing = remoteClient?.isPlaying == true || controller?.isPlaying == true
        binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnPlayPause.contentDescription = getString(R.string.action_play_pause)

        val muted = session?.isMute == true
        binding.btnMute.setImageResource(if (muted) R.drawable.ic_volume_mute_remote else R.drawable.ic_volume)
        binding.btnMute.alpha = if (muted) 0.72f else if (connected) 1f else 0.38f

        binding.tvVolume.text = if (connected) {
            "${((session?.volume ?: 0.0) * 100.0).toInt().coerceIn(0, 100)}%"
        } else {
            "--%"
        }

        updateTimeline(connected, remoteClient)
    }

    private fun updateTimeline(
        connected: Boolean,
        remoteClient: RemoteMediaClient?
    ) {
        val remoteDuration = remoteClient?.streamDuration?.takeIf { it > 0L }
        val localDuration = controller?.duration?.takeIf { it > 0L && it != C.TIME_UNSET }
        val duration = remoteDuration ?: localDuration ?: 0L
        val remotePosition = remoteClient?.approximateStreamPosition?.coerceAtLeast(0L)
        val localPosition = controller?.currentPosition?.coerceAtLeast(0L)
        val position = (remotePosition ?: localPosition ?: 0L).coerceAtMost(duration.coerceAtLeast(0L))

        timelineDurationMs = duration
        val seekEnabled = connected && duration > 0L
        binding.seekPosition.isEnabled = seekEnabled
        binding.timelineContainer.alpha = if (seekEnabled) 1f else 0.45f
        binding.tvDuration.text = if (duration > 0L) formatTime(duration) else "--:--"

        if (!isUserSeeking && SystemClock.uptimeMillis() >= seekCommitUntilMs) {
            binding.tvPosition.text = formatTime(position)
            binding.seekPosition.progress = if (duration > 0L) {
                ((position * binding.seekPosition.max) / duration).toInt()
                    .coerceIn(0, binding.seekPosition.max)
            } else {
                0
            }
        }
    }

    private fun formatTime(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setControlsEnabled(enabled: Boolean) {
        castControlViews.forEach { view ->
            view.isEnabled = enabled
            if (view !== binding.btnMute) view.alpha = if (enabled) 1f else 0.38f
        }
        binding.remoteControls.alpha = if (enabled) 1f else 0.72f
    }
}
