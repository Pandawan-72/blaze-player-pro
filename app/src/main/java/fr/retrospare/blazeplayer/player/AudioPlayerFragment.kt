package fr.retrospare.blazeplayer.player

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
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
    private val sharedAudioVm: fr.retrospare.blazeplayer.home.SharedAudioViewModel by activityViewModels()
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
                savePlaylist()
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

        val vmTracks = sharedAudioVm.playlist.value
        if (vmTracks.isNotEmpty()) {
            // Restaure depuis ViewModel
            val items = vmTracks.map { PlaylistItem(it.path, it.name) }.toMutableList()
            val vmIndex = sharedAudioVm.currentIndex.value
            setupPlaylistWithItems(items, vmIndex)
        } else {
            setupPlaylist("", "")
        }
        setupControls()
        setupSeekBar()
        startProgressUpdate()
        startDancerAnimation()

        requireContext().startService(Intent(requireContext(), AudioPlaybackService::class.java))

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
        svc.onPrev = { handler.post { playPrev() } }
        svc.onNext = { handler.post { playNext() } }
        svc.onPlaybackChanged = { playing ->
            _binding?.btnPlayPause?.setImageResource(
                if (playing) fr.retrospare.blazeplayer.R.drawable.ic_pause
                else fr.retrospare.blazeplayer.R.drawable.ic_play
            )
        }
        svc.play(path, name)
    }

    private fun setupPlaylistWithItems(items: MutableList<PlaylistItem>, savedIndex: Int) {
        playlistAdapter = PlaylistAdapter(items) { index ->
            currentIndex = index
            val item = playlistAdapter.getItems()[index]
            playlistAdapter.setCurrentIndex(currentIndex)
            savePlaylist()
            doPlay(item.path, item.name)
            loadMetadata(item.path, item.name)
        }
        currentIndex = savedIndex.coerceAtMost((items.size - 1).coerceAtLeast(0))
        playlistAdapter.setCurrentIndex(currentIndex)
        binding.recyclerPlaylist.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            adapter = playlistAdapter
        }
        // Branche les callbacks du service une seule fois
        handler.postDelayed({
            AudioPlaybackService.instance?.let { svc ->
                svc.onPrev = { handler.post { playPrev() } }
                svc.onNext = { handler.post { playNext() } }
                svc.onPlaybackChanged = { playing ->
                    _binding?.btnPlayPause?.setImageResource(
                        if (playing) fr.retrospare.blazeplayer.R.drawable.ic_pause
                        else fr.retrospare.blazeplayer.R.drawable.ic_play
                    )
                }
            }
        }, 500)

        binding.btnCleanPlaylist.setOnClickListener { showCleanDialog() }
        binding.btnAddFolder.setOnClickListener {
            pickAudio.launch(Intent(requireContext(), AudioBrowserActivity::class.java))
        }
        binding.btnEq.setOnClickListener {
            if (eqManager == null) {
                val service = AudioPlaybackService.instance
                val sessionId = service?.exoPlayer?.audioSessionId ?: 0
                if (sessionId != 0) try { eqManager = EqualizerManager(sessionId, requireContext()) } catch (e: Exception) {}
            }
            eqManager?.let { eq -> EqualizerDialog(eq).show(parentFragmentManager, "eq") }
        }
    }

    private fun setupPlaylist(path: String, name: String) {
        if (::playlistAdapter.isInitialized) {
            if (path.isNotEmpty() && playlistAdapter.getItems().none { it.path == path }) {
                playlistAdapter.addItem(PlaylistItem(path, name))
                savePlaylist()
            }
            return
        }
        // Charge la playlist sauvegardée
        val saved = loadPlaylist().distinctBy { it.name } // supprime les doublons
        val items = saved.toMutableList().also { list ->
            if (path.isNotEmpty() && list.none { it.path == path }) list.add(PlaylistItem(path, name))
        }.ifEmpty { if (path.isNotEmpty()) mutableListOf(PlaylistItem(path, name)) else mutableListOf() }
        playlistAdapter = PlaylistAdapter(items) { index ->
            currentIndex = index
            val item = playlistAdapter.getItems()[index]
            playlistAdapter.setCurrentIndex(currentIndex)
            savePlaylist()
            doPlay(item.path, item.name)
            loadMetadata(item.path, item.name)
        }
        val savedIndex = requireContext().getSharedPreferences("blaze_playlist", android.content.Context.MODE_PRIVATE).getInt("index", 0)
        playlistAdapter.setCurrentIndex(savedIndex.coerceAtMost(items.size - 1).coerceAtLeast(0))
        binding.recyclerPlaylist.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            adapter = playlistAdapter
        }
        // N'enregistre les listeners qu'une seule fois
        if (!binding.btnAddFolder.hasOnClickListeners()) {
            binding.btnCleanPlaylist.setOnClickListener { showCleanDialog() }
            binding.btnAddFolder.setOnClickListener {
                pickAudio.launch(Intent(requireContext(), AudioBrowserActivity::class.java))
            }
        }
        binding.btnEq.setOnClickListener {
            // Initialise EQ si pas encore fait
            if (eqManager == null) {
                val service = AudioPlaybackService.instance
                val sessionId = service?.exoPlayer?.audioSessionId ?: 0
                if (sessionId != 0) {
                    try {
                        eqManager = EqualizerManager(sessionId, requireContext())
                    } catch (e: Exception) {}
                }
            }
            eqManager?.let { eq -> EqualizerDialog(eq).show(parentFragmentManager, "eq") }

        }
    }

    private fun saveToHistory(path: String, name: String) {
        val ext = name.substringAfterLast(".", "").lowercase()
        val item = AppMediaItem(id = path, name = name, path = path,
            extension = ext, mimeType = "audio/$ext",
            isNetwork = false, lastPlayedAt = System.currentTimeMillis())
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { mediaRepository.saveRecentItem(item) }
    }

    private fun loadMetadata(path: String, fileName: String) {
        val ext = fileName.substringAfterLast(".", "mp3").uppercase()
        _binding?.tvCodec?.text = ext
        _binding?.tvCodec?.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
                viewLifecycleOwner.lifecycleScope.launch {
                    _binding?.tvTitle?.text = title
                    _binding?.tvArtist?.text = artist
                    _binding?.tvAlbum?.text = album
                    _binding?.tvCodec?.text = ext
                    _binding?.tvBitrate?.visibility = if (bitrate != null) View.VISIBLE else View.GONE
                    _binding?.tvBitrate?.text = if (bitrate != null) "${bitrate / 1000} kbps" else ""
                    if (bitmap != null) _binding?.ivArtwork?.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                viewLifecycleOwner.lifecycleScope.launch { _binding?.tvTitle?.text = fileName }
            }
        }
    }

    private fun setupControls() {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetBehavior.from(binding.playlistSheet)
        bottomSheet.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
        binding.btnPlaylistSheet.setOnClickListener {
            bottomSheet.state = if (bottomSheet.state == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN)
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            else
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
        }

        binding.btnPlayPause.setOnClickListener {
            val svc = AudioPlaybackService.instance ?: run {

                return@setOnClickListener
            }

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
                        (binding.btnSleepTimer.getChildAt(0) as? android.widget.ImageView)?.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent))
                        sleepTimerJob = viewLifecycleOwner.lifecycleScope.launch {
                            delay(minutes * 60 * 1000)
                            AudioPlaybackService.instance?.pause()
                            (_binding?.btnSleepTimer?.getChildAt(0) as? android.widget.ImageView)?.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant))
                        }
                    } else {
                        (binding.btnSleepTimer.getChildAt(0) as? android.widget.ImageView)?.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant))
                    }
                }.show()
        }
    }

    private fun playNext() {
        val items = playlistAdapter.getItems()
        if (items.isEmpty()) return
        val svc = AudioPlaybackService.instance
        when {
            repeatMode == 2 -> {
                val item = items[currentIndex]
                handler.postDelayed({ doPlay(item.path, item.name)
                    handler.postDelayed({ AudioPlaybackService.instance?.play() }, 500) }, 100)
            }
            isShuffled -> {
                currentIndex = (0 until items.size).filter { it != currentIndex }.randomOrNull() ?: 0
                val item = items[currentIndex]
                playlistAdapter.setCurrentIndex(currentIndex)
                loadMetadata(item.path, item.name)
                handler.postDelayed({ doPlay(item.path, item.name)
                    handler.postDelayed({ AudioPlaybackService.instance?.play() }, 500) }, 100)
            }
            currentIndex < items.size - 1 -> {
                currentIndex++
                val item = items[currentIndex]
                playlistAdapter.setCurrentIndex(currentIndex)
                loadMetadata(item.path, item.name)
                handler.postDelayed({
                    doPlay(item.path, item.name)
                    handler.postDelayed({ AudioPlaybackService.instance?.play() }, 500)
                }, 100)
            }
            repeatMode == 1 -> {
                currentIndex = 0
                val item = items[0]
                playlistAdapter.setCurrentIndex(0)
                loadMetadata(item.path, item.name)
                handler.postDelayed({
                    doPlay(item.path, item.name)
                    handler.postDelayed({ AudioPlaybackService.instance?.play() }, 500)
                }, 100)
            }
        }
    }

    private fun playPrev() {
        val items = playlistAdapter.getItems()
        if (items.isEmpty()) return
        val svc = AudioPlaybackService.instance
        when {
            repeatMode == 2 -> {
                val item = items[currentIndex]
                handler.postDelayed({ doPlay(item.path, item.name)
                    handler.postDelayed({ AudioPlaybackService.instance?.play() }, 500) }, 100)
            }
            isShuffled -> {
                currentIndex = (0 until items.size).filter { it != currentIndex }.randomOrNull() ?: 0
                val item = items[currentIndex]
                playlistAdapter.setCurrentIndex(currentIndex)
                loadMetadata(item.path, item.name)
                handler.postDelayed({ doPlay(item.path, item.name)
                    handler.postDelayed({ AudioPlaybackService.instance?.play() }, 500) }, 100)
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
        // Sync playlist vers ViewModel avant destruction de la vue
        if (::playlistAdapter.isInitialized) {
            sharedAudioVm.setPlaylist(
                playlistAdapter.getItems().map { fr.retrospare.blazeplayer.home.AudioTrack(it.path, it.name) }
            )
            sharedAudioVm.setCurrentIndex(currentIndex)
        }
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        sleepTimerJob?.cancel()
        eqManager?.release()

    }
    fun savePlaylist() {
        val items = if (::playlistAdapter.isInitialized) playlistAdapter.getItems() else return
        val prefs = requireContext().getSharedPreferences("blaze_playlist", android.content.Context.MODE_PRIVATE)
        val json = org.json.JSONArray().apply {
            items.forEach { item ->
                put(org.json.JSONObject().put("path", item.path).put("name", item.name))
            }
        }
        prefs.edit().putString("items", json.toString()).putInt("index", currentIndex).apply()
    }

    private fun loadPlaylist(): List<PlaylistItem> {
        val prefs = requireContext().getSharedPreferences("blaze_playlist", android.content.Context.MODE_PRIVATE)
        val json = prefs.getString("items", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                PlaylistItem(obj.getString("path"), obj.getString("name"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun showCleanDialog() {
        val items = if (::playlistAdapter.isInitialized) playlistAdapter.getItems().toList() else return
        if (items.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "Liste déjà vide", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val checked = BooleanArray(items.size) { false }
        val names = items.map { it.name }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Nettoyer la liste")
            .setMultiChoiceItems(names, checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton("Retirer sélection") { _, _ ->
                val toRemove = items.filterIndexed { i, _ -> checked[i] }
                if (toRemove.isEmpty()) return@setPositiveButton
                toRemove.forEach { playlistAdapter.removeItem(it) }
                savePlaylist()
            }
            .setNeutralButton("Tout effacer") { _, _ ->
                playlistAdapter.clearAll()
                savePlaylist()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }


}
