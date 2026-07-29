package fr.retrospare.blazeplayer.player

import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.ui.showPremium
import fr.retrospare.blazeplayer.ui.ButtonTextFitter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.widget.ImageView
import android.widget.ImageButton
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
import android.content.SharedPreferences
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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject

@AndroidEntryPoint
class AudioPlayerFragment : Fragment() {

    /** Clé propre à cette instance. Plusieurs fragments peuvent être restaurés brièvement par
     * FragmentManager ; une clé globale faisait alors pointer la FFT vers une vue hors écran. */
    private val audioVisualizerListenerTag: String =
        "audio-player-fullscreen@${System.identityHashCode(this)}"

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
    private val lyricsClockSyncRunnable = object : Runnable {
        override fun run() {
            val b = _binding
            val ctrl = controller
            if (
                b != null &&
                ctrl != null &&
                isResumed &&
                !isHidden &&
                karaokeLandscapeActive &&
                currentLyrics.isNotEmpty()
            ) {
                b.karaokeLyricsView.updatePlaybackPosition(
                    karaoKastLyricsPosition(ctrl.currentPosition),
                    ctrl.isPlaying,
                    ctrl.playbackParameters.speed
                )
            }
            if (_binding != null) {
                handler.postDelayed(this, LYRICS_CLOCK_SYNC_INTERVAL_MS)
            }
        }
    }
    private var isSeekBarTracking = false
    private var sleepTimerIndicatorJob: Job? = null
    private var eqManager: EqualizerManager? = null
    private var currentDynamicBgColor: Int = Color.rgb(10, 12, 14)
    private var currentAccentColor: Int = Color.rgb(63, 215, 143)
    private var bgAnimator: ValueAnimator? = null
    private var currentVisualizerSessionId: Int = 0
    private var lastKnownVisualizerSessionId: Int = 0
    private var pendingVisualizerSessionId: Int = 0
    private var audioSpectrumEnabled: Boolean = true
    private var visualizerRequestGeneration: Int = 0
    private var visualizerRequestInFlight: Boolean = false
    private var visualizerAttachedAtMs: Long = 0L
    private var lastVisualizerFrameAtMs: Long = 0L
    private var lastVisualizerRestartAtMs: Long = 0L
    private var visualizerPlaybackStartAtMs: Long = 0L
    private var visualizerStartupVerificationAttempts: Int = 0
    private var visualizerFailureCount: Int = 0
    private var visualizerFreshStartEpoch: Long = 0L
    private var visualizerFreshStartRunnable: Runnable? = null
    private var visualizerFreshStartReason: String = ""

    /**
     * Vrai jusqu'à la première trame FFT reçue après la création de cet écran. Ce cas est
     * particulièrement important après restauration d'une file/position : ExoPlayer peut être
     * READY alors que son AudioTrack — donc son audioSessionId — n'est pas encore recréé.
     */
    private var visualizerColdResumePending: Boolean = true
    private var visualizerColdResumeRecoveryStarted: Boolean = false
    private var visualizerColdResumeAttempts: Int = 0
    private val visualizerColdResumeRunnable = object : Runnable {
        override fun run() {
            if (!visualizerColdResumePending || !canRunAudioVisualizerRecovery()) return

            // La santé de la capture doit être évaluée depuis le thread FFT, jamais depuis le
            // callback UI : un rafraîchissement de bibliothèque peut retarder le thread principal
            // sans que le Visualizer soit réellement en panne.
            val receivedCaptureForCurrentPlayback =
                visualizerPlaybackStartAtMs > 0L &&
                    AudioFftStream.hasCapturedSince(visualizerPlaybackStartAtMs)

            if (receivedCaptureForCurrentPlayback) {
                visualizerColdResumePending = false
                visualizerColdResumeRecoveryStarted = false
                visualizerColdResumeAttempts = 0
                handler.removeCallbacks(this)
                startVisualizerWatchdog()
                return
            }

            visualizerColdResumeAttempts++
            val sessionId = currentVisualizerSessionId
            val streamRunning = isValidAudioSessionId(sessionId) &&
                AudioFftStream.isRunning(sessionId)
            val frameStale = AudioFftStream.millisSinceLastCapture() >
                VISUALIZER_COLD_RESUME_FRAME_TIMEOUT_MS

            // Tant qu'aucune vraie trame de la lecture restaurée n'est arrivée, redemander la
            // session. Si un Visualizer existe mais reste muet, le recréer réellement.
            ensureAudioVisualizer(forceRestart = streamRunning && frameStale)
            scheduleVisualizerStartupVerification()

            val retryDelay = VISUALIZER_COLD_RESUME_DELAYS_MS[
                (visualizerColdResumeAttempts - 1)
                    .coerceIn(0, VISUALIZER_COLD_RESUME_DELAYS_MS.lastIndex)
            ]
            handler.postDelayed(this, retryDelay)
        }
    }

