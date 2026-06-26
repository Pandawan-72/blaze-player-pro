package fr.retrospare.blazeplayer.player

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.data.model.MediaItem as AppMediaItem
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.databinding.ActivityAudioPlayerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import fr.retrospare.blazeplayer.player.EqualizerManager
import fr.retrospare.blazeplayer.player.EqualizerDialog

@AndroidEntryPoint
class AudioPlayerActivity : AppCompatActivity() {

    @Inject lateinit var mediaRepository: MediaRepository
    private var eqManager: EqualizerManager? = null
    private lateinit var binding: ActivityAudioPlayerBinding
    private lateinit var player: ExoPlayer
    private lateinit var playlistAdapter: PlaylistAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var isSeekBarTracking = false
    private var currentIndex = 0
    private var dancerFrame = 0
    private val dancerFrames = listOf(
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_1,
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_2
    )

    private val pickAudio = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val paths = result.data?.getStringArrayListExtra(AudioBrowserActivity.EXTRA_PATHS) ?: return@registerForActivityResult
            val names = result.data?.getStringArrayListExtra(AudioBrowserActivity.EXTRA_NAMES) ?: return@registerForActivityResult
            paths.forEachIndexed { i, path ->
                if (::playlistAdapter.isInitialized) {
                    playlistAdapter.addItem(PlaylistItem(path, names[i]))
                    binding.recyclerPlaylist.scrollToPosition(playlistAdapter.itemCount - 1)
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

        setupPlaylist(path, name)
        setupPlayer(path, name)
        loadMetadata(path, name)
        setupControls()
        setupSeekBar()
        startProgressUpdate()
        startDancerAnimation()
    }

    private fun setupPlaylist(path: String, name: String) {
        val initialItem = PlaylistItem(path, name)
        playlistAdapter = PlaylistAdapter(mutableListOf(initialItem)) { index ->
            currentIndex = index
            val item = playlistAdapter.getItems()[index]
            playlistAdapter.setCurrentIndex(currentIndex)
            player.setMediaItem(ExoMediaItem.fromUri(Uri.parse(item.path)))
            player.prepare()
            player.play()
            loadMetadata(item.path, item.name)
        }
        playlistAdapter.setCurrentIndex(0)

        binding.recyclerPlaylist.apply {
            layoutManager = LinearLayoutManager(this@AudioPlayerActivity)
            adapter = playlistAdapter
        }

        binding.btnAddFolder.setOnClickListener {
            val intent = Intent(this, AudioBrowserActivity::class.java)
            pickAudio.launch(intent)
        }

        binding.btnEq.setOnClickListener {
            eqManager?.let { eq ->
                EqualizerDialog(eq).show(supportFragmentManager, "eq")
            }
        }
    }

    private fun setupPlayer(path: String, name: String) {
        player = ExoPlayer.Builder(this).build()
        player.setMediaItem(ExoMediaItem.fromUri(Uri.parse(path)))
        player.prepare()
        player.play()
        saveToHistory(path, name)
        eqManager = EqualizerManager(player.audioSessionId, this).also { it.restoreLastSession() }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.btnPlayPause.setImageResource(
                    if (isPlaying) fr.retrospare.blazeplayer.R.drawable.ic_pause
                    else fr.retrospare.blazeplayer.R.drawable.ic_play
                )
            }
        })
    }

    private fun saveToHistory(path: String, name: String) {
        val ext = name.substringAfterLast('.', "").lowercase()
        val item = AppMediaItem(
            id = path,
            name = name,
            path = path,
            extension = ext,
            mimeType = "audio/$ext",
            isNetwork = false,
            lastPlayedAt = System.currentTimeMillis()
        )
        CoroutineScope(Dispatchers.IO).launch {
            mediaRepository.saveRecentItem(item)
        }
    }

    private fun loadMetadata(path: String, fileName: String) {
        val extImmediate = fileName.substringAfterLast('.', "")
            .ifEmpty { path.substringAfterLast('.', "mp3") }
            .uppercase()
        runOnUiThread {
            binding.tvCodec.visibility = android.view.View.VISIBLE
            binding.tvCodec.text = extImmediate
        }

        try {
            val retriever = MediaMetadataRetriever()
            if (path.startsWith("content://")) {
                retriever.setDataSource(this, Uri.parse(path))
            } else {
                retriever.setDataSource(path)
            }
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: fileName.substringBeforeLast(".")
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Artiste inconnu"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            val ext = fileName.substringAfterLast('.', "").uppercase()

            binding.tvTitle.text = title
            binding.tvArtist.text = artist
            binding.tvAlbum.text = album
            binding.tvCodec.visibility = android.view.View.VISIBLE
            binding.tvCodec.text = ext
            binding.tvBitrate.visibility = if (bitrate != null) android.view.View.VISIBLE else android.view.View.GONE
            binding.tvBitrate.text = if (bitrate != null) "${bitrate / 1000} kbps" else ""

            val art = retriever.embeddedPicture
            if (art != null) {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
                binding.ivArtwork.setImageBitmap(bitmap)
            }
            retriever.release()
        } catch (e: Exception) {
            binding.tvTitle.text = fileName
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener {
            if (player.isPlaying) player.pause() else player.play()
        }
        binding.btnRewind.setOnClickListener {
            player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
        }
        binding.btnForward.setOnClickListener {
            player.seekTo((player.currentPosition + 10_000).coerceAtMost(player.duration))
        }
        binding.btnPrev.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                val item = playlistAdapter.getItems()[currentIndex]
                playlistAdapter.setCurrentIndex(currentIndex)
                player.setMediaItem(ExoMediaItem.fromUri(Uri.parse(item.path)))
                player.prepare()
                player.play()
                loadMetadata(item.path, item.name)
            }
        }
        binding.btnNext.setOnClickListener {
            val items = playlistAdapter.getItems()
            if (currentIndex < items.size - 1) {
                currentIndex++
                val item = items[currentIndex]
                playlistAdapter.setCurrentIndex(currentIndex)
                player.setMediaItem(ExoMediaItem.fromUri(Uri.parse(item.path)))
                player.prepare()
                player.play()
                loadMetadata(item.path, item.name)
            }
        }
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && player.duration > 0) {
                    player.seekTo(player.duration * progress / 100)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { isSeekBarTracking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) { isSeekBarTracking = false }
        })
    }

    private fun startProgressUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                if (!isSeekBarTracking && player.duration > 0) {
                    binding.seekBar.progress = (player.currentPosition * 100 / player.duration).toInt()
                    binding.tvCurrentTime.text = formatTime(player.currentPosition)
                    binding.tvTotalTime.text = formatTime(player.duration)
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    private val dancerFFrames = listOf(
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_f1,
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_f2
    )

    private fun startDancerAnimation() {
        handler.post(object : Runnable {
            override fun run() {
                if (player.isPlaying) {
                    dancerFrame = (dancerFrame + 1) % dancerFrames.size
                    binding.ivPixelChar.setImageResource(dancerFrames[dancerFrame])
                    binding.ivPixelCharLeft.setImageResource(dancerFFrames[(dancerFrame + 1) % dancerFFrames.size])
                }
                handler.postDelayed(this, 300)
            }
        })
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        eqManager?.release()
        player.release()
    }
}
