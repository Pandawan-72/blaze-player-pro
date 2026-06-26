package fr.retrospare.blazeplayer.player

import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.data.model.MediaItem as AppMediaItem
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.databinding.ActivityAudioPlayerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AudioPlayerActivity : AppCompatActivity() {

    @Inject lateinit var mediaRepository: MediaRepository
    private lateinit var binding: ActivityAudioPlayerBinding
    private lateinit var playlistAdapter: PlaylistAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var isSeekBarTracking = false
    private var currentIndex = 0
    private var dancerFrame = 0
    private var eqManager: EqualizerManager? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val dancerFrames = listOf(
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_1,
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_2
    )
    private val dancerFFrames = listOf(
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_f1,
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_f2
    )

    private val pickAudio = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val paths = result.data?.getStringArrayListExtra(AudioBrowserActivity.EXTRA_PATHS) ?: return@registerForActivityResult
            val names = result.data?.getStringArrayListExtra(AudioBrowserActivity.EXTRA_NAMES) ?: return@registerForActivityResult
            paths.forEachIndexed { i, path ->
                if (::playlistAdapter.isInitialized) {
                    playlistAdapter.addItem(PlaylistItem(path, names[i]))
                    binding.recyclerPlaylist.scrollToPosition(playlistAdapter.itemCount - 1)
                    // Ajoute aussi au MediaController
                    val mediaItem = ExoMediaItem.Builder()
                        .setUri(Uri.parse(path))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(names[i]).build())
                        .build()
                    controller?.addMediaItem(mediaItem)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityAudioPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("mediaPath") ?: return finish()
        val name = intent.getStringExtra("mediaName") ?: ""

        // Retour Android = minimise
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    moveTaskToBack(true)
                }
            }
        )
        setupPlaylistUI(path, name)
        setupControls()
        setupSeekBar()
        loadMetadata(path, name)
        startDancerAnimation()
        connectToService(path, name)
    }

    private fun connectToService(path: String, name: String) {
        val sessionToken = SessionToken(this, ComponentName(this, AudioPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            controller?.let { ctrl ->
                // Init EQ
                // Note: audioSessionId non disponible via MediaController, on init EQ après
                
                // Charge la piste
                val mediaItem = ExoMediaItem.Builder()
                    .setUri(Uri.parse(path))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
                    .build()
                ctrl.setMediaItem(mediaItem)
                ctrl.prepare()
                ctrl.play()

                ctrl.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        binding.btnPlayPause.setImageResource(
                            if (isPlaying) fr.retrospare.blazeplayer.R.drawable.ic_pause
                            else fr.retrospare.blazeplayer.R.drawable.ic_play
                        )
                    }
                })

                startProgressUpdate()
                saveToHistory(path, name)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlaylistUI(path: String, name: String) {
        playlistAdapter = PlaylistAdapter(mutableListOf(PlaylistItem(path, name))) { index ->
            currentIndex = index
            val item = playlistAdapter.getItems()[index]
            playlistAdapter.setCurrentIndex(index)
            controller?.seekTo(index, 0)
            loadMetadata(item.path, item.name)
        }
        playlistAdapter.setCurrentIndex(0)
        binding.recyclerPlaylist.apply {
            layoutManager = LinearLayoutManager(this@AudioPlayerActivity)
            adapter = playlistAdapter
        }
        binding.btnAddFolder.setOnClickListener {
            pickAudio.launch(Intent(this, AudioBrowserActivity::class.java))
        }
        binding.btnEq.setOnClickListener {
            eqManager?.let { EqualizerDialog(it).show(supportFragmentManager, "eq") }
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { moveTaskToBack(true) }
        binding.btnPlayPause.setOnClickListener {
            val ctrl = controller ?: return@setOnClickListener
            if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
        }
        binding.btnRewind.setOnClickListener {
            controller?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
        }
        binding.btnForward.setOnClickListener {
            controller?.let { it.seekTo((it.currentPosition + 10_000).coerceAtMost(it.duration)) }
        }
        binding.btnPrev.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                playlistAdapter.setCurrentIndex(currentIndex)
                controller?.seekToPreviousMediaItem()
                loadMetadata(playlistAdapter.getItems()[currentIndex].path, playlistAdapter.getItems()[currentIndex].name)
            }
        }
        binding.btnNext.setOnClickListener {
            val items = playlistAdapter.getItems()
            if (currentIndex < items.size - 1) {
                currentIndex++
                playlistAdapter.setCurrentIndex(currentIndex)
                controller?.seekToNextMediaItem()
                loadMetadata(items[currentIndex].path, items[currentIndex].name)
            }
        }
    }

    private fun loadMetadata(path: String, fileName: String) {
        val ext = fileName.substringAfterLast('.', "").ifEmpty { path.substringAfterLast('.', "mp3") }.uppercase()
        runOnUiThread {
            binding.tvCodec.visibility = android.view.View.VISIBLE
            binding.tvCodec.text = ext
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val retriever = MediaMetadataRetriever()
                if (path.startsWith("content://")) retriever.setDataSource(this@AudioPlayerActivity, Uri.parse(path))
                else retriever.setDataSource(path)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fileName.substringBeforeLast(".")
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Artiste inconnu"
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                val art = retriever.embeddedPicture?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                retriever.release()
                runOnUiThread {
                    binding.tvTitle.text = title
                    binding.tvArtist.text = artist
                    binding.tvAlbum.text = album
                    binding.tvBitrate.visibility = if (bitrate != null) android.view.View.VISIBLE else android.view.View.GONE
                    binding.tvBitrate.text = if (bitrate != null) "${bitrate / 1000} kbps" else ""
                    art?.let { binding.ivArtwork.setImageBitmap(it) }
                }
            } catch (e: Exception) {
                runOnUiThread { binding.tvTitle.text = fileName }
            }
        }
    }

    private fun saveToHistory(path: String, name: String) {
        val ext = name.substringAfterLast('.', "").lowercase()
        CoroutineScope(Dispatchers.IO).launch {
            mediaRepository.saveRecentItem(AppMediaItem(
                id = path, name = name, path = path,
                extension = ext, mimeType = "audio/$ext",
                isNetwork = false, lastPlayedAt = System.currentTimeMillis()
            ))
        }
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = controller?.duration ?: return
                    if (dur > 0) controller?.seekTo(dur * progress / 100)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { isSeekBarTracking = true }
            override fun onStopTrackingTouch(sb: SeekBar) { isSeekBarTracking = false }
        })
    }

    private fun startProgressUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                val ctrl = controller
                if (!isSeekBarTracking && ctrl != null && ctrl.duration > 0) {
                    binding.seekBar.progress = (ctrl.currentPosition * 100 / ctrl.duration).toInt()
                    binding.tvCurrentTime.text = formatTime(ctrl.currentPosition)
                    binding.tvTotalTime.text = formatTime(ctrl.duration)
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun startDancerAnimation() {
        handler.post(object : Runnable {
            override fun run() {
                if (controller?.isPlaying == true) {
                    dancerFrame = (dancerFrame + 1) % 2
                    binding.ivPixelChar.setImageResource(dancerFrames[dancerFrame])
                    binding.ivPixelCharLeft.setImageResource(dancerFFrames[(dancerFrame + 1) % 2])
                }
                handler.postDelayed(this, 300)
            }
        })
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onStop() {
        super.onStop()
        // Ne pas release - la lecture continue en arrière-plan
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        eqManager?.release()
        if (isFinishing) {
            stopService(Intent(this, AudioPlaybackService::class.java))
        }
    }
}