    private val visualizerRecoveryRunnable = Runnable {
        if (canRunAudioVisualizerRecovery()) {
            ensureAudioVisualizer(forceRestart = shouldForceVisualizerRestart())
            scheduleVisualizerStartupVerification()
        }
    }
    private val visualizerUiRecoveryRunnable = Runnable {
        if (canRunAudioVisualizerRecovery()) {
            ensureAudioVisualizer(forceRestart = shouldForceVisualizerRestart())
            scheduleVisualizerStartupVerification()
        }
    }
    private val visualizerWatchdogRunnable = object : Runnable {
        override fun run() {
            val ctrl = controller
            if (!audioSpectrumEnabled || _binding == null || isHidden || !isResumed || ctrl?.isPlaying != true) {
                return
            }

            // Pendant un mirroring actif, ne jamais recréer l'effet afin d'éviter une coupure dans
            // le flux capturé. En simple vue paroles/KaraoKast locale, le watchdog reste actif :
            // c'est précisément lors de ces changements de disposition que certains appareils
            // interrompent silencieusement les callbacks FFT.
            if (karaokeMirroringActive) {
                handler.postDelayed(this, VISUALIZER_WATCHDOG_INTERVAL_MS)
                return
            }

            val now = SystemClock.elapsedRealtime()
            val sessionId = currentVisualizerSessionId
            val attachedLongEnough = visualizerAttachedAtMs > 0L &&
                now - visualizerAttachedAtMs > VISUALIZER_STALE_AFTER_MS
            // Timestamp mis à jour directement depuis le thread de capture du Visualizer : il ne
            // dépend pas de la fluidité du thread UI et évite les faux blocages pendant un rendu lourd.
            val callbacksStale = attachedLongEnough &&
                AudioFftStream.millisSinceLastCapture(now) > VISUALIZER_STALE_AFTER_MS
            val streamUnavailable = sessionId == 0 || !AudioFftStream.isRunning(sessionId)

            if ((streamUnavailable || callbacksStale) &&
                now - lastVisualizerRestartAtMs >= VISUALIZER_RESTART_COOLDOWN_MS
            ) {
                // lastVisualizerRestartAtMs n'est mis à jour qu'au moment où une nouvelle instance
                // est réellement attachée. Le renseigner ici ferait bloquer la requête elle-même
                // par le garde-fou anti-redémarrages rapprochés.
                ensureAudioVisualizer(forceRestart = true)
            }
            handler.postDelayed(this, VISUALIZER_WATCHDOG_INTERVAL_MS)
        }
    }
    private val visualizerTrackRestartRunnable = Runnable {
        if (audioSpectrumEnabled && _binding != null && controller?.isPlaying == true) {
            // Après un démarrage/changement de titre, certains appareils conservent le même
            // audioSessionId tout en arrêtant silencieusement les callbacks FFT. Si aucune trame
            // n'est arrivée depuis le début de cette lecture, on recrée réellement le Visualizer.
            val receivedCaptureForThisPlayback = visualizerPlaybackStartAtMs > 0L &&
                AudioFftStream.hasCapturedSince(visualizerPlaybackStartAtMs)
            val sessionId = currentVisualizerSessionId
            val streamHealthy = isValidAudioSessionId(sessionId) &&
                AudioFftStream.isHealthy(sessionId, VISUALIZER_TRACK_HEALTH_WINDOW_MS)
            // Ne jamais recréer un Visualizer qui capture correctement uniquement parce que le
            // thread principal n'a pas encore dessiné la trame. C'était la cause de l'alternance
            // visible « figé / redémarré » pendant les publications de bibliothèque.
            ensureAudioVisualizer(
                forceRestart = !streamHealthy && !receivedCaptureForThisPlayback
            )
            scheduleVisualizerStartupVerification()
        }
    }
    private val visualizerStartupVerifyRunnable = object : Runnable {
        override fun run() {
            val ctrl = controller
            if (!audioSpectrumEnabled || _binding == null || isHidden || !isResumed || ctrl?.isPlaying != true) {
                return
            }
            // Seul le mirroring interdit une recréation. La vue paroles locale doit au contraire
            // pouvoir récupérer automatiquement une FFT devenue muette après le changement de layout.
            if (karaokeMirroringActive) return

            val now = SystemClock.elapsedRealtime()
            val sessionId = currentVisualizerSessionId
            val expectedCaptureAfterMs = maxOf(
                visualizerAttachedAtMs,
                visualizerPlaybackStartAtMs
            )
            val receivedCurrentCapture = expectedCaptureAfterMs > 0L &&
                AudioFftStream.hasCapturedSince(expectedCaptureAfterMs)
            val streamRunning = sessionId > 0 && AudioFftStream.isRunning(sessionId)
            val streamHealthy = streamRunning &&
                receivedCurrentCapture &&
                AudioFftStream.millisSinceLastCapture(now) <= VISUALIZER_STARTUP_FRAME_TIMEOUT_MS
            if (streamHealthy) {
                visualizerStartupVerificationAttempts = 0
                visualizerColdResumePending = false
                visualizerColdResumeRecoveryStarted = false
                startVisualizerWatchdog()
                return
            }

            visualizerStartupVerificationAttempts++
            val captureActuallyStale = streamRunning &&
                now - visualizerAttachedAtMs >= VISUALIZER_ATTACH_GRACE_MS &&
                AudioFftStream.millisSinceLastCapture(now) > VISUALIZER_STARTUP_FRAME_TIMEOUT_MS
            ensureAudioVisualizer(forceRestart = captureActuallyStale)

            // Après la rafale initiale, continuer à vérifier à cadence douce au lieu d'abandonner.
            // L'utilisateur ne doit jamais avoir à désactiver/réactiver l'option pour repartir.
            val retryDelay = if (
                visualizerStartupVerificationAttempts <= VISUALIZER_STARTUP_MAX_RETRIES
            ) {
                VISUALIZER_STARTUP_RETRY_DELAY_MS
            } else {
                VISUALIZER_LONG_RECOVERY_DELAY_MS
            }
            handler.postDelayed(this, retryDelay)
        }
    }
    private var currentLyrics: List<AudioLocalEnhancements.LyricLine> = emptyList()
    private var currentLyricsData: AudioLocalEnhancements.LocalLyrics? = null
    private var lastLyricsLine: String? = null
    private var lastLyricsOverlayKey: String? = null
    private var lyricsJob: Job? = null
    private var currentLyricsPath: String = ""
    private var completedLyricsLookupPath: String = ""
    private var karaokeAvailable: Boolean = false
    private var karaokeLandscapeActive: Boolean = false
    private var karaokeMirroringActive: Boolean = false
    private var karaoKastSyncOffsetMs: Long = AudioProSettings.DEFAULT_KARAOKAST_SYNC_OFFSET_MS.toLong()
    private var screenMirrorStateMonitor: fr.retrospare.blazeplayer.cast.SystemScreenMirrorStateMonitor? = null
    private var artworkLoadJob: Job? = null
    private var dynamicColorJob: Job? = null
    private var currentArtworkPath: String = ""
    private var currentDynamicArtworkKey: String = ""
    private var lastAppliedDynamicArtworkKey: String = ""
    private val mediaItemReplacementMutex = Mutex()
    private var lastMediaItemReplacementAtMs: Long = 0L
    private val audioProPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (isAdded && key == AudioProSettings.KEY_SYNCED_LYRICS) {
            var masterSettingChanged = false
            if (key == AudioProSettings.KEY_SYNCED_LYRICS) {
                masterSettingChanged = true
                val prefs = AudioProSettings.prefs(requireContext())
                val enabled = prefs.getBoolean(AudioProSettings.KEY_SYNCED_LYRICS, true)
                // Ne pas réécrire la préférence si elle possède déjà la bonne valeur : cela évite
                // un second callback et donc un redémarrage inutile du lookup .LRC.
                if (prefs.getBoolean(AudioProSettings.KEY_LYRICS_PLAYER, true) != enabled) {
                    prefs.edit().putBoolean(AudioProSettings.KEY_LYRICS_PLAYER, enabled).apply()
                }
                if (!enabled) {
                    currentLyricsData = null
                    currentLyrics = emptyList()
                    lastLyricsLine = null
                    lastLyricsOverlayKey = null
                    updateKaraokeAvailability()
                } else {
                    completedLyricsLookupPath = ""
                }
            }
            view?.post {
                val settings = AudioProSettings.read(requireContext())
                if (masterSettingChanged && settings.syncedLyrics) {
                    val path = controller?.currentMediaItem?.let { originalPathOf(it) }.orEmpty()
                    if (path.isNotBlank()) loadLyricsForCurrentTrack(path, force = true)
                }
                updateKaraokeAvailability()
                applyAudioProInterfaceSettings()
                updateLyricsLine(controller?.currentPosition ?: 0L)
                scheduleVisualizerUiRecovery(forceRestart = false)
            }
        }
    }
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
        if (granted) {
            val sessionId = pendingVisualizerSessionId.takeIf { it > 0 }
                ?: lastKnownVisualizerSessionId
            if (sessionId > 0) {
                startAudioVisualizer(sessionId, forceRestart = true)
            } else {
                scheduleVisualizerUiRecovery(forceRestart = true)
            }
            scheduleVisualizerStartupVerification()
            startVisualizerWatchdog()
        } else {
            setAudioSpectrumIdle()
        }
    }


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
            viewLifecycleOwner.lifecycleScope.launch(AudioPlaybackDispatchers.io) {
                // L'enrichissement de toute une sélection peut ouvrir de nombreuses covers SMB.
                // Il n'est jamais prioritaire sur le flux audio qui vient de démarrer.
                AudioLibraryWorkState.awaitPlaybackIdle()
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
                                    if (replaceMediaItemRateLimited(c, idx, enriched)) {
                                        playlistAdapter.notifyItemChanged(idx)
                                    }
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
        binding.artworkMetadataOverlay.radiusDp = 0f
        binding.lyricsOverlay.clipToOutline = false
        binding.lyricsOverlay.outlineProvider = null
        binding.artworkTopRightInfo.bringToFront()
        binding.artworkFrame.clipToOutline = false
        binding.artworkFrame.outlineProvider = null
        binding.artworkFrame.foreground = null
        binding.ivArtwork.clipToOutline = false
        binding.ivArtwork.outlineProvider = null
        binding.ivArtwork.background = null
        binding.ivArtwork.foreground = null
        binding.ivArtwork.elevation = 0f
        binding.ivArtwork.translationZ = 0f
        binding.ivArtwork.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.karaokeArtworkFrame.radiusDp = 23f
        binding.karaokeArtworkOverlay.radiusDp = 23f
        binding.ivKaraokeArtwork.radiusDp = 23f
        binding.ivKaraokeArtwork.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.karaokeLyricsView.setAccentColor(currentAccentColor)
        setupSquareArtwork()
        setupCompactMetadataAutoFit()
        restoreStaticAudioControlColors()
        restorePersistedDynamicAudioColors()
        AudioProSettings.prefs(requireContext()).registerOnSharedPreferenceChangeListener(audioProPrefsListener)
        refreshKaraoKastSyncOffset()
        applyAudioProInterfaceSettings()
        updateKaraokeAvailability()

        initPlaylistUi()
        setupControls()
        setupKaraokeCastControls()
        binding.karaokeLyricsView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.karaokeEqualizerView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        screenMirrorStateMonitor = fr.retrospare.blazeplayer.cast.SystemScreenMirrorStateMonitor(requireContext()) { active ->
            if (_binding == null) return@SystemScreenMirrorStateMonitor
            karaokeMirroringActive = active
            applyKaraoKastRenderingMode()
            updateKaraokeCastButtonState()
            if (active) {
                stopVisualizerWatchdog()
            } else if (audioSpectrumEnabled && controller?.isPlaying == true) {
                scheduleVisualizerUiRecovery()
                startVisualizerWatchdog()
            }
        }
        binding.root.post {
            ButtonTextFitter.fitRecursively(
                binding.root,
                minSp = 9,
                maxSp = 13,
                excludedViewIds = setOf(
                    R.id.btnBlazeParty,
                    R.id.btnAudioPlaylistParty,
                    R.id.btnAudioLibrary,
                    R.id.btnPlaylistSheet
                )
            )
            applyCompactTopMenuTypography()
        }
        setupSeekBar()
        startProgressUpdate()
        startLyricsClockSync()
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
        refreshKaraoKastSyncOffset()
        applyAudioProInterfaceSettings()
        syncSelection()
        syncMetadata()
        syncButtons()
        updateKaraokeOrientationPolicy()
        applyKaraokeLayoutForCurrentOrientation()
        screenMirrorStateMonitor?.start()
        screenMirrorStateMonitor?.refresh()
        updateKaraokeCastButtonState()
        val resumedPlaybackActive = controller?.isPlaying == true
        if (resumedPlaybackActive) {
            // Le retour dans le fragment ne doit pas coïncider avec une promotion de Room, du
            // snapshot ou des covers de bibliothèque. Les contrôles, LRC et visualiseurs disposent
            // déjà de leurs propres pools ; cette fenêtre couvre le rebind MediaController/UI.
            AudioLibraryWorkState.beginPlaybackCriticalWindow(4_000L)
        }
        _binding?.audioEqualizerView?.setPlaybackActive(resumedPlaybackActive)
        _binding?.karaokeEqualizerView?.setPlaybackActive(resumedPlaybackActive)
        _binding?.artworkFrame?.post {
            lastPortraitEqualizerArtworkSize = 0
            syncPortraitEqualizerGeometry()
        }
        if (audioSpectrumEnabled && resumedPlaybackActive) {
            // Une activité recréée peut se rattacher à un Visualizer déjà valide tout en conservant
            // une enveloppe d'affichage minuscule. Le changement d'onglet corrigeait le problème
            // parce qu'il forçait implicitement cette recalibration. On la déclenche désormais à
            // chaque retour visible, sans détacher l'effet et sans toucher au flux audio.
            recalibrateVisualizerForVisibleResume()
        }
    }

    override fun onPause() {
        screenMirrorStateMonitor?.stop()
        // Une rotation déclenche onPause()/onResume(). Conserver le Visualizer attaché évite de
        // libérer puis recréer l'effet sur la session audio, ce qui coupait le son sur certains appareils.
        stopVisualizerWatchdog()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.setInAudioPlayer(true)
            playlistAdapter.refresh()
            if (::partyPlaylistAdapter.isInitialized) refreshPartyPlaylistSheet()
            setupSavedPlaylistDrawers()
            applyAudioProInterfaceSettings()
            syncSelection()
            syncMetadata()
            syncButtons()
            updateKaraokeOrientationPolicy()
            applyKaraokeLayoutForCurrentOrientation()
            screenMirrorStateMonitor?.start()
            screenMirrorStateMonitor?.refresh()
            updateKaraokeCastButtonState()
            val resumedPlaybackActive = controller?.isPlaying == true
            if (resumedPlaybackActive) {
                AudioLibraryWorkState.beginPlaybackCriticalWindow(4_000L)
            }
            _binding?.audioEqualizerView?.setPlaybackActive(resumedPlaybackActive)
            _binding?.karaokeEqualizerView?.setPlaybackActive(resumedPlaybackActive)
            _binding?.artworkFrame?.post {
                lastPortraitEqualizerArtworkSize = 0
                syncPortraitEqualizerGeometry()
            }
            if (audioSpectrumEnabled && resumedPlaybackActive) {
                recalibrateVisualizerForVisibleResume()
            }
        } else {
            screenMirrorStateMonitor?.stop()
            _binding?.audioEqualizerView?.setPlaybackActive(false)
            _binding?.karaokeEqualizerView?.setPlaybackActive(false)
            stopVisualizerWatchdog()
            stopAudioVisualizer()
            setAudioSpectrumIdle()
            (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.setInAudioPlayer(false)
            setKaraokeLandscapeUi(false)
            lockAudioPlayerToPortrait()
            updateLyricsKeepScreenOn(false)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        _binding?.root?.post { applyKaraokeLayoutForCurrentOrientation() }
    }

    override fun onDestroyView() {
        screenMirrorStateMonitor?.stop()
        screenMirrorStateMonitor = null
        artworkLoadJob?.cancel()
        dynamicColorJob?.cancel()
        setKaraokeLandscapeUi(false)
        lockAudioPlayerToPortrait()
        stopVisualizerWatchdog()
        handler.removeCallbacksAndMessages(null)
        stopGuestPartyPolling()
        stopHostPartyRefresh()
        sleepTimerIndicatorJob?.cancel()
        lyricsJob?.cancel()
        updateLyricsKeepScreenOn(false)
        runCatching { AudioProSettings.prefs(requireContext()).unregisterOnSharedPreferenceChangeListener(audioProPrefsListener) }
        eqManager?.release()
        eqManager = null
        stopAudioVisualizer()
        savePlaylistFromController()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        try { bgAnimator?.cancel() } catch (_: Exception) {}
        controller = null
        squareArtworkListener?.let { squareArtworkContainer?.viewTreeObserver?.removeOnGlobalLayoutListener(it) }
        squareArtworkListener = null
        squareArtworkContainer = null
        lastPortraitEqualizerArtworkSize = 0
        compactMetadataFitPosted = false
        lastCompactMetadataFitKey = ""
        _binding = null
        super.onDestroyView()
    }


    // ── Mode karaoké paysage ──────────────────────────────────────────────────

    /** Le paysage n'est autorisé que pour un vrai fichier LRC avec des time codes exploitables. */
    private fun hasUsableKaraokeLyrics(data: AudioLocalEnhancements.LocalLyrics?): Boolean =
        data?.isLrc == true && data.isSynced && data.lines.any { it.text.isNotBlank() }

    private fun updateKaraokeAvailability() {
        val b = _binding ?: return
        val settings = AudioProSettings.read(requireContext())
        karaokeAvailable = settings.syncedLyrics && hasUsableKaraokeLyrics(currentLyricsData)
        b.karaokeLyricsView.setLyrics(if (karaokeAvailable) currentLyrics else emptyList())
        b.karaokeLyricsView.setAccentColor(currentAccentColor)
        b.standardLyricsView.setLyrics(currentLyrics)
        b.standardLyricsView.setAccentColor(currentAccentColor)
        updateKaraokeOrientationPolicy()
        applyKaraokeLayoutForCurrentOrientation()
        updateKaraokeCastButtonState()
    }

    private fun updateKaraokeOrientationPolicy() {
        if (!isAdded) return
        val canRotate = karaokeAvailable && !isHidden
        val requested = if (canRotate) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val activity = requireActivity()
        if (activity.requestedOrientation != requested) activity.requestedOrientation = requested
    }

    private fun lockAudioPlayerToPortrait() {
        if (!isAdded) return
        val activity = requireActivity()
        if (activity.requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun applyKaraokeLayoutForCurrentOrientation() {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        setKaraokeLandscapeUi(karaokeAvailable && landscape && !isHidden)
    }

    private fun setKaraokeLandscapeUi(active: Boolean) {
        val b = _binding ?: return
        val expectedVisibility = if (active) View.VISIBLE else View.GONE
        if (karaokeLandscapeActive == active && b.karaokeLandscapeRoot.visibility == expectedVisibility) {
            if (active) {
                b.karaokeLyricsView.updatePlaybackPosition(
                    karaoKastLyricsPosition(controller?.currentPosition ?: 0L),
                    controller?.isPlaying == true,
                    controller?.playbackParameters?.speed ?: 1f
                )
            }
            return
        }
        karaokeLandscapeActive = active
        b.karaokeLandscapeRoot.visibility = if (active) View.VISIBLE else View.GONE
        if (active) startLyricsClockSync()
        b.playerPanel.visibility = if (active) View.GONE else View.VISIBLE
        if (active) {
            b.playlistSheet.visibility = View.GONE
            b.partyPlaylistSheet.visibility = View.GONE
            b.karaokeLandscapeRoot.bringToFront()
            b.karaokeLyricsView.setLyrics(currentLyrics)
            b.karaokeLyricsView.setAccentColor(currentAccentColor)
            b.karaokeLyricsView.updatePlaybackPosition(
                    karaoKastLyricsPosition(controller?.currentPosition ?: 0L),
                    controller?.isPlaying == true,
                    controller?.playbackParameters?.speed ?: 1f
                )
            if (audioSpectrumEnabled) ensureAudioVisualizer() else setAudioSpectrumIdle()
        }
        // Recalcule immédiatement les vues actives. Cela coupe la boucle d'animation du moteur
        // de paroles couvert et évite qu'il continue à invalider son canvas sous KaraoKast.
        updateLyricsLine(controller?.currentPosition ?: 0L)
        (parentFragment as? fr.retrospare.blazeplayer.home.HomeFragment)?.setKaraokeLandscapeActive(active)
        val insetsController = androidx.core.view.WindowInsetsControllerCompat(requireActivity().window, requireActivity().window.decorView)
        insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (active) {
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        if (audioSpectrumEnabled && controller?.isPlaying == true) {
            if (karaokeMirroringActive) stopVisualizerWatchdog()
            else {
                scheduleVisualizerUiRecovery()
                startVisualizerWatchdog()
            }
        }
    }

    private fun setAudioSpectrumIdle() {
        _binding?.audioEqualizerView?.setIdle()
        _binding?.karaokeEqualizerView?.setIdle()
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
                        if (controller?.isPlaying == true) {
                            requestFreshVisualizerForCurrentTrack(
                                reason = "spectrum-enabled",
                                delayMs = VISUALIZER_FRESH_START_DELAY_MS
                            )
                        } else {
                            setAudioSpectrumIdle()
                        }
                    } else {
                        stopVisualizerWatchdog()
                        stopAudioVisualizer()
                        setAudioSpectrumIdle()
                    }
                }
        }
    }

    private fun setAudioSpectrumOverlayVisible(enabled: Boolean) {
        val b = _binding ?: return
        val settings = AudioProSettings.read(requireContext())
        val lyricsEnabled = settings.syncedLyrics
        b.artworkMetadataOverlay.visibility = if (enabled || lyricsEnabled) View.VISIBLE else View.GONE
        b.audioEqualizerView.visibility = if (enabled) View.VISIBLE else View.GONE
        b.karaokeEqualizerView.visibility = if (enabled) View.VISIBLE else View.GONE
        val playbackActive = enabled && controller?.isPlaying == true
        b.audioEqualizerView.setPlaybackActive(playbackActive)
        b.karaokeEqualizerView.setPlaybackActive(playbackActive)
        updatePortraitLyricsOverlayState(lyricsEnabled)
        if (enabled && controller?.isPlaying == true) scheduleVisualizerUiRecovery()
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

        if (ctrl.isPlaying || (ctrl.playWhenReady && ctrl.playbackState != Player.STATE_IDLE)) {
            AudioLibraryWorkState.beginPlaybackCriticalWindow(4_500L)
        }

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
            viewLifecycleOwner.lifecycleScope.launch(AudioPlaybackDispatchers.io) {
                val savedState = AudioRepository.loadState(requireContext())
                val savedItems = savedState.items
                if (savedItems.isNotEmpty()) {
                    // Chargement rapide : MediaItem simples d'abord, metadonnees enrichies ensuite
                    val simpleItems = savedItems.map { AudioRepository.buildSimpleMediaItem(requireContext(), it.path, it.name, it.artworkPath) }
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
                        if (i != savedState.index) {
                            AudioLibraryWorkState.awaitPlaybackIdle()
                        }
                        val item = savedItems[i]
                        try {
                            val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), item.path, item.name, item.artworkPath)
                            launch(Dispatchers.Main) {
                                val c = controller ?: return@launch
                                if (i < c.mediaItemCount) {
                                    if (replaceMediaItemRateLimited(c, i, enriched)) {
                                        playlistAdapter.notifyItemChanged(i)
                                    }
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
                viewLifecycleOwner.lifecycleScope.launch(AudioPlaybackDispatchers.io) {
                    newTracks.forEach { track ->
                        val currentPath = controller?.currentMediaItem?.let(::originalPathOf).orEmpty()
                        if (track.path != currentPath) {
                            AudioLibraryWorkState.awaitPlaybackIdle()
                        }
                        try {
                            val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), track.path, track.name)
                            launch(Dispatchers.Main) {
                                val c = controller ?: return@launch
                                val idx = (0 until c.mediaItemCount).firstOrNull { originalPathOf(c.getMediaItemAt(it)) == track.path }
                                if (idx != null) {
                                    if (replaceMediaItemRateLimited(c, idx, enriched)) {
                                        playlistAdapter.notifyItemChanged(idx)
                                    }
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
                if (isPlaying) {
                    AudioLibraryWorkState.beginPlaybackCriticalWindow(1_500L)
                }
                syncButtons()
                _binding?.audioEqualizerView?.setPlaybackActive(isPlaying && audioSpectrumEnabled)
                _binding?.karaokeEqualizerView?.setPlaybackActive(isPlaying && audioSpectrumEnabled)
                val idx = ctrl.currentMediaItemIndex
                if (playlistAdapter.hasOverrideItems()) playlistAdapter.setPlayingIndex(-1)
                else if (isPlaying) playlistAdapter.setPlayingIndex(idx)
                else playlistAdapter.setPlayingIndex(-1)
                partyPlaylistAdapter.setCurrentPath(if (isPlaying && idx in 0 until ctrl.mediaItemCount) originalPathOf(ctrl.getMediaItemAt(idx)) else null)
                updateLyricsLine(ctrl.currentPosition)
                if (!audioSpectrumEnabled) {
                    stopVisualizerWatchdog()
                    stopAudioVisualizer()
                    setAudioSpectrumIdle()
                } else if (isPlaying) {
                    // Toute reprise doit produire une nouvelle trame FFT. Après une relance de
                    // l'application, il faut en plus attendre la nouvelle session AudioTrack au lieu
                    // de réutiliser AUDIO_SESSION_ID_UNSET ou une ancienne session libérée.
                    requestFreshVisualizerForCurrentTrack(
                        reason = "playback-start",
                        delayMs = VISUALIZER_FRESH_START_DELAY_MS
                    )
                } else {
                    stopVisualizerWatchdog()
                    stopAudioVisualizer()
                    setAudioSpectrumIdle()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // Seek utilisateur, changement de morceau ou correction interne Media3 : recaler
                // immédiatement l'horloge des paroles au lieu d'attendre le prochain polling UI.
                syncLyricsPlaybackClock(ctrl.currentPosition)

                // Un clic sur le titre déjà courant peut simplement le relancer à zéro sans
                // déclencher onMediaItemTransition ni onIsPlayingChanged. Ce redémarrage logique
                // doit lui aussi obtenir une instance Visualizer neuve.
                if (
                    audioSpectrumEnabled &&
                    ctrl.isPlaying &&
                    reason == Player.DISCONTINUITY_REASON_SEEK &&
                    oldPosition.mediaItemIndex == newPosition.mediaItemIndex &&
                    newPosition.positionMs <= 1_500L &&
                    oldPosition.positionMs - newPosition.positionMs >= 2_500L
                ) {
                    requestFreshVisualizerForCurrentTrack(
                        reason = "current-track-restarted",
                        delayMs = VISUALIZER_FRESH_TRACK_DELAY_MS
                    )
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                // Une variation de vitesse change la pente de l'horloge extrapolée par la vue.
                syncLyricsPlaybackClock(ctrl.currentPosition)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                syncMetadata()
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

                // Lance immédiatement le lookup de la piste qui vient d'être sélectionnée ;
                // syncMetadata() rejoindra le même travail sans le redémarrer.
                if (!newPartyPath.isNullOrBlank()) loadLyricsForCurrentTrack(newPartyPath)
                syncLyricsPlaybackClock(ctrl.currentPosition)

                syncSelection()
                syncMetadata()
                savePlaylistFromController()
                if (playlistAdapter.hasOverrideItems()) {
                    playlistAdapter.setPlayingIndex(-1)
                } else {
                    playlistAdapter.setCurrentIndex(idx)
                    playlistAdapter.setPlayingIndex(if (ctrl.isPlaying) idx else -1)
                }
                partyPlaylistAdapter.setCurrentPath(if (ctrl.isPlaying) newPartyPath else null)
                if (audioSpectrumEnabled && ctrl.isPlaying) {
                    requestFreshVisualizerForCurrentTrack(
                        reason = "media-item-transition",
                        delayMs = VISUALIZER_FRESH_TRACK_DELAY_MS
                    )
                } else {
                    setAudioSpectrumIdle()
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (isValidAudioSessionId(audioSessionId)) {
                    lastKnownVisualizerSessionId = audioSessionId
                }
                if (
                    audioSpectrumEnabled &&
                    ctrl.isPlaying &&
                    isValidAudioSessionId(audioSessionId)
                ) {
                    // Toute nouvelle notification de session associée à une lecture active
                    // invalide l'ancienne instance Visualizer. Même si Media3 réutilise le même
                    // audioSessionId, la capture FFT doit repartir d'une instance neuve pour le
                    // morceau/reprise en cours.
                    requestFreshVisualizerForCurrentTrack(
                        reason = "audio-session-changed",
                        preferredSessionId = audioSessionId,
                        delayMs = VISUALIZER_FRESH_SESSION_DELAY_MS
                    )
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    syncLyricsPlaybackClock(player.currentPosition)
                    if (isPlayingBlazePartyQueue && player.playbackState == Player.STATE_ENDED && !currentBlazePartyPath.isNullOrBlank()) {
                        syncBlazePartyPlaybackOrder(resetPlayedPath = currentBlazePartyPath)
                        currentBlazePartyPath = null
                    }
                    syncButtons()
                }
                if (events.contains(Player.EVENT_TRACKS_CHANGED)) {
                    // La durée et les informations techniques sont maintenant disponibles : le
                    // badge bitrate peut être recalculé depuis Room sans rouvrir le flux NAS.
                    syncMetadata()
                    if (audioSpectrumEnabled && player.isPlaying) {
                        ensureAudioVisualizer()
                        startVisualizerWatchdog()
                    }
                }
            }
        })

        _binding?.audioEqualizerView?.setPlaybackActive(ctrl.isPlaying && audioSpectrumEnabled)
        _binding?.karaokeEqualizerView?.setPlaybackActive(ctrl.isPlaying && audioSpectrumEnabled)
        if (audioSpectrumEnabled && ctrl.isPlaying) {
            requestFreshVisualizerForCurrentTrack(
                reason = "controller-ready-resume",
                delayMs = VISUALIZER_FRESH_RESUME_DELAY_MS
            )
        } else {
            setAudioSpectrumIdle()
        }
    }

    private fun isValidAudioSessionId(sessionId: Int): Boolean =
        sessionId > 0 && sessionId != C.AUDIO_SESSION_ID_UNSET

    /**
     * Au retour visible, recrée réellement l'instance Visualizer sur la session audio courante.
     * C'est le même cycle propre que lors d'un nouveau morceau, y compris si Media3 conserve le
     * même audioSessionId pendant la lecture en arrière-plan.
     */
    private fun recalibrateVisualizerForVisibleResume() {
        requestFreshVisualizerForCurrentTrack(
            reason = "visible-resume",
            delayMs = VISUALIZER_FRESH_RESUME_DELAY_MS
        )
    }

    /**
     * Chaque démarrage logique de morceau possède sa propre instance Visualizer. Media3 peut garder
     * le même audioSessionId entre deux titres ou après recréation de l'activité ; cela ne doit plus
     * entraîner la réutilisation de l'ancienne capture ni de son ancienne normalisation.
     *
     * Les événements très proches (transition, isPlaying, audioSessionId) sont coalescés afin de ne
     * créer qu'une seule instance neuve pour le morceau réellement courant.
     */
    private fun requestFreshVisualizerForCurrentTrack(
        reason: String,
        preferredSessionId: Int = 0,
        delayMs: Long = VISUALIZER_FRESH_START_DELAY_MS
    ) {
        if (!audioSpectrumEnabled || _binding == null || !isAdded || controller?.isPlaying != true) {
            return
        }

        visualizerFreshStartReason = reason
        val epoch = ++visualizerFreshStartEpoch
        visualizerFreshStartRunnable?.let { handler.removeCallbacks(it) }
        visualizerFreshStartRunnable = null

        // Invalide les réponses de session demandées pour le titre précédent.
        visualizerRequestGeneration++
        visualizerRequestInFlight = false
        visualizerPlaybackStartAtMs = SystemClock.elapsedRealtime()
        visualizerStartupVerificationAttempts = 0
        visualizerFailureCount = 0
        visualizerColdResumePending = true
        visualizerColdResumeRecoveryStarted = true
        visualizerColdResumeAttempts = 0

        // La vue repart sur la géométrie et l'enveloppe définies avant d'accepter les nouvelles FFT.
        _binding?.audioEqualizerView?.prepareForFreshVisualizerSession()
        _binding?.karaokeEqualizerView?.prepareForFreshVisualizerSession()
        lastPortraitEqualizerArtworkSize = 0
        _binding?.artworkFrame?.post { syncPortraitEqualizerGeometry() }

        val runnable = Runnable {
            if (
                epoch != visualizerFreshStartEpoch ||
                !audioSpectrumEnabled ||
                _binding == null ||
                !isAdded ||
                controller?.isPlaying != true
            ) {
                return@Runnable
            }
            visualizerFreshStartRunnable = null

            if (isValidAudioSessionId(preferredSessionId)) {
                startAudioVisualizer(preferredSessionId, forceRestart = true)
            } else {
                ensureAudioVisualizer(
                    forceRestart = true,
                    bypassRestartThrottle = true
                )
            }
            scheduleVisualizerStartupVerification()
            startVisualizerWatchdog()
        }
        visualizerFreshStartRunnable = runnable
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
    }

    private fun ensureAudioVisualizer(
        forceRestart: Boolean = false,
        bypassRestartThrottle: Boolean = false
    ) {
        if (!audioSpectrumEnabled || _binding == null || !isAdded) {
            stopVisualizerWatchdog()
            stopAudioVisualizer()
            setAudioSpectrumIdle()
            return
        }
        val ctrl = controller ?: return
        if (!ctrl.isPlaying) {
            stopVisualizerWatchdog()
            stopAudioVisualizer()
            setAudioSpectrumIdle()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val existingSessionId = currentVisualizerSessionId
        val streamRunning = isValidAudioSessionId(existingSessionId) &&
            AudioFftStream.isRunning(existingSessionId)
        val streamHealthy = streamRunning && AudioFftStream.isHealthy(
            existingSessionId,
            VISUALIZER_HEALTHY_CAPTURE_WINDOW_MS,
            now
        )
        val inStartupGrace = streamRunning && visualizerAttachedAtMs > 0L &&
            now - visualizerAttachedAtMs < VISUALIZER_ATTACH_GRACE_MS

        if (!forceRestart && streamHealthy) {
            visualizerFailureCount = 0
            handler.removeCallbacks(visualizerRecoveryRunnable)
            startVisualizerWatchdog()
            return
        }
        if (!forceRestart && inStartupGrace) {
            scheduleVisualizerStartupVerification()
            startVisualizerWatchdog()
            return
        }

        val effectiveForceRestart = forceRestart || (streamRunning && !streamHealthy)
        if (effectiveForceRestart &&
            !bypassRestartThrottle &&
            now - lastVisualizerRestartAtMs < VISUALIZER_MIN_RESTART_GAP_MS
        ) {
            scheduleVisualizerRecovery(incrementFailure = false)
            return
        }
        if (visualizerRequestInFlight) {
            scheduleVisualizerRecovery(incrementFailure = false)
            return
        }

        val requestGeneration = ++visualizerRequestGeneration
        visualizerRequestInFlight = true
        val future = ctrl.sendCustomCommand(
            androidx.media3.session.SessionCommand(BlazePlayerService.COMMAND_GET_AUDIO_SESSION_ID, android.os.Bundle.EMPTY),
            android.os.Bundle.EMPTY
        )
        val executor = ContextCompat.getMainExecutor(requireContext())
        future.addListener({
            if (requestGeneration != visualizerRequestGeneration) return@addListener
            visualizerRequestInFlight = false
            if (_binding == null || !isAdded || controller?.isPlaying != true || !audioSpectrumEnabled) {
                return@addListener
            }

            val sessionId = try {
                future.get().extras.getInt(BlazePlayerService.EXTRA_AUDIO_SESSION_ID, 0)
            } catch (error: Exception) {
                CrashReporter.log(requireContext(), "Audio visualizer session request failed", error)
                0
            }
            if (isValidAudioSessionId(sessionId)) {
                lastKnownVisualizerSessionId = sessionId
                // AudioFftStream sait distinguer seul une nouvelle session d'une session déjà
                // active. Ne pas transformer chaque retour d'écran en redémarrage forcé.
                startAudioVisualizer(sessionId, effectiveForceRestart)
            } else {
                val fallbackSessionId = lastKnownVisualizerSessionId
                if (isValidAudioSessionId(fallbackSessionId)) {
                    // Media3 peut renvoyer brièvement 0 pendant une transition alors que l'ancienne
                    // session reste valide. La tenter évite un visualiseur vide jusqu'au watchdog.
                    startAudioVisualizer(fallbackSessionId, forceRestart = false)
                } else {
                    setAudioSpectrumIdle()
                }
                scheduleVisualizerRecovery(incrementFailure = true)
                startVisualizerWatchdog()
            }
        }, executor)
    }

    private fun startAudioVisualizer(sessionId: Int, forceRestart: Boolean = false) {
        if (!isValidAudioSessionId(sessionId) || _binding == null || !audioSpectrumEnabled) return
        pendingVisualizerSessionId = sessionId
        lastKnownVisualizerSessionId = sessionId
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = requireContext().getSharedPreferences("blaze_runtime_permissions", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("audio_permissions_prompted", false)) {
                setAudioSpectrumIdle()
                return
            }
            prefs.edit().putBoolean("audio_permissions_prompted", true).apply()
            requestAudioVisualizerPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!forceRestart && currentVisualizerSessionId == sessionId && AudioFftStream.isRunning(sessionId)) {
            startVisualizerWatchdog()
            return
        }

        try {
            val attachedAt = SystemClock.elapsedRealtime()
            if (forceRestart) lastVisualizerRestartAtMs = attachedAt
            AudioFftStream.attach(
                tag = audioVisualizerListenerTag,
                sessionId = sessionId,
                onSpectrum = { spectrum ->
                    if (spectrum != null && spectrum.isNotEmpty()) {
                        val callbackAt = SystemClock.elapsedRealtime()
                        val firstFrameForAttach = lastVisualizerFrameAtMs <= 0L
                        lastVisualizerFrameAtMs = callbackAt
                        if (firstFrameForAttach) {
                        }
                        visualizerFailureCount = 0
                        handler.removeCallbacks(visualizerRecoveryRunnable)

                        if (
                            visualizerColdResumePending &&
                            visualizerPlaybackStartAtMs > 0L &&
                            lastVisualizerFrameAtMs >= visualizerPlaybackStartAtMs
                        ) {
                            visualizerColdResumePending = false
                            visualizerColdResumeRecoveryStarted = false
                            visualizerColdResumeAttempts = 0
                            handler.removeCallbacks(visualizerColdResumeRunnable)
                        }
                    }
                    _binding?.let { b ->
                        // Un seul visualiseur reçoit la FFT. Envoyer chaque trame aux deux vues
                        // doublerait les calculs et les invalidations pendant le screen mirroring.
                        if (karaokeLandscapeActive) {
                            b.karaokeEqualizerView
                                .takeIf { it.isShown }
                                ?.updateSpectrum(spectrum)
                        } else {
                            b.audioEqualizerView
                                .takeIf { it.isShown }
                                ?.updateSpectrum(spectrum)
                        }
                    }
                },
                forceRestart = forceRestart
            )
            currentVisualizerSessionId = sessionId
            visualizerAttachedAtMs = attachedAt
            lastVisualizerFrameAtMs = 0L
            scheduleVisualizerStartupVerification()
            startVisualizerWatchdog()
        } catch (e: Exception) {
            currentVisualizerSessionId = 0
            visualizerAttachedAtMs = 0L
            lastVisualizerFrameAtMs = 0L
            CrashReporter.log(
                requireContext(),
                "Audio visualizer failed during $visualizerFreshStartReason",
                e
            )
            AudioFftStream.detach(audioVisualizerListenerTag)
            setAudioSpectrumIdle()
            scheduleVisualizerRecovery(incrementFailure = true)
            startVisualizerWatchdog()
        }
    }

    private fun scheduleVisualizerStartupVerification() {
        handler.removeCallbacks(visualizerStartupVerifyRunnable)
        if (audioSpectrumEnabled &&
            _binding != null &&
            !isHidden &&
            isResumed &&
            controller?.isPlaying == true &&
            !karaokeMirroringActive
        ) {
            handler.postDelayed(visualizerStartupVerifyRunnable, VISUALIZER_STARTUP_VERIFY_DELAY_MS)
        }
    }

    private fun startVisualizerWatchdog() {
        handler.removeCallbacks(visualizerWatchdogRunnable)
        if (audioSpectrumEnabled &&
            _binding != null &&
            !isHidden &&
            isResumed &&
            controller?.isPlaying == true &&
            !karaokeMirroringActive
        ) {
            handler.postDelayed(visualizerWatchdogRunnable, VISUALIZER_WATCHDOG_INTERVAL_MS)
        }
    }

    private fun canRunAudioVisualizerRecovery(): Boolean =
        audioSpectrumEnabled &&
            _binding != null &&
            isAdded &&
            !isHidden &&
            isResumed &&
            controller?.isPlaying == true &&
            !karaokeMirroringActive

    private fun shouldForceVisualizerRestart(): Boolean {
        val sessionId = currentVisualizerSessionId
        if (sessionId <= 0) return false
        val now = SystemClock.elapsedRealtime()
        val attachedLongEnough = visualizerAttachedAtMs <= 0L ||
            now - visualizerAttachedAtMs >= VISUALIZER_ATTACH_GRACE_MS
        return attachedLongEnough && !AudioFftStream.isHealthy(
            sessionId,
            VISUALIZER_HEALTHY_CAPTURE_WINDOW_MS,
            now
        )
    }

    private fun scheduleVisualizerUiRecovery(forceRestart: Boolean = false) {
        handler.removeCallbacks(visualizerUiRecoveryRunnable)
        if (!canRunAudioVisualizerRecovery()) return

        if (forceRestart) {
            handler.postDelayed({
                if (canRunAudioVisualizerRecovery()) {
                    ensureAudioVisualizer(forceRestart = true)
                    scheduleVisualizerStartupVerification()
                }
            }, VISUALIZER_UI_RECOVERY_DELAY_MS)
        } else {
            handler.postDelayed(
                visualizerUiRecoveryRunnable,
                VISUALIZER_UI_RECOVERY_DELAY_MS
            )
        }
    }

    private fun scheduleVisualizerRecovery(incrementFailure: Boolean) {
        if (incrementFailure) {
            visualizerFailureCount = (visualizerFailureCount + 1).coerceAtMost(VISUALIZER_RECOVERY_DELAYS_MS.size)
        }
        handler.removeCallbacks(visualizerRecoveryRunnable)
        if (!canRunAudioVisualizerRecovery()) return
        val delayIndex = (visualizerFailureCount - 1)
            .coerceIn(0, VISUALIZER_RECOVERY_DELAYS_MS.lastIndex)
        handler.postDelayed(visualizerRecoveryRunnable, VISUALIZER_RECOVERY_DELAYS_MS[delayIndex])
    }

    private fun stopVisualizerWatchdog() {
        handler.removeCallbacks(visualizerWatchdogRunnable)
        handler.removeCallbacks(visualizerTrackRestartRunnable)
        handler.removeCallbacks(visualizerStartupVerifyRunnable)
        handler.removeCallbacks(visualizerRecoveryRunnable)
        handler.removeCallbacks(visualizerUiRecoveryRunnable)
        handler.removeCallbacks(visualizerColdResumeRunnable)
        visualizerStartupVerificationAttempts = 0
    }

    private fun stopAudioVisualizer() {
        visualizerFreshStartEpoch++
        visualizerFreshStartRunnable?.let { handler.removeCallbacks(it) }
        visualizerFreshStartRunnable = null
        visualizerRequestGeneration++
        visualizerRequestInFlight = false
        AudioFftStream.detach(audioVisualizerListenerTag)
        currentVisualizerSessionId = 0
        pendingVisualizerSessionId = 0
        visualizerAttachedAtMs = 0L
        lastVisualizerFrameAtMs = 0L
        visualizerPlaybackStartAtMs = 0L
        visualizerStartupVerificationAttempts = 0
        visualizerFailureCount = 0
        visualizerColdResumeRecoveryStarted = false
        handler.removeCallbacks(visualizerRecoveryRunnable)
        handler.removeCallbacks(visualizerUiRecoveryRunnable)
        handler.removeCallbacks(visualizerColdResumeRunnable)
    }

    // ── Sync UI depuis MediaController (source unique) ─────────────────────────

    /**
     * Ne modifie jamais les éléments non courants de la timeline uniquement pour y ajouter une
     * pochette. Android Auto expose directement cette timeline comme file d'attente : chaque
     * replaceMediaItem() oblige le DHU à reconstruire la liste et lui fait perdre sa position de
     * défilement. Les titres non courants conservent donc leur MediaItem stable ; leurs pochettes
     * restent chargées directement par les adapters/caches de l'application.
     *
     * Le morceau courant peut encore recevoir une mise à jour visuelle, mais uniquement si les
     * métadonnées ont réellement changé. Le service audio applique déjà la même pochette à la
     * notification et à Android Auto lors de la transition du morceau.
     */
    private suspend fun replaceMediaItemRateLimited(
        ctrl: MediaController,
        index: Int,
        enriched: MediaItem
    ): Boolean = mediaItemReplacementMutex.withLock {
        // Toute mutation de MediaItem republie la timeline Media3. Tant que la file Android Auto
        // est réellement affichée, les pochettes du player téléphone restent chargées directement
        // dans les vues et ne doivent pas reconstruire la liste automobile.
        if (AndroidAutoConnectionState.isQueueVisible) return@withLock false
        if (index !in 0 until ctrl.mediaItemCount) return@withLock false
        if (index != ctrl.currentMediaItemIndex) return@withLock false

        val current = ctrl.getMediaItemAt(index)
        val expectedPath = originalPathOf(enriched)
        if (expectedPath.isNotBlank() && originalPathOf(current) != expectedPath) {
            return@withLock false
        }
        if (!hasMeaningfulMediaMetadataChange(current, enriched)) return@withLock false

        val elapsed = SystemClock.elapsedRealtime() - lastMediaItemReplacementAtMs
        val waitMs = (220L - elapsed).coerceAtLeast(0L)
        if (waitMs > 0L) delay(waitMs)
        if (AndroidAutoConnectionState.isQueueVisible) return@withLock false
        if (index !in 0 until ctrl.mediaItemCount || index != ctrl.currentMediaItemIndex) {
            return@withLock false
        }
        if (expectedPath.isNotBlank() && originalPathOf(ctrl.getMediaItemAt(index)) != expectedPath) {
            return@withLock false
        }
        if (!hasMeaningfulMediaMetadataChange(ctrl.getMediaItemAt(index), enriched)) {
            return@withLock false
        }

        ctrl.replaceMediaItem(index, enriched)
        lastMediaItemReplacementAtMs = SystemClock.elapsedRealtime()
        true
    }

    private fun hasMeaningfulMediaMetadataChange(current: MediaItem, enriched: MediaItem): Boolean {
        val currentMeta = current.mediaMetadata
        val enrichedMeta = enriched.mediaMetadata
        if (currentMeta.title?.toString() != enrichedMeta.title?.toString()) return true
        if (currentMeta.artist?.toString() != enrichedMeta.artist?.toString()) return true
        if (currentMeta.albumTitle?.toString() != enrichedMeta.albumTitle?.toString()) return true
        if (currentMeta.artworkUri != enrichedMeta.artworkUri) return true
        if (
            currentMeta.extras?.getString(AudioRepository.EXTRA_ARTWORK_PATH).orEmpty() !=
            enrichedMeta.extras?.getString(AudioRepository.EXTRA_ARTWORK_PATH).orEmpty()
        ) return true

        val currentArtwork = currentMeta.artworkData
        val enrichedArtwork = enrichedMeta.artworkData
        return when {
            currentArtwork == null && enrichedArtwork == null -> false
            currentArtwork == null || enrichedArtwork == null -> true
            else -> !currentArtwork.contentEquals(enrichedArtwork)
        }
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

        val pathForMeta = originalPathOf(mediaItem)

        // Démarrer la recherche du .LRC avant les badges, l'historique et la cover : la lecture
        // du fichier paroles se fait sur IO et progresse donc réellement en parallèle du média.
        loadLyricsForCurrentTrack(pathForMeta)

        val cachedMeta = fr.retrospare.blazeplayer.player.AudioMediaCache.getCachedMetadata(requireContext(), pathForMeta)
        val originalName = mediaItem.mediaMetadata.extras?.getString("blaze_original_name")
            .orEmpty().ifBlank { AudioLibraryHeuristics.fileNameFromPath(pathForMeta) }
        val folderMeta = AudioLibraryHeuristics.folderMetadata(pathForMeta, originalName)

        _binding?.tvTitle?.text = folderMeta.title
        _binding?.tvArtist?.text = folderMeta.artist
        updateCombinedTitleArtist()
        val safeAlbum = sanitizeAudioSecondaryText(folderMeta.album)
        _binding?.tvAlbum?.text = safeAlbum
        _binding?.tvAlbum?.visibility = if (safeAlbum.isBlank()) View.GONE else View.VISIBLE

        val ext = mediaItem.mediaMetadata.extras
            ?.getString(AudioRepository.EXTRA_CONTAINER_EXTENSION)
            ?.takeIf { it.isNotBlank() }
            ?: cachedMeta?.extension?.takeIf { it.isNotBlank() }
            ?: pathForMeta.substringBefore('?').substringAfterLast('.', "").uppercase()
        val safeExt = sanitizeAudioExtension(ext)
        fr.retrospare.blazeplayer.player.AudioPlaybackHistory.markPlayed(
            requireContext().applicationContext,
            pathForMeta,
            folderMeta.title,
            folderMeta.artist,
            folderMeta.album,
            cachedMeta?.duration?.takeIf { it > 0L }?.times(1000L) ?: ctrl.duration.takeIf { it > 0L } ?: 0L,
            AudioLibraryHeuristics.inferTrackNo(originalName),
            safeExt
        )
        AudioQualityBadgeBinder.bind(
            codecView = _binding?.tvCodec,
            qualityView = _binding?.tvBitrate,
            path = pathForMeta,
            originalName = originalName,
            fallbackExtension = safeExt,
            codecTextColor = currentAccentColor,
            knownDurationMs = ctrl.duration.takeIf { it > 0L } ?: 0L,
            textOnly = true
        )
        scheduleCompactMetadataFit()
        syncPortraitEqualizerTechnicalSpacing()

        applyAudioProInterfaceSettings()

        // Point d'entrée unique partagé avec Mes albums, le détail album et les mini-players :
        // cover.jpg, cover.png, puis embedded. Le chemin Room de la cover est consulté avant tout
        // ancien artworkData Media3, afin qu'une pochette validée dans la bibliothèque gagne partout.
        val path = originalPathOf(mediaItem)
        val preferredArtworkPath = mediaItem.mediaMetadata.extras
            ?.getString(AudioRepository.EXTRA_ARTWORK_PATH)
            .orEmpty()
        val artworkKey = "$path\u0000$preferredArtworkPath"
        val pathChanged = currentArtworkPath != artworkKey
        if (pathChanged) {
            currentArtworkPath = artworkKey
            artworkLoadJob?.cancel()
        }
        val mediaArtworkData = meta.artworkData
        // Le thread UI ne lit jamais le disque, Room, le NAS ou le fichier audio. La RAM puis les
        // octets déjà présents dans Media3 suffisent pour la première frame ; le cache disque local
        // est consulté sur BlazePlaybackIo juste après.
        val immediateBitmap = AudioArtworkResolver.memoryCachedBitmap(path, preferredArtworkPath)
            ?: mediaArtworkData?.let(::decodePlayerArtworkBytes)
        if (immediateBitmap != null) {
            _binding?.ivArtwork?.setImageBitmap(immediateBitmap)
            _binding?.ivKaraokeArtwork?.setImageBitmap(immediateBitmap)
            applyDynamicBackgroundFromBitmap(immediateBitmap, artworkKey)
        } else if (pathChanged) {
            dynamicColorJob?.cancel()
            currentDynamicArtworkKey = ""
            lastAppliedDynamicArtworkKey = ""
            _binding?.ivArtwork?.setImageResource(fr.retrospare.blazeplayer.R.drawable.bg_audio_artwork_placeholder_square)
            _binding?.ivKaraokeArtwork?.setImageResource(fr.retrospare.blazeplayer.R.drawable.bg_thumbnail)
            resetDynamicBackground()
        }

        if (path.isNotEmpty() && (pathChanged || (immediateBitmap == null && artworkLoadJob?.isActive != true))) {
            artworkLoadJob = viewLifecycleOwner.lifecycleScope.launch(AudioPlaybackDispatchers.io) {
                // Étape 1 : cache local uniquement. Elle ne liste aucun dossier et n'ouvre jamais le
                // morceau SMB/UPnP en cours de lecture.
                val cachedBytes = AudioArtworkResolver.cachedJpegBytes(
                    requireContext(),
                    path,
                    preferredArtworkPath
                )
                val firstBytes = cachedBytes ?: mediaArtworkData
                if (firstBytes != null) {
                    val firstBitmap = decodePlayerArtworkBytes(firstBytes)
                    if (firstBitmap != null) {
                        launch(Dispatchers.Main) {
                            val current = controller?.currentMediaItem ?: return@launch
                            if (originalPathOf(current) != path || currentArtworkPath != artworkKey) return@launch
                            _binding?.ivArtwork?.setImageBitmap(firstBitmap)
                            _binding?.ivKaraokeArtwork?.setImageBitmap(firstBitmap)
                            applyDynamicBackgroundFromBitmap(firstBitmap, artworkKey)
                        }
                    }
                }

                // Étape 2 : une vraie recherche cover.jpg -> embedded n'est autorisée que hors
                // lecture. Si Media3 possède déjà l'embedded, elle reste affichée sans concurrencer
                // le flux audio ; la cover externe prioritaire sera adoptée à la prochaine pause.
                if (cachedBytes != null) return@launch
                if (AudioLibraryWorkState.isPlaybackProtected()) {
                    AudioLibraryWorkState.awaitPlaybackIdle()
                } else {
                    AudioLibraryWorkState.awaitPlaybackCriticalWindowEnd()
                }
                val resolvedBytes = AudioArtworkResolver.resolveJpegBytes(
                    requireContext(),
                    path,
                    preferredArtworkPath
                ) ?: return@launch
                val bitmap = decodePlayerArtworkBytes(resolvedBytes) ?: return@launch
                launch(Dispatchers.Main) {
                    val c = controller ?: return@launch
                    val current = c.currentMediaItem ?: return@launch
                    if (originalPathOf(current) != path || currentArtworkPath != artworkKey) return@launch
                    _binding?.ivArtwork?.setImageBitmap(bitmap)
                    _binding?.ivKaraokeArtwork?.setImageBitmap(bitmap)
                    applyDynamicBackgroundFromBitmap(bitmap, artworkKey)
                    val stableArtworkPath = AudioArtworkPersistence.existingPath(
                        requireContext(),
                        path
                    ).orEmpty().ifBlank { preferredArtworkPath }
                    val enrichedExtras = android.os.Bundle(
                        current.mediaMetadata.extras ?: android.os.Bundle()
                    ).apply {
                        if (stableArtworkPath.isNotBlank()) {
                            putString(AudioRepository.EXTRA_ARTWORK_PATH, stableArtworkPath)
                        }
                    }
                    val enrichedMeta = current.mediaMetadata.buildUpon()
                        .setArtworkData(resolvedBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .setExtras(enrichedExtras)
                        .build()
                    val enriched = current.buildUpon().setMediaMetadata(enrichedMeta).build()
                    replaceMediaItemRateLimited(c, c.currentMediaItemIndex, enriched)
                }
            }
        }
    }

    /** Affiche "Titre - Artiste" sur une seule ligne auto-ajustée. Toute la ligne reste en gras,
     *  tandis que seul le nom de l'artiste reçoit la couleur dynamique de la pochette. */
    private fun updateCombinedTitleArtist() {
        val b = _binding ?: return
        val primary = runCatching {
            ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.on_surface)
        }.getOrDefault(Color.WHITE)
        val secondary = runCatching {
            ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.on_surface_variant)
        }.getOrDefault(Color.rgb(175, 178, 198))
        val title = b.tvTitle.text?.toString().orEmpty().trim()
        val artist = b.tvArtist.text?.toString().orEmpty().trim()
        val line = android.text.SpannableStringBuilder()
        if (title.isNotEmpty()) line.append(title)
        if (artist.isNotEmpty()) {
            if (line.isNotEmpty()) line.append(" - ")
            val artistStart = line.length
            line.append(artist)
            line.setSpan(
                android.text.style.ForegroundColorSpan(currentAccentColor),
                artistStart,
                line.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (line.isNotEmpty()) {
            line.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0,
                line.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        b.tvTitleArtist.setTextColor(primary)
        b.tvTitleArtist.text = line
        scheduleCompactMetadataFit()

        // Le mode karaoké reprend exactement les métadonnées déjà normalisées par
        // AudioLibraryHeuristics pour le player portrait : morceau d'abord, artiste ensuite.
        // Elles sont affichées séparément sur la cover afin de ne plus occuper la colonne paroles.
        b.tvKaraokeTitle.text = title
        b.tvKaraokeTitle.setTextColor(Color.WHITE)
        b.tvKaraokeArtist.text = artist
        b.tvKaraokeArtist.setTextColor(currentAccentColor)
        b.tvKaraokeArtist.visibility = if (artist.isBlank()) View.GONE else View.VISIBLE

        b.tvAlbum.setTextColor(secondary)
        b.tvAlbum.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
    }


    private var compactMetadataFitPosted = false
    private var lastCompactMetadataFitKey = ""

    /** Centre le titre et l'artiste et réduit leur taille jusqu'à affichage complet. */
    private fun setupCompactMetadataAutoFit() {
        val b = _binding ?: return
        val requestFit = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleCompactMetadataFit()
        }
        b.audioTrackPrimaryRow.gravity = android.view.Gravity.CENTER
        b.audioTrackPrimaryRow.addOnLayoutChangeListener(requestFit)
        b.tvTitleArtist.ellipsize = null
        b.tvTitleArtist.maxLines = 1
        b.tvTitleArtist.gravity = android.view.Gravity.CENTER
        scheduleCompactMetadataFit()
    }

    private fun scheduleCompactMetadataFit() {
        val b = _binding ?: return
        if (compactMetadataFitPosted) return
        compactMetadataFitPosted = true
        b.audioTrackPrimaryRow.post {
            compactMetadataFitPosted = false
            fitCompactMetadataLine()
        }
    }

    private fun fitCompactMetadataLine() {
        val b = _binding ?: return
        val row = b.audioTrackPrimaryRow
        if (row.width <= 0) return

        val availableWidth = (row.width - row.paddingLeft - row.paddingRight)
            .coerceAtLeast(dp(44f).toInt())
        val textView = b.tvTitleArtist
        val text = textView.text?.toString().orEmpty()
        val fitKey = "${row.width}|$availableWidth|$text"
        if (fitKey == lastCompactMetadataFitKey) return
        lastCompactMetadataFitKey = fitKey

        textView.ellipsize = null
        textView.maxLines = 1
        textView.maxWidth = availableWidth
        textView.textScaleX = 1f
        textView.gravity = android.view.Gravity.CENTER

        if (text.isBlank()) {
            textView.layoutParams = textView.layoutParams.apply { width = 0 }
            return
        }

        val scaledDensity = resources.displayMetrics.scaledDensity
        val maxPx = 18f * scaledDensity
        val minPx = 7f * scaledDensity
        val probe = android.text.TextPaint(textView.paint)

        var low = minPx
        var high = maxPx
        repeat(14) {
            val mid = (low + high) / 2f
            probe.textSize = mid
            if (probe.measureText(text) <= availableWidth) low = mid else high = mid
        }
        val chosenPx = low.coerceIn(minPx, maxPx)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, chosenPx)

        val naturalWidth = textView.paint.measureText(text).coerceAtLeast(1f)
        val horizontalScale = if (naturalWidth > availableWidth) {
            (availableWidth / naturalWidth).coerceIn(0.30f, 1f)
        } else 1f
        textView.textScaleX = horizontalScale

        val exactWidth = kotlin.math.ceil(naturalWidth * horizontalScale + dp(2f)).toInt()
            .coerceIn(1, availableWidth)
        val params = textView.layoutParams
        if (params.width != exactWidth) {
            params.width = exactWidth
            textView.layoutParams = params
        }
    }


    /**
     * Fond dynamique plus visible, inspiré des lecteurs audio modernes : on extrait une couleur
     * dominante robuste depuis la pochette, puis on renforce légèrement saturation/luminosité.
     * Le fond reste sombre via un dégradé noir -> accent afin de garder les contrôles lisibles.
     */
    private fun applyDynamicBackgroundFromBitmap(bitmap: Bitmap?, artworkKey: String = currentArtworkPath) {
        if (!AudioProSettings.read(requireContext()).dynamicTheme) {
            dynamicColorJob?.cancel()
            currentDynamicArtworkKey = ""
            lastAppliedDynamicArtworkKey = ""
            return resetDynamicBackground()
        }
        bitmap ?: run {
            dynamicColorJob?.cancel()
            currentDynamicArtworkKey = ""
            lastAppliedDynamicArtworkKey = ""
            return resetDynamicBackground()
        }

        // generationId distingue une embedded temporaire d'une cover.jpg résolue plus tard, même
        // quand elles correspondent au même morceau/chemin. Une ancienne extraction ne peut donc
        // plus appliquer sa couleur après l'arrivée de la véritable pochette affichée.
        val colorKey = "$artworkKey:${bitmap.width}x${bitmap.height}:${bitmap.generationId}"
        if (lastAppliedDynamicArtworkKey == colorKey) {
            applyAudioPlayerBackground(currentDynamicBgColor)
            return
        }
        if (currentDynamicArtworkKey == colorKey && dynamicColorJob?.isActive == true) return

        currentDynamicArtworkKey = colorKey
        dynamicColorJob?.cancel()
        dynamicColorJob = viewLifecycleOwner.lifecycleScope.launch(AudioPlaybackDispatchers.compute) {
            val accent = AudioDynamicColor.accentFromBitmap(bitmap)
            val bg = AudioDynamicColor.backgroundFromAccent(accent)
            launch(Dispatchers.Main) {
                if (_binding == null || currentDynamicArtworkKey != colorKey || currentArtworkPath != artworkKey) {
                    return@launch
                }
                lastAppliedDynamicArtworkKey = colorKey
                animateDynamicBackground(bg, accent)
            }
        }
    }

    /** Décodage borné pour les artworkData Media3/embedded afin de ne jamais allouer une cover
     *  gigantesque uniquement pour l'affichage et l'extraction de couleur. */
    private fun decodePlayerArtworkBytes(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val maxSide = 1_280
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceAtLeast(1)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun mixColors(a: Int, b: Int, amount: Float): Int = AudioDynamicColor.mix(a, b, amount)

    private fun resetDynamicBackground() {
        val accent = AudioDynamicColor.DEFAULT_ACCENT
        animateDynamicBackground(AudioDynamicColor.backgroundFromAccent(accent), accent)
    }

    private fun buildAudioPlayerBackground(color: Int): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            mixColors(Color.rgb(2, 7, 9), color, 0.56f),
            mixColors(color, Color.rgb(18, 24, 30), 0.08f),
            mixColors(Color.rgb(2, 7, 9), color, 0.34f)
        )
    )

    private fun applyAudioPlayerBackground(color: Int) {
        val b = _binding ?: return
        // Aucun outline arrondi ne doit être réintroduit par le fond dynamique.
        b.root.clipToOutline = false
        b.root.outlineProvider = null
        b.playerPanel.clipToOutline = false
        b.playerPanel.outlineProvider = null
        // Sur tablette, playerPanel est volontairement centré dans une colonne via une marge
        // horizontale. Le dégradé doit donc être posé aussi sur le root plein écran, sinon les
        // marges restent noires et donnent deux bandes de chaque côté du lecteur.
        b.root.background = buildAudioPlayerBackground(color)
        b.playerPanel.background = buildAudioPlayerBackground(color)
        b.karaokeLandscapeRoot.background = buildAudioPlayerBackground(color)
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
        updateKaraokeCastButtonState()
        val tint = ColorStateList.valueOf(accentColor)
        _binding?.seekBar?.progressTintList = tint
        _binding?.seekBar?.thumbTintList = tint
        _binding?.btnPlayPause?.backgroundTintList = null
        _binding?.btnPlayPause?.background = buildPlayButtonBackground(accentColor)
        _binding?.btnPlayPause?.elevation = dp(10f)
        _binding?.btnPlayPause?.translationZ = dp(6f)
        _binding?.audioEqualizerView?.setAccentColor(accentColor)
        _binding?.karaokeEqualizerView?.setAccentColor(accentColor)
        _binding?.karaokeLyricsView?.setAccentColor(accentColor)
        _binding?.standardLyricsView?.setAccentColor(accentColor)
        clearArtworkAccentBorders()
        restoreStaticAudioControlColors()
        persistDynamicAudioColors(targetColor, accentColor)
        // Le titre/artiste du lecteur suit immédiatement la nouvelle couleur de pochette.
        updateCombinedTitleArtist()
    }

    private fun restorePersistedDynamicAudioColors() {
        val prefs = requireContext().getSharedPreferences(DYNAMIC_AUDIO_PREFS, android.content.Context.MODE_PRIVATE)
        if (!AudioProSettings.read(requireContext()).dynamicTheme) {
            resetDynamicBackground()
            return
        }
        val accent = AudioDynamicColor.ensureReadableAccent(
            prefs.getInt(KEY_DYNAMIC_ACCENT, AudioDynamicColor.DEFAULT_ACCENT)
        )
        // Recalcule le fond depuis l'accent au lieu de reprendre un ancien cache : cela applique
        // immédiatement le nouveau dosage plus doux, même si une couleur avait été persistée par
        // une version précédente plus saturée.
        val bg = AudioDynamicColor.backgroundFromAccent(accent)
        currentDynamicBgColor = bg
        currentAccentColor = accent
        updateKaraokeCastButtonState()
        applyAudioPlayerBackground(bg)
        val tint = ColorStateList.valueOf(accent)
        _binding?.seekBar?.progressTintList = tint
        _binding?.seekBar?.thumbTintList = tint
        _binding?.btnPlayPause?.backgroundTintList = null
        _binding?.btnPlayPause?.background = buildPlayButtonBackground(accent)
        _binding?.btnPlayPause?.elevation = dp(10f)
        _binding?.btnPlayPause?.translationZ = dp(6f)
        _binding?.audioEqualizerView?.setAccentColor(accent)
        _binding?.karaokeEqualizerView?.setAccentColor(accent)
        _binding?.karaokeLyricsView?.setAccentColor(accent)
        _binding?.standardLyricsView?.setAccentColor(accent)
        clearArtworkAccentBorders()
        restoreStaticAudioControlColors()
        updateCombinedTitleArtist()
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

    private fun clearArtworkAccentBorders() {
        val b = _binding ?: return
        // La cover reste propre et sans liseré, dans le lecteur portrait comme dans KaraoKast.
        // La couleur dynamique est désormais portée par le fond, les contrôles et le VU-mètre.
        b.artworkFrame.foreground = null
        b.karaokeArtworkFrame.foreground = null
    }


    /**
     * Les quatre actions du haut doivent avoir exactement la même taille que les onglets du
     * menu général. Elles sont volontairement exclues de ButtonTextFitter : le style Material
     * leur attribue d'abord une taille plus grande et l'ancien fitter la réutilisait ensuite
     * comme plafond, annulant visuellement la réduction déclarée dans le XML.
     */
    private fun applyCompactTopMenuTypography() {
        if (resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT) return

        listOf(
            binding.btnBlazeParty,
            binding.btnAudioPlaylistParty,
            binding.btnAudioLibrary,
            binding.btnPlaylistSheet
        ).forEach { button ->
            // Même métrique réelle que les TextView du menu général inférieur.
            button.tag = "skip_button_text_fitter"
            TextViewCompat.setAutoSizeTextTypeWithDefaults(
                button,
                TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE
            )
            TextViewCompat.setTextAppearance(
                button,
                fr.retrospare.blazeplayer.R.style.TextAppearance_BlazePlayer_NavigationLabel
            )
            button.typeface = android.graphics.Typeface.create(
                "sans-serif-condensed",
                android.graphics.Typeface.BOLD
            )
            button.includeFontPadding = false
            button.letterSpacing = 0f
            button.textScaleX = 1f
            button.setLineSpacing(0f, 1f)
            button.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(fr.retrospare.blazeplayer.R.dimen.bottom_navigation_label_text_size)
            )
            button.requestLayout()
        }
    }

    private fun restoreStaticAudioControlColors(accentColor: Int = currentAccentColor) {
        val b = _binding ?: return
        val yellow = try { ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.yellow_accent) } catch (_: Exception) { Color.rgb(255, 193, 7) }
        val muted = try { ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.on_surface_variant) } catch (_: Exception) { Color.rgb(175, 178, 198) }
        b.tvCodec.setTextColor(accentColor)
        b.tvBitrate.setTextColor(Color.WHITE)
        updateCombinedTitleArtist()
        // La rangée supérieure reprend exactement le langage visuel de la rangée inférieure :
        // icône au-dessus, libellé centré et cinq zones de largeur identique.
        b.btnBlazeParty.setIconResource(fr.retrospare.blazeplayer.R.drawable.ic_group_people)
        b.btnBlazeParty.iconTint = ColorStateList.valueOf(yellow)
        b.btnBlazeParty.setTextColor(yellow)
        b.btnBlazeParty.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        b.btnBlazeParty.strokeWidth = 0

        b.btnAudioPlaylistParty.setIconResource(fr.retrospare.blazeplayer.R.drawable.ic_layout_list)
        b.btnAudioPlaylistParty.iconTint = ColorStateList.valueOf(yellow)
        b.btnAudioPlaylistParty.setTextColor(yellow)
        b.btnAudioPlaylistParty.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        b.btnAudioPlaylistParty.strokeWidth = 0

        listOf(b.btnAudioLibrary, b.btnPlaylistSheet).forEach { button ->
            button.iconTint = ColorStateList.valueOf(muted)
            button.setTextColor(muted)
            button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            button.strokeWidth = 0
        }
        b.btnBottomAudioSettings.alpha = 1f
        b.btnBottomAudioSettings.isSelected = false
        b.btnBottomAudioSettings.isActivated = false
        (b.btnBottomAudioSettings.getChildAt(0) as? ImageView)?.setColorFilter(muted)
        (b.btnBottomAudioSettings.getChildAt(1) as? TextView)?.setTextColor(muted)
    }

    /** Halo lumineux dynamique affiché derrière la pochette (ivArtworkGlow, sous artworkFrame).
     *  Important : la cover masque le centre du halo. On dessine donc surtout une couronne
     *  lumineuse vers les bords visibles, sinon le dégradé radial reste caché derrière la pochette. */
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

    /**
     * La pochette portrait est mesurée directement par SquareFrameLayout : sa hauteur
     * égale toujours sa largeur dès la première passe de mesure. Aucun redimensionnement différé
     * n'est nécessaire, ce qui garantit un rendu bord à bord stable et sans coins arrondis.
     */
    private var squareArtworkListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    private var squareArtworkContainer: View? = null
    private var lastPortraitEqualizerArtworkSize: Int = 0

    private fun setupSquareArtwork() {
        val artwork = binding.artworkFrame
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val b = _binding ?: return@OnGlobalLayoutListener
            val size = b.artworkFrame.width
            if (size <= 0) return@OnGlobalLayoutListener

            b.artworkFrame.clipToOutline = false
            b.artworkFrame.outlineProvider = null
            b.artworkFrame.foreground = null
            b.artworkFrame.elevation = 0f
            b.artworkFrame.translationZ = 0f
            b.ivArtwork.clipToOutline = false
            b.ivArtwork.outlineProvider = null
            b.ivArtwork.background = null
            b.ivArtwork.foreground = null
            b.ivArtwork.elevation = 0f
            b.ivArtwork.translationZ = 0f
            b.artworkMetadataOverlay.radiusDp = 0f

            val glowParams = b.ivArtworkGlow.layoutParams
            if (glowParams.width != 0 || glowParams.height != 0) {
                glowParams.width = 0
                glowParams.height = 0
                b.ivArtworkGlow.layoutParams = glowParams
            }
            b.ivArtworkGlow.visibility = View.GONE
            b.ivArtworkGlow.alpha = 0f

            syncPortraitEqualizerGeometry(size)
            syncPortraitEqualizerTechnicalSpacing()
        }
        artwork.viewTreeObserver.addOnGlobalLayoutListener(listener)
        squareArtworkListener = listener
        squareArtworkContainer = artwork
    }

    private fun syncPortraitEqualizerGeometry(artworkSizePx: Int = _binding?.artworkFrame?.height ?: 0) {
        val b = _binding ?: return
        if (artworkSizePx <= 0 || artworkSizePx == lastPortraitEqualizerArtworkSize) return
        lastPortraitEqualizerArtworkSize = artworkSizePx

        // Le visualiseur portrait reprend exactement la géométrie de KaraoKast :
        // conteneur de 112 dp et 14 dp de marge verticale, soit une zone de barres de 84 dp.
        // Les amplitudes ont ainsi strictement la même hauteur visuelle dans les deux vues.
        val desiredHeight = dp(112f).toInt()
        val params = b.audioEqualizerContainer.layoutParams
        if (params.height != desiredHeight) {
            params.height = desiredHeight
            b.audioEqualizerContainer.layoutParams = params
        }

        val horizontalPadding = dp(16f).toInt()
        val verticalPadding = dp(14f).toInt()
        b.audioEqualizerContainer.setPadding(
            horizontalPadding,
            verticalPadding,
            horizontalPadding,
            verticalPadding
        )
        b.audioEqualizerView.requestLayout()
        b.audioEqualizerView.invalidate()
        b.audioEqualizerContainer.post {
        }
    }

    /**
     * Positionne le VU-mètre et les informations codec/bitrate avec deux espaces
     * visuellement identiques : VU-mètre → badges et badges → bas de la cover.
     * Le calcul repose sur la hauteur réellement mesurée des textes, afin de rester
     * correct quelle que soit la densité ou la taille de police de l'appareil.
     */
    private fun syncPortraitEqualizerTechnicalSpacing() {
        val b = _binding ?: return
        val edgeGap = dp(10f).toInt()

        val technicalParams = b.artworkBottomTechnicalInfo.layoutParams as? android.widget.FrameLayout.LayoutParams
        if (technicalParams != null && technicalParams.bottomMargin != edgeGap) {
            technicalParams.bottomMargin = edgeGap
            b.artworkBottomTechnicalInfo.layoutParams = technicalParams
        }

        b.artworkBottomTechnicalInfo.post {
            val current = _binding ?: return@post
            val technicalHeight = current.artworkBottomTechnicalInfo.height
                .takeIf { it > 0 }
                ?: dp(14f).toInt()
            val desiredBottomMargin = (
                technicalHeight + (edgeGap * 2) - current.audioEqualizerContainer.paddingBottom
            ).coerceAtLeast(dp(8f).toInt())

            val equalizerParams = current.audioEqualizerContainer.layoutParams
                as? android.widget.FrameLayout.LayoutParams
                ?: return@post
            if (equalizerParams.bottomMargin != desiredBottomMargin) {
                equalizerParams.bottomMargin = desiredBottomMargin
                current.audioEqualizerContainer.layoutParams = equalizerParams
            }
        }
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
                    val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
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

    /**
     * Relance explicitement un élément de la file standard depuis son début.
     *
     * La commande dédiée est traitée par BlazePlayerService afin qu'il puisse interrompre un flux
     * SMB bloqué avant de reconstruire la source. Cela couvre à la fois un titre terminé et un
     * Loader réseau suspendu.
     */
    private fun playStandardQueueItemFromStart(index: Int) {
        val ctrl = controller ?: return
        if (index !in 0 until ctrl.mediaItemCount) return

        playlistAdapter.setCurrentIndex(index)
        playlistAdapter.setPlayingIndex(index)
        syncSelection()
        syncMetadata()
        syncButtons()

        // Une sélection dans la file passe par une commande de session dédiée. Le service peut
        // ainsi interrompre immédiatement un DataSource SMB éventuellement bloqué avant de recréer
        // la timeline. Des commandes Player classiques (seek/prepare/play) seules restent en attente
        // derrière le Loader réseau lorsque smbj est suspendu.
        val command = SessionCommand(
            BlazePlayerService.COMMAND_PLAY_QUEUE_INDEX_FROM_START,
            Bundle.EMPTY
        )
        val args = Bundle().apply {
            putInt(BlazePlayerService.EXTRA_QUEUE_INDEX, index)
        }

        try {
            val future = ctrl.sendCustomCommand(command, args)
            future.addListener({
                val resultCode = runCatching { future.get().resultCode }
                    .getOrDefault(androidx.media3.session.SessionResult.RESULT_ERROR_UNKNOWN)
                if (resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS &&
                    resultCode != androidx.media3.session.SessionResult.RESULT_ERROR_PERMISSION_DENIED) {
                    handler.post {
                        if (_binding == null || controller !== ctrl) return@post
                        fallbackPlayQueueItemFromStart(ctrl, index)
                    }
                }
            }, MoreExecutors.directExecutor())
        } catch (error: Exception) {
            CrashReporter.log(requireContext(), "Queue replay command failed for index $index", error)
            fallbackPlayQueueItemFromStart(ctrl, index)
        }
    }

    private fun fallbackPlayQueueItemFromStart(ctrl: MediaController, index: Int) {
        if (index !in 0 until ctrl.mediaItemCount) return
        ctrl.pause()
        ctrl.seekTo(index, 0L)
        ctrl.prepare()
        ctrl.playWhenReady = true
        ctrl.play()
    }

    private fun updateAudioQueueTitle() {
        val count = when {
            ::playlistAdapter.isInitialized -> playlistAdapter.itemCount
            else -> controller?.mediaItemCount ?: 0
        }
        _binding?.tvAudioQueueTitle?.text = "${getString(fr.retrospare.blazeplayer.R.string.queue)} ($count)"
    }

    private fun initPlaylistUi() {
        playlistAdapter = PlaylistAdapter({ controller }) { index ->
            if (isPlayingBlazePartyQueue && playlistAdapter.hasOverrideItems()) {
                restoreLocalQueueFromSnapshot(index, true)
            } else {
                playStandardQueueItemFromStart(index)
            }
        }
        playlistAdapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            private fun refreshCount() {
                _binding?.root?.post { updateAudioQueueTitle() }
            }
            override fun onChanged() = refreshCount()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = refreshCount()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = refreshCount()
        })
        binding.recyclerPlaylist.apply {
            // Une seule ligne pleine largeur par titre pour maximiser la lisibilité.
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            adapter = playlistAdapter
            configureSmoothQueueRecycler(this, playlistAdapter) { enabled ->
                playlistAdapter.setMetadataLoadsEnabled(enabled)
            }
            attachStandardAudioQueueDragAndDrop(this)
        }
        updateAudioQueueTitle()
        partyPlaylistAdapter = PartyPlaylistAdapter(
            voteCountProvider = { path -> voteCountFor(path) },
            onItemClick = { track -> showBlazePartyTrackVotes(track) }
        )
        binding.recyclerPartyPlaylist.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
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
        AudioLibraryActivity.warmUpForFastOpen(requireContext().applicationContext)
        binding.btnAudioLibrary.setOnClickListener {
            // L'ouverture de la bibliothèque doit être immédiate. Le warmup est déjà lancé quand
            // l'écran audio est créé ; on évite donc de déclencher une lecture cache au moment du tap.
            startActivity(android.content.Intent(requireContext(), AudioLibraryActivity::class.java))
        }
        fun openPlaylist() {
            updateAudioQueueTitle()
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

    /** Gestion des playlists audio nommées, totalement indépendante des playlists vidéo. */
    private fun setupSavedPlaylistDrawers() {
        val ctx = context ?: return
        val category = fr.retrospare.blazeplayer.playlist.PlaylistCategory.AUDIO
        val btnNew = binding.root.findViewById<com.google.android.material.button.MaterialButton>(
            fr.retrospare.blazeplayer.R.id.btnNewAudioPlaylist
        )
        val btnChoose = binding.root.findViewById<com.google.android.material.button.MaterialButton>(
            fr.retrospare.blazeplayer.R.id.btnChooseAudioPlaylist
        )
        val playlists = fr.retrospare.blazeplayer.playlist.PlaylistManager.getNamedPlaylists(ctx, category)
        btnChoose?.visibility = if (playlists.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        btnNew?.setOnClickListener {
            fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showCreatePlaylistDialog(ctx, category) {
                setupSavedPlaylistDrawers()
            }
        }
        btnChoose?.setOnClickListener {
            fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showChoosePlaylistForQueue(
                ctx,
                category,
                onPlaylistsChanged = { setupSavedPlaylistDrawers() }
            ) { playlist, tracks ->
                val added = appendNamedAudioPlaylistToQueue(tracks)
                val already = (tracks.distinctBy { it.path }.size - added).coerceAtLeast(0)
                val message = if (already == 0) {
                    getString(fr.retrospare.blazeplayer.R.string.toast_named_playlist_sent_to_queue, playlist.name)
                } else {
                    val addedText = resources.getQuantityString(fr.retrospare.blazeplayer.R.plurals.playlist_items_added, added, added)
                    val existingText = resources.getQuantityString(fr.retrospare.blazeplayer.R.plurals.playlist_items_already_present, already, already)
                    getString(fr.retrospare.blazeplayer.R.string.playlist_added_partial_named, addedText, existingText, playlist.name)
                }
                android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
                fr.retrospare.blazeplayer.playlist.PlaylistManager.setLastPlayedNamed(ctx, category, playlist.id)
                setupSavedPlaylistDrawers()
            }
        }

        val partyBtn = binding.btnAudioPlaylistParty
        partyBtn.isSelected = fr.retrospare.blazeplayer.playlist.PlaylistManager.getBlazePartyPlaylist(ctx).isNotEmpty()
        partyBtn.setOnClickListener { openBlazePartyAudioPlaylist() }
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
        _binding?.tvPartyQueueTitle?.text =
            "${getString(fr.retrospare.blazeplayer.R.string.blaze_party_playlist_title)} (${tracks.size})"
        val sharedCurrentPath = if (useLocalQueue) currentBlazePartyPath else guestPartyState?.currentPath
        partyPlaylistAdapter.setCurrentPath(sharedCurrentPath)
        // Boutons Lancer/Vider : visibles pour la file locale de ce téléphone. Côté invité réseau,
        // seul le vote a un sens puisque les fichiers appartiennent à l'hôte.
        _binding?.btnLaunchPartyPlaylist?.visibility = if (useLocalQueue) android.view.View.VISIBLE else android.view.View.GONE
        _binding?.btnClearPartyPlaylist?.visibility = if (useLocalQueue) android.view.View.VISIBLE else android.view.View.GONE
        val partyBtn = binding.btnAudioPlaylistParty
        partyBtn.isSelected = if (useLocalQueue) {
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

    private fun appendNamedAudioPlaylistToQueue(
        tracks: List<fr.retrospare.blazeplayer.playlist.PlaylistTrackRef>
    ): Int {
        val ctx = context ?: return 0
        val ordered = fr.retrospare.blazeplayer.playlist.PlaylistPlayOrder
            .sortedForPlayback(fr.retrospare.blazeplayer.playlist.PlaylistCategory.AUDIO, tracks)
            .distinctBy { it.path }
        if (ordered.isEmpty()) return 0
        val ctrl = controller
        if (ctrl == null) {
            ordered.forEach { sharedVm.addToPlaylist(it.path, it.name) }
            return ordered.size
        }
        val existing = (0 until ctrl.mediaItemCount).mapTo(HashSet(ctrl.mediaItemCount + ordered.size)) { index ->
            originalPathOf(ctrl.getMediaItemAt(index))
        }
        val additions = ordered.filter { it.path.isNotBlank() && existing.add(it.path) }
        if (additions.isEmpty()) return 0
        ctrl.addMediaItems(additions.map { track -> AudioRepository.buildSimpleMediaItem(ctx, track.path, track.name) })
        if (ctrl.playbackState == Player.STATE_IDLE) ctrl.prepare()
        if (::playlistAdapter.isInitialized) playlistAdapter.refresh()
        syncSelection()
        syncMetadata()
        syncButtons()
        savePlaylistFromController()
        return additions.size
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
            val cachedMeta = runCatching { fr.retrospare.blazeplayer.player.AudioMediaCache.getCachedMetadata(requireContext(), path) }.getOrNull()
            val meta = mediaItem.mediaMetadata
            val fileName = AudioLibraryHeuristics.fileNameFromPath(path).ifBlank {
                mediaItem.localConfiguration?.uri?.lastPathSegment.orEmpty()
            }
            val folderMeta = AudioLibraryHeuristics.folderMetadata(path, fileName)
            val extension = meta.extras?.getString(AudioRepository.EXTRA_CONTAINER_EXTENSION)?.takeIf { it.isNotBlank() }
                ?: cachedMeta?.extension?.takeIf { it.isNotBlank() }
                ?: path.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
            fr.retrospare.blazeplayer.playlist.PlaylistTrackRef(
                path = path,
                name = fileName,
                artist = folderMeta.artist,
                title = folderMeta.title,
                album = folderMeta.album,
                trackNumber = cachedMeta?.trackNumber ?: AudioLibraryHeuristics.inferTrackNo(fileName),
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
                val name = AudioLibraryHeuristics.fileNameFromPath(path).ifBlank {
                    mi.localConfiguration?.uri?.lastPathSegment.orEmpty()
                }
                PlaylistItem(path, name, mi.mediaMetadata.extras?.getString(AudioRepository.EXTRA_ARTWORK_PATH).orEmpty())
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
            val name = AudioLibraryHeuristics.fileNameFromPath(path).ifBlank {
                mi.localConfiguration?.uri?.lastPathSegment.orEmpty()
            }
            PlaylistItem(path, name, mi.mediaMetadata.extras?.getString(AudioRepository.EXTRA_ARTWORK_PATH).orEmpty())
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

        viewLifecycleOwner.lifecycleScope.launch(AudioPlaybackDispatchers.io) {
            try {
                val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), path, name)
                launch(Dispatchers.Main) {
                    val c = controller ?: return@launch
                    val idx = (0 until c.mediaItemCount).firstOrNull { originalPathOf(c.getMediaItemAt(it)) == path }
                    if (idx != null) {
                        if (replaceMediaItemRateLimited(c, idx, enriched)) {
                            playlistAdapter.notifyItemChanged(idx)
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // ── Mirroring karaoké natif ─────────────────────────────────────────────

    private fun setupKaraokeCastControls() {
        binding.btnKaraokeCast.setOnClickListener { openSystemKaraokeMirror() }
        binding.btnKaraokeCastLandscape.setOnClickListener { openSystemKaraokeMirror() }
        binding.btnKaraokeSyncMinus.setOnClickListener { adjustKaraoKastSyncOffset(-AudioProSettings.KARAOKAST_SYNC_STEP_MS) }
        binding.btnKaraokeSyncPlus.setOnClickListener { adjustKaraoKastSyncOffset(AudioProSettings.KARAOKAST_SYNC_STEP_MS) }
        binding.tvKaraokeSyncOffset.setOnLongClickListener {
            setKaraoKastSyncOffset(AudioProSettings.DEFAULT_KARAOKAST_SYNC_OFFSET_MS.toLong())
            true
        }
        updateKaraoKastSyncOffsetUi()

        // Raccourci pratique : un appui long permet de choisir la sortie locale sans quitter
        // le lecteur. Le mirroring reste géré par Android, la musique par l'ExoPlayer local.
        binding.btnKaraokeCast.setOnLongClickListener {
            LocalAudioOutputDialog.show(requireActivity())
            true
        }
        binding.btnKaraokeCastLandscape.setOnLongClickListener {
            LocalAudioOutputDialog.show(requireActivity())
            true
        }
    }

    private fun refreshKaraoKastSyncOffset() {
        if (!isAdded) return
        karaoKastSyncOffsetMs = AudioProSettings.karaoKastSyncOffsetMs(requireContext())
        updateKaraoKastSyncOffsetUi()
    }

    private fun adjustKaraoKastSyncOffset(deltaMs: Int) {
        setKaraoKastSyncOffset(karaoKastSyncOffsetMs + deltaMs)
    }

    private fun setKaraoKastSyncOffset(valueMs: Long) {
        if (!isAdded) return
        karaoKastSyncOffsetMs = AudioProSettings.setKaraoKastSyncOffsetMs(requireContext(), valueMs)
        updateKaraoKastSyncOffsetUi()
        updateLyricsLine(controller?.currentPosition ?: 0L)
    }

    private fun updateKaraoKastSyncOffsetUi() {
        val b = _binding ?: return
        val sign = if (karaoKastSyncOffsetMs >= 0L) "+" else ""
        b.tvKaraokeSyncOffset.text = getString(
            R.string.karaoke_sync_offset_value,
            sign,
            karaoKastSyncOffsetMs
        )
        b.btnKaraokeSyncMinus.isEnabled =
            karaoKastSyncOffsetMs > AudioProSettings.MIN_KARAOKAST_SYNC_OFFSET_MS.toLong()
        b.btnKaraokeSyncPlus.isEnabled =
            karaoKastSyncOffsetMs < AudioProSettings.MAX_KARAOKAST_SYNC_OFFSET_MS.toLong()
    }

    /**
     * Le mirroring affiche les images après un délai d'encodage, de transport et de mise en
     * mémoire tampon. On avance uniquement l'horloge visuelle des paroles pour compenser ce délai
     * sans dégrader la réactivité ni la qualité de la sortie audio locale.
     */
    private fun karaoKastLyricsPosition(positionMs: Long): Long =
        if (karaokeMirroringActive) (positionMs + karaoKastSyncOffsetMs).coerceAtLeast(0L)
        else positionMs

    /**
     * Le bouton reste gris lorsque le mirroring est inactif et adopte la couleur dynamique de la
     * pochette uniquement lorsqu'une route vidéo distante est réellement détectée.
     */
    private fun updateKaraokeCastButtonState() {
        val b = _binding ?: return
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.on_surface_variant)
        val color = if (karaokeMirroringActive) currentAccentColor else inactiveColor
        val alpha = when {
            karaokeMirroringActive -> 1f
            karaokeAvailable -> 0.86f
            else -> 0.58f
        }

        b.ivKaraokeCast.setColorFilter(color)
        b.tvKaraokeCast.setTextColor(color)
        b.btnKaraokeCast.alpha = alpha
        b.btnKaraokeCast.isSelected = karaokeMirroringActive
        b.btnKaraokeCast.isActivated = karaokeMirroringActive

        b.btnKaraokeCastLandscape.setColorFilter(color)
        b.btnKaraokeCastLandscape.alpha = alpha
        b.btnKaraokeCastLandscape.isSelected = karaokeMirroringActive
        b.btnKaraokeCastLandscape.isActivated = karaokeMirroringActive
    }

    /**
     * Le mirroring natif compresse toute la surface de l'écran en temps réel. Pendant KaraoKast,
     * on aligne les animations sur 30 i/s et on retire les effets de texte les plus coûteux afin de
     * réduire les images perdues et la chauffe. La compensation LRC KaraoKast reste indépendante.
     */
    private fun applyKaraoKastRenderingMode() {
        val b = _binding ?: return
        b.karaokeLyricsView.setKaraoKastPerformanceMode(karaokeMirroringActive)
        b.standardLyricsView.setKaraoKastPerformanceMode(karaokeMirroringActive)
        b.karaokeEqualizerView.setKaraoKastPerformanceMode(karaokeMirroringActive)
        b.audioEqualizerView.setKaraoKastPerformanceMode(karaokeMirroringActive)
    }

    private fun openSystemKaraokeMirror() {
        if (!karaokeAvailable) {
            android.widget.Toast.makeText(
                requireContext(),
                getString(R.string.karaoke_cast_requires_lrc),
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        // Active immédiatement le profil de rendu léger. Le détecteur système confirmera ou
        // annulera cet état au retour dans l'application, mais on évite ainsi plusieurs secondes
        // de rendu à fréquence native pendant l'établissement de la projection.
        karaokeMirroringActive = true
        applyKaraoKastRenderingMode()
        updateKaraokeCastButtonState()

        when (fr.retrospare.blazeplayer.cast.SystemScreenMirrorLauncher.open(requireActivity())) {
            fr.retrospare.blazeplayer.cast.SystemScreenMirrorLauncher.Result.OPENED -> {
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.karaoke_mirror_opened),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            fr.retrospare.blazeplayer.cast.SystemScreenMirrorLauncher.Result.UNAVAILABLE -> {
                karaokeMirroringActive = false
                applyKaraoKastRenderingMode()
                updateKaraokeCastButtonState()
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.karaoke_mirror_unavailable),
                    android.widget.Toast.LENGTH_LONG
                ).show()
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
            if (parentFragmentManager.findFragmentByTag("eq") != null) return@setOnClickListener
            binding.btnEq.isEnabled = false
            val existingManager = eqManager
            val appContext = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val manager = existingManager ?: withContext(AudioPlaybackDispatchers.io) {
                        EqualizerManager(
                            audioSessionId = 0,
                            context = appContext,
                            attachToAudioSession = false
                        ).also { it.warmUpForUi() }
                    }
                    if (!isAdded || _binding == null) {
                        if (existingManager == null) manager.release()
                        return@launch
                    }
                    eqManager = manager
                    EqualizerDialog(manager).show(parentFragmentManager, "eq")
                } catch (error: Throwable) {
                    if (isAdded) {
                        CrashReporter.log(requireContext(), "Open sound settings failed", error)
                    }
                } finally {
                    _binding?.btnEq?.isEnabled = true
                }
            }
        }

        binding.btnBottomAudioSettings.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), AudioProSettingsActivity::class.java))
        }
        binding.btnLyricsDownload.setOnClickListener { showLyricsDialog() }
        binding.btnSleepTimer.setOnClickListener { showSleepTimerEditor() }
        refreshSleepTimerIndicator()
    }

    private fun showSleepTimerEditor() {
        val ctrl = controller ?: return
        val future = ctrl.sendCustomCommand(
            SessionCommand(BlazePlayerService.COMMAND_GET_SLEEP_TIMER, Bundle.EMPTY),
            Bundle.EMPTY
        )
        future.addListener({
            val remaining = runCatching {
                future.get().extras.getLong(BlazePlayerService.EXTRA_SLEEP_TIMER_REMAINING_MS, 0L)
            }.getOrDefault(0L)
            _binding?.root?.post {
                val ctx = context ?: return@post
                SleepTimerDialog.show(
                    context = ctx,
                    initialRemainingMs = remaining,
                    onStart = { durationMs -> setSleepTimer(durationMs) },
                    onCancelTimer = { setSleepTimer(0L) }
                )
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setSleepTimer(durationMs: Long) {
        val ctrl = controller ?: return
        val args = Bundle().apply {
            putLong(BlazePlayerService.EXTRA_SLEEP_TIMER_DURATION_MS, durationMs)
        }
        val future = ctrl.sendCustomCommand(
            SessionCommand(BlazePlayerService.COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY),
            args
        )
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            val remaining = result?.extras?.getLong(BlazePlayerService.EXTRA_SLEEP_TIMER_REMAINING_MS, 0L) ?: 0L
            _binding?.root?.post {
                if (result?.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                    updateSleepTimerIndicator(remaining)
                    val message = if (remaining > 0L) {
                        getString(R.string.sleep_timer_started, formatSleepTimerDuration(remaining))
                    } else {
                        getString(R.string.sleep_timer_cancelled)
                    }
                    android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }, MoreExecutors.directExecutor())
    }

    private fun refreshSleepTimerIndicator() {
        val ctrl = controller ?: return
        val future = ctrl.sendCustomCommand(
            SessionCommand(BlazePlayerService.COMMAND_GET_SLEEP_TIMER, Bundle.EMPTY),
            Bundle.EMPTY
        )
        future.addListener({
            val remaining = runCatching {
                future.get().extras.getLong(BlazePlayerService.EXTRA_SLEEP_TIMER_REMAINING_MS, 0L)
            }.getOrDefault(0L)
            _binding?.root?.post { updateSleepTimerIndicator(remaining) }
        }, MoreExecutors.directExecutor())
    }

    private fun updateSleepTimerIndicator(remainingMs: Long) {
        val b = _binding ?: return
        val active = remainingMs > 0L
        (b.btnSleepTimer.getChildAt(0) as? android.widget.ImageView)?.setColorFilter(
            requireContext().getColor(if (active) R.color.green_accent else R.color.on_surface_variant)
        )
        sleepTimerIndicatorJob?.cancel()
        if (active) {
            sleepTimerIndicatorJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(remainingMs)
                (_binding?.btnSleepTimer?.getChildAt(0) as? android.widget.ImageView)?.setColorFilter(
                    requireContext().getColor(R.color.on_surface_variant)
                )
            }
        }
    }

    private fun formatSleepTimerDuration(durationMs: Long): String {
        val totalMinutes = ((durationMs + 59_999L) / 60_000L).coerceAtLeast(1L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) "%dh %02d".format(hours, minutes) else "%d min".format(minutes)
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

    private fun startLyricsClockSync() {
        handler.removeCallbacks(lyricsClockSyncRunnable)
        handler.post(lyricsClockSyncRunnable)
    }

    private fun syncLyricsPlaybackClock(positionMs: Long = controller?.currentPosition ?: 0L) {
        val b = _binding ?: return
        val ctrl = controller ?: return
        val speed = ctrl.playbackParameters.speed
        if (karaokeLandscapeActive) {
            b.karaokeLyricsView.updatePlaybackPosition(
                karaoKastLyricsPosition(positionMs),
                ctrl.isPlaying,
                speed
            )
        } else if (currentLyrics.isNotEmpty()) {
            b.standardLyricsView.updatePlaybackPosition(positionMs, ctrl.isPlaying, speed)
        }
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
                    updateLyricsLine(ctrl.currentPosition)
                    if (!useRemotePartyClock) {
                        val currentPath = if (ctrl.currentMediaItemIndex in 0 until ctrl.mediaItemCount) originalPathOf(ctrl.getMediaItemAt(ctrl.currentMediaItemIndex)) else null
                        partyPlaylistAdapter.updateCurrentProgress(currentPath, ctrl.currentPosition, dur)
                    }
                }
                handler.postDelayed(this, 500)
            }
        })
    }


    private fun applyAudioProInterfaceSettings() {
        val b = _binding ?: return
        val settings = AudioProSettings.read(requireContext())
        val lyricsEnabled = settings.syncedLyrics
        val hasLyrics = lyricsEnabled && currentLyrics.any { it.text.isNotBlank() }
        b.artworkMetadataOverlay.visibility = if (audioSpectrumEnabled || lyricsEnabled) View.VISIBLE else View.GONE
        b.audioEqualizerView.visibility = if (audioSpectrumEnabled) View.VISIBLE else View.GONE
        b.karaokeEqualizerView.visibility = if (audioSpectrumEnabled) View.VISIBLE else View.GONE
        clearArtworkAccentBorders()
        updatePortraitLyricsOverlayState(lyricsEnabled)
        if (!lyricsEnabled) {
            lastLyricsLine = null
            lastLyricsOverlayKey = null
            updateLyricsKeepScreenOn(false)
        } else if (hasLyrics) {
            updateLyricsLine(controller?.currentPosition ?: 0L)
        } else {
            updateLyricsKeepScreenOn(false)
        }
        val muted = ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.on_surface_variant)
        b.btnLyricsDownload.isEnabled = lyricsEnabled
        b.btnLyricsDownload.isClickable = lyricsEnabled
        b.btnLyricsDownload.contentDescription = getString(R.string.audio_lyrics_button_description)
        b.btnLyricsDownload.alpha = if (lyricsEnabled) 1f else 0.48f
        b.btnLyricsDownload.imageTintList = ColorStateList.valueOf(if (lyricsEnabled) currentAccentColor else muted)
        squareArtworkContainer?.requestLayout()
        if (audioSpectrumEnabled && controller?.isPlaying == true) scheduleVisualizerUiRecovery()
    }

    /**
     * Laisse le bouton de gestion/téléchargement des paroles disponible lorsque l'option est
     * activée, mais n'assombrit la pochette que lorsqu'un vrai flux LRC est présent à l'écran.
     * L'absence de paroles ne doit donc plus poser un grand dégradé sombre inutile sur la cover.
     */
    private fun updatePortraitLyricsOverlayState(lyricsEnabled: Boolean, forceHidden: Boolean = false) {
        val b = _binding ?: return
        val showContainer = lyricsEnabled && !forceHidden && !karaokeLandscapeActive
        val hasScrollingLyrics = showContainer && currentLyrics.any { it.text.isNotBlank() }

        b.lyricsOverlay.visibility = if (showContainer) View.VISIBLE else View.GONE
        b.btnLyricsDownload.visibility = if (showContainer) View.VISIBLE else View.GONE
        b.lyricsOverlay.clipToOutline = false
        b.lyricsOverlay.outlineProvider = null
        b.lyricsOverlay.background = if (hasScrollingLyrics) {
            ContextCompat.getDrawable(requireContext(), R.drawable.bg_audio_lyrics_top_overlay)
        } else {
            null
        }
        b.standardLyricsView.visibility = if (hasScrollingLyrics) View.VISIBLE else View.INVISIBLE
        if (showContainer) b.lyricsOverlay.bringToFront()
    }


    private fun updateLyricsKeepScreenOn(enabled: Boolean) {
        val shouldKeepAwake = enabled && !isHidden && isAdded
        _binding?.lyricsOverlay?.keepScreenOn = shouldKeepAwake
        _binding?.root?.keepScreenOn = shouldKeepAwake
        val window = activity?.window ?: return
        if (shouldKeepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }


    private fun loadLyricsForCurrentTrack(path: String, force: Boolean = false) {
        val settings = AudioProSettings.read(requireContext())
        if (path.isBlank() || !settings.syncedLyrics) {
            lyricsJob?.cancel()
            currentLyricsPath = path
            completedLyricsLookupPath = path
            currentLyricsData = null
            currentLyrics = emptyList()
            lastLyricsLine = null
            lastLyricsOverlayKey = null
            updateKaraokeAvailability()
            updateLyricsLine(controller?.currentPosition ?: 0L)
            applyAudioProInterfaceSettings()
            return
        }

        // syncMetadata() est rappelé pour play/pause, buffering et diverses notifications Media3.
        // Ne jamais annuler/recommencer la lecture du même .LRC à chacun de ces événements.
        if (!force && currentLyricsPath == path &&
            (lyricsJob?.isActive == true || completedLyricsLookupPath == path || currentLyricsData != null)
        ) return

        lyricsJob?.cancel()
        currentLyricsPath = path
        completedLyricsLookupPath = ""
        currentLyricsData = null
        currentLyrics = emptyList()
        lastLyricsLine = null
        lastLyricsOverlayKey = null
        updateKaraokeAvailability()
        updateLyricsLine(controller?.currentPosition ?: 0L)

        val appContext = requireContext().applicationContext
        val expectedPath = path
        lyricsJob = viewLifecycleOwner.lifecycleScope.launch(AudioPlaybackDispatchers.lyrics) {
            val loaded = AudioLocalEnhancements.findLocalLyricsData(appContext, expectedPath)
            launch(Dispatchers.Main) {
                val currentPath = controller?.currentMediaItem?.let { originalPathOf(it) }.orEmpty()
                if (currentPath != expectedPath || currentLyricsPath != expectedPath) return@launch
                completedLyricsLookupPath = expectedPath
                currentLyricsData = loaded
                currentLyrics = loaded?.lines.orEmpty()
                lastLyricsLine = null
                lastLyricsOverlayKey = null
                updateKaraokeAvailability()
                updateLyricsLine(controller?.currentPosition ?: 0L)
                applyAudioProInterfaceSettings()
            }
        }
    }

    private fun updateLyricsLine(positionMs: Long) {
        val b = _binding ?: return
        val isPlaying = controller?.isPlaying == true
        val speed = controller?.playbackParameters?.speed ?: 1f
        if (karaokeLandscapeActive) {
            b.karaokeLyricsView.updatePlaybackPosition(karaoKastLyricsPosition(positionMs), isPlaying, speed)
            // Stoppe explicitement l'animation de la vue portrait cachée.
            b.standardLyricsView.updatePlaybackPosition(positionMs, false, speed)
        } else {
            b.standardLyricsView.updatePlaybackPosition(positionMs, isPlaying, speed)
            // Stoppe explicitement l'animation de la vue karaoké cachée.
            b.karaokeLyricsView.updatePlaybackPosition(positionMs, false, speed)
        }

        val settings = AudioProSettings.read(requireContext())
        if (!settings.syncedLyrics) {
            updatePortraitLyricsOverlayState(lyricsEnabled = false)
            lastLyricsLine = null
            lastLyricsOverlayKey = null
            updateLyricsKeepScreenOn(false)
            return
        }

        if (karaokeLandscapeActive) {
            updatePortraitLyricsOverlayState(lyricsEnabled = true, forceHidden = true)
            updateLyricsKeepScreenOn(currentLyrics.any { it.text.isNotBlank() })
        } else {
            updatePortraitLyricsOverlayState(lyricsEnabled = true)
            if (currentLyrics.any { it.text.isNotBlank() }) {
                b.standardLyricsView.setAccentColor(currentAccentColor)
            }
            updateLyricsKeepScreenOn(currentLyrics.any { it.text.isNotBlank() })
        }
    }

    // ── Dancer ─────────────────────────────────────────────────────────────────

    // ── Utils ──────────────────────────────────────────────────────────────────

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }


    private fun showLyricsDialog() {
        val ctx = context ?: return
        val settings = AudioProSettings.read(ctx)
        val ctrl = controller
        val mediaItem = ctrl?.currentMediaItem
        val path = mediaItem?.let { originalPathOf(it) }.orEmpty()
        val metadata = mediaItem?.mediaMetadata
        val fallbackTrackName = Uri.parse(path).lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            .orEmpty()
        val trackName = metadata?.title?.toString().orEmpty().ifBlank { fallbackTrackName }
        val artistName = metadata?.artist?.toString().orEmpty()
        val albumName = metadata?.albumTitle?.toString().orEmpty()
        val trackDurationMs = ctrl?.duration?.takeIf { it > 0L } ?: 0L
        val lyrics = if (settings.syncedLyrics) currentLyricsData else null

        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(18))
            background = ContextCompat.getDrawable(ctx, fr.retrospare.blazeplayer.R.drawable.bg_dialog_rounded)
        }
        root.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(ContextCompat.getColor(ctx, fr.retrospare.blazeplayer.R.color.outline_variant))
            }
        }, LinearLayout.LayoutParams(dp(42), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(ImageButton(ctx).apply {
            setImageResource(fr.retrospare.blazeplayer.R.drawable.ic_close)
            setColorFilter(ContextCompat.getColor(ctx, fr.retrospare.blazeplayer.R.color.on_surface_variant))
            background = ColorDrawable(Color.TRANSPARENT)
            contentDescription = getString(fr.retrospare.blazeplayer.R.string.action_close)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(header)

        root.addView(View(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, fr.retrospare.blazeplayer.R.color.outline_variant))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(8); bottomMargin = dp(12) })

        val status = TextView(ctx).apply {
            text = when {
                !settings.syncedLyrics -> getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_disabled)
                lyrics == null -> getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_none)
                lyrics.isSynced -> getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_synced)
                else -> getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_static)
            }
            setTextColor(if (lyrics != null) currentAccentColor else ContextCompat.getColor(ctx, fr.retrospare.blazeplayer.R.color.on_surface_variant))
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(status)

        val bodyText = when {
            !settings.syncedLyrics -> getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_hint)
            lyrics?.displayText?.isNotBlank() == true -> lyrics.displayText
            else -> getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_hint)
        }
        val bodyView = TextView(ctx).apply {
            text = bodyText
            setTextColor(ContextCompat.getColor(ctx, fr.retrospare.blazeplayer.R.color.on_surface))
            textSize = 15f
            setLineSpacing(dp(2).toFloat(), 1.08f)
            gravity = if (lyrics == null) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val scroll = android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(bodyView, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val sourceView = TextView(ctx).apply {
            text = lyrics?.let { getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_source, it.fileName) }.orEmpty()
            setTextColor(ContextCompat.getColor(ctx, fr.retrospare.blazeplayer.R.color.on_surface_variant))
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(8), 0, 0)
            visibility = if (lyrics != null) View.VISIBLE else View.GONE
        }

        val searchOnlineButton = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(fr.retrospare.blazeplayer.R.string.audio_lyrics_search_online)
            isAllCaps = false
            isEnabled = path.isNotBlank()
            setOnClickListener {
                LrclibLyricsDialog.show(
                    fragment = this@AudioPlayerFragment,
                    audioPath = path,
                    trackName = trackName,
                    artistName = artistName,
                    albumName = albumName,
                    durationMs = trackDurationMs,
                    accentColor = currentAccentColor
                ) {
                    AudioLocalEnhancements.invalidateLyrics(path)
                    completedLyricsLookupPath = ""
                    loadLyricsForCurrentTrack(path, force = true)
                    viewLifecycleOwner.lifecycleScope.launch(AudioPlaybackDispatchers.io) {
                        val fresh = AudioLocalEnhancements.findLocalLyricsData(ctx.applicationContext, path)
                        launch(Dispatchers.Main) {
                            if (!dialog.isShowing) return@launch
                            currentLyricsData = fresh
                            currentLyrics = fresh?.lines.orEmpty()
                            lastLyricsLine = null
                            updateLyricsLine(controller?.currentPosition ?: 0L)
                            applyAudioProInterfaceSettings()
                            status.text = when {
                                fresh == null -> getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_none)
                                fresh.isSynced -> getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_synced)
                                else -> getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_static)
                            }
                            status.setTextColor(if (fresh != null) currentAccentColor else ContextCompat.getColor(ctx, fr.retrospare.blazeplayer.R.color.on_surface_variant))
                            bodyView.text = fresh?.displayText?.takeIf { it.isNotBlank() } ?: getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_hint)
                            bodyView.gravity = if (fresh == null) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
                            sourceView.text = fresh?.let { getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_source, it.fileName) }.orEmpty()
                            sourceView.visibility = if (fresh != null) View.VISIBLE else View.GONE
                        }
                    }
                }
            }
        }
        root.addView(searchOnlineButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        root.addView(TextView(ctx).apply {
            setText(fr.retrospare.blazeplayer.R.string.audio_lyrics_download_hint)
            setTextColor(ContextCompat.getColor(ctx, fr.retrospare.blazeplayer.R.color.on_surface_variant))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), 0)
        })

        root.addView(sourceView)

        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val width = kotlin.math.min((resources.displayMetrics.widthPixels * 0.92f).toInt(), dp(520))
                val height = kotlin.math.min((resources.displayMetrics.heightPixels * 0.78f).toInt(), dp(620))
                setLayout(width, height)
            }
        }
        dialog.show()
        fr.retrospare.blazeplayer.ui.HapticFeedbackManager.attachToWindow(dialog.window)

        if (settings.syncedLyrics && path.isNotBlank() && lyrics == null) {
            val appContext = ctx.applicationContext
            viewLifecycleOwner.lifecycleScope.launch(AudioPlaybackDispatchers.io) {
                val fresh = AudioLocalEnhancements.findLocalLyricsData(appContext, path)
                launch(Dispatchers.Main) {
                    val currentPath = controller?.currentMediaItem?.let { originalPathOf(it) }.orEmpty()
                    if (!dialog.isShowing || currentPath != path) return@launch
                    currentLyricsData = fresh
                    currentLyrics = fresh?.lines.orEmpty()
                    lastLyricsLine = null
                    updateLyricsLine(controller?.currentPosition ?: 0L)
                    applyAudioProInterfaceSettings()
                    status.text = when {
                        fresh == null -> getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_none)
                        fresh.isSynced -> getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_synced)
                        else -> getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_static)
                    }
                    status.setTextColor(if (fresh != null) currentAccentColor else ContextCompat.getColor(ctx, fr.retrospare.blazeplayer.R.color.on_surface_variant))
                    bodyView.text = fresh?.displayText?.takeIf { it.isNotBlank() } ?: getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_hint)
                    bodyView.gravity = if (fresh == null) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
                    sourceView.text = fresh?.let { getString(fr.retrospare.blazeplayer.R.string.audio_lyrics_source, it.fileName) }.orEmpty()
                    sourceView.visibility = if (fresh != null) View.VISIBLE else View.GONE
                }
            }
        }
    }

    // ── Blaze Party V1 ───────────────────────────────────────────────────────

    private fun showBlazePartyDialog() {
        val dialog = Dialog(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = ContextCompat.getDrawable(requireContext(), fr.retrospare.blazeplayer.R.drawable.bg_dialog_rounded)
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
        fr.retrospare.blazeplayer.ui.HapticFeedbackManager.attachToWindow(dialog.window)
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
            background = ContextCompat.getDrawable(requireContext(), fr.retrospare.blazeplayer.R.drawable.bg_dialog_rounded)
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
        fr.retrospare.blazeplayer.ui.HapticFeedbackManager.attachToWindow(dialog.window)
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

    @Suppress("DEPRECATION")
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
            val name = AudioLibraryHeuristics.fileNameFromPath(path).ifBlank {
                mi.localConfiguration?.uri?.lastPathSegment.orEmpty()
            }
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
        val ctx = context ?: return
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount == 0) {
            android.widget.Toast.makeText(ctx, getString(fr.retrospare.blazeplayer.R.string.toast_list_already_empty), android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val itemsSnapshot = (0 until ctrl.mediaItemCount).map { i ->
            val mi = ctrl.getMediaItemAt(i)
            val path = originalPathOf(mi)
            val fileName = AudioLibraryHeuristics.fileNameFromPath(path).ifBlank {
                mi.localConfiguration?.uri?.lastPathSegment.orEmpty()
            }
            val folderMeta = AudioLibraryHeuristics.folderMetadata(path, fileName)
            folderMeta.title.ifBlank { "?" } to folderMeta.artist
        }
        val checked = BooleanArray(itemsSnapshot.size) { false }
        val accent = currentAccentColor

        val view = layoutInflater.inflate(fr.retrospare.blazeplayer.R.layout.dialog_audio_queue_clear, null)
        val list = view.findViewById<LinearLayout>(fr.retrospare.blazeplayer.R.id.audioQueueClearTrackList)
        val btnClose = view.findViewById<ImageButton>(fr.retrospare.blazeplayer.R.id.btnAudioQueueClearClose)
        val btnClearAll = view.findViewById<MaterialButton>(fr.retrospare.blazeplayer.R.id.btnAudioQueueClearAll)
        val btnClearSelection = view.findViewById<MaterialButton>(fr.retrospare.blazeplayer.R.id.btnAudioQueueClearSelection)

        itemsSnapshot.forEachIndexed { index, (title, artist) ->
            val label = android.text.SpannableStringBuilder(title).apply {
                setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    0, title.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                append("\n")
                val start = length
                append(artist)
                setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(ctx, fr.retrospare.blazeplayer.R.color.on_surface_variant)),
                    start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    android.text.style.RelativeSizeSpan(0.88f),
                    start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            val box = android.widget.CheckBox(ctx).apply {
                text = label
                isChecked = false
                setTextColor(ContextCompat.getColor(ctx, fr.retrospare.blazeplayer.R.color.on_surface))
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                buttonTintList = null
                setButtonDrawable(fr.retrospare.blazeplayer.R.drawable.bg_multi_select_checkbox)
                minimumWidth = dp(32)
                minimumHeight = dp(32)
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, isChecked -> checked[index] = isChecked }
            }
            list.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        btnClearAll.apply {
            strokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, fr.retrospare.blazeplayer.R.color.red_accent))
            setTextColor(ContextCompat.getColor(ctx, fr.retrospare.blazeplayer.R.color.red_accent))
            ButtonTextFitter.fit(this, minSp = 9, maxSp = 13)
        }
        btnClearSelection.apply {
            strokeColor = ColorStateList.valueOf(accent)
            setTextColor(accent)
            iconTint = ColorStateList.valueOf(accent)
            ButtonTextFitter.fit(this, minSp = 9, maxSp = 13)
        }
        ButtonTextFitter.fitRecursively(view, minSp = 9, maxSp = 13)

        val dialog = Dialog(ctx).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(view)
            setCanceledOnTouchOutside(true)
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        btnClearAll.setOnClickListener {
            controller?.clearMediaItems()
            playlistAdapter.refresh()
            AudioRepository.clear(ctx)
            savePlaylistFromController()
            dialog.dismiss()
        }
        btnClearSelection.setOnClickListener {
            if (checked.none { it }) {
                android.widget.Toast.makeText(ctx, getString(fr.retrospare.blazeplayer.R.string.toast_select_tracks_first), android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val c = controller ?: return@setOnClickListener
            // Supprime du plus grand index au plus petit pour ne pas décaler les indices.
            checked.indices.reversed().forEach { i ->
                if (checked[i] && i < c.mediaItemCount) {
                    c.removeMediaItem(i)
                }
            }
            playlistAdapter.refresh()
            savePlaylistFromController()
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val width = kotlin.math.min((resources.displayMetrics.widthPixels * 0.92f).toInt(), dp(520))
                setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
        dialog.show()
        fr.retrospare.blazeplayer.ui.HapticFeedbackManager.attachToWindow(dialog.window)
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
        private const val LYRICS_CLOCK_SYNC_INTERVAL_MS = 120L
        private const val VISUALIZER_WATCHDOG_INTERVAL_MS = 1_500L
        private const val VISUALIZER_STALE_AFTER_MS = 5_500L
        private const val VISUALIZER_RESTART_COOLDOWN_MS = 6_000L
        private const val VISUALIZER_MIN_RESTART_GAP_MS = 4_500L
        private const val VISUALIZER_ATTACH_GRACE_MS = 2_500L
        private const val VISUALIZER_HEALTHY_CAPTURE_WINDOW_MS = 4_500L
        private const val VISUALIZER_TRACK_HEALTH_WINDOW_MS = 3_500L
        private const val VISUALIZER_STARTUP_VERIFY_DELAY_MS = 1_400L
        private const val VISUALIZER_STARTUP_FRAME_TIMEOUT_MS = 3_500L
        private const val VISUALIZER_STARTUP_RETRY_DELAY_MS = 1_600L
        private const val VISUALIZER_STARTUP_MAX_RETRIES = 5
        private const val VISUALIZER_LONG_RECOVERY_DELAY_MS = 6_000L
        private const val VISUALIZER_UI_RECOVERY_DELAY_MS = 180L
        private const val VISUALIZER_FRESH_SESSION_DELAY_MS = 40L
        private const val VISUALIZER_FRESH_TRACK_DELAY_MS = 90L
        private const val VISUALIZER_FRESH_START_DELAY_MS = 120L
        private const val VISUALIZER_FRESH_RESUME_DELAY_MS = 160L
        private const val VISUALIZER_COLD_RESUME_FRAME_TIMEOUT_MS = 3_500L
        private val VISUALIZER_COLD_RESUME_DELAYS_MS =
            longArrayOf(700L, 1_100L, 1_700L, 2_500L, 3_500L)
        private val VISUALIZER_RECOVERY_DELAYS_MS = longArrayOf(350L, 700L, 1_400L, 2_500L, 4_000L)
        private const val DYNAMIC_AUDIO_PREFS = "blaze_audio_dynamic_colors"
        private const val KEY_DYNAMIC_BG = "dynamic_bg"
        private const val KEY_DYNAMIC_ACCENT = "dynamic_accent"
    }
}
