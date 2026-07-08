package fr.retrospare.blazeplayer.player

import fr.retrospare.blazeplayer.ui.showPremium
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random
import android.content.ComponentName
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.debug.CrashReporter
import fr.retrospare.blazeplayer.databinding.ActivityAudioPlayerBinding
import fr.retrospare.blazeplayer.home.SharedAudioViewModel
import fr.retrospare.blazeplayer.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject

@AndroidEntryPoint
class AudioPlayerFragment : Fragment() {

    @Inject lateinit var mediaRepository: MediaRepository
    @Inject lateinit var dataStore: DataStore<Preferences>
    private var _binding: ActivityAudioPlayerBinding? = null
    private val binding get() = _binding!!
    private val sharedVm: SharedAudioViewModel by activityViewModels()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var partyPlaylistAdapter: PartyPlaylistAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var isSeekBarTracking = false
    private var sleepTimerJob: Job? = null
    private var eqManager: EqualizerManager? = null
    private var dancerFrame = 0
    private var currentDynamicBgColor: Int = Color.rgb(10, 12, 14)
    private var currentAccentColor: Int = Color.rgb(63, 215, 143)
    private var bgAnimator: ValueAnimator? = null
    private var audioVisualizer: Visualizer? = null
    private var currentVisualizerSessionId: Int = 0
    private var pendingVisualizerSessionId: Int = 0
    private var audioSpectrumEnabled: Boolean = true
    private var isPlayingBlazePartyQueue: Boolean = false
    private var localHostQueueSnapshot: List<MediaItem>? = null
    private val playedBlazePartyPaths: MutableSet<String> = linkedSetOf()
    private var currentBlazePartyPath: String? = null

