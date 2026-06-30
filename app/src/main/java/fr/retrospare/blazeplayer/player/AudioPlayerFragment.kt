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
            val ctrl = controller ?: return@registerForActivityResult

            // Source unique de verite = le Player. Ajout immediat avec MediaItem simples (sans connexion reseau).
            val simpleMediaItems = paths.mapIndexed { i, path ->
                AudioRepository.buildSimpleMediaItem(path, names[i])
            }
            val wasEmpty = ctrl.mediaItemCount == 0
            ctrl.addMediaItems(simpleMediaItems)
            playlistAdapter.refresh()
            if (wasEmpty || ctrl.playbackState == Player.STATE_IDLE || ctrl.playbackState == Player.STATE_ENDED) {
                ctrl.prepare()
                ctrl.play()
            }
            savePlaylistFromController()
            binding.recyclerPlaylist.scrollToPosition(ctrl.mediaItemCount - 1)

            // Enrichissement metadonnees + cover en arriere-plan, sans bloquer la lecture
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                paths.forEachIndexed { i, path ->
                    try {
                        val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), path, names[i])
                        launch(Dispatchers.Main) {
                            val c = controller ?: return@launch
                            val idx = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).localConfiguration?.uri.toString() == path }
                            if (idx != null) {
                                c.replaceMediaItem(idx, enriched)
                                playlistAdapter.notifyItemChanged(idx)
                            }
                        }
                    } catch (_: Exception) { }
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
        playlistAdapter.refresh()
        syncSelection()
        syncMetadata()
        syncButtons()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.setInAudioPlayer(true)
            playlistAdapter.refresh()
            syncSelection()
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
        savePlaylistFromController()
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

        // Charge la playlist sauvegardée dans ExoPlayer si vide. Le Player reste la seule source de verite ;
        // AudioRepository ne sert qu'a la persistance disque entre lancements de l'app.
        if (ctrl.mediaItemCount == 0) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val (savedItems, savedIndex) = AudioRepository.load(requireContext())
                if (savedItems.isNotEmpty()) {
                    // Chargement rapide : MediaItem simples d'abord, metadonnees enrichies ensuite
                    val simpleItems = savedItems.map { AudioRepository.buildSimpleMediaItem(it.path, it.name) }
                    launch(Dispatchers.Main) {
                        ctrl.setMediaItems(simpleItems, savedIndex.coerceIn(0, savedItems.size - 1), 0L)
                        ctrl.prepare()
                        playlistAdapter.refresh()
                        syncSelection()
                        syncMetadata()
                        syncButtons()
                    }
                    // Enrichissement en arriere plan
                    savedItems.forEachIndexed { i, item ->
                        try {
                            val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), item.path, item.name)
                            launch(Dispatchers.Main) {
                                val c = controller ?: return@launch
                                if (i < c.mediaItemCount) {
                                    c.replaceMediaItem(i, enriched)
                                    playlistAdapter.notifyItemChanged(i)
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        } else {
            playlistAdapter.refresh()
            syncSelection()
            syncMetadata()
            syncButtons()
        }

        // Pending tracks depuis SharedViewModel
        val pending = sharedVm.consumePendingTracks()
        if (pending.isNotEmpty()) {
            val existingPaths = (0 until ctrl.mediaItemCount).map { ctrl.getMediaItemAt(it).localConfiguration?.uri.toString() }.toSet()
            val newTracks = pending.filter { it.path !in existingPaths }
            if (newTracks.isNotEmpty()) {
                val simpleItems = newTracks.map { AudioRepository.buildSimpleMediaItem(it.path, it.name) }
                ctrl.addMediaItems(simpleItems)
                playlistAdapter.refresh()
                ctrl.prepare()
                ctrl.play()
                savePlaylistFromController()

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    newTracks.forEach { track ->
                        try {
                            val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), track.path, track.name)
                            launch(Dispatchers.Main) {
                                val c = controller ?: return@launch
                                val idx = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).localConfiguration?.uri.toString() == track.path }
                                if (idx != null) {
                                    c.replaceMediaItem(idx, enriched)
                                    playlistAdapter.notifyItemChanged(idx)
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        }

        // Listener natif Media3 - source unique de vérité pour toute la playlist
        ctrl.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                playlistAdapter.refresh()
            }

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
                savePlaylistFromController()
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

        // Bitrate via MediaMetadataRetriever (gère aussi smb://)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var smbDataSource: SmbMediaDataSource? = null
            try {
                val path = mediaItem.localConfiguration?.uri?.toString() ?: return@launch
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    when {
                        path.startsWith("smb://") -> {
                            smbDataSource = SmbMediaDataSource(path)
                            retriever.setDataSource(smbDataSource)
                        }
                        path.startsWith("content://") -> retriever.setDataSource(requireContext(), android.net.Uri.parse(path))
                        else -> retriever.setDataSource(path)
                    }
                    val bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                    launch(Dispatchers.Main) {
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
                } finally {
                    retriever.release()
                }
            } catch (_: Exception) {
            } finally {
                try { smbDataSource?.close() } catch (_: Exception) {}
            }
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
        playlistAdapter = PlaylistAdapter({ controller }) { index ->
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

        fun openPlaylist() {
            binding.playlistSheet.visibility = android.view.View.VISIBLE
            binding.playlistSheet.translationY = binding.playlistSheet.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
            binding.playlistSheet.animate()
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            _binding?.btnBack?.visibility = android.view.View.INVISIBLE
        }

        fun closePlaylist() {
            binding.playlistSheet.animate()
                .translationY(resources.displayMetrics.heightPixels.toFloat())
                .setDuration(200)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    _binding?.playlistSheet?.visibility = android.view.View.GONE
                }
                .start()
            _binding?.btnBack?.visibility = android.view.View.VISIBLE
        }

        binding.btnPlaylistSheet.setOnClickListener {
            if (binding.playlistSheet.visibility == android.view.View.VISIBLE) closePlaylist() else openPlaylist()
        }
        binding.btnClosePlaylist.setOnClickListener { closePlaylist() }
    }

    /** Sauvegarde sur disque l'etat courant du Player (seule source de verite). */
    fun savePlaylistFromController() {
        val ctx = context ?: return
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount == 0) return
        val items = (0 until ctrl.mediaItemCount).map { i ->
            val mi = ctrl.getMediaItemAt(i)
            val path = mi.localConfiguration?.uri?.toString() ?: ""
            val name = mi.mediaMetadata.title?.toString()?.ifEmpty { null }
                ?: mi.localConfiguration?.uri?.lastPathSegment ?: ""
            PlaylistItem(path, name)
        }
        AudioRepository.save(ctx, items, ctrl.currentMediaItemIndex)
    }

    fun addTrack(path: String, name: String) {
        val ctrl = controller ?: return
        val exists = (0 until ctrl.mediaItemCount).any { ctrl.getMediaItemAt(it).localConfiguration?.uri.toString() == path }
        if (exists) return

        val simpleItem = AudioRepository.buildSimpleMediaItem(path, name)
        ctrl.addMediaItem(simpleItem)
        playlistAdapter.refresh()
        if (ctrl.playbackState == Player.STATE_IDLE) {
            ctrl.prepare()
            ctrl.play()
        }
        savePlaylistFromController()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), path, name)
                launch(Dispatchers.Main) {
                    val c = controller ?: return@launch
                    val idx = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).localConfiguration?.uri.toString() == path }
                    if (idx != null) {
                        c.replaceMediaItem(idx, enriched)
                        playlistAdapter.notifyItemChanged(idx)
                    }
                }
            } catch (_: Exception) { }
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
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount == 0) {
            android.widget.Toast.makeText(requireContext(), "Liste déjà vide", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val itemsSnapshot = (0 until ctrl.mediaItemCount).map { i ->
            val mi = ctrl.getMediaItemAt(i)
            mi.mediaMetadata.title?.toString()?.ifEmpty { null } ?: mi.localConfiguration?.uri?.lastPathSegment ?: "?"
        }
        val checked = BooleanArray(itemsSnapshot.size) { false }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Nettoyer la liste")
            .setMultiChoiceItems(itemsSnapshot.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Retirer sélection") { _, _ ->
                val c = controller ?: return@setPositiveButton
                // Supprime du plus grand index au plus petit pour ne pas decaler les indices
                checked.indices.reversed().forEach { i ->
                    if (checked[i] && i < c.mediaItemCount) {
                        c.removeMediaItem(i)
                    }
                }
                playlistAdapter.refresh()
                savePlaylistFromController()
            }
            .setNeutralButton("Tout effacer") { _, _ ->
                controller?.clearMediaItems()
                playlistAdapter.refresh()
                AudioRepository.clear(requireContext())
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
