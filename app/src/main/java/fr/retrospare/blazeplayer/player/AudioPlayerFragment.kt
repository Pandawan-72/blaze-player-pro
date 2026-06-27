package fr.retrospare.blazeplayer.player

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.data.model.MediaItem as AppMediaItem
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.databinding.ActivityAudioPlayerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AudioPlayerFragment : Fragment() {

    @Inject lateinit var mediaRepository: MediaRepository
    private var _binding: ActivityAudioPlayerBinding? = null
    private val binding get() = _binding!!
    private var eqManager: EqualizerManager? = null
    private lateinit var playlistAdapter: PlaylistAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var isSeekBarTracking = false
    private var currentIndex = 0
    private var dancerFrame = 0
    private var isShuffled = false
    private var repeatMode = 0
    private var sleepTimerJob: Job? = null

    private val dancerFrames = listOf(
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_1,
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_2
    )
    private val dancerFFrames = listOf(
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_f1,
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_f2
    )

    private val pickAudio = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val paths = result.data?.getStringArrayListExtra(AudioBrowserActivity.EXTRA_PATHS) ?: return@registerForActivityResult
            val names = result.data?.getStringArrayListExtra(AudioBrowserActivity.EXTRA_NAMES) ?: return@registerForActivityResult
            paths.forEachIndexed { i, path ->
                playlistAdapter.addItem(PlaylistItem(path, names[i]))
                binding.recyclerPlaylist.scrollToPosition(playlistAdapter.itemCount - 1)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityAudioPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.visibility = View.VISIBLE
        binding.btnBack.setOnClickListener {
            (parentFragment as? fr.retrospare.blazeplayer.home.HomeFragment)?.returnToHome()
        }

        setupPlaylist("", "")
        setupControls()
        setupSeekBar()
        startProgressUpdate()
        startDancerAnimation()

        requireContext().startForegroundService(Intent(requireContext(), AudioPlaybackService::class.java))

        val path = arguments?.getString("mediaPath") ?: ""
        val name = arguments?.getString("mediaName") ?: ""
        if (path.isNotEmpty()) handler.postDelayed({ doPlay(path, name) }, 500)
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (requireContext().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    fun playPath(path: String, name: String) {
        requestNotificationPermission()
        setupPlaylist(path, name)
        loadMetadata(path, name)
        saveToHistory(path, name)
        handler.postDelayed({ doPlay(path, name) }, 300)
    }

    private fun doPlay(path: String, name: String, retries: Int = 0) {
        android.util.Log.d("BlazeAudio", "doPlay retry=$retries instance=${AudioPlaybackService.instance}")
        val svc = AudioPlaybackService.instance
        if (svc == null) {
            if (retries < 10) handler.postDelayed({ doPlay(path, name, retries + 1) }, 300)
            return
        }
        android.util.Log.d("BlazeAudio", "calling svc.play()")
        svc.onPrev = { playPrev() }
        svc.onNext = { playNext() }
        svc.onPlaybackChanged = { playing ->
            _binding?.btnPlayPause?.setImageResource(
                if (playing) fr.retrospare.blazeplayer.R.drawable.ic_pause
                else fr.retrospare.blazeplayer.R.drawable.ic_play
            )
        }
        svc.play(path, name)
    }

    private fun setupPlaylist(path: String, name: String) {
        if (::playlistAdapter.isInitialized) {
            if (path.isNotEmpty() && playlistAdapter.getItems().none { it.path == path }) {
                playlistAdapter.addItem(PlaylistItem(path, name))
            }
            return
        }
        val items = if (path.isNotEmpty()) mutableListOf(PlaylistItem(path, name)) else mutableListOf()
        playlistAdapter = PlaylistAdapter(items) { index ->
            currentIndex = index
            val item = playlistAdapter.getItems()[index]
            playlistAdapter.setCurrentIndex(currentIndex)
            doPlay(item.path, item.name)
            loadMetadata(item.path, item.name)
        }
        playlistAdapter.setCurrentIndex(0)
        binding.recyclerPlaylist.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            adapter = playlistAdapter
        }
        binding.btnAddFolder.setOnClickListener {
            pickAudio.launch(Intent(requireContext(), AudioBrowserActivity::class.java))
        }
        binding.btnEq.setOnClickListener {
            eqManager?.let { eq -> EqualizerDialog(eq).show(parentFragmentManager, "eq") }
        }
    }

    private fun saveToHistory(path: String, name: String) {
        val ext = name.substringAfterLast(".", "").lowercase()
        val item = AppMediaItem(id = path, name = name, path = path,
            extension = ext, mimeType = "audio/$ext",
            isNetwork = false, lastPlayedAt = System.currentTimeMillis())
        CoroutineScope(Dispatchers.IO).launch { mediaRepository.saveRecentItem(item) }
    }

    private fun loadMetadata(path: String, fileName: String) {
        val ext = fileName.substringAfterLast(".", "mp3").uppercase()
        _binding?.tvCodec?.text = ext
        _binding?.tvCodec?.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val retriever = MediaMetadataRetriever()
                if (path.startsWith("content://")) retriever.setDataSource(requireContext(), Uri.parse(path))
                else retriever.setDataSource(path)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fileName.substringBeforeLast(".")
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Artiste inconnu"
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                val art = retriever.embeddedPicture
                retriever.release()
                val bitmap = art?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                CoroutineScope(Dispatchers.Main).launch {
                    _binding?.tvTitle?.text = title
                    _binding?.tvArtist?.text = artist
                    _binding?.tvAlbum?.text = album
                    _binding?.tvCodec?.text = ext
                    _binding?.tvBitrate?.visibility = if (bitrate != null) View.VISIBLE else View.GONE
                    _binding?.tvBitrate?.text = if (bitrate != null) "${bitrate / 1000} kbps" else ""
                    if (bitmap != null) _binding?.ivArtwork?.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                CoroutineScope(Dispatchers.Main).launch { _binding?.tvTitle?.text = fileName }
            }
        }
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener {
            val svc = AudioPlaybackService.instance ?: run {
                android.widget.Toast.makeText(requireContext(), "svc null", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            android.widget.Toast.makeText(requireContext(), "isPlaying=${svc.isPlaying}", android.widget.Toast.LENGTH_SHORT).show()
            if (svc.isPlaying) svc.pause() else svc.resume()
        }
        binding.btnRewind.setOnClickListener {
            val svc = AudioPlaybackService.instance ?: return@setOnClickListener
            svc.seekTo((svc.currentPosition - 10_000).coerceAtLeast(0))
        }
        binding.btnForward.setOnClickListener {
            val svc = AudioPlaybackService.instance ?: return@setOnClickListener
            svc.seekTo((svc.currentPosition + 10_000).coerceAtMost(svc.duration))
        }
        binding.btnPrev.setOnClickListener { playPrev() }
        binding.btnNext.setOnClickListener { playNext() }

        binding.btnShuffle.setOnClickListener {
            isShuffled = !isShuffled
            binding.btnShuffle.setColorFilter(
                if (isShuffled) requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent)
                else requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant)
            )
        }

        binding.btnRepeat.setOnClickListener {
            repeatMode = (repeatMode + 1) % 3
            when (repeatMode) {
                0 -> { binding.btnRepeat.setImageResource(fr.retrospare.blazeplayer.R.drawable.ic_repeat)
                    binding.btnRepeat.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant)) }
                1 -> { binding.btnRepeat.setImageResource(fr.retrospare.blazeplayer.R.drawable.ic_repeat)
                    binding.btnRepeat.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent)) }
                2 -> { binding.btnRepeat.setImageResource(fr.retrospare.blazeplayer.R.drawable.ic_repeat_one)
                    binding.btnRepeat.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent)) }
            }
        }

        binding.btnSleepTimer.setOnClickListener {
            val options = arrayOf("5 minutes", "15 minutes", "30 minutes", "1 heure", "Annuler")
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Sleep Timer")
                .setItems(options) { _, which ->
                    sleepTimerJob?.cancel()
                    val minutes = when (which) { 0->5L; 1->15L; 2->30L; 3->60L; else->0L }
                    if (minutes > 0) {
                        binding.btnSleepTimer.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent))
                        sleepTimerJob = CoroutineScope(Dispatchers.Main).launch {
                            delay(minutes * 60 * 1000)
                            AudioPlaybackService.instance?.pause()
                            _binding?.btnSleepTimer?.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant))
                        }
                    } else {
                        binding.btnSleepTimer.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant))
                    }
                }.show()
        }
    }

    private fun playNext() {
        val items = playlistAdapter.getItems()
        if (items.isEmpty()) return
        val svc = AudioPlaybackService.instance
        when {
            repeatMode == 2 -> { svc?.seekTo(0); svc?.resume() }
            isShuffled -> {
                currentIndex = (0 until items.size).filter { it != currentIndex }.randomOrNull() ?: 0
                val item = items[currentIndex]; playlistAdapter.setCurrentIndex(currentIndex)
                doPlay(item.path, item.name); loadMetadata(item.path, item.name)
            }
            currentIndex < items.size - 1 -> {
                currentIndex++
                val item = items[currentIndex]; playlistAdapter.setCurrentIndex(currentIndex)
                doPlay(item.path, item.name); loadMetadata(item.path, item.name)
            }
            repeatMode == 1 -> {
                currentIndex = 0
                val item = items[0]; playlistAdapter.setCurrentIndex(0)
                doPlay(item.path, item.name); loadMetadata(item.path, item.name)
            }
        }
    }

    private fun playPrev() {
        val items = playlistAdapter.getItems()
        if (items.isEmpty()) return
        val svc = AudioPlaybackService.instance
        when {
            repeatMode == 2 -> { svc?.seekTo(0); svc?.resume() }
            isShuffled -> {
                currentIndex = (0 until items.size).filter { it != currentIndex }.randomOrNull() ?: 0
                val item = items[currentIndex]; playlistAdapter.setCurrentIndex(currentIndex)
                doPlay(item.path, item.name); loadMetadata(item.path, item.name)
            }
            currentIndex > 0 -> {
                currentIndex--
                val item = items[currentIndex]; playlistAdapter.setCurrentIndex(currentIndex)
                doPlay(item.path, item.name); loadMetadata(item.path, item.name)
            }
            repeatMode == 1 -> {
                currentIndex = items.size - 1
                val item = items[currentIndex]; playlistAdapter.setCurrentIndex(currentIndex)
                doPlay(item.path, item.name); loadMetadata(item.path, item.name)
            }
        }
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val svc = AudioPlaybackService.instance ?: return
                    val dur = svc.duration
                    if (dur > 0) {
                        val targetMs = dur * progress / 100
                        svc.seekTo(targetMs)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { isSeekBarTracking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isSeekBarTracking = false
                val svc = AudioPlaybackService.instance ?: return
                val dur = svc.duration
                if (dur > 0) svc.seekTo(dur * seekBar.progress / 100)
            }
        })
    }

    private fun startProgressUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                val svc = AudioPlaybackService.instance
                val dur = svc?.duration ?: 0
                if (!isSeekBarTracking && dur > 0 && svc != null) {
                    _binding?.seekBar?.progress = ((svc.currentPosition * 100) / dur).toInt()
                    _binding?.tvCurrentTime?.text = formatTime(svc.currentPosition)
                    _binding?.tvTotalTime?.text = formatTime(dur)
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun startDancerAnimation() {
        handler.post(object : Runnable {
            override fun run() {
                if (AudioPlaybackService.instance?.isPlaying == true) {
                    dancerFrame = (dancerFrame + 1) % dancerFrames.size
                    _binding?.ivPixelChar?.setImageResource(dancerFrames[dancerFrame])
                    _binding?.ivPixelCharLeft?.setImageResource(dancerFFrames[(dancerFrame + 1) % dancerFFrames.size])
                }
                handler.postDelayed(this, 300)
            }
        })
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }

    fun stopPlayback() { AudioPlaybackService.instance?.stop() }

    fun returnToHome() {
        (parentFragment as? fr.retrospare.blazeplayer.home.HomeFragment)?.returnToHome()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        sleepTimerJob?.cancel()
        eqManager?.release()

    }
}
