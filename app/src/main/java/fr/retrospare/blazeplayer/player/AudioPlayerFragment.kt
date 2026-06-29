package fr.retrospare.blazeplayer.player

import android.app.Activity
import android.content.ComponentName
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.databinding.ActivityAudioPlayerBinding
import fr.retrospare.blazeplayer.home.SharedAudioViewModel
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
    private val sharedVm: SharedAudioViewModel by activityViewModels()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private lateinit var playlistAdapter: PlaylistAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var isSeekBarTracking = false
    private var sleepTimerJob: Job? = null
    private var eqManager: EqualizerManager? = null
    private var dancerFrame = 0

    private val dancerFrames = listOf(
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_1,
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_2
    )
    private val dancerFFrames = listOf(
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_f1,
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_f2
    )

    // ── Ajout de fichiers depuis le navigateur ─────────────────────────────────
    private val pickAudio = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val paths = result.data?.getStringArrayListExtra(AudioBrowserActivity.EXTRA_PATHS) ?: return@registerForActivityResult
            val names = result.data?.getStringArrayListExtra(AudioBrowserActivity.EXTRA_NAMES) ?: return@registerForActivityResult
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val mediaItems = paths.mapIndexed { i, path ->
                    AudioRepository.buildMediaItemWithMetadata(requireContext(), path, names[i])
                }
                launch(Dispatchers.Main) {
                    paths.forEachIndexed { i, path -> playlistAdapter.addItem(PlaylistItem(path, names[i])) }
                    controller?.addMediaItems(mediaItems)
                    if (controller?.playbackState == Player.STATE_IDLE ||
                        controller?.playbackState == Player.STATE_ENDED) {
                        controller?.prepare()
                        controller?.play()
                    }
                    savePlaylist()
                    binding.recyclerPlaylist.scrollToPosition(playlistAdapter.itemCount - 1)
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityAudioPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            v.setPadding(0, bars.top, 0, 0)
            insets
        }
        binding.btnBack.setOnClickListener {
            (parentFragment as? fr.retrospare.blazeplayer.home.HomeFragment)?.returnToHome()
        }

        initPlaylistUi()
        setupControls()
        setupSeekBar()
        startProgressUpdate()
        connectMediaController()
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.setInAudioPlayer(true)
        syncPlaylist()
        syncMetadata()
        syncButtons()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.setInAudioPlayer(true)
            syncPlaylist()
            syncMetadata()
            syncButtons()
        } else {
            (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.setInAudioPlayer(false)
        }
    }

    override fun onDestroyView() {
        requireActivity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        handler.removeCallbacksAndMessages(null)
        sleepTimerJob?.cancel()
        eqManager?.release()
        savePlaylist()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        _binding = null
        super.onDestroyView()
    }

    // ── MediaController ────────────────────────────────────────────────────────

    private fun connectMediaController() {
        val token = SessionToken(requireContext(), ComponentName(requireContext(), BlazePlayerService::class.java))
        controllerFuture = MediaController.Builder(requireContext(), token).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            onControllerReady()
        }, MoreExecutors.directExecutor())
    }

    private fun onControllerReady() {
        val ctrl = controller ?: return

        // Charge la playlist sauvegardée dans ExoPlayer si vide
        if (ctrl.mediaItemCount == 0) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val (savedItems, savedIndex) = AudioRepository.load(requireContext())
                if (savedItems.isNotEmpty()) {
                    val mediaItems = savedItems.map { AudioRepository.buildMediaItemWithMetadata(requireContext(), it.path, it.name) }
                    launch(Dispatchers.Main) {
                        savedItems.forEach { playlistAdapter.addItem(it) }
                        ctrl.setMediaItems(mediaItems, savedIndex.coerceIn(0, savedItems.size - 1), 0L)
                        ctrl.prepare()
                        syncPlaylist()
                        syncMetadata()
                        syncButtons()
                    }
                }
            }
        } else {
            syncPlaylist()
            syncMetadata()
            syncButtons()
        }

        // Pending tracks depuis SharedViewModel
        val pending = sharedVm.consumePendingTracks()
        if (pending.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                pending.forEach { track ->
                    if (playlistAdapter.getItems().none { it.path == track.path }) {
                        val mediaItem = AudioRepository.buildMediaItemWithMetadata(requireContext(), track.path, track.name)
                        launch(Dispatchers.Main) {
                            playlistAdapter.addItem(PlaylistItem(track.path, track.name))
                            ctrl.addMediaItem(mediaItem)
                        }
                    }
                }
                launch(Dispatchers.Main) {
                    ctrl.prepare()
                    ctrl.play()
                    savePlaylist()
                }
            }
        }

        // Listener natif Media3 - source unique de vérité
        ctrl.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncButtons()
                val idx = ctrl.currentMediaItemIndex
                if (isPlaying) playlistAdapter.setPlayingIndex(idx)
                else playlistAdapter.setPlayingIndex(-1)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = ctrl.currentMediaItemIndex
                syncSelection()
                syncMetadata()
                savePlaylist()
                playlistAdapter.setCurrentIndex(idx)
                playlistAdapter.setPlayingIndex(if (ctrl.isPlaying) idx else -1)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    syncButtons()
                }
            }
        })
    }

    // ── Sync UI depuis MediaController (source unique) ─────────────────────────

    private fun syncPlaylist() {
        val ctrl = controller ?: return
        if (!::playlistAdapter.isInitialized) return
        // Si adapter vide mais controller a items, recharge depuis repository
        if (playlistAdapter.getItems().isEmpty() && ctrl.mediaItemCount > 0) {
            val (savedItems, _) = AudioRepository.load(requireContext())
            savedItems.forEach { item ->
                if (playlistAdapter.getItems().none { it.path == item.path })
                    playlistAdapter.addItem(item)
            }
        }
        syncSelection()
    }

    private fun syncSelection() {
        val ctrl = controller ?: return
        if (!::playlistAdapter.isInitialized) return
        playlistAdapter.setCurrentIndex(ctrl.currentMediaItemIndex)
    }

    private fun syncMetadata() {
        val ctrl = controller ?: return
        val mediaItem = ctrl.currentMediaItem ?: return
        val meta = mediaItem.mediaMetadata

        // Lit directement depuis MediaMetadata - pas de MediaMetadataRetriever
        _binding?.tvTitle?.text = meta.title?.toString()?.ifEmpty { null }
            ?: mediaItem.localConfiguration?.uri?.lastPathSegment ?: "Titre inconnu"
        _binding?.tvArtist?.text = meta.artist?.toString() ?: "Artiste inconnu"
        _binding?.tvAlbum?.text = meta.albumTitle?.toString() ?: ""

        val ext = (mediaItem.localConfiguration?.uri?.lastPathSegment ?: "").substringAfterLast(".", "").uppercase()
        if (ext.isNotEmpty()) {
            _binding?.tvCodec?.text = ext
            _binding?.tvCodec?.visibility = View.VISIBLE
        }

        // Bitrate depuis MediaMetadataRetriever
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val path = mediaItem.localConfiguration?.uri?.toString() ?: return@launch
                val retriever = android.media.MediaMetadataRetriever()
                if (path.startsWith("content://"))
                    retriever.setDataSource(requireContext(), android.net.Uri.parse(path))
                else retriever.setDataSource(path)
                val bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                retriever.release()
                viewLifecycleOwner.lifecycleScope.launch {
                    val lossless = ext in listOf("FLAC", "WAV", "ALAC", "APE", "AIFF")
                    when {
                        lossless -> {
                            _binding?.tvBitrate?.text = "Lossless"
                            _binding?.tvBitrate?.visibility = View.VISIBLE
                        }
                        bitrate > 0 -> {
                            _binding?.tvBitrate?.text = "${bitrate / 1000} kbps"
                            _binding?.tvBitrate?.visibility = View.VISIBLE
                        }
                        else -> _binding?.tvBitrate?.visibility = View.GONE
                    }
                }
            } catch (_: Exception) {}
        }

        // Artwork depuis MediaMetadata
        val artworkData = meta.artworkData
        if (artworkData != null) {
            val bitmap = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
            _binding?.ivArtwork?.setImageBitmap(bitmap)
        } else {
            _binding?.ivArtwork?.setImageResource(fr.retrospare.blazeplayer.R.drawable.bg_thumbnail)
        }
    }

    private fun syncButtons() {
        _binding?.btnPlayPause?.setImageResource(
            if (controller?.isPlaying == true) fr.retrospare.blazeplayer.R.drawable.ic_pause
            else fr.retrospare.blazeplayer.R.drawable.ic_play
        )
    }

    // ── Playlist UI ───────────────────────────────────────────────────────────

    private fun initPlaylistUi() {
        playlistAdapter = PlaylistAdapter(mutableListOf()) { index ->
            controller?.seekToDefaultPosition(index)
            controller?.play()
        }
        binding.recyclerPlaylist.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            adapter = playlistAdapter
        }
        binding.btnCleanPlaylist.setOnClickListener { showCleanDialog() }
        binding.btnAddFolder.setOnClickListener {
            pickAudio.launch(android.content.Intent(requireContext(), AudioBrowserActivity::class.java))
        }

        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetBehavior.from(binding.playlistSheet)
        bottomSheet.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
        bottomSheet.maxHeight = resources.displayMetrics.heightPixels
        bottomSheet.isFitToContents = false
        bottomSheet.halfExpandedRatio = 0.01f

        bottomSheet.addBottomSheetCallback(object : com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(view: android.view.View, newState: Int) {
                val isExpanded = newState == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                _binding?.btnBack?.visibility = if (isExpanded) android.view.View.INVISIBLE else android.view.View.VISIBLE
                if (isExpanded) {
                    // Force la hauteur plein écran
                    val params = binding.playlistSheet.layoutParams
                    params.height = resources.displayMetrics.heightPixels
                    binding.playlistSheet.layoutParams = params
                }
            }
            override fun onSlide(view: android.view.View, slideOffset: Float) {}
        })

        binding.btnPlaylistSheet.setOnClickListener {
            bottomSheet.state = if (bottomSheet.state == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN)
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            else
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
        }
    }

    fun savePlaylist() {
        val ctx = context ?: return
        if (!::playlistAdapter.isInitialized) return
        val items = playlistAdapter.getItems()
        if (items.isEmpty()) return
        val idx = controller?.currentMediaItemIndex ?: 0
        AudioRepository.save(ctx, items, idx)
    }

    fun addTrack(path: String, name: String) {
        if (!::playlistAdapter.isInitialized) return
        if (playlistAdapter.getItems().any { it.path == path }) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val mediaItem = AudioRepository.buildMediaItemWithMetadata(requireContext(), path, name)
            launch(Dispatchers.Main) {
                playlistAdapter.addItem(PlaylistItem(path, name))
                controller?.addMediaItem(mediaItem)
                if (controller?.playbackState == Player.STATE_IDLE) {
                    controller?.prepare()
                    controller?.play()
                }
                savePlaylist()
            }
        }
    }

    // ── Contrôles ─────────────────────────────────────────────────────────────

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener {
            val ctrl = controller ?: return@setOnClickListener
            if (ctrl.isPlaying) ctrl.pause()
            else {
                if (ctrl.playbackState == Player.STATE_IDLE) ctrl.prepare()
                ctrl.play()
            }
        }
        binding.btnPrev.setOnClickListener { controller?.seekToPreviousMediaItem() }
        binding.btnNext.setOnClickListener { controller?.seekToNextMediaItem() }
        binding.btnRewind.setOnClickListener {
            controller?.seekTo((controller!!.currentPosition - 10_000).coerceAtLeast(0))
        }
        binding.btnForward.setOnClickListener {
            controller?.seekTo((controller!!.currentPosition + 10_000).coerceAtMost(controller!!.duration))
        }

        var repeatMode = 0
        binding.btnRepeat.setOnClickListener {
            repeatMode = (repeatMode + 1) % 3
            when (repeatMode) {
                0 -> { controller?.repeatMode = Player.REPEAT_MODE_OFF
                    binding.btnRepeat.setImageResource(fr.retrospare.blazeplayer.R.drawable.ic_repeat)
                    binding.btnRepeat.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant)) }
                1 -> { controller?.repeatMode = Player.REPEAT_MODE_ALL
                    binding.btnRepeat.setImageResource(fr.retrospare.blazeplayer.R.drawable.ic_repeat)
                    binding.btnRepeat.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent)) }
                2 -> { controller?.repeatMode = Player.REPEAT_MODE_ONE
                    binding.btnRepeat.setImageResource(fr.retrospare.blazeplayer.R.drawable.ic_repeat_one)
                    binding.btnRepeat.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent)) }
            }
        }

        var isShuffled = false
        binding.btnShuffle.setOnClickListener {
            isShuffled = !isShuffled
            controller?.shuffleModeEnabled = isShuffled
            binding.btnShuffle.setColorFilter(
                if (isShuffled) requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent)
                else requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant)
            )
        }

        binding.btnEq.setOnClickListener {
            if (eqManager == null) {
                val sessionId = BlazePlayerService.instance?.getAudioSessionId() ?: 0
                if (sessionId != 0) try { eqManager = EqualizerManager(sessionId, requireContext()) } catch (e: Exception) {}
            }
            eqManager?.let { eq -> EqualizerDialog(eq).show(parentFragmentManager, "eq") }
        }

        binding.btnInfos?.setOnClickListener {
            val ctrl = controller ?: return@setOnClickListener
            val meta = ctrl.currentMediaItem?.mediaMetadata
            val title = meta?.title ?: "Inconnu"
            val artist = meta?.artist ?: "Inconnu"
            val album = meta?.albumTitle ?: "Inconnu"
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Infos")
                .setMessage("Titre : $title\nArtiste : $artist\nAlbum : $album")
                .setPositiveButton("OK", null)
                .show()
        }
        binding.btnSleepTimer.setOnClickListener {
            val options = arrayOf("5 minutes", "15 minutes", "30 minutes", "1 heure", "Annuler")
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Sleep Timer")
                .setItems(options) { _, which ->
                    sleepTimerJob?.cancel()
                    val minutes = when (which) { 0->5L; 1->15L; 2->30L; 3->60L; else->0L }
                    if (minutes > 0) {
                        (binding.btnSleepTimer.getChildAt(0) as? android.widget.ImageView)
                            ?.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent))
                        sleepTimerJob = viewLifecycleOwner.lifecycleScope.launch {
                            delay(minutes * 60 * 1000)
                            controller?.pause()
                            (_binding?.btnSleepTimer?.getChildAt(0) as? android.widget.ImageView)
                                ?.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant))
                        }
                    } else {
                        (binding.btnSleepTimer.getChildAt(0) as? android.widget.ImageView)
                            ?.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant))
                    }
                }.show()
        }
    }

    // ── SeekBar ────────────────────────────────────────────────────────────────

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = controller?.duration ?: 0L
                    if (dur > 0) controller?.seekTo(dur * progress / 100)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { isSeekBarTracking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isSeekBarTracking = false
                val dur = controller?.duration ?: 0L
                if (dur > 0) controller?.seekTo(dur * seekBar.progress / 100)
            }
        })
    }

    private fun startProgressUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                val ctrl = controller
                val dur = ctrl?.duration ?: 0L
                if (!isSeekBarTracking && dur > 0 && ctrl != null) {
                    _binding?.seekBar?.progress = ((ctrl.currentPosition * 100) / dur).toInt()
                    _binding?.tvCurrentTime?.text = formatTime(ctrl.currentPosition)
                    _binding?.tvTotalTime?.text = formatTime(dur)
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    // ── Dancer ─────────────────────────────────────────────────────────────────

    private fun startDancerAnimation() {
        handler.post(object : Runnable {
            override fun run() {
                if (controller?.isPlaying == true) {
                    dancerFrame = (dancerFrame + 1) % dancerFrames.size
                }
                handler.postDelayed(this, 300)
            }
        })
    }

    // ── Utils ──────────────────────────────────────────────────────────────────

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }

    private fun showCleanDialog() {
        if (!::playlistAdapter.isInitialized) return
        val items = playlistAdapter.getItems().toList()
        if (items.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "Liste déjà vide", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(items.size) { false }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Nettoyer la liste")
            .setMultiChoiceItems(items.map { it.name }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Retirer sélection") { _, _ ->
                items.forEachIndexed { i, item ->
                    if (checked[i]) {
                        val idx = playlistAdapter.getItems().indexOfFirst { it.path == item.path }
                        if (idx >= 0) {
                            playlistAdapter.removeItem(item)
                            controller?.removeMediaItem(idx)
                        }
                    }
                }
                savePlaylist()
            }
            .setNeutralButton("Tout effacer") { _, _ ->
                playlistAdapter.clearAll()
                controller?.clearMediaItems()
                AudioRepository.clear(requireContext())
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