    // ── Blaze Party réseau ──────────────────────────────────────────────────
    // Le serveur HTTP hôte vit désormais dans BlazePlayerService (cf. COMMAND_PARTY_START_HOST/
    // STOP_HOST) pour survivre à la fermeture de cet écran : ce Fragment ne fait plus que le
    // piloter via des commandes de session, comme le reste de l'UI le fait déjà pour le player.
    // Client HTTP côté invité, construit une fois la connexion (host/port/token) connue.
    private var partyClient: PartyClient? = null
    // Dernier état reçu du serveur de l'hôte, source de vérité de la file côté invité
    // (la playlist Party locale de l'invité est vide : ses fichiers ne sont pas ceux de l'hôte).
    private var guestPartyState: PartyState? = null
    private var guestPartyPollingActive = false
    // Après plusieurs échecs consécutifs (hôte injoignable : party terminée côté hôte, changement
    // de réseau...), on abandonne et on repasse en file locale automatiquement — sans ce compteur,
    // un invité dont l'hôte a disparu restait bloqué "connecté" indéfiniment (même après avoir
    // relancé l'app, puisque l'état vient des SharedPreferences), et ses propres ajouts à Blaze
    // Party via le navigateur audio devenaient invisibles : refreshPartyPlaylistSheet() continuait
    // de choisir la file distante (vide/injoignable) plutôt que la file locale qui venait d'être
    // modifiée. C'était la cause du bug "la file d'attente n'affiche plus les fichiers ajoutés".
    private var guestPartyConsecutiveFailures = 0
    private var guestPartyHasReceivedState = false
    private var guestPartyStateReceivedAtMs = 0L
    private val guestPartyPollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!guestPartyPollingActive) return
            partyClient?.fetchState { state ->
                if (!guestPartyPollingActive) return@fetchState
                handleGuestPartyFetchResult(state)
                if (guestPartyPollingActive) handler.postDelayed(this, 1500L)
            }
        }
    }

    // Côté hôte, le serveur tourne dans BlazePlayerService : ce poll purement local (aucun réseau,
    // juste une relecture de BlazePartyVoteManager/PlaylistManager) sert uniquement à rafraîchir
    // la feuille "Party" affichée à l'écran si un invité distant vote pendant que l'hôte regarde.
    private var hostPartyRefreshActive = false
    private val hostPartyRefreshRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!hostPartyRefreshActive) return
            val ctx = context
            if (ctx != null && BlazePartyVoteManager.isHost(ctx) && ::partyPlaylistAdapter.isInitialized) {
                refreshPartyPlaylistSheet()
                reorderBlazePartyPlaybackIfActive()
            }
            if (hostPartyRefreshActive) handler.postDelayed(this, 3000L)
        }
    }

    private val requestAudioVisualizerPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAudioVisualizer(pendingVisualizerSessionId)
        else _binding?.audioEqualizerView?.setIdle()
    }

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
            if (result.data?.getBooleanExtra(AudioBrowserActivity.EXTRA_BLAZE_PARTY_CHANGED, false) == true) {
                refreshBlazePartyUiAfterPlaylistChange(openSheet = true)
                return@registerForActivityResult
            }
            val paths = result.data?.getStringArrayListExtra(AudioBrowserActivity.EXTRA_PATHS) ?: return@registerForActivityResult
            val names = result.data?.getStringArrayListExtra(AudioBrowserActivity.EXTRA_NAMES) ?: return@registerForActivityResult
            val ctrl = controller ?: return@registerForActivityResult

            val simpleMediaItems = paths.mapIndexed { i, path ->
                AudioRepository.buildSimpleMediaItem(requireContext(), path, names[i])
            }

            if (isPlayingBlazePartyQueue) {
                // Pendant une lecture Blaze Party, le MediaController contient volontairement
                // la file Party. La file locale de l’hôte est donc la copie indépendante
                // localHostQueueSnapshot : les ajouts depuis le navigateur audio doivent aller
                // UNIQUEMENT dedans, sinon ils disparaissent de l’affichage local ou polluent
                // la timeline Party.
                val base = localHostQueueSnapshot
                    ?: loadLocalQueueSnapshot(requireContext())
                    ?: emptyList()
                val updated = base + simpleMediaItems
                localHostQueueSnapshot = updated
                saveLocalQueueSnapshot(requireContext(), updated)
                playlistAdapter.setOverrideItems(updated)
                savePlaylistFromController()
                if (updated.isNotEmpty()) binding.recyclerPlaylist.scrollToPosition(updated.lastIndex)
            } else {
                // Hors Blaze Party, la source de vérité reste le Player local standard.
                val wasEmpty = ctrl.mediaItemCount == 0
                ctrl.addMediaItems(simpleMediaItems)
                playlistAdapter.setOverrideItems(null)
                playlistAdapter.refresh()
                if (wasEmpty || ctrl.playbackState == Player.STATE_IDLE || ctrl.playbackState == Player.STATE_ENDED) {
                    ctrl.prepare()
                    ctrl.play()
                }
                savePlaylistFromController()
                binding.recyclerPlaylist.scrollToPosition(ctrl.mediaItemCount - 1)
            }

            // Enrichissement metadonnees + cover en arriere-plan, sans bloquer la lecture.
            // En mode Party, on enrichit seulement la copie locale affichée, sans toucher au
            // MediaController pour ne pas modifier/couper la lecture Party.
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                paths.forEachIndexed { i, path ->
                    try {
                        val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), path, names[i])
                        launch(Dispatchers.Main) {
                            if (isPlayingBlazePartyQueue) {
                                val current = localHostQueueSnapshot ?: return@launch
                                val idx = current.indexOfFirst { originalPathOf(it) == path }
                                if (idx != -1) {
                                    val mutable = current.toMutableList()
                                    mutable[idx] = enriched
                                    localHostQueueSnapshot = mutable
                                    saveLocalQueueSnapshot(requireContext(), mutable)
                                    playlistAdapter.setOverrideItems(mutable)
                                    savePlaylistFromController()
                                }
                            } else {
                                val c = controller ?: return@launch
                                val idx = (0 until c.mediaItemCount).firstOrNull { originalPathOf(c.getMediaItemAt(it)) == path }
                                if (idx != null) {
                                    c.replaceMediaItem(idx, enriched)
                                    playlistAdapter.notifyItemChanged(idx)
                                }
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
            // Le fragment est déjà placé sous la barre système par l'activité ;
            // ne pas rajouter d'inset haut ici, sinon le bouton Blaze Party descend trop.
            v.setPadding(0, 0, 0, 0)
            insets
        }
        // Bouton retour supprimé visuellement : le lecteur audio reste accessible via la navigation principale.
        binding.btnBack.visibility = android.view.View.GONE
        binding.artworkMetadataOverlay.radiusDp = 23f
        binding.ivArtwork.radiusDp = 23f
        binding.ivArtwork.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        setupSquareArtwork()
        restoreStaticAudioControlColors()
        restorePersistedDynamicAudioColors()

        initPlaylistUi()
        setupControls()
        setupSeekBar()
        startProgressUpdate()
        observeAudioSpectrumSetting()
        connectMediaController()
    }

    override fun onResume() {
        super.onResume()
        if (isHidden) return
        (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.setInAudioPlayer(true)
        playlistAdapter.refresh()
        if (::partyPlaylistAdapter.isInitialized) refreshPartyPlaylistSheet()
        maybeResumeGuestPartySync()
        maybeOpenPendingBlazePartySheet()
        setupSavedPlaylistDrawers()
        syncSelection()
        syncMetadata()
        syncButtons()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.setInAudioPlayer(true)
            playlistAdapter.refresh()
            if (::partyPlaylistAdapter.isInitialized) refreshPartyPlaylistSheet()
            setupSavedPlaylistDrawers()
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
        stopGuestPartyPolling()
        stopHostPartyRefresh()
        sleepTimerJob?.cancel()
        eqManager?.release()
        stopAudioVisualizer()
        savePlaylistFromController()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        try { bgAnimator?.cancel() } catch (_: Exception) {}
        controller = null
        squareArtworkListener?.let { squareArtworkContainer?.viewTreeObserver?.removeOnGlobalLayoutListener(it) }
        squareArtworkListener = null
        squareArtworkContainer = null
        _binding = null
        super.onDestroyView()
    }


    // ── MediaController ────────────────────────────────────────────────────────


    private fun observeAudioSpectrumSetting() {
        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.data
                .map { it[SettingsViewModel.KEY_AUDIO_SPECTRUM] ?: true }
                .distinctUntilChanged()
                .collect { enabled ->
                    audioSpectrumEnabled = enabled
                    setAudioSpectrumOverlayVisible(enabled)
                    if (enabled) {
                        if (controller?.isPlaying == true) ensureAudioVisualizer() else binding.audioEqualizerView.setIdle()
                    } else {
                        stopAudioVisualizer()
                        binding.audioEqualizerView.setIdle()
                    }
                }
        }
    }

    private fun setAudioSpectrumOverlayVisible(enabled: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.GONE
        _binding?.artworkMetadataOverlay?.visibility = visibility
        _binding?.audioEqualizerView?.visibility = visibility
    }


    private fun connectMediaController() {
        val token = SessionToken(requireContext(), ComponentName(requireContext(), BlazePlayerService::class.java))
        controllerFuture = MediaController.Builder(requireContext(), token).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                onControllerReady()
            } catch (e: Exception) {
                CrashReporter.log(requireContext(), "AudioPlayer MediaController connection failed", e)
                controller = null
            }
        }, MoreExecutors.directExecutor())
    }

    private fun onControllerReady() {
        val ctrl = controller ?: return

        // Garde-fou d'isolation : si une ancienne version a laissé un MediaItem vidéo/cast dans la
        // session audio, on l'élimine immédiatement avant que l'UI ou Play/Pause ne puisse le piloter.
        purgeNonAudioItems(ctrl)
        restoreBlazePartyRuntimeIfNeeded(ctrl)

        // Les fichiers ouverts depuis Android (DocumentsUI / navigateur externe) sont prioritaires :
        // ils doivent remplacer le morceau/la playlist en cours et démarrer immédiatement, comme la vidéo.
        // Ce cas couvre aussi le démarrage à froid, quand l'intent arrive avant que le MediaController soit prêt.
        val priorityExternalTrack = sharedVm.consumePriorityExternalTrack()
        if (priorityExternalTrack != null) {
            startExternalAudioServiceFallback(priorityExternalTrack.path, priorityExternalTrack.name)
        }

        // Charge la playlist sauvegardée dans ExoPlayer si vide, sauf si un fichier externe vient
        // déjà d'être chargé en priorité. Le Player reste la seule source de verite ;
        // AudioRepository ne sert qu'a la persistance disque entre lancements de l'app.
        if (priorityExternalTrack == null && ctrl.mediaItemCount == 0) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val savedState = AudioRepository.loadState(requireContext())
                val savedItems = savedState.items
                if (savedItems.isNotEmpty()) {
                    // Chargement rapide : MediaItem simples d'abord, metadonnees enrichies ensuite
                    val simpleItems = savedItems.map { AudioRepository.buildSimpleMediaItem(requireContext(), it.path, it.name) }
                    launch(Dispatchers.Main) {
                        ctrl.setMediaItems(
                            simpleItems,
                            savedState.index.coerceIn(0, savedItems.size - 1),
                            savedState.positionMs
                        )
                        ctrl.repeatMode = savedState.repeatMode
                        ctrl.shuffleModeEnabled = savedState.shuffle
                        ctrl.prepare()
                        playlistAdapter.refresh()
                        syncSelection()
                        syncMetadata()
                        syncButtons()
                    }
                    // Enrichissement en arriere-plan. On traite d'abord le morceau courant :
                    // après une fermeture complète, la position revient immédiatement, mais les
                    // métadonnées/cover du FLAC SMB peuvent être absentes du MediaItem minimal.
                    // Prioriser l'index courant évite d'attendre toute la queue avant de revoir
                    // titre/artiste/album/cover dans le lecteur.
                    val ordered = savedItems.indices
                        .sortedBy { if (it == savedState.index) 0 else 1 }
                    ordered.forEach { i ->
                        val item = savedItems[i]
                        try {
                            val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), item.path, item.name)
                            launch(Dispatchers.Main) {
                                val c = controller ?: return@launch
                                if (i < c.mediaItemCount) {
                                    c.replaceMediaItem(i, enriched)
                                    playlistAdapter.notifyItemChanged(i)
                                    if (i == c.currentMediaItemIndex) {
                                        syncMetadata()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            CrashReporter.log(requireContext(), "Audio metadata enrichment failed", e)
                        }
                    }
                }
            }
        } else {
            playlistAdapter.refresh()
            syncSelection()
            syncMetadata()
            syncButtons()
        }

        // Pending tracks depuis SharedViewModel. Quand un fichier audio arrive depuis Android
        // (DocumentsUI / navigateur de fichiers), le dernier fichier cliqué doit prendre la main
        // immédiatement, même si une ancienne playlist est déjà chargée. On l'ajoute si besoin,
        // puis on seek explicitement dessus avant prepare/play.
        val pending = sharedVm.consumePendingTracks()
        if (pending.isNotEmpty()) {
            val newTracks = mutableListOf<fr.retrospare.blazeplayer.home.AudioTrack>()
            pending.forEach { track ->
                val exists = (0 until ctrl.mediaItemCount).any { originalPathOf(ctrl.getMediaItemAt(it)) == track.path }
                if (!exists) {
                    ctrl.addMediaItem(AudioRepository.buildSimpleMediaItem(requireContext(), track.path, track.name))
                    newTracks += track
                }
            }

            val priorityTrack = pending.last()
            val priorityIndex = (0 until ctrl.mediaItemCount)
                .firstOrNull { originalPathOf(ctrl.getMediaItemAt(it)) == priorityTrack.path }
            if (priorityIndex != null) {
                ctrl.seekTo(priorityIndex, 0L)
                ctrl.prepare()
                ctrl.play()
            }
            playlistAdapter.refresh()
            syncSelection()
            syncMetadata()
            syncButtons()
            savePlaylistFromController()

            if (newTracks.isNotEmpty()) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    newTracks.forEach { track ->
                        try {
                            val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), track.path, track.name)
                            launch(Dispatchers.Main) {
                                val c = controller ?: return@launch
                                val idx = (0 until c.mediaItemCount).firstOrNull { originalPathOf(c.getMediaItemAt(it)) == track.path }
                                if (idx != null) {
                                    c.replaceMediaItem(idx, enriched)
                                    playlistAdapter.notifyItemChanged(idx)
                                    if (idx == c.currentMediaItemIndex) {
                                        syncMetadata()
                                    }
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
                if (playlistAdapter.hasOverrideItems()) playlistAdapter.setPlayingIndex(-1)
                else if (isPlaying) playlistAdapter.setPlayingIndex(idx)
                else playlistAdapter.setPlayingIndex(-1)
                partyPlaylistAdapter.setCurrentPath(if (isPlaying && idx in 0 until ctrl.mediaItemCount) originalPathOf(ctrl.getMediaItemAt(idx)) else null)
                if (!audioSpectrumEnabled) {
                    stopAudioVisualizer()
                    binding.audioEqualizerView.setIdle()
                } else if (isPlaying) ensureAudioVisualizer() else binding.audioEqualizerView.setIdle()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = ctrl.currentMediaItemIndex
                val newPartyPath = if (idx in 0 until ctrl.mediaItemCount) originalPathOf(ctrl.getMediaItemAt(idx)) else null
                if (isPlayingBlazePartyQueue && !currentBlazePartyPath.isNullOrBlank() && currentBlazePartyPath != newPartyPath) {
                    // Quand un titre Party vient d'être joué, ses votes sont remis à zéro
                    // et la suite de la timeline est recalculée selon les votes actuels.
                    syncBlazePartyPlaybackOrder(resetPlayedPath = currentBlazePartyPath)
                }
                currentBlazePartyPath = newPartyPath
                syncSelection()
                syncMetadata()
                savePlaylistFromController()
                if (playlistAdapter.hasOverrideItems()) {
                    playlistAdapter.setPlayingIndex(-1)
                } else {
                    playlistAdapter.setCurrentIndex(idx)
                    playlistAdapter.setPlayingIndex(if (ctrl.isPlaying) idx else -1)
                }
                partyPlaylistAdapter.setCurrentPath(newPartyPath)
                if (audioSpectrumEnabled) ensureAudioVisualizer() else binding.audioEqualizerView.setIdle()
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    if (isPlayingBlazePartyQueue && player.playbackState == Player.STATE_ENDED && !currentBlazePartyPath.isNullOrBlank()) {
                        syncBlazePartyPlaybackOrder(resetPlayedPath = currentBlazePartyPath)
                        currentBlazePartyPath = null
                    }
                    syncButtons()
                }
            }
        })

        if (audioSpectrumEnabled) ensureAudioVisualizer() else binding.audioEqualizerView.setIdle()
    }

    private fun ensureAudioVisualizer() {
        if (!audioSpectrumEnabled || _binding == null) {
            stopAudioVisualizer()
            _binding?.audioEqualizerView?.setIdle()
            return
        }
        val ctrl = controller ?: return
        val future = ctrl.sendCustomCommand(
            androidx.media3.session.SessionCommand(BlazePlayerService.COMMAND_GET_AUDIO_SESSION_ID, android.os.Bundle.EMPTY),
            android.os.Bundle.EMPTY
        )
        future.addListener({
            val sessionId = try {
                future.get().extras.getInt(BlazePlayerService.EXTRA_AUDIO_SESSION_ID, 0)
            } catch (_: Exception) { 0 }
            if (sessionId != 0) startAudioVisualizer(sessionId)
            else binding.audioEqualizerView.setIdle()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun startAudioVisualizer(sessionId: Int) {
        if (sessionId == 0 || _binding == null) return
        pendingVisualizerSessionId = sessionId
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioVisualizerPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (audioVisualizer?.enabled == true && currentVisualizerSessionId == sessionId) return
        stopAudioVisualizer()
        try {
            val captureSize = Visualizer.getCaptureSizeRange().lastOrNull() ?: 1024
            audioVisualizer = Visualizer(sessionId).apply {
                setCaptureSize(captureSize)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit
                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        _binding?.audioEqualizerView?.post { _binding?.audioEqualizerView?.updateFft(fft) }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
            currentVisualizerSessionId = sessionId
        } catch (e: Exception) {
            CrashReporter.log(requireContext(), "Audio visualizer failed", e)
            _binding?.audioEqualizerView?.setIdle()
            stopAudioVisualizer()
        }
    }

    private fun stopAudioVisualizer() {
        try { audioVisualizer?.enabled = false } catch (_: Exception) {}
        try { audioVisualizer?.release() } catch (_: Exception) {}
        audioVisualizer = null
        currentVisualizerSessionId = 0
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

        val pathForMeta = originalPathOf(mediaItem)
        val cachedMeta = fr.retrospare.blazeplayer.player.AudioMetadataExtractor.getCached(requireContext(), pathForMeta)
        val unknownArtist = getString(fr.retrospare.blazeplayer.R.string.unknown_artist)
        val metaArtist = meta.artist?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(unknownArtist, ignoreCase = true) && !it.equals("unknown", ignoreCase = true) }

        // Cache local prioritaire pour éviter le retour de "Unknown" après fermeture complète.
        _binding?.tvTitle?.text = cachedMeta?.title?.ifEmpty { null }
            ?: meta.title?.toString()?.ifEmpty { null }
            ?: mediaItem.localConfiguration?.uri?.lastPathSegment ?: getString(fr.retrospare.blazeplayer.R.string.unknown_title)
        _binding?.tvArtist?.text = cachedMeta?.artist?.ifEmpty { null }
            ?: metaArtist
            ?: unknownArtist
        updateCombinedTitleArtist()
        val safeAlbum = sanitizeAudioSecondaryText(
            cachedMeta?.album?.ifEmpty { null } ?: meta.albumTitle?.toString()
        )
        _binding?.tvAlbum?.text = safeAlbum
        // Évite l'ancien badge/chemin SAF résiduel : le lecteur ne doit
        // jamais afficher un content:// ou STORAGE/DOCUMENT sous le titre.
        _binding?.tvAlbum?.visibility = if (safeAlbum.isBlank()) View.GONE else View.GONE

        val ext = mediaItem.mediaMetadata.extras
            ?.getString(AudioRepository.EXTRA_CONTAINER_EXTENSION)
            ?.takeIf { it.isNotBlank() }
            ?: cachedMeta?.extension?.takeIf { it.isNotBlank() }
            ?: run {
                val sourceName = meta.title?.toString()?.takeIf { it.contains('.') }
                    ?: mediaItem.mediaMetadata.displayTitle?.toString()?.takeIf { it.contains('.') }
                    ?: mediaItem.mediaMetadata.extras?.getString("blaze_original_name")?.takeIf { it.contains('.') }
                    ?: mediaItem.localConfiguration?.uri?.lastPathSegment?.takeIf { it.contains('.') }
                    ?: pathForMeta.takeIf { it.contains('.') }
                sourceName?.substringAfterLast('.', "")?.uppercase().orEmpty()
            }
        val safeExt = sanitizeAudioExtension(ext)
        if (safeExt.isNotEmpty()) {
            fr.retrospare.blazeplayer.ui.BadgeStyle.applyContainerBadge(_binding?.tvCodec, safeExt)
            // BadgeStyle applique sa propre couleur par format (MP3/FLAC/...) : on la remplace
            // aussitôt par la couleur d'accent dynamique de la pochette courante, pour que le
            // badge reste cohérent avec le reste de l'écran plutôt que d'avoir sa propre teinte.
            _binding?.tvCodec?.setTextColor(currentAccentColor)
            _binding?.tvCodec?.visibility = View.VISIBLE
        } else {
            _binding?.tvCodec?.text = ""
            _binding?.tvCodec?.visibility = View.GONE
        }

        // Affichage immédiat du badge qualité existant : ne pas le remplacer par le badge
        // conteneur. Si le cache possède déjà le débit/lossless, on le garde visible pendant
        // que l'extraction complète se fait en tâche IO.
        val losslessExt = safeExt.uppercase() in setOf("FLAC", "WAV", "ALAC", "APE", "AIFF")
        when {
            cachedMeta?.isLossless == true || losslessExt -> {
                _binding?.tvBitrate?.text = getString(fr.retrospare.blazeplayer.R.string.lossless_label)
                _binding?.tvBitrate?.visibility = View.VISIBLE
            }
            (cachedMeta?.bitrate ?: 0L) > 0L -> {
                _binding?.tvBitrate?.text = "${cachedMeta!!.bitrate / 1000} kbps"
                _binding?.tvBitrate?.visibility = View.VISIBLE
            }
            else -> _binding?.tvBitrate?.visibility = View.GONE
        }

        // Bitrate via AudioMetadataExtractor (gère aussi smb://, avec cache disque — évite de
        // ré-extraire à chaque fois qu'on rouvre le même morceau)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val path = pathForMeta.ifEmpty { return@launch }
            val info = fr.retrospare.blazeplayer.player.AudioMetadataExtractor.extract(
                requireContext(), path, path.substringAfterLast("/")
            )
            launch(Dispatchers.Main) {
                if (originalPathOf(controller?.currentMediaItem ?: return@launch) == path) {
                    if (info.title.isNotEmpty()) _binding?.tvTitle?.text = info.title
                    if (info.artist.isNotEmpty()) _binding?.tvArtist?.text = info.artist
                    updateCombinedTitleArtist()
                    sanitizeAudioSecondaryText(info.album).takeIf { it.isNotBlank() }?.let { _binding?.tvAlbum?.text = it }
                    // Le badge doit lui aussi rester derrière cette garde : sans elle, une
                    // extraction encore en vol pour la piste PRÉCÉDENTE (après un skip rapide)
                    // pouvait écraser le badge de la piste actuellement affichée avec des
                    // données obsolètes, y compris en le masquant par erreur (GONE).
                    when {
                        info.isLossless || losslessExt -> {
                            _binding?.tvBitrate?.text = getString(fr.retrospare.blazeplayer.R.string.lossless_label)
                            _binding?.tvBitrate?.visibility = View.VISIBLE
                        }
                        info.bitrate > 0 -> {
                            _binding?.tvBitrate?.text = "${info.bitrate / 1000} kbps"
                            _binding?.tvBitrate?.visibility = View.VISIBLE
                        }
                        else -> _binding?.tvBitrate?.visibility = View.GONE
                    }
                }
            }
        }

        // Artwork depuis MediaMetadata, puis cache disque/RAM si absent (réouverture app, SMB/FLAC).
        val artworkData = meta.artworkData
        if (artworkData != null) {
            fr.retrospare.blazeplayer.ui.ThumbnailUtils.cacheAudioArtworkData(requireContext(), originalPathOf(mediaItem), artworkData)
            val bitmap = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
            _binding?.ivArtwork?.setImageBitmap(bitmap)
            applyDynamicBackgroundFromBitmap(bitmap)
        } else {
            val path = originalPathOf(mediaItem)
            val cached = fr.retrospare.blazeplayer.ui.ThumbnailUtils.getCachedAudioArtworkJpegBytes(requireContext(), path)
            if (cached != null) {
                val bitmap = BitmapFactory.decodeByteArray(cached, 0, cached.size)
                _binding?.ivArtwork?.setImageBitmap(bitmap)
                applyDynamicBackgroundFromBitmap(bitmap)
            } else {
                _binding?.ivArtwork?.setImageResource(fr.retrospare.blazeplayer.R.drawable.bg_thumbnail)
                resetDynamicBackground()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val bytes = fr.retrospare.blazeplayer.ui.ThumbnailUtils.getAudioArtworkJpegBytes(requireContext(), path)
                    if (bytes != null) {
                        launch(Dispatchers.Main) {
                            val c = controller ?: return@launch
                            val current = c.currentMediaItem ?: return@launch
                            if (originalPathOf(current) != path) return@launch
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            _binding?.ivArtwork?.setImageBitmap(bitmap)
                            applyDynamicBackgroundFromBitmap(bitmap)
                            val enrichedMeta = current.mediaMetadata.buildUpon()
                                .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                .build()
                            val enriched = current.buildUpon().setMediaMetadata(enrichedMeta).build()
                            c.replaceMediaItem(c.currentMediaItemIndex, enriched)
                        }
                    }
                }
            }
        }
    }

    /** Combine tvTitle + " - " + tvArtist (lus depuis les porteurs de données invisibles) en un
     *  seul texte enrichi sur [tvTitleArtist] : gras + couleur d'accent uniquement sur la partie
     *  artiste. Un unique TextView se centre naturellement selon la largeur RÉELLE du texte
     *  combiné et s'ellipse comme un tout — deux TextView à largeur fixe 50/50 centraient chaque
     *  moitié indépendamment de la taille effective du titre et de l'artiste, ce qui déséquilibrait
     *  visuellement l'ensemble dès que l'un était bien plus court que l'autre. */
    private fun updateCombinedTitleArtist() {
        val b = _binding ?: return
        val title = b.tvTitle.text?.toString().orEmpty()
        val artist = b.tvArtist.text?.toString().orEmpty()
        val separator = " - "
        val combined = android.text.SpannableStringBuilder()
        combined.append(title)
        if (title.isNotEmpty() && artist.isNotEmpty()) combined.append(separator)
        val artistStart = combined.length
        combined.append(artist)
        val artistEnd = combined.length
        if (artistEnd > artistStart) {
            combined.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                artistStart, artistEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            combined.setSpan(
                android.text.style.ForegroundColorSpan(currentAccentColor),
                artistStart, artistEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        b.tvTitleArtist.text = combined
    }


    /**
     * Fond dynamique plus visible, inspiré des lecteurs audio modernes : on extrait une couleur
     * dominante robuste depuis la pochette, puis on renforce légèrement saturation/luminosité.
     * Le fond reste sombre via un dégradé noir -> accent afin de garder les contrôles lisibles.
     */
    private fun applyDynamicBackgroundFromBitmap(bitmap: Bitmap?) {
        bitmap ?: return resetDynamicBackground()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val accent = AudioDynamicColor.accentFromBitmap(bitmap)
            val bg = AudioDynamicColor.backgroundFromAccent(accent)
            launch(Dispatchers.Main) { animateDynamicBackground(bg, accent) }
        }
    }

    private fun boostAudioAccent(color: Int): Int = AudioDynamicColor.boostAccent(color)

    private fun mixColors(a: Int, b: Int, amount: Float): Int = AudioDynamicColor.mix(a, b, amount)

    private fun resetDynamicBackground() {
        animateDynamicBackground(Color.rgb(6, 47, 48), Color.rgb(64, 238, 213))
    }

    private fun buildAudioPlayerBackground(color: Int): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            mixColors(Color.rgb(2, 7, 9), color, 0.72f),
            mixColors(color, Color.WHITE, 0.05f),
            mixColors(Color.rgb(2, 7, 9), color, 0.48f)
        )
    )

    private fun applyAudioPlayerBackground(color: Int) {
        val b = _binding ?: return
        // Sur tablette, playerPanel est volontairement centré dans une colonne via une marge
        // horizontale. Le dégradé doit donc être posé aussi sur le root plein écran, sinon les
        // marges restent noires et donnent deux bandes de chaque côté du lecteur.
        b.root.background = buildAudioPlayerBackground(color)
        b.playerPanel.background = buildAudioPlayerBackground(color)
    }

    private fun animateDynamicBackground(targetColor: Int, accentColor: Int = currentAccentColor) {
        _binding ?: return
        // Ne saute que l'animation elle-même si rien n'a changé (évite un à-coup visuel inutile) —
        // mais applique quand même les couleurs dynamiques plus bas : sinon, après une recréation
        // de vue (rotation, changement d'onglet) où les champs currentDynamicBgColor/currentAccentColor
        // gardent leur ancienne valeur alors que les nouvelles vues n'ont jamais reçu ces couleurs,
        // tout restait bloqué sur les valeurs par défaut du layout (halo, contour, teintes...).
        if (currentDynamicBgColor != targetColor || currentAccentColor != accentColor) {
            bgAnimator?.cancel()
            bgAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentDynamicBgColor, targetColor).apply {
                duration = 380L
                addUpdateListener { animator ->
                    val color = animator.animatedValue as Int
                    applyAudioPlayerBackground(color)
                    currentDynamicBgColor = color
                }
                start()
            }
        } else {
            applyAudioPlayerBackground(targetColor)
        }
        currentAccentColor = accentColor
        val tint = ColorStateList.valueOf(accentColor)
        _binding?.seekBar?.progressTintList = tint
        _binding?.seekBar?.thumbTintList = tint
        _binding?.btnPlayPause?.backgroundTintList = null
        _binding?.btnPlayPause?.background = buildPlayButtonBackground(accentColor)
        _binding?.btnPlayPause?.elevation = dp(10f)
        _binding?.btnPlayPause?.translationZ = dp(6f)
        _binding?.audioEqualizerView?.setAccentColor(accentColor)
        applyArtworkAccentBorder(accentColor)
        restoreStaticAudioControlColors()
        persistDynamicAudioColors(targetColor, accentColor)
    }

    private fun restorePersistedDynamicAudioColors() {
        val prefs = requireContext().getSharedPreferences(DYNAMIC_AUDIO_PREFS, android.content.Context.MODE_PRIVATE)
        val bg = prefs.getInt(KEY_DYNAMIC_BG, Color.rgb(6, 47, 48))
        val accent = prefs.getInt(KEY_DYNAMIC_ACCENT, Color.rgb(64, 238, 213))
        currentDynamicBgColor = bg
        currentAccentColor = accent
        applyAudioPlayerBackground(bg)
        val tint = ColorStateList.valueOf(accent)
        _binding?.seekBar?.progressTintList = tint
        _binding?.seekBar?.thumbTintList = tint
        _binding?.btnPlayPause?.backgroundTintList = null
        _binding?.btnPlayPause?.background = buildPlayButtonBackground(accent)
        _binding?.btnPlayPause?.elevation = dp(10f)
        _binding?.btnPlayPause?.translationZ = dp(6f)
        _binding?.audioEqualizerView?.setAccentColor(accent)
        applyArtworkAccentBorder(accent)
        restoreStaticAudioControlColors()
    }

    private fun persistDynamicAudioColors(bg: Int, accent: Int) {
        try {
            requireContext().getSharedPreferences(DYNAMIC_AUDIO_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_DYNAMIC_BG, bg)
                .putInt(KEY_DYNAMIC_ACCENT, accent)
                .apply()
        } catch (_: Exception) { }
    }

    private fun applyArtworkAccentBorder(accentColor: Int) {
        val b = _binding ?: return
        b.artworkFrame.foreground = buildArtworkAccentBorder(accentColor)
        b.artworkFrame.foregroundGravity = Gravity.FILL
    }

    private fun buildArtworkAccentBorder(accentColor: Int): LayerDrawable {
        val bright = mixColors(accentColor, Color.WHITE, 0.44f)
        val shifted = rotateHue(accentColor, 28f)
        val deep = mixColors(accentColor, Color.BLACK, 0.22f)
        val outer = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24f)
            setColor(Color.TRANSPARENT)
            setStroke(dp(2.4f).toInt().coerceAtLeast(2), bright)
        }
        val colorRing = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22.5f)
            setColor(Color.TRANSPARENT)
            setStroke(dp(1.4f).toInt().coerceAtLeast(1), withAlpha(shifted, 220))
        }
        val innerShade = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(21f)
            setColor(Color.TRANSPARENT)
            setStroke(dp(1f).toInt().coerceAtLeast(1), withAlpha(deep, 150))
        }
        return LayerDrawable(arrayOf(outer, colorRing, innerShade)).apply {
            val one = dp(1f).toInt().coerceAtLeast(1)
            val two = dp(2f).toInt().coerceAtLeast(2)
            setLayerInset(1, one, one, one, one)
            setLayerInset(2, two, two, two, two)
        }
    }

    private fun appGreenColor(): Int = try {
        androidx.core.content.ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.green_accent)
    } catch (_: Exception) {
        Color.rgb(63, 215, 143)
    }


    private fun restoreStaticAudioControlColors(accentColor: Int = currentAccentColor) {
        val b = _binding ?: return
        val yellow = try { ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.yellow_accent) } catch (_: Exception) { Color.rgb(255, 193, 7) }
        val muted = try { ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.on_surface_variant) } catch (_: Exception) { Color.rgb(175, 178, 198) }
        b.tvCodec.setTextColor(accentColor)
        b.tvBitrate.setTextColor(muted)
        updateCombinedTitleArtist()
        b.btnBlazeParty.setIconResource(fr.retrospare.blazeplayer.R.drawable.ic_equalizer)
        b.btnBlazeParty.iconTint = ColorStateList.valueOf(Color.WHITE)
        b.btnBlazeParty.strokeColor = ColorStateList.valueOf(yellow)
        b.btnBlazeParty.setTextColor(Color.WHITE)
        b.btnBlazeParty.typeface = android.graphics.Typeface.DEFAULT_BOLD
        b.btnBlazeParty.compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
        b.btnBlazeParty.backgroundTintList = ColorStateList.valueOf(try { ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.surface_variant) } catch (_: Exception) { Color.rgb(31, 34, 48) })
        b.btnAudioPlaylistParty.imageTintList = ColorStateList.valueOf(yellow)
        b.btnAudioPlaylistParty.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        b.btnAudioPlaylistParty.elevation = 0f
        b.btnAudioPlaylistParty.translationZ = 0f
    }

    /** Halo lumineux dynamique affiché derrière la pochette (ivArtworkGlow, sous artworkFrame).
     *  Important : la cover masque le centre du halo. On dessine donc surtout une couronne
     *  lumineuse vers les bords visibles, sinon le dégradé radial reste caché derrière la pochette. */
    private fun buildArtworkGlow(accentColor: Int): Drawable {
        val accentBright = mixColors(accentColor, Color.WHITE, 0.24f)
        val accentDeep = mixColors(accentColor, Color.BLACK, 0.12f)
        val secondary = rotateHue(accentColor, 34f)
        val secondaryBright = mixColors(secondary, Color.WHITE, 0.20f)
        return ArtworkGlowDrawable(accentBright, secondaryBright, accentDeep)
    }

    private inner class ArtworkGlowDrawable(
        private val accentBright: Int,
        private val secondaryBright: Int,
        private val accentDeep: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            val cx = rect.centerX()
            val cy = rect.centerY()
            val r = minOf(rect.width(), rect.height()) / 2f

            // Couronne : forte près de la bordure de la cover, pas au centre caché.
            paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    withAlpha(accentBright, 0),
                    withAlpha(accentBright, 24),
                    withAlpha(secondaryBright, 120),
                    withAlpha(accentDeep, 94),
                    withAlpha(accentDeep, 0)
                ),
                floatArrayOf(0f, 0.54f, 0.76f, 0.90f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(rect, paint)

            // Lavis diagonal pour retrouver l'effet dégradé dynamique des autres éléments.
            paint.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(
                    withAlpha(accentBright, 74),
                    withAlpha(secondaryBright, 50),
                    withAlpha(accentDeep, 0)
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(rect, paint)
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha.coerceIn(0, 255); invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun rotateHue(color: Int, degrees: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + degrees) % 360f
        hsv[1] = (hsv[1] * 1.08f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * 1.12f).coerceIn(0.35f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun buildPlayButtonBackground(accentColor: Int): LayerDrawable {
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(72, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
        }
        val rim = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(52, 255, 255, 255))
            setStroke(dp(1.2f).toInt(), Color.argb(130, 210, 255, 255))
        }
        val face = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                mixColors(accentColor, Color.WHITE, 0.58f),
                mixColors(accentColor, Color.rgb(0, 220, 210), 0.22f),
                mixColors(accentColor, Color.BLACK, 0.34f)
            )
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1.4f).toInt(), mixColors(accentColor, Color.WHITE, 0.55f))
        }
        return LayerDrawable(arrayOf(glow, rim, face)).apply {
            setLayerInset(1, dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
            setLayerInset(2, dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sanitizeAudioExtension(raw: String?): String {
        val ext = raw.orEmpty().trim().removePrefix(".").uppercase()
        if (ext.isBlank() || ext.length !in 2..5 || !ext.all { it.isLetterOrDigit() }) return ""
        val allowed = setOf("MP3", "FLAC", "M4A", "AAC", "WAV", "OGG", "OGA", "OPUS", "WMA", "APE", "DTS", "AC3", "EAC3", "MKA", "WV", "AIFF", "ALAC")
        return ext.takeIf { it in allowed }.orEmpty()
    }

    private fun sanitizeAudioSecondaryText(raw: CharSequence?): String {
        val text = raw?.toString()?.trim().orEmpty()
        if (text.isBlank()) return ""
        val lower = text.lowercase()
        if (lower.startsWith("content://") || lower.contains("storage/document") || lower.contains("documents/document")) return ""
        if (text.length > 80 && (text.contains('%') || text.contains('/'))) return ""
        return text
    }

    private fun syncButtons() {
        _binding?.btnPlayPause?.setImageResource(
            if (controller?.isPlaying == true) fr.retrospare.blazeplayer.R.drawable.ic_pause
            else fr.retrospare.blazeplayer.R.drawable.ic_play
        )
    }

    // ── Playlist UI ───────────────────────────────────────────────────────────

    /** Force la pochette à rester parfaitement carrée : sa taille = le plus petit des deux côtés
     *  disponibles dans son conteneur, recalculé à chaque passage de layout (rotation, ajout de
     *  la rangée playlists en dessous...) au lieu d'un simple match_parent qui l'étirait. */
    private var squareArtworkListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    private var squareArtworkContainer: View? = null

    private fun setupSquareArtwork() {
        // artworkFrame -> FrameLayout englobant (fixe 342dp, juste là pour superposer le halo) ->
        // la vraie rangée dont la taille reflète l'espace réellement disponible à l'écran.
        val container = binding.artworkFrame.parent?.parent as? View ?: return
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val b = _binding ?: return@OnGlobalLayoutListener
            val availableWidth = container.width - container.paddingLeft - container.paddingRight
            val availableHeight = container.height - container.paddingTop - container.paddingBottom
            if (availableWidth <= 0 || availableHeight <= 0) return@OnGlobalLayoutListener
            val size = minOf(availableWidth, availableHeight)
            // La cover reste strictement carrée et occupe maintenant l'espace libéré
            // par la barre d'onglets plus compacte. Le halo est supprimé : aucun dessin,
            // aucun blur et aucune animation derrière la pochette.
            val params = b.artworkFrame.layoutParams
            if (params.width != size || params.height != size) {
                params.width = size
                params.height = size
                b.artworkFrame.layoutParams = params
            }

            // Le FrameLayout parent n'a plus besoin de surface de débordement pour le glow.
            val artworkContainer = b.artworkFrame.parent as? View
            artworkContainer?.layoutParams?.let { containerParams ->
                if (containerParams.width != size || containerParams.height != size) {
                    containerParams.width = size
                    containerParams.height = size
                    artworkContainer.layoutParams = containerParams
                }
            }

            b.artworkFrame.elevation = dp(18f)
            b.artworkFrame.translationZ = dp(12f)

            val glowParams = b.ivArtworkGlow.layoutParams
            if (glowParams.width != 0 || glowParams.height != 0) {
                glowParams.width = 0
                glowParams.height = 0
                b.ivArtworkGlow.layoutParams = glowParams
            }
            b.ivArtworkGlow.visibility = View.GONE
            b.ivArtworkGlow.alpha = 0f
        }
        container.viewTreeObserver.addOnGlobalLayoutListener(listener)
        squareArtworkListener = listener
        squareArtworkContainer = container
    }

    private fun configureSmoothQueueRecycler(
        recyclerView: androidx.recyclerview.widget.RecyclerView,
        queueAdapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>,
        setMetadataLoadsEnabled: (Boolean) -> Unit
    ) {
        recyclerView.setHasFixedSize(true)
        recyclerView.itemAnimator = null
        recyclerView.setItemViewCacheSize(24)
        recyclerView.recycledViewPool.setMaxRecycledViews(0, 56)
        recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                val idle = newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
                setMetadataLoadsEnabled(idle)
                if (idle) {
                    // On relance les métadonnées uniquement pour les lignes visibles quand le doigt
                    // s'arrête, au lieu de déclencher des extractions réseau pendant tout le scroll.
                    val lm = rv.layoutManager as? androidx.recyclerview.widget.GridLayoutManager ?: return
                    val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
                    val last = lm.findLastVisibleItemPosition().coerceAtLeast(first)
                    val count = (last - first + 1).coerceAtLeast(0)
                    if (count > 0) rv.post {
                        val safeFirst = first.coerceAtMost((queueAdapter.itemCount - 1).coerceAtLeast(0))
                        val safeCount = minOf(count, (queueAdapter.itemCount - safeFirst).coerceAtLeast(0))
                        if (safeCount > 0) queueAdapter.notifyItemRangeChanged(safeFirst, safeCount)
                    }
                }
            }
        })
    }

    private fun attachStandardAudioQueueDragAndDrop(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        var movedDuringDrag = false
        var dragOverrideActive = false

        fun mediaItemKey(item: MediaItem): String = originalPathOf(item).ifBlank {
            item.localConfiguration?.uri?.toString().orEmpty()
        }

        fun commitDraggedAudioOrder(ctrl: Player, desiredOrder: List<MediaItem>) {
            if (desiredOrder.isEmpty() || ctrl.mediaItemCount <= 1) return
            val working = (0 until ctrl.mediaItemCount).map { ctrl.getMediaItemAt(it) }.toMutableList()
            desiredOrder.forEachIndexed { desiredIndex, desiredItem ->
                val key = mediaItemKey(desiredItem)
                val currentIndex = working.indexOfFirst { mediaItemKey(it) == key }
                if (currentIndex != -1 && currentIndex != desiredIndex && desiredIndex in working.indices) {
                    ctrl.moveMediaItem(currentIndex, desiredIndex)
                    val moved = working.removeAt(currentIndex)
                    working.add(desiredIndex, moved)
                }
            }
        }

        val helper = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or
                    androidx.recyclerview.widget.ItemTouchHelper.DOWN or
                    androidx.recyclerview.widget.ItemTouchHelper.LEFT or
                    androidx.recyclerview.widget.ItemTouchHelper.RIGHT,
                0
            ) {
                override fun isLongPressDragEnabled(): Boolean = true
                override fun isItemViewSwipeEnabled(): Boolean = false

                override fun canDropOver(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    current: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    target: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ): Boolean = !isPlayingBlazePartyQueue && (dragOverrideActive || !playlistAdapter.hasOverrideItems())

                override fun onMove(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    target: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ): Boolean {
                    if (isPlayingBlazePartyQueue || !dragOverrideActive) return false
                    val from = viewHolder.adapterPosition
                    val to = target.adapterPosition
                    if (from == androidx.recyclerview.widget.RecyclerView.NO_POSITION ||
                        to == androidx.recyclerview.widget.RecyclerView.NO_POSITION ||
                        from == to
                    ) return false

                    val moved = playlistAdapter.moveOverrideItem(from, to)
                    if (moved) movedDuringDrag = true
                    return moved
                }

                override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) = Unit

                override fun onSelectedChanged(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                        movedDuringDrag = false
                        dragOverrideActive = false
                        if (isPlayingBlazePartyQueue || playlistAdapter.hasOverrideItems()) return
                        val ctrl = controller ?: return
                        if (ctrl.mediaItemCount <= 1) return
                        val snapshot = (0 until ctrl.mediaItemCount).map { ctrl.getMediaItemAt(it) }
                        playlistAdapter.beginDragOverrideItems(snapshot, ctrl.currentMediaItemIndex)
                        playlistAdapter.setQueueDragInProgress(true)
                        dragOverrideActive = true
                        viewHolder?.itemView?.alpha = 0.92f
                        viewHolder?.itemView?.elevation = 10f
                    }
                }

                override fun clearView(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.alpha = 1f
                    viewHolder.itemView.elevation = 0f
                    if (dragOverrideActive) {
                        val finalOrder = playlistAdapter.overrideItemsSnapshot()
                        val ctrl = controller
                        if (movedDuringDrag && ctrl != null) {
                            commitDraggedAudioOrder(ctrl, finalOrder)
                        }
                        playlistAdapter.setQueueDragInProgress(false)
                        playlistAdapter.setOverrideItems(null)
                        playlistAdapter.refresh()
                        syncSelection()
                        if (movedDuringDrag) savePlaylistFromController()
                        movedDuringDrag = false
                        dragOverrideActive = false
                    }
                }
            }
        )
        helper.attachToRecyclerView(recyclerView)
    }

    private fun initPlaylistUi() {
        playlistAdapter = PlaylistAdapter({ controller }) { index ->
            if (isPlayingBlazePartyQueue && playlistAdapter.hasOverrideItems()) {
                restoreLocalQueueFromSnapshot(index, true)
            } else {
                controller?.seekToDefaultPosition(index)
                controller?.play()
            }
        }
        binding.recyclerPlaylist.apply {
            // File d'attente plus dense : deux colonnes, lecture naturelle gauche → droite.
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
            adapter = playlistAdapter
            configureSmoothQueueRecycler(this, playlistAdapter) { enabled ->
                playlistAdapter.setMetadataLoadsEnabled(enabled)
            }
            attachStandardAudioQueueDragAndDrop(this)
        }
        partyPlaylistAdapter = PartyPlaylistAdapter(
            voteCountProvider = { path -> voteCountFor(path) },
            onItemClick = { track -> showBlazePartyTrackVotes(track) }
        )
        binding.recyclerPartyPlaylist.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
            adapter = partyPlaylistAdapter
            configureSmoothQueueRecycler(this, partyPlaylistAdapter) { enabled ->
                partyPlaylistAdapter.setMetadataLoadsEnabled(enabled)
            }
        }
        binding.btnCleanPlaylist.setOnClickListener { showCleanDialog() }
        binding.btnAddFolder.setOnClickListener {
            pickAudio.launch(android.content.Intent(requireContext(), AudioBrowserActivity::class.java))
        }
        binding.btnQueueToPlaylist.setOnClickListener {
            val tracks = currentAudioQueuePlaylistRefs()
            if (tracks.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), getString(fr.retrospare.blazeplayer.R.string.toast_list_already_empty), android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showAddToPlaylistPicker(
                requireContext(),
                fr.retrospare.blazeplayer.playlist.PlaylistCategory.AUDIO,
                tracks,
                onAdded = { setupSavedPlaylistDrawers() }
            )
        }
        binding.btnAudioFavoriteFolders.setOnClickListener {
            fr.retrospare.blazeplayer.favorites.FavoriteDialogs.showFavoritesList(
                requireContext(),
                fr.retrospare.blazeplayer.favorites.FavoriteCategory.AUDIO
            ) { favorite ->
                pickAudio.launch(android.content.Intent(requireContext(), AudioBrowserActivity::class.java).apply {
                    putExtra(AudioBrowserActivity.EXTRA_FAVORITE_PATH, favorite.path)
                    favorite.shareId?.let { putExtra(AudioBrowserActivity.EXTRA_FAVORITE_SHARE_ID, it) }
                })
            }
        }
        binding.btnBlazeParty.setOnClickListener { showBlazePartyDialog() }

        fun openPlaylist() {
            binding.ivArtworkGlow.visibility = View.GONE
            binding.playlistSheet.visibility = android.view.View.VISIBLE
            binding.playlistSheet.translationY = binding.playlistSheet.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
            binding.playlistSheet.animate()
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            _binding?.btnBack?.visibility = android.view.View.GONE
            // MiniEqualizerView s'arrête de lui-même quand son ancêtre (ce sheet) passe en GONE à
            // la fermeture (onVisibilityChanged) ; sans ce refresh, rouvrir le sheet ne relance pas
            // son animation puisque les ViewHolder déjà liés ne repassent pas par bind().
            playlistAdapter.refresh()
        }

        fun closePlaylist() {
            binding.playlistSheet.animate()
                .translationY(resources.displayMetrics.heightPixels.toFloat())
                .setDuration(200)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    _binding?.playlistSheet?.visibility = android.view.View.GONE
                    _binding?.ivArtworkGlow?.visibility = View.GONE
                }
                .start()
            _binding?.btnBack?.visibility = android.view.View.GONE
        }

        binding.btnPlaylistSheet.setOnClickListener {
            if (binding.playlistSheet.visibility == android.view.View.VISIBLE) closePlaylist() else openPlaylist()
        }
        binding.btnClosePlaylist.setOnClickListener { closePlaylist() }
        binding.btnClosePartyPlaylist.setOnClickListener { closePartyPlaylistSheet() }
        binding.btnLaunchPartyPlaylist.setOnClickListener { launchBlazePartyQueue() }
        binding.btnVotePartyPlaylist.setOnClickListener {
            android.widget.Toast.makeText(requireContext(), getString(fr.retrospare.blazeplayer.R.string.blaze_party_vote_hint), android.widget.Toast.LENGTH_SHORT).show()
        }
        binding.btnClearPartyPlaylist.setOnClickListener { clearBlazePartyQueue() }

        setupSavedPlaylistDrawers()
    }

    /** Les 3 tiroirs (1/2/3) sur le bord droit de l'écran, pour les playlists audio sauvegardées
     *  (différentes de la file d'attente en cours, ouverte via btnPlaylistSheet). */
    private fun setupSavedPlaylistDrawers() {
        val buttons = listOf(
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnAudioPlaylist1),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnAudioPlaylist2),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnAudioPlaylist3),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnAudioPlaylist4),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnAudioPlaylist5)
        )
        val ctx = context
        val lastPlayed = if (ctx != null) fr.retrospare.blazeplayer.playlist.PlaylistManager
            .getLastPlayed(ctx, fr.retrospare.blazeplayer.playlist.PlaylistCategory.AUDIO) else 0
        buttons.forEachIndexed { i, btn ->
            if (ctx != null) {
                val hasItems = fr.retrospare.blazeplayer.playlist.PlaylistManager
                    .getPlaylist(ctx, fr.retrospare.blazeplayer.playlist.PlaylistCategory.AUDIO, i + 1).isNotEmpty()
                btn?.isSelected = hasItems
                btn?.isActivated = hasItems && (lastPlayed == i + 1)
                btn?.setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        when {
                            hasItems -> fr.retrospare.blazeplayer.R.color.black
                            else -> fr.retrospare.blazeplayer.R.color.on_surface_variant
                        }
                    )
                )
            } else {
                btn?.isSelected = false
                btn?.isActivated = false
            }
            btn?.setOnClickListener { openSavedAudioPlaylist(i + 1) }
        }
        val partyBtn = binding.root.findViewById<android.widget.ImageButton>(fr.retrospare.blazeplayer.R.id.btnAudioPlaylistParty)
        if (ctx != null) {
            partyBtn?.isSelected = fr.retrospare.blazeplayer.playlist.PlaylistManager
                .getBlazePartyPlaylist(ctx).isNotEmpty()
        }
        partyBtn?.setOnClickListener { openBlazePartyAudioPlaylist() }
    }

    private fun openBlazePartyAudioPlaylist() {
        openPartyPlaylistSheet()
    }

    private fun refreshBlazePartyUiAfterPlaylistChange(openSheet: Boolean = false) {
        val ctx = context ?: return
        if (::partyPlaylistAdapter.isInitialized) {
            refreshPartyPlaylistSheet()
        }
        setupSavedPlaylistDrawers()
        if (usesLocalBlazePartyQueue(ctx)) {
            startHostPartyRefresh()
            reorderBlazePartyPlaybackIfActive()
        }
        if (openSheet && ::partyPlaylistAdapter.isInitialized) {
            openPartyPlaylistSheet()
        }
    }

    private fun usesLocalBlazePartyQueue(ctx: android.content.Context): Boolean {
        if (BlazePartyVoteManager.isHost(ctx)) return true
        // Quand l'appareil n'est pas connecté comme invité, la file Blaze Party affichée doit être
        // la file locale de ce téléphone. Sinon, après un ajout depuis le navigateur audio, l'écran
        // utilisait par erreur l'état invité réseau (vide) et l'hôte ne voyait plus ses ajouts.
        return !BlazePartyVoteManager.isActive(ctx) || BlazePartyVoteManager.getConnection(ctx) == null
    }

    private fun refreshPartyPlaylistSheet() {
        val ctx = context ?: return
        val useLocalQueue = usesLocalBlazePartyQueue(ctx)
        val tracks = if (useLocalQueue) sortedBlazePartyTracks(ctx) else sortedGuestPartyTracks()
        partyPlaylistAdapter.submitList(tracks)
        val sharedCurrentPath = if (useLocalQueue) currentBlazePartyPath else guestPartyState?.currentPath
        partyPlaylistAdapter.setCurrentPath(sharedCurrentPath)
        // Boutons Lancer/Vider : visibles pour la file locale de ce téléphone. Côté invité réseau,
        // seul le vote a un sens puisque les fichiers appartiennent à l'hôte.
        _binding?.btnLaunchPartyPlaylist?.visibility = if (useLocalQueue) android.view.View.VISIBLE else android.view.View.GONE
        _binding?.btnClearPartyPlaylist?.visibility = if (useLocalQueue) android.view.View.VISIBLE else android.view.View.GONE
        val partyBtn = binding.root.findViewById<android.widget.ImageButton>(fr.retrospare.blazeplayer.R.id.btnAudioPlaylistParty)
        partyBtn?.isSelected = if (useLocalQueue) {
            fr.retrospare.blazeplayer.playlist.PlaylistManager.getBlazePartyPlaylist(ctx).isNotEmpty()
        } else {
            tracks.isNotEmpty()
        }
    }

    /** Convertit le dernier état reçu du serveur de l'hôte ([guestPartyState]) en la même
     *  structure [PlaylistTrackRef] que celle utilisée localement côté hôte, triée par votes
     *  décroissants, pour réutiliser tel quel [PartyPlaylistAdapter]. */
    private fun sortedGuestPartyTracks(): List<fr.retrospare.blazeplayer.playlist.PlaylistTrackRef> =
        guestPartyState?.tracks
            // L'hôte envoie déjà l'ordre canonique : votes décroissants, puis morceaux joués en bas.
            // Ne pas retrier ici, sinon on casse le placement “piste jouée tout en bas”.
            ?.map {
                fr.retrospare.blazeplayer.playlist.PlaylistTrackRef(
                    path = it.path,
                    name = it.name,
                    artist = it.artist,
                    title = it.title,
                    extension = it.extension,
                    bitrate = it.bitrate,
                    isLossless = it.isLossless,
                    durationMs = it.durationMs,
                    playedOrder = it.playedOrder
                )
            }
            ?: emptyList()

    /** Nombre de votes pour un morceau, quelle que soit la source (locale côté hôte, ou dernier
     *  état réseau reçu côté invité). */
    private fun voteCountFor(path: String): Int {
        val ctx = context ?: return 0
        return if (usesLocalBlazePartyQueue(ctx)) {
            BlazePartyVoteManager.voteCount(ctx, path)
        } else {
            guestPartyState?.tracks?.firstOrNull { it.path == path }?.votes ?: 0
        }
    }

    private fun openPartyPlaylistSheet() {
        val b = _binding ?: return
        refreshPartyPlaylistSheet()
        b.ivArtworkGlow.visibility = View.GONE
        b.partyPlaylistSheet.visibility = android.view.View.VISIBLE
        b.partyPlaylistSheet.translationY = b.partyPlaylistSheet.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        b.partyPlaylistSheet.animate()
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        b.btnBack.visibility = android.view.View.GONE
    }

    private fun closePartyPlaylistSheet() {
        val b = _binding ?: return
        b.partyPlaylistSheet.animate()
            .translationY(resources.displayMetrics.heightPixels.toFloat())
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                _binding?.partyPlaylistSheet?.visibility = android.view.View.GONE
                _binding?.ivArtworkGlow?.visibility = View.GONE
            }
            .start()
        b.btnBack.visibility = android.view.View.GONE
    }

    private fun restoreLocalQueueFromSnapshot(startIndex: Int = 0, play: Boolean = false) {
        val ctrl = controller ?: return
        val snapshot = localHostQueueSnapshot ?: return
        if (snapshot.isEmpty()) return
        isPlayingBlazePartyQueue = false
        currentBlazePartyPath = null
        playlistAdapter.setOverrideItems(null)
        ctrl.shuffleModeEnabled = false
        ctrl.clearMediaItems()
        ctrl.setMediaItems(snapshot, startIndex.coerceIn(0, snapshot.size - 1), 0L)
        ctrl.prepare()
        if (play) ctrl.play()
        playlistAdapter.refresh()
        syncSelection()
        syncMetadata()
        syncButtons()
        savePlaylistFromController()
    }

    private fun launchBlazePartyQueue() {
        val ctx = context ?: return
        if (!usesLocalBlazePartyQueue(ctx)) {
            android.widget.Toast.makeText(ctx, getString(fr.retrospare.blazeplayer.R.string.blaze_party_not_host_queue), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val tracks = sortedBlazePartyTracks(ctx)
        if (tracks.isEmpty()) {
            android.widget.Toast.makeText(ctx, getString(fr.retrospare.blazeplayer.R.string.blaze_party_playlist_empty_message), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val ctrl = controller ?: return
        // Sauvegarde une copie stricte de la file locale personnelle AVANT de remplacer
        // temporairement la timeline du Player par la file Party. La file locale affichée
        // par le bouton file d’attente reste donc indépendante et ne montre jamais les
        // morceaux Party.
        if (!isPlayingBlazePartyQueue) {
            localHostQueueSnapshot = (0 until ctrl.mediaItemCount).map { ctrl.getMediaItemAt(it) }
            saveLocalQueueSnapshot(ctx, localHostQueueSnapshot.orEmpty())
        }
        BlazePartyVoteManager.setHost(ctx, true)
        playlistAdapter.setOverrideItems(localHostQueueSnapshot ?: loadLocalQueueSnapshot(ctx))

        // En mode Party, l'ordre de lecture doit etre uniquement l'ordre des votes.
        // On coupe donc explicitement le shuffle et on reconstruit la timeline Media3
        // avec la liste deja triee par voteCount desc, sans tenir compte des numeros
        // de piste presents dans les metadonnees des fichiers.
        ctrl.shuffleModeEnabled = false
        ctrl.clearMediaItems()
        val mediaItems = tracks.map { track -> AudioRepository.buildSimpleMediaItem(ctx, track.path, track.name) }
        ctrl.setMediaItems(mediaItems, 0, 0L)
        ctrl.prepare()
        ctrl.play()
        isPlayingBlazePartyQueue = true
        playedBlazePartyPaths.clear()
        BlazePartyVoteManager.clearPlayedOrder(ctx)
        currentBlazePartyPath = tracks.firstOrNull()?.path
        playlistAdapter.refresh()
        syncSelection()
        syncMetadata()
        syncButtons()
        partyPlaylistAdapter.setCurrentPath(currentBlazePartyPath)
        refreshPartyPlaylistSheet()
        syncBlazePartyPlaybackOrder()
    }

    private fun clearBlazePartyQueue() {
        val ctx = context ?: return
        if (!usesLocalBlazePartyQueue(ctx)) {
            android.widget.Toast.makeText(ctx, getString(fr.retrospare.blazeplayer.R.string.blaze_party_not_host_queue), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val tracks = fr.retrospare.blazeplayer.playlist.PlaylistManager.getBlazePartyPlaylist(ctx)
        if (tracks.isEmpty()) {
            android.widget.Toast.makeText(ctx, getString(fr.retrospare.blazeplayer.R.string.blaze_party_playlist_empty_message), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(fr.retrospare.blazeplayer.R.string.blaze_party_playlist_title))
            .setMessage(getString(fr.retrospare.blazeplayer.R.string.action_empty_playlist) + " ?")
            .setPositiveButton(getString(fr.retrospare.blazeplayer.R.string.action_empty_playlist)) { _, _ ->
                fr.retrospare.blazeplayer.playlist.PlaylistManager.clearBlazePartyPlaylist(ctx)
                BlazePartyVoteManager.clearAll(ctx)
                isPlayingBlazePartyQueue = false
                playedBlazePartyPaths.clear()
                currentBlazePartyPath = null
                refreshPartyPlaylistSheet()
                setupSavedPlaylistDrawers()
                android.widget.Toast.makeText(ctx, getString(fr.retrospare.blazeplayer.R.string.toast_blaze_party_playlist_emptied), android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(fr.retrospare.blazeplayer.R.string.action_cancel), null)
            .showPremium()
    }

    private fun sortedBlazePartyTracks(ctx: android.content.Context): List<fr.retrospare.blazeplayer.playlist.PlaylistTrackRef> =
        fr.retrospare.blazeplayer.playlist.PlaylistManager.getBlazePartyPlaylist(ctx)
            .withIndex()
            .map { indexed ->
                val rank = playedBlazePartyPaths.indexOf(indexed.value.path).let { if (it >= 0) it + 1 else BlazePartyVoteManager.playedRank(ctx, indexed.value.path) }
                indexed.copy(value = indexed.value.copy(playedOrder = rank))
            }
            .sortedWith(
                compareBy<IndexedValue<fr.retrospare.blazeplayer.playlist.PlaylistTrackRef>> { if (it.value.playedOrder > 0) 1 else 0 }
                    .thenByDescending { if (it.value.playedOrder == 0) BlazePartyVoteManager.voteCount(ctx, it.value.path) else 0 }
                    // Les morceaux déjà joués dans la session courante retombent en bas
                    // après remise à zéro des votes, sans disparaître de la file Party.
                    .thenBy { if (it.value.playedOrder > 0) it.value.playedOrder else it.index }
            )
            .map { it.value }

    private fun syncBlazePartyPlaybackOrder(resetPlayedPath: String? = null) {
        val ctx = context ?: return
        val ctrl = controller ?: return

        resetPlayedPath?.let { playedPath ->
            playedBlazePartyPaths.add(playedPath)
            BlazePartyVoteManager.markPlayed(ctx, playedPath)
            BlazePartyVoteManager.clearVotesForTrack(ctx, playedPath)
        }

        val sorted = sortedBlazePartyTracks(ctx)
        val currentIndex = ctrl.currentMediaItemIndex
        val currentPath = if (currentIndex in 0 until ctrl.mediaItemCount) originalPathOf(ctrl.getMediaItemAt(currentIndex)) else currentBlazePartyPath
        currentBlazePartyPath = currentPath

        partyPlaylistAdapter.submitList(sorted)
        partyPlaylistAdapter.setCurrentPath(currentPath)

        if (!isPlayingBlazePartyQueue || currentPath.isNullOrBlank() || currentIndex !in 0 until ctrl.mediaItemCount) return

        // Réordonne uniquement les morceaux à venir. Le morceau en cours reste intact,
        // donc un vote ne déclenche ni clearMediaItems(), ni prepare(), ni micro-coupure.
        val playedOrCurrent = playedBlazePartyPaths + currentPath
        val desiredFuture = sorted.filterNot { it.path in playedOrCurrent }
        var targetIndex = currentIndex + 1
        desiredFuture.forEach { track ->
            val existing = (targetIndex until ctrl.mediaItemCount).firstOrNull { idx ->
                originalPathOf(ctrl.getMediaItemAt(idx)) == track.path
            }
            if (existing == null) {
                ctrl.addMediaItem(targetIndex, AudioRepository.buildSimpleMediaItem(ctx, track.path, track.name))
            } else if (existing != targetIndex) {
                ctrl.moveMediaItem(existing, targetIndex)
            }
            targetIndex++
        }
    }

    private fun reorderBlazePartyPlaybackIfActive() {
        syncBlazePartyPlaybackOrder()
    }

    private fun showBlazePartyTrackVotes(track: fr.retrospare.blazeplayer.playlist.PlaylistTrackRef) {
        val ctx = requireContext()
        val useLocalQueue = usesLocalBlazePartyQueue(ctx)
        val nickname = BlazePartyVoteManager.getNickname(ctx)
        val voters = if (useLocalQueue) {
            BlazePartyVoteManager.votersFor(ctx, track.path)
        } else {
            guestPartyState?.tracks?.firstOrNull { it.path == track.path }?.voters.orEmpty()
        }
        val hasVoted = voters.any { it.equals(nickname, ignoreCase = true) }
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(8))
        }
        root.addView(android.widget.TextView(ctx).apply {
            text = track.name.substringBeforeLast(".")
            setTextColor(ctx.getColor(fr.retrospare.blazeplayer.R.color.on_surface))
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(android.widget.TextView(ctx).apply {
            text = resources.getQuantityString(fr.retrospare.blazeplayer.R.plurals.blaze_party_vote_count, voters.size, voters.size)
            setTextColor(ctx.getColor(fr.retrospare.blazeplayer.R.color.yellow_accent))
            textSize = 14f
            setPadding(0, dp(12), 0, dp(8))
        })
        root.addView(android.widget.TextView(ctx).apply {
            text = if (voters.isEmpty()) getString(fr.retrospare.blazeplayer.R.string.blaze_party_no_voters) else voters.joinToString("\n") { "• $it" }
            setTextColor(ctx.getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant))
            textSize = 14f
        })
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setView(root)
            .setPositiveButton(getString(fr.retrospare.blazeplayer.R.string.blaze_party_vote)) { _, _ ->
                castPartyVote(track.path, nickname, add = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (hasVoted) {
            builder.setNeutralButton(getString(fr.retrospare.blazeplayer.R.string.blaze_party_remove_vote)) { _, _ ->
                castPartyVote(track.path, nickname, add = false)
            }
        }
        builder.showPremium()
    }

    /** Envoie un vote : localement si l'appareil est l'hôte (source de vérité), sinon via
     *  [PartyClient] vers le serveur de l'hôte. Dans les deux cas, l'UI locale ne se met à jour
     *  qu'une fois l'opération confirmée, pour ne jamais afficher un état qui n'a pas réellement
     *  été enregistré côté hôte. */
    private fun castPartyVote(path: String, nickname: String, add: Boolean) {
        val ctx = context ?: return
        val savedMsg = if (add) fr.retrospare.blazeplayer.R.string.blaze_party_vote_saved else fr.retrospare.blazeplayer.R.string.blaze_party_vote_removed
        if (usesLocalBlazePartyQueue(ctx)) {
            if (add) BlazePartyVoteManager.addVote(ctx, path, nickname) else BlazePartyVoteManager.removeVote(ctx, path, nickname)
            refreshPartyPlaylistSheet()
            reorderBlazePartyPlaybackIfActive()
            android.widget.Toast.makeText(ctx, getString(savedMsg), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val client = partyClient
        if (client == null) {
            android.widget.Toast.makeText(ctx, getString(fr.retrospare.blazeplayer.R.string.blaze_party_network_error), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        client.sendVoteAndFetch(path, nickname, add) { state ->
            if (!isAdded) return@sendVoteAndFetch
            if (state != null) {
                guestPartyState = state
                guestPartyHasReceivedState = true
                guestPartyConsecutiveFailures = 0
                if (::partyPlaylistAdapter.isInitialized) refreshPartyPlaylistSheet()
                android.widget.Toast.makeText(ctx, getString(savedMsg), android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(ctx, getString(fr.retrospare.blazeplayer.R.string.blaze_party_network_error), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openSavedAudioPlaylist(slot: Int) {
        val ctx = context ?: return
        fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showPlaylistViewer(
            ctx, fr.retrospare.blazeplayer.playlist.PlaylistCategory.AUDIO, slot,
            onPlayAll = { tracks ->
                playSavedAudioPlaylistFromStart(tracks)
                fr.retrospare.blazeplayer.playlist.PlaylistManager.setLastPlayed(ctx, fr.retrospare.blazeplayer.playlist.PlaylistCategory.AUDIO, slot)
                setupSavedPlaylistDrawers()
            },
            onPlayOne = { track -> addTrack(track.path, track.name) },
            onAddToParty = { tracks ->
                val added = fr.retrospare.blazeplayer.playlist.PlaylistManager.addToBlazePartyPlaylist(ctx, tracks)
                refreshBlazePartyUiAfterPlaylistChange(openSheet = true)
                val msg = if (added > 0) resources.getQuantityString(fr.retrospare.blazeplayer.R.plurals.blaze_party_items_added, added, added) else getString(fr.retrospare.blazeplayer.R.string.blaze_party_items_already_present)
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
            },
            onChanged = { setupSavedPlaylistDrawers() }
        )
    }

    private fun playSavedAudioPlaylistFromStart(tracks: List<fr.retrospare.blazeplayer.playlist.PlaylistTrackRef>) {
        val ctx = context ?: return
        val ordered = fr.retrospare.blazeplayer.playlist.PlaylistPlayOrder
            .sortedForPlayback(fr.retrospare.blazeplayer.playlist.PlaylistCategory.AUDIO, tracks)
        if (ordered.isEmpty()) return

        val ctrl = controller
        if (ctrl == null) {
            // Fallback rare : contrôleur pas encore connecté. On garde l'ordre trié dans la file
            // partagée, sans utiliser addTrack() qui lancerait successivement chaque morceau.
            ordered.forEach { sharedVm.addToPlaylist(it.path, it.name) }
            return
        }

        ctrl.clearMediaItems()
        ordered.forEach { track ->
            ctrl.addMediaItem(AudioRepository.buildSimpleMediaItem(ctx, track.path, track.name))
        }

        // Important : addTrack() lance chaque ajout immédiatement. Pour "Jouer la playlist",
        // on reconstruit donc la file puis on force explicitement le départ au premier élément.
        ctrl.seekTo(0, 0L)
        ctrl.prepare()
        ctrl.play()

        playlistAdapter.refresh()
        syncSelection()
        syncMetadata()
        syncButtons()
        savePlaylistFromController()

        ordered.forEach { track ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val enriched = AudioRepository.buildMediaItemWithMetadata(ctx, track.path, track.name)
                    launch(Dispatchers.Main) {
                        val c = controller ?: return@launch
                        val idx = (0 until c.mediaItemCount).firstOrNull {
                            originalPathOf(c.getMediaItemAt(it)) == track.path
                        } ?: return@launch
                        c.replaceMediaItem(idx, enriched)
                        playlistAdapter.notifyItemChanged(idx)
                    }
                } catch (_: Exception) { }
            }
        }
    }


    private fun currentAudioQueuePlaylistRefs(): List<fr.retrospare.blazeplayer.playlist.PlaylistTrackRef> {
        val ctrl = controller
        val visibleItems = when {
            ::playlistAdapter.isInitialized && playlistAdapter.hasOverrideItems() -> playlistAdapter.overrideItemsSnapshot()
            ctrl != null -> (0 until ctrl.mediaItemCount).map { ctrl.getMediaItemAt(it) }
            else -> emptyList()
        }
        return visibleItems.mapNotNull { mediaItem ->
            val path = originalPathOf(mediaItem)
            if (path.isBlank() || !AudioRepository.isAudioExtension(path)) return@mapNotNull null
            val cachedMeta = runCatching { fr.retrospare.blazeplayer.player.AudioMetadataExtractor.getCached(requireContext(), path) }.getOrNull()
            val meta = mediaItem.mediaMetadata
            val name = meta.title?.toString()?.takeIf { it.isNotBlank() }
                ?: mediaItem.localConfiguration?.uri?.lastPathSegment?.takeIf { it.isNotBlank() }
                ?: path.substringAfterLast('/').ifBlank { path }
            val extension = meta.extras?.getString(AudioRepository.EXTRA_CONTAINER_EXTENSION)?.takeIf { it.isNotBlank() }
                ?: cachedMeta?.extension?.takeIf { it.isNotBlank() }
                ?: path.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
            fr.retrospare.blazeplayer.playlist.PlaylistTrackRef(
                path = path,
                name = name,
                artist = cachedMeta?.artist?.takeIf { it.isNotBlank() } ?: meta.artist?.toString().orEmpty(),
                title = cachedMeta?.title?.takeIf { it.isNotBlank() } ?: meta.title?.toString().orEmpty(),
                album = cachedMeta?.album.orEmpty(),
                trackNumber = cachedMeta?.trackNumber ?: 0,
                extension = extension.uppercase(),
                durationMs = if (ctrl != null && mediaItem == ctrl.currentMediaItem) ctrl.duration.takeIf { it > 0L } ?: 0L else 0L
            )
        }
    }

    /** Sauvegarde sur disque l'etat courant du Player (seule source de verite). */
    fun savePlaylistFromController() {
        val ctx = context ?: return
        if (isPlayingBlazePartyQueue) {
            val snapshot = localHostQueueSnapshot ?: loadLocalQueueSnapshot(ctx) ?: return
            val items = snapshot.mapNotNull { mi ->
                val path = originalPathOf(mi)
                if (path.isBlank() || !AudioRepository.isAudioExtension(path)) return@mapNotNull null
                val name = mi.mediaMetadata.title?.toString()?.ifEmpty { null }
                    ?: mi.localConfiguration?.uri?.lastPathSegment ?: ""
                PlaylistItem(path, name)
            }
            if (items.isNotEmpty()) {
                AudioRepository.save(ctx, items, 0, 0L, Player.REPEAT_MODE_OFF, false)
            }
            return
        }
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount == 0) return
        val items = (0 until ctrl.mediaItemCount).mapNotNull { i ->
            val mi = ctrl.getMediaItemAt(i)
            val path = originalPathOf(mi)
            if (path.isBlank() || !AudioRepository.isAudioExtension(path)) return@mapNotNull null
            val name = mi.mediaMetadata.title?.toString()?.ifEmpty { null }
                ?: mi.localConfiguration?.uri?.lastPathSegment ?: ""
            PlaylistItem(path, name)
        }
        if (items.isEmpty()) return
        AudioRepository.save(
            ctx,
            items,
            ctrl.currentMediaItemIndex,
            ctrl.currentPosition.coerceAtLeast(0L),
            ctrl.repeatMode,
            ctrl.shuffleModeEnabled
        )
    }

    fun playExternalTrack(path: String, name: String) {
        restartAudioServiceForExternalTrack(path, name)
    }

    fun restartAudioServiceForExternalTrack(path: String, name: String) {
        // Ancien nom conservé pour compatibilité avec MainActivity, mais la logique change :
        // on ne tue plus le service audio et on ne libère plus le MediaController depuis l'UI.
        // Media3 est conçu pour recevoir des commandes via le MediaSessionService stable.
        startExternalAudioServiceFallback(path, name)
    }

    fun onExternalAudioReplaced() {
        handler.postDelayed({
            if (_binding == null) return@postDelayed
            playlistAdapter.refresh()
            syncSelection()
            syncMetadata()
            syncButtons()
        }, 200L)
    }

    private fun playExternalTrackOnController(ctrl: MediaController, path: String, name: String) {
        // Cas critique "Ouvrir avec" depuis un navigateur de fichiers.
        // Quand le lecteur audio est déjà ouvert, le second ACTION_VIEW arrive ici via onNewIntent().
        // Ne pas utiliser startService() en priorité dans ce cas : sur certains appareils l'intent
        // explicite du service est retardé/ignoré alors que la MediaSession existe déjà, ce qui
        // laisse l'ancien morceau actif. On envoie donc une commande MediaSession directe au
        // BlazePlayerService existant. Le service fait ensuite le remplacement strict de sa file.
        val args = Bundle().apply {
            putString(BlazePlayerService.EXTRA_EXTERNAL_AUDIO_PATH, path)
            putString(BlazePlayerService.EXTRA_EXTERNAL_AUDIO_NAME, name)
        }
        try {
            val future = ctrl.sendCustomCommand(
                SessionCommand(BlazePlayerService.COMMAND_PLAY_EXTERNAL_AUDIO, Bundle.EMPTY),
                args
            )
            future.addListener({
                try {
                    val result = future.get()
                    if (result.resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                        startExternalAudioServiceFallback(path, name)
                    }
                } catch (e: Exception) {
                    CrashReporter.log(requireContext(), "External audio session command failed for $path", e)
                    startExternalAudioServiceFallback(path, name)
                }
                handler.post {
                    if (_binding == null) return@post
                    playlistAdapter.refresh()
                    syncSelection()
                    syncMetadata()
                    syncButtons()
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            CrashReporter.log(requireContext(), "Send external audio session command failed for $path", e)
            startExternalAudioServiceFallback(path, name)
        }
    }

    private fun startExternalAudioServiceFallback(path: String, name: String) {
        try {
            requireContext().startService(android.content.Intent(requireContext(), BlazePlayerService::class.java).apply {
                action = BlazePlayerService.ACTION_PLAY_EXTERNAL_AUDIO
                putExtra(BlazePlayerService.EXTRA_EXTERNAL_AUDIO_PATH, path)
                putExtra(BlazePlayerService.EXTRA_EXTERNAL_AUDIO_NAME, name)
            })
        } catch (e: Exception) {
            CrashReporter.log(requireContext(), "Start external audio service fallback failed for $path", e)
        }
        handler.postDelayed({
            if (_binding == null) return@postDelayed
            playlistAdapter.refresh()
            syncSelection()
            syncMetadata()
            syncButtons()
        }, 250L)
    }

    fun addTrack(path: String, name: String) {
        // Ne jamais manipuler ce fragment s'il n'est plus attaché. C'est la signature exacte
        // observée dans les logs : addTrack() -> sharedVm/activityViewModels -> requireActivity()
        // alors que FragmentManager a rendu une ancienne instance détachée.
        if (!isAdded || context == null) return
        val ctrl = controller
        if (ctrl == null) {
            // Quand Blaze Audio vient d'être affiché depuis un intent Android externe, la
            // connexion MediaController n'est pas toujours prête au moment exact de l'appel.
            // Avant, le morceau était simplement perdu : l'onglet audio s'ouvrait mais rien ne
            // se lançait. On le place dans la file d'attente partagée pour qu'il soit consommé
            // dès que le contrôleur est prêt.
            sharedVm.addToPlaylist(path, name)
            return
        }
        val exists = (0 until ctrl.mediaItemCount).any { originalPathOf(ctrl.getMediaItemAt(it)) == path }
        if (exists) {
            val index = (0 until ctrl.mediaItemCount).firstOrNull { originalPathOf(ctrl.getMediaItemAt(it)) == path } ?: return
            ctrl.seekTo(index, 0L)
            if (ctrl.playbackState == Player.STATE_IDLE) ctrl.prepare()
            ctrl.play()
            return
        }

        val simpleItem = AudioRepository.buildSimpleMediaItem(requireContext(), path, name)
        ctrl.addMediaItem(simpleItem)
        val newIndex = ctrl.mediaItemCount - 1
        // Un fichier ouvert depuis un navigateur externe est une demande de lecture immédiate,
        // pas seulement un ajout en fin de playlist.
        ctrl.seekTo(newIndex, 0L)
        ctrl.prepare()
        ctrl.play()
        playlistAdapter.refresh()
        syncSelection()
        syncMetadata()
        syncButtons()
        savePlaylistFromController()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), path, name)
                launch(Dispatchers.Main) {
                    val c = controller ?: return@launch
                    val idx = (0 until c.mediaItemCount).firstOrNull { originalPathOf(c.getMediaItemAt(it)) == path }
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
            savePlaylistFromController()
        }

        var isShuffled = controller?.shuffleModeEnabled ?: false
        binding.btnShuffle.setOnClickListener {
            isShuffled = !isShuffled
            controller?.shuffleModeEnabled = isShuffled
            savePlaylistFromController()
            binding.btnShuffle.setColorFilter(
                if (isShuffled) requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent)
                else requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant)
            )
        }

        binding.btnEq.setOnClickListener {
            val existing = eqManager
            if (existing != null) {
                EqualizerDialog(existing).show(parentFragmentManager, "eq")
                return@setOnClickListener
            }
            val ctrl = controller ?: return@setOnClickListener
            // L'audioSessionId n'est pas exposé par l'API Player standard : on le récupère via une
            // commande de session personnalisée plutôt qu'une référence statique vers le service
            // (cf. BlazePlayerService.SessionCallback), conformément aux best practices Media3.
            val future = ctrl.sendCustomCommand(
                androidx.media3.session.SessionCommand(BlazePlayerService.COMMAND_GET_AUDIO_SESSION_ID, android.os.Bundle.EMPTY),
                android.os.Bundle.EMPTY
            )
            future.addListener({
                val sessionId = try {
                    future.get().extras.getInt(BlazePlayerService.EXTRA_AUDIO_SESSION_ID, 0)
                } catch (_: Exception) { 0 }
                if (sessionId != 0) {
                    try {
                        eqManager = EqualizerManager(sessionId, requireContext()).also { it.restoreLastSession() }
                        eqManager?.let { eq -> EqualizerDialog(eq).show(parentFragmentManager, "eq") }
                    } catch (_: Exception) { }
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(requireContext()))
        }

        binding.btnInfos?.setOnClickListener {
            val ctrl = controller ?: return@setOnClickListener
            val meta = ctrl.currentMediaItem?.mediaMetadata
            val title = meta?.title ?: getString(fr.retrospare.blazeplayer.R.string.unknown_generic)
            val artist = meta?.artist ?: getString(fr.retrospare.blazeplayer.R.string.unknown_generic)
            val album = meta?.albumTitle ?: getString(fr.retrospare.blazeplayer.R.string.unknown_generic)
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(fr.retrospare.blazeplayer.R.string.info))
                .setMessage(getString(fr.retrospare.blazeplayer.R.string.dialog_track_info_message, title, artist, album))
                .setPositiveButton("OK", null)
                .showPremium()
        }
        binding.btnSleepTimer.setOnClickListener {
            val options = arrayOf(getString(fr.retrospare.blazeplayer.R.string.minutes_5), getString(fr.retrospare.blazeplayer.R.string.minutes_15), getString(fr.retrospare.blazeplayer.R.string.minutes_30), getString(fr.retrospare.blazeplayer.R.string.hour_1), getString(fr.retrospare.blazeplayer.R.string.action_cancel))
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(fr.retrospare.blazeplayer.R.string.sleep_timer_title))
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
                }.showPremium()
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
                if (dur > 0) {
                    controller?.seekTo(dur * seekBar.progress / 100)
                    savePlaylistFromController()
                }
            }
        })
    }

    private fun startProgressUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                val ctrl = controller
                val dur = ctrl?.duration ?: 0L
                val ctx = context
                val useRemotePartyClock = ctx != null && !usesLocalBlazePartyQueue(ctx)
                if (useRemotePartyClock) {
                    updateGuestPartyProgressFromHostClock()
                }
                if (!isSeekBarTracking && dur > 0 && ctrl != null) {
                    _binding?.seekBar?.progress = ((ctrl.currentPosition * 100) / dur).toInt()
                    _binding?.tvCurrentTime?.text = formatTime(ctrl.currentPosition)
                    _binding?.tvTotalTime?.text = formatTime(dur)
                    playlistAdapter.updateCurrentProgress(ctrl.currentMediaItemIndex, ctrl.currentPosition, dur)
                    if (!useRemotePartyClock) {
                        val currentPath = if (ctrl.currentMediaItemIndex in 0 until ctrl.mediaItemCount) originalPathOf(ctrl.getMediaItemAt(ctrl.currentMediaItemIndex)) else null
                        partyPlaylistAdapter.updateCurrentProgress(currentPath, ctrl.currentPosition, dur)
                    }
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


    // ── Blaze Party V1 ───────────────────────────────────────────────────────

    private fun showBlazePartyDialog() {
        val dialog = Dialog(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = ContextCompat.getDrawable(requireContext(), fr.retrospare.blazeplayer.R.drawable.bg_dialog)
        }
        root.addView(TextView(requireContext()).apply {
            text = getString(fr.retrospare.blazeplayer.R.string.blaze_party)
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(requireContext()).apply {
            text = getString(fr.retrospare.blazeplayer.R.string.blaze_party_intro)
            setTextColor(ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.on_surface_variant))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        })
        fun partyButton(label: String, icon: Int, action: () -> Unit) = MaterialButton(requireContext()).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setIconResource(icon)
            iconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.green_accent))
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.surface_variant))
            strokeColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.outline_variant))
            strokeWidth = dp(1)
            cornerRadius = dp(20)
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { bottomMargin = dp(8) }
            setOnClickListener { action() }
        }
        root.addView(partyButton(getString(fr.retrospare.blazeplayer.R.string.blaze_party_host), fr.retrospare.blazeplayer.R.drawable.ic_wifi) {
            dialog.dismiss(); showBlazePartyHostDialog()
        })
        root.addView(partyButton(getString(fr.retrospare.blazeplayer.R.string.blaze_party_join), fr.retrospare.blazeplayer.R.drawable.ic_camera) {
            dialog.dismiss(); scanBlazePartyQr()
        })
        root.addView(partyButton(getString(fr.retrospare.blazeplayer.R.string.blaze_party_disconnect), fr.retrospare.blazeplayer.R.drawable.ic_close) {
            dialog.dismiss()
            stopGuestPartyPolling()
            stopHostPartyRefresh()
            sendStopPartyHostCommand()
            partyClient = null
            guestPartyState = null
            BlazePartyVoteManager.disconnect(requireContext())
            android.widget.Toast.makeText(requireContext(), getString(fr.retrospare.blazeplayer.R.string.blaze_party_disconnected), android.widget.Toast.LENGTH_SHORT).show()
        })
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showBlazePartyHostDialog() {
        BlazePartyVoteManager.setHost(requireContext(), true)
        val hostIps = getLocalIpv4Addresses()
        val ip = hostIps.firstOrNull() ?: "0.0.0.0"
        val token = Random.nextInt(100000, 999999).toString()
        val payload = PartyProtocol.buildPayload(ip, token, alternateHosts = hostIps.drop(1))
        BlazePartyVoteManager.saveSessionPayload(requireContext(), payload)
        BlazePartyVoteManager.saveHostToken(requireContext(), token)
        sendStartPartyHostCommand(token)
        startHostPartyRefresh()
        val dialog = Dialog(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = ContextCompat.getDrawable(requireContext(), fr.retrospare.blazeplayer.R.drawable.bg_dialog)
        }
        root.addView(TextView(requireContext()).apply {
            text = getString(fr.retrospare.blazeplayer.R.string.blaze_party_host_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        try {
            root.addView(ImageView(requireContext()).apply {
                setImageBitmap(SimpleQrCode.bitmap(payload))
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(16); bottomMargin = dp(12) }
                contentDescription = getString(fr.retrospare.blazeplayer.R.string.blaze_party_qr_desc)
            })
        } catch (e: IllegalArgumentException) {
            CrashReporter.log(requireContext(), "Blaze Party QR generation failed for payload length=${payload.length}", e)
            root.addView(TextView(requireContext()).apply {
                text = payload
                setTextColor(ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.green_accent))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(12))
            })
        }
        root.addView(TextView(requireContext()).apply {
            text = getString(fr.retrospare.blazeplayer.R.string.blaze_party_share_code, ip, token)
            setTextColor(ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.on_surface_variant))
            textSize = 13f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(requireContext()).apply {
            text = getString(fr.retrospare.blazeplayer.R.string.blaze_party_host_waiting)
            setTextColor(ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.green_accent))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun scanBlazePartyQr() {
        // Scan intégré côté Blaze Player : on ne délègue plus à l'appareil photo Android, car
        // certains scanners système ouvrent la feuille native de partage au lieu de transmettre le
        // lien profond à Blaze. Ici, Blaze lit le QR, parse le payload et rejoint la session lui-même.
        val scanner = GmsBarcodeScanning.getClient(
            requireContext(),
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val payload = barcode.rawValue.orEmpty()
                if (payload.isBlank()) {
                    android.widget.Toast.makeText(requireContext(), getString(fr.retrospare.blazeplayer.R.string.blaze_party_scan_unavailable), android.widget.Toast.LENGTH_LONG).show()
                } else {
                    showBlazePartyJoined(payload)
                }
            }
            .addOnCanceledListener { /* Annulation utilisateur : rien à faire. */ }
            .addOnFailureListener { error ->
                CrashReporter.log(requireContext(), "Blaze Party integrated QR scan failed", error)
                startLegacyBlazePartyScanner()
            }
    }

    private fun startLegacyBlazePartyScanner() {
        // Dernier recours : ZXing externe renvoie le contenu du QR à notre ActivityResult. On évite
        // volontairement l'intent caméra générique, qui était la source de la feuille de partage.
        try {
            val scannerIntent = android.content.Intent("com.google.zxing.client.android.SCAN").apply {
                putExtra("SCAN_MODE", "QR_CODE_MODE")
            }
            blazePartyScan.launch(scannerIntent)
        } catch (_: Exception) {
            android.widget.Toast.makeText(requireContext(), getString(fr.retrospare.blazeplayer.R.string.blaze_party_scan_unavailable), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private val blazePartyScan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val contents = result.data?.getStringExtra("SCAN_RESULT")
        if (result.resultCode == Activity.RESULT_OK && !contents.isNullOrBlank()) {
            showBlazePartyJoined(contents)
        }
    }

    private fun showBlazePartyJoined(payload: String) {
        val connection = PartyProtocol.parse(payload)
        if (connection == null) {
            android.widget.Toast.makeText(requireContext(), getString(fr.retrospare.blazeplayer.R.string.blaze_party_scan_unavailable), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        BlazePartyVoteManager.setHost(requireContext(), false)
        BlazePartyVoteManager.saveSessionPayload(requireContext(), payload)
        BlazePartyVoteManager.saveConnection(requireContext(), connection)
        requestOpenBlazePartySheet()
        showBlazePartyNicknameDialog()
        startGuestPartySync(connection, showJoinedToastAfterNetworkJoin = true)
    }

    // ── Blaze Party réseau : hôte ────────────────────────────────────────────
    // Le serveur lui-même vit dans BlazePlayerService (COMMAND_PARTY_START_HOST/STOP_HOST) pour
    // continuer de répondre aux invités même après que cet écran a été quitté — exactement comme
    // la lecture audio elle-même survit à la fermeture de l'écran. Ce Fragment se contente de le
    // piloter via le MediaController, au même titre que play/pause/seek.

    private fun sendStartPartyHostCommand(token: String) {
        // Secours direct vers le service : évite que le QR soit affiché alors que le serveur
        // NanoHTTPD n'a pas démarré parce que le MediaController n'était pas encore disponible.
        try {
            requireContext().startService(android.content.Intent(requireContext(), BlazePlayerService::class.java).apply {
                action = BlazePlayerService.ACTION_PARTY_START_HOST
                putExtra(BlazePlayerService.EXTRA_PARTY_TOKEN, token)
            })
        } catch (e: Exception) {
            CrashReporter.log(requireContext(), "Blaze Party service start fallback failed", e)
        }
        val ctrl = controller ?: return
        val args = android.os.Bundle().apply { putString(BlazePlayerService.EXTRA_PARTY_TOKEN, token) }
        ctrl.sendCustomCommand(SessionCommand(BlazePlayerService.COMMAND_PARTY_START_HOST, android.os.Bundle.EMPTY), args)
    }

    private fun sendStopPartyHostCommand() {
        try {
            requireContext().startService(android.content.Intent(requireContext(), BlazePlayerService::class.java).apply {
                action = BlazePlayerService.ACTION_PARTY_STOP_HOST
            })
        } catch (_: Exception) {}
        val ctrl = controller ?: return
        ctrl.sendCustomCommand(SessionCommand(BlazePlayerService.COMMAND_PARTY_STOP_HOST, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
    }

    private fun startHostPartyRefresh() {
        if (hostPartyRefreshActive) return
        hostPartyRefreshActive = true
        handler.removeCallbacks(hostPartyRefreshRunnable)
        handler.post(hostPartyRefreshRunnable)
    }

    private fun stopHostPartyRefresh() {
        hostPartyRefreshActive = false
        handler.removeCallbacks(hostPartyRefreshRunnable)
    }

    // ── Blaze Party réseau : invité ──────────────────────────────────────────


    private fun applyGuestPartyState(state: PartyState?) {
        if (state == null) return
        guestPartyState = state
        guestPartyStateReceivedAtMs = SystemClock.elapsedRealtime()
        if (::partyPlaylistAdapter.isInitialized) {
            refreshPartyPlaylistSheet()
            updateGuestPartyProgressFromHostClock()
        }
        maybeOpenPendingBlazePartySheet()
    }

    private fun updateGuestPartyProgressFromHostClock() {
        val state = guestPartyState ?: return
        if (!::partyPlaylistAdapter.isInitialized) return
        val currentPath = state.currentPath
        val duration = state.currentDurationMs.takeIf { it > 0L }
            ?: state.tracks.firstOrNull { it.path == currentPath }?.durationMs
            ?: 0L
        if (currentPath.isNullOrBlank() || duration <= 0L) {
            partyPlaylistAdapter.updateCurrentProgress(currentPath, 0L, duration)
            return
        }
        val elapsed = if (state.isPlaying) {
            (SystemClock.elapsedRealtime() - guestPartyStateReceivedAtMs).coerceAtLeast(0L)
        } else 0L
        val position = (state.currentPositionMs + elapsed).coerceAtLeast(0L).coerceAtMost(duration)
        partyPlaylistAdapter.updateCurrentProgress(currentPath, position, duration)
    }

    /** Point d'entrée unique pour tout résultat réseau côté invité (join initial ET polling).
     *
     *  Après un premier snapshot valide, on ne bascule plus automatiquement en file locale sur
     *  quelques /state ratés : Android peut interrompre brièvement le Wi‑Fi, recycler une socket ou
     *  retarder NanoHTTPD alors que la session est toujours correcte. L'ancien comportement affichait
     *  donc à tort "connexion à l'hôte perdue" après une connexion réussie. On conserve le dernier
     *  état reçu, on garde la file partagée affichée et on continue à réessayer jusqu'à déconnexion
     *  manuelle. Avant le tout premier état, on garde seulement un garde-fou pour les QR vraiment
     *  invalides/injoignables. */
    private fun handleGuestPartyFetchResult(state: PartyState?) {
        if (state != null) {
            guestPartyConsecutiveFailures = 0
            guestPartyHasReceivedState = true
            applyGuestPartyState(state)
            return
        }

        guestPartyConsecutiveFailures++
        if (guestPartyHasReceivedState) {
            // Connexion déjà établie : ne détruis pas la session sur un incident de polling. Le
            // prochain /state remettra l'UI à jour ; les votes restent envoyés via PartyClient.
            android.util.Log.w("AudioPlayerFragment", "Blaze Party /state temporairement indisponible ($guestPartyConsecutiveFailures échecs consécutifs)")
            return
        }

        // Aucun état n'a jamais été reçu : là seulement on considère que le QR/l'hôte est injoignable.
        val maxFailuresBeforeFirstState = 10
        if (guestPartyConsecutiveFailures >= maxFailuresBeforeFirstState) {
            val ctx = context
            stopGuestPartyPolling()
            partyClient = null
            guestPartyState = null
            guestPartyHasReceivedState = false
            guestPartyConsecutiveFailures = 0
            if (ctx != null) {
                BlazePartyVoteManager.disconnect(ctx)
                android.widget.Toast.makeText(ctx, getString(fr.retrospare.blazeplayer.R.string.blaze_party_connection_lost), android.widget.Toast.LENGTH_LONG).show()
                if (::partyPlaylistAdapter.isInitialized) refreshPartyPlaylistSheet()
            }
        }
    }

    private fun startGuestPartySync(connection: PartyConnection, showJoinedToastAfterNetworkJoin: Boolean = false) {
        partyClient = PartyClient(requireContext().applicationContext, connection)
        guestPartyConsecutiveFailures = 0
        guestPartyHasReceivedState = false
        partyClient?.joinAndFetch(BlazePartyVoteManager.getNickname(requireContext())) { state ->
            val joined = state != null
            handleGuestPartyFetchResult(state)
            if (joined && showJoinedToastAfterNetworkJoin && isAdded) {
                android.widget.Toast.makeText(requireContext(), getString(fr.retrospare.blazeplayer.R.string.blaze_party_joined), android.widget.Toast.LENGTH_LONG).show()
            }
        }
        startGuestPartyPolling(initialDelayMs = 1800L)
    }

    private fun startGuestPartyPolling(initialDelayMs: Long = 0L) {
        if (guestPartyPollingActive) return
        guestPartyPollingActive = true
        handler.removeCallbacks(guestPartyPollRunnable)
        if (initialDelayMs > 0L) handler.postDelayed(guestPartyPollRunnable, initialDelayMs) else handler.post(guestPartyPollRunnable)
    }

    private fun requestOpenBlazePartySheet() {
        context?.getSharedPreferences("launcher_requests", android.content.Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean("pendingOpenBlazePartySheet", true)
            ?.putLong("pendingOpenBlazePartySheetAt", System.currentTimeMillis())
            ?.apply()
    }

    private fun maybeOpenPendingBlazePartySheet() {
        val ctx = context ?: return
        if (!isAdded || _binding == null || !::partyPlaylistAdapter.isInitialized) return
        if (BlazePartyVoteManager.isHost(ctx) || !BlazePartyVoteManager.isActive(ctx)) return
        val prefs = ctx.getSharedPreferences("launcher_requests", android.content.Context.MODE_PRIVATE)
        val pending = prefs.getBoolean("pendingOpenBlazePartySheet", false) ||
            prefs.getLong("pendingOpenBlazePartySheetAt", 0L) > 0L
        if (!pending) return

        // On attend d'avoir reçu un état réel de l'hôte avant d'ouvrir la feuille. Ne pas bloquer sur
        // tracks.isEmpty() : une session valide peut démarrer vide ou être alimentée juste après le
        // scan, et l'ancien test donnait l'impression que rien ne s'était connecté.
        if (guestPartyState == null) return

        openPartyPlaylistSheet()
        prefs.edit()
            .putBoolean("pendingOpenBlazePartySheet", false)
            .remove("pendingOpenBlazePartySheetAt")
            .apply()
    }

    private fun stopGuestPartyPolling() {
        guestPartyPollingActive = false
        handler.removeCallbacks(guestPartyPollRunnable)
    }

    /** Reconnecte le client réseau si le fragment est recréé (rotation, retour d'un autre écran)
     *  alors qu'une session invité était déjà active. */
    private fun maybeResumeGuestPartySync() {
        val ctx = context ?: return
        if (BlazePartyVoteManager.isHost(ctx) || !BlazePartyVoteManager.isActive(ctx)) return
        val connection = BlazePartyVoteManager.getConnection(ctx) ?: return
        if (partyClient == null) {
            val client = PartyClient(ctx.applicationContext, connection)
            partyClient = client
            guestPartyConsecutiveFailures = 0
            client.joinAndFetch(BlazePartyVoteManager.getNickname(ctx)) { state ->
                handleGuestPartyFetchResult(state)
            }
        }
        startGuestPartyPolling(initialDelayMs = 1800L)
    }

    private fun showBlazePartyNicknameDialog() {
        val ctx = requireContext()
        val input = com.google.android.material.textfield.TextInputEditText(ctx).apply {
            setSingleLine(true)
            hint = getString(fr.retrospare.blazeplayer.R.string.blaze_party_nickname_hint)
            setText(BlazePartyVoteManager.getNickname(ctx).takeIf { it != getString(fr.retrospare.blazeplayer.R.string.blaze_party_default_host) && it != "Hôte" }.orEmpty())
        }
        val box = com.google.android.material.textfield.TextInputLayout(ctx).apply {
            setPadding(dp(20), dp(10), dp(20), 0)
            hint = getString(fr.retrospare.blazeplayer.R.string.blaze_party_nickname)
            addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(fr.retrospare.blazeplayer.R.string.blaze_party_nickname_title))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> BlazePartyVoteManager.saveNickname(ctx, input.text?.toString().orEmpty()) }
            .setCancelable(false)
            .showPremium()
    }

    private fun getLocalIpv4Address(): String? = getLocalIpv4Addresses().firstOrNull()

    private fun getLocalIpv4Addresses(): List<String> {
        // Blaze Party doit annoncer une adresse réellement joignable par les invités sur le LAN.
        // On privilégie explicitement Wi‑Fi/ethernet/hotspot et on évite les interfaces mobile/VPN :
        // si l'IP mobile est encodée dans le QR, le client ouvre bien Blaze Player mais ne peut pas
        // joindre NanoHTTPD, ce qui provoque aussitôt "connexion à l'hôte perdue".
        fun valid(address: java.net.InetAddress): Boolean =
            address is Inet4Address &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isAnyLocalAddress

        val result = linkedSetOf<String>()
        fun addAddress(address: java.net.InetAddress?) {
            if (address != null && valid(address)) address.hostAddress?.takeIf { it.isNotBlank() }?.let(result::add)
        }

        try {
            val wifi = requireContext().applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            val rawIp = wifi?.connectionInfo?.ipAddress ?: 0
            if (rawIp != 0) {
                val ip = listOf(
                    rawIp and 0xff,
                    rawIp shr 8 and 0xff,
                    rawIp shr 16 and 0xff,
                    rawIp shr 24 and 0xff
                ).joinToString(".")
                addAddress(java.net.InetAddress.getByName(ip))
            }
        } catch (_: Exception) { }

        try {
            val cm = requireContext().applicationContext
                .getSystemService(android.net.ConnectivityManager::class.java)
            cm?.allNetworks
                ?.mapNotNull { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                    val props = cm.getLinkProperties(network) ?: return@mapNotNull null
                    caps to props
                }
                ?.filter { (caps, _) ->
                    (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                        !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                }
                ?.forEach { (_, props) -> props.linkAddresses.forEach { addAddress(it.address) } }

            val active = cm?.activeNetwork
            val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
            if (active != null && activeCaps != null &&
                !activeCaps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !activeCaps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                cm.getLinkProperties(active)?.linkAddresses?.forEach { addAddress(it.address) }
            }
        } catch (_: Exception) { }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
                .filter { iface ->
                    try { iface.isUp && !iface.isLoopback } catch (_: Exception) { false }
                }
                .mapNotNull { iface ->
                    val n = iface.name.lowercase()
                    val rank = when {
                        n.startsWith("wlan") || n.contains("wifi") -> 0
                        n.startsWith("eth") -> 1
                        n.startsWith("ap") || n.contains("softap") || n.contains("p2p") -> 2
                        else -> 99
                    }
                    if (rank < 99) rank to iface else null
                }
                .sortedBy { it.first }
            interfaces.forEach { (_, iface) -> iface.inetAddresses.toList().forEach { addAddress(it) } }
        } catch (_: Exception) { }

        return result.toList()
    }

    private fun restoreBlazePartyRuntimeIfNeeded(ctrl: MediaController) {
        val ctx = context ?: return
        if (!BlazePartyVoteManager.isActive(ctx)) return

        if (!BlazePartyVoteManager.isHost(ctx)) {
            // Invité : pas de file locale à reconstruire (les fichiers sont ceux de l'hôte, pas
            // les nôtres) — on se contente de reprendre la synchronisation réseau si besoin.
            maybeResumeGuestPartySync()
            return
        }

        // Hôte : le serveur est géré par BlazePlayerService, qui le redémarre lui-même si besoin
        // (cf. son onCreate) — ce Fragment n'a qu'à reprendre le rafraîchissement local de l'écran.
        startHostPartyRefresh()

        // Au démarrage, si l'hôte n'a aucun client Blaze Party connecté, l'écran
        // "file d'attente" doit revenir sur la file locale personnelle. Sinon une
        // ancienne session Party pouvait remplacer visuellement la file locale par
        // défaut, même quand personne n'était connecté.
        if (!BlazePartyVoteManager.isConnected(ctx)) {
            val snapshot = loadLocalQueueSnapshot(ctx)
            localHostQueueSnapshot = snapshot
            isPlayingBlazePartyQueue = false
            currentBlazePartyPath = null
            if (::playlistAdapter.isInitialized) playlistAdapter.setOverrideItems(null)
            if (!snapshot.isNullOrEmpty()) {
                ctrl.shuffleModeEnabled = false
                ctrl.clearMediaItems()
                ctrl.setMediaItems(snapshot, 0, 0L)
                ctrl.prepare()
                if (::playlistAdapter.isInitialized) playlistAdapter.refresh()
                syncSelection()
                syncMetadata()
                syncButtons()
                savePlaylistFromController()
            }
            return
        }

        val partyTracks = sortedBlazePartyTracks(ctx)
        if (partyTracks.isEmpty()) return
        localHostQueueSnapshot = loadLocalQueueSnapshot(ctx)
        playlistAdapter.setOverrideItems(localHostQueueSnapshot)
        if (ctrl.mediaItemCount == 0) {
            val mediaItems = partyTracks.map { AudioRepository.buildSimpleMediaItem(ctx, it.path, it.name) }
            ctrl.shuffleModeEnabled = false
            ctrl.setMediaItems(mediaItems, 0, 0L)
            ctrl.prepare()
            isPlayingBlazePartyQueue = true
            currentBlazePartyPath = partyTracks.firstOrNull()?.path
            partyPlaylistAdapter.setCurrentPath(currentBlazePartyPath)
            refreshPartyPlaylistSheet()
        } else {
            val controllerPaths = (0 until ctrl.mediaItemCount).map { originalPathOf(ctrl.getMediaItemAt(it)) }.toSet()
            val partyPaths = partyTracks.map { it.path }.toSet()
            if (controllerPaths.isNotEmpty() && controllerPaths.all { it in partyPaths }) {
                isPlayingBlazePartyQueue = true
                currentBlazePartyPath = if (ctrl.currentMediaItemIndex in 0 until ctrl.mediaItemCount) originalPathOf(ctrl.getMediaItemAt(ctrl.currentMediaItemIndex)) else null
                partyPlaylistAdapter.setCurrentPath(currentBlazePartyPath)
                refreshPartyPlaylistSheet()
            }
        }
    }

    private fun saveLocalQueueSnapshot(ctx: android.content.Context, snapshot: List<MediaItem>) {
        val arr = JSONArray()
        snapshot.forEach { mi ->
            val path = originalPathOf(mi)
            if (path.isBlank() || !AudioRepository.isSupportedAudioPath(path)) return@forEach
            val name = mi.mediaMetadata.title?.toString()?.ifEmpty { null } ?: mi.localConfiguration?.uri?.lastPathSegment ?: ""
            arr.put(JSONObject().apply { put("path", path); put("name", name) })
        }
        ctx.getSharedPreferences("blaze_party_local_snapshot", android.content.Context.MODE_PRIVATE)
            .edit().putString("items", arr.toString()).apply()
    }

    private fun loadLocalQueueSnapshot(ctx: android.content.Context): List<MediaItem>? {
        val raw = ctx.getSharedPreferences("blaze_party_local_snapshot", android.content.Context.MODE_PRIVATE).getString("items", null) ?: return null
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = o.optString("path")
                val name = o.optString("name")
                if (path.isBlank()) null else AudioRepository.buildSimpleMediaItem(ctx, path, name)
            }
        } catch (_: Exception) { null }
    }


    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showCleanDialog() {
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount == 0) {
            android.widget.Toast.makeText(requireContext(), getString(fr.retrospare.blazeplayer.R.string.toast_list_already_empty), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val itemsSnapshot = (0 until ctrl.mediaItemCount).map { i ->
            val mi = ctrl.getMediaItemAt(i)
            mi.mediaMetadata.title?.toString()?.ifEmpty { null } ?: mi.localConfiguration?.uri?.lastPathSegment ?: "?"
        }
        val checked = BooleanArray(itemsSnapshot.size) { false }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(fr.retrospare.blazeplayer.R.string.dialog_clean_list))
            .setMultiChoiceItems(itemsSnapshot.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton(getString(fr.retrospare.blazeplayer.R.string.action_remove_selection)) { _, _ ->
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
            .setNeutralButton(getString(fr.retrospare.blazeplayer.R.string.action_clear_all)) { _, _ ->
                controller?.clearMediaItems()
                playlistAdapter.refresh()
                AudioRepository.clear(requireContext())
            }
            .setNegativeButton(getString(fr.retrospare.blazeplayer.R.string.action_cancel), null)
            .showPremium()
    }
    private fun purgeNonAudioItems(ctrl: MediaController) {
        try {
            for (i in ctrl.mediaItemCount - 1 downTo 0) {
                val path = originalPathOf(ctrl.getMediaItemAt(i))
                if (path.isBlank() || !AudioRepository.isAudioExtension(path)) {
                    ctrl.removeMediaItem(i)
                }
            }
        } catch (e: Exception) {
            CrashReporter.log(requireContext(), "Purge non-audio items from audio player failed", e)
        }
    }

    private fun originalPathOf(item: androidx.media3.common.MediaItem): String {
        val fromExtras = item.mediaMetadata.extras?.getString("blaze_original_path")
            ?.takeIf { it.isNotBlank() && AudioRepository.isAudioExtension(it) }
        if (fromExtras != null) return fromExtras
        return item.mediaId.takeIf { it.isNotBlank() && AudioRepository.isAudioExtension(it) }
            ?: item.localConfiguration?.uri?.toString()?.takeIf { AudioRepository.isAudioExtension(it) }
            ?: ""
    }



    companion object {
        private const val DYNAMIC_AUDIO_PREFS = "blaze_audio_dynamic_colors"
        private const val KEY_DYNAMIC_BG = "dynamic_bg"
        private const val KEY_DYNAMIC_ACCENT = "dynamic_accent"
    }
}
