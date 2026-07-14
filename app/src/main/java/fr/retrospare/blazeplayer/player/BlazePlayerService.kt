package fr.retrospare.blazeplayer.player

import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.SharedPreferences
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import android.net.wifi.WifiManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import dagger.hilt.android.AndroidEntryPoint
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import fi.iki.elonen.NanoHTTPD
import fr.retrospare.blazeplayer.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong

/**
 * Service de lecture AUDIO basé sur [MediaSessionService] : c'est l'unique source de vérité pour
 * la lecture audio de l'application. Toute UI (AudioPlayerFragment, MiniPlayer, notification
 * système, Android Auto...) doit communiquer avec le player exclusivement via un
 * [androidx.media3.session.MediaController] connecté à ce service.
 *
 * On évite délibérément toute référence statique directe au player ou au service (pattern
 * `companion object { var instance }`) : c'est un anti-pattern Media3 qui casse l'encapsulation
 * de la session et ne fonctionne pas si le contrôleur tourne dans un autre process. Le seul état
 * interne qui n'est pas exposé par l'API [androidx.media3.common.Player] standard (l'audioSessionId,
 * nécessaire pour brancher l'égaliseur système) est exposé via une commande de session personnalisée.
 *
 * La notification média (lockscreen, barre de notif, contrôles Bluetooth/casque) est entièrement
 * gérée automatiquement par [MediaSessionService] via son [androidx.media3.session.MediaNotification.Provider]
 * par défaut — il ne faut surtout pas la dupliquer manuellement avec une notification "maison".
 */
@AndroidEntryPoint
@UnstableApi
class BlazePlayerService : MediaSessionService() {

    companion object {
        /** Commande de session permettant à un [androidx.media3.session.MediaController] de
         *  récupérer l'audioSessionId courant du player, utilisé pour brancher l'égaliseur système
         *  (android.media.audiofx). Non exposé par l'API Player standard. */
        const val COMMAND_GET_AUDIO_SESSION_ID = "fr.retrospare.blazeplayer.GET_AUDIO_SESSION_ID"
        const val COMMAND_PLAY_EXTERNAL_AUDIO = "fr.retrospare.blazeplayer.PLAY_EXTERNAL_AUDIO"
        const val COMMAND_PLAY_QUEUE_INDEX_FROM_START = "fr.retrospare.blazeplayer.PLAY_QUEUE_INDEX_FROM_START"
        const val ACTION_PLAY_EXTERNAL_AUDIO = "fr.retrospare.blazeplayer.action.PLAY_EXTERNAL_AUDIO"
        const val ACTION_PLAY_AUDIO_QUEUE = "fr.retrospare.blazeplayer.action.PLAY_AUDIO_QUEUE"
        const val ACTION_APPEND_AUDIO_QUEUE = "fr.retrospare.blazeplayer.action.APPEND_AUDIO_QUEUE"
        const val ACTION_APPEND_AUDIO_QUEUE_AND_PLAY = "fr.retrospare.blazeplayer.action.APPEND_AUDIO_QUEUE_AND_PLAY"
        const val EXTRA_AUDIO_SESSION_ID = "audioSessionId"
        const val EXTRA_EXTERNAL_AUDIO_PATH = "path"
        const val EXTRA_EXTERNAL_AUDIO_NAME = "name"
        const val EXTRA_AUDIO_QUEUE_PATHS = "paths"
        const val EXTRA_AUDIO_QUEUE_NAMES = "names"
        const val EXTRA_AUDIO_QUEUE_ARTWORK_PATHS = "artworkPaths"
        const val EXTRA_AUDIO_QUEUE_INDEX = "index"
        const val EXTRA_QUEUE_INDEX = "queueIndex"

        /** Commandes de session pour piloter le serveur réseau Blaze Party hébergé par CE service
         *  (et non par AudioPlayerFragment), afin qu'une session hôte survive à la fermeture de
         *  l'écran audio tant que la lecture — donc ce service — reste active en arrière-plan. */
        const val COMMAND_PARTY_START_HOST = "fr.retrospare.blazeplayer.PARTY_START_HOST"
        const val COMMAND_PARTY_STOP_HOST = "fr.retrospare.blazeplayer.PARTY_STOP_HOST"
        // Actions de service en secours : elles garantissent que NanoHTTPD démarre même si le
        // MediaController n'est pas encore prêt au moment où l'hôte affiche le QR.
        const val ACTION_PARTY_START_HOST = "fr.retrospare.blazeplayer.action.PARTY_START_HOST"
        const val ACTION_PARTY_STOP_HOST = "fr.retrospare.blazeplayer.action.PARTY_STOP_HOST"
        const val EXTRA_PARTY_TOKEN = "partyToken"

        // ID de notification et channel dédiés : Media3 utilise le même ID par défaut pour tous
        // les MediaSessionService de l'app si non personnalisé, ce qui faisait que la notification
        // vidéo écrasait purement et simplement la notification audio (même slot de notification).
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "blaze_audio_channel"
        private const val MAX_SMB_PLAYBACK_RECOVERY_ATTEMPTS = 2
        private const val SMB_PLAYBACK_RECOVERY_DELAY_MS = 650L
        private const val SMB_STALL_WATCHDOG_MS = 10_000L

        /** Signal léger lu par la bibliothèque audio : quand une piste joue, les scans
         *  métadonnées/covers doivent rester strictement non prioritaires pour éviter
         *  toute coupure, surtout avec des fichiers sur NAS. */
        @Volatile var isAudioPlaybackActive: Boolean = false
            private set

        /** Vrai uniquement quand le service possède réellement une file audio. MainActivity s'en
         * sert pour ne jamais démarrer ce service à froid uniquement afin d'afficher le mini player. */
        @Volatile var isAudioSessionActive: Boolean = false
            private set
    }

    @Inject lateinit var settingsDataStore: DataStore<Preferences>
    @Inject lateinit var userRepository: fr.retrospare.blazeplayer.data.repository.UserRepository

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var sessionPlayer: Player? = null
    private var eqManager: EqualizerManager? = null
    private var equalizerSessionId: Int = 0
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessEnhancerSessionId: Int = 0
    private var pcmAudioProcessor: BlazePcmAudioProcessor? = null
    private lateinit var eqPrefs: SharedPreferences
    private lateinit var audioProPrefs: SharedPreferences
    private var audioProValues: AudioProSettings.Values = AudioProSettings.Values()
    private var currentReplayGainDb: Float = 0f
    private var playerVolumeAnimator: ValueAnimator? = null
    private var crossfadeInProgress = false
    private var crossfadeIndex = -1
    private val eqApplyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val audioPipelineHandler = Handler(Looper.getMainLooper())
    private val partyStateHandler = Handler(Looper.getMainLooper())
    private val crossfadeHandler = Handler(Looper.getMainLooper())
    private val smbRecoveryHandler = Handler(Looper.getMainLooper())
    private val smbStallHandler = Handler(Looper.getMainLooper())
    private val autoAdvanceHandler = Handler(Looper.getMainLooper())
    @Volatile private var autoPlayNextEnabled = true
    @Volatile private var autoAdvanceGeneration = 0L
    private var smbRecoveryPath = ""
    private var smbRecoveryAttempts = 0
    private var smbStallGeneration = 0L
    @Volatile private var ignoreSmbErrorsUntilMs = 0L
    private val playbackRequestGeneration = AtomicLong(0L)
    private var activeAudioPipelineSignature = ""
    private var rebuildingAudioPipeline = false
    @Volatile private var accessResolved = false
    @Volatile private var hasAudioAccess = false
    private var accessExpiryJob: Job? = null

    private data class AudioPlayerBundle(
        val player: ExoPlayer,
        val processor: BlazePcmAudioProcessor?
    )
    private val audioProListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        audioProValues = AudioProSettings.read(applicationContext)
        if (key == AudioProSettings.KEY_HI_RES || key == AudioProSettings.KEY_OUTPUT_MODE) {
            scheduleAudioPipelineRebuild()
        }
        refreshReplayGainForCurrentItem()
        applyAudioProSettings()
    }
    private val eqPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == EqualizerManager.KEY_LOUDNESS || key == EqualizerManager.KEY_EQ_ENABLED) {
            applyLoudnessEnhancer(player?.audioSessionId ?: 0)
        }
        if (key == EqualizerManager.KEY_EQ_ENABLED) {
            scheduleAudioPipelineRebuild()
            player?.let { tryApplyHighQualityOutput(it) }
        }
    }
    private val crossfadeRunnable = object : Runnable {
        override fun run() {
            checkCrossfadeWindow()
            scheduleCrossfadeCheck()
        }
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Serveur HTTP local Blaze Party : vit ici (et non dans AudioPlayerFragment) précisément pour
    // continuer à répondre aux invités même quand l'utilisateur quitte l'écran audio, tant que la
    // lecture — donc ce service — tourne toujours en arrière-plan.
    private var partyHostServer: PartyHostServer? = null
    private var partyWifiLock: WifiManager.WifiLock? = null
    @Volatile private var partyStateSnapshot: PartyState? = null
    private val partyStateRefreshRunnable = object : Runnable {
        override fun run() {
            refreshPartyStateSnapshotNow()
            if (partyHostServer != null) {
                partyStateHandler.postDelayed(this, 1000L)
            }
        }
    }


    override fun onCreate() {
        super.onCreate()

        // Aucun accès DataStore bloquant ici : onCreate() est exécuté sur le thread principal.
        // Le contrôle Pro+ est lancé après la création de la session et reste entièrement asynchrone.

        // Le réglage global doit être connu même lorsque l'écran Audio n'est pas ouvert : le
        // service est la seule source de vérité pour les files audio et Blaze Party.
        serviceScope.launch {
            settingsDataStore.data
                .map { prefs -> prefs[SettingsViewModel.KEY_AUTO_PLAY] ?: true }
                .distinctUntilChanged()
                .collect { enabled ->
                    autoPlayNextEnabled = enabled
                    if (!enabled) {
                        autoAdvanceGeneration++
                        autoAdvanceHandler.removeCallbacksAndMessages(null)
                    }
                }
        }

        audioProPrefs = AudioProSettings.prefs(this)
        audioProValues = AudioProSettings.read(this)
        audioProPrefs.registerOnSharedPreferenceChangeListener(audioProListener)
        eqPrefs = getSharedPreferences(EqualizerManager.PREFS_NAME, Context.MODE_PRIVATE)
        eqPrefs.registerOnSharedPreferenceChangeListener(eqPreferenceListener)

        // Media3 recommande de garder le Player et la MediaSession dans le MediaSessionService.
        // On ne détruit/recrée plus le service pour chaque fichier externe : on remplace simplement
        // la playlist du Player dans cette session stable. Cela évite les blocages de bind/release
        // et les ANR au démarrage.

        // Notification/channel dédiés à l'audio, distincts de ceux de la vidéo (cf. constantes
        // ci-dessus) pour que les deux notifications coexistent sans se remplacer l'une l'autre.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(fr.retrospare.blazeplayer.R.string.notif_channel_audio)
                .build()
        )

        val initialBundle = createAudioPlayer()
        val exoPlayer = initialBundle.player
        pcmAudioProcessor = initialBundle.processor
        player = exoPlayer
        sessionPlayer = exoPlayer
        attachCorePlayerListener(exoPlayer)
        attachSessionStateListener(exoPlayer)
        restoreEqualizerForPlayer(exoPlayer)
        applyAudioProSettings(exoPlayer)
        activeAudioPipelineSignature = currentAudioPipelineSignature()

        val openIntent = PendingIntent.getActivity(
            this, 2001,
            Intent(this, fr.retrospare.blazeplayer.BlazeAudioLauncherActivity::class.java).apply {
                action = "fr.retrospare.blazeplayer.action.OPEN_BLAZE_AUDIO_FROM_NOTIFICATION"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("openBlazeAudio", true)
                putExtra("requestedTab", 4)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setId("BlazeAudio")
            .setSessionActivity(openIntent)
            .setCallback(SessionCallback())
            .build()

        resolveInitialAccessAsync()
    }

    private fun resolveInitialAccessAsync() {
        serviceScope.launch {
            val state = runCatching { userRepository.ensureTrialStarted() }
                .onFailure { error ->
                    fr.retrospare.blazeplayer.debug.CrashReporter.log(
                        applicationContext,
                        "Initial Pro+ access check failed",
                        error
                    )
                }
                .getOrNull()
            withContext(Dispatchers.Main.immediate) {
                if (state == null || !state.hasProPlusAccess) {
                    accessResolved = true
                    hasAudioAccess = false
                    stopForMissingAudioAccess("initial access denied")
                    return@withContext
                }
                applyResolvedAccessState(state)

                // Si une party était hébergée avant que le service ne soit recréé, on ne la
                // redémarre qu'après confirmation asynchrone du droit Pro+.
                if (BlazePartyVoteManager.isActive(applicationContext) &&
                    BlazePartyVoteManager.isHost(applicationContext)) {
                    BlazePartyVoteManager.getHostToken(applicationContext)?.let { startPartyHostServer(it) }
                }
            }
        }
    }

    private fun applyResolvedAccessState(
        state: fr.retrospare.blazeplayer.data.repository.SubscriptionAccessState,
        scheduleExpiry: Boolean = true
    ) {
        accessResolved = true
        hasAudioAccess = state.hasProPlusAccess
        if (!scheduleExpiry) return

        accessExpiryJob?.cancel()
        accessExpiryJob = null
        if (state.hasProPlusAccess && state.isTrialActive && !state.isProPlusPurchased) {
            val remaining = (state.trialEndMillis - state.evaluatedAtMillis).coerceAtLeast(1L)
            accessExpiryJob = serviceScope.launch {
                delay(remaining)
                val refreshed = runCatching { userRepository.currentAccessState() }.getOrNull()
                withContext(Dispatchers.Main.immediate) {
                    if (refreshed == null) {
                        accessResolved = true
                        hasAudioAccess = false
                        stopForMissingAudioAccess("trial expiry check failed")
                    } else {
                        applyResolvedAccessState(refreshed, scheduleExpiry = false)
                        if (!refreshed.hasProPlusAccess) {
                            stopForMissingAudioAccess("Pro+ trial expired")
                        }
                    }
                }
            }
        }
    }

    private fun stopForMissingAudioAccess(reason: String) {
        android.util.Log.w("BlazePlayerService", "Stopping audio service: $reason")
        hasAudioAccess = false
        isAudioPlaybackActive = false
        isAudioSessionActive = false
        cancelPendingAudioLoad(reason)
        runCatching { sessionPlayer?.pause() }
        runCatching { sessionPlayer?.clearMediaItems() }
        stopPartyHostServer()
        stopSelf()
    }

    private fun runAudioActionWhenAllowed(actionName: String, action: () -> Unit) {
        // Une fois l'accès résolu, une commande utilisateur ne doit jamais attendre une nouvelle
        // lecture DataStore avant d'atteindre le Player. Le contrôle d'expiration est rafraîchi en
        // arrière-plan ; le dernier état connu reste la réponse immédiate de l'UI.
        if (accessResolved && hasAudioAccess) {
            action()
            refreshAccessWithoutBlocking()
            return
        }

        serviceScope.launch {
            val state = runCatching { userRepository.ensureTrialStarted() }
                .onFailure { error ->
                    fr.retrospare.blazeplayer.debug.CrashReporter.log(
                        applicationContext,
                        "Pro+ access check failed for $actionName",
                        error
                    )
                }
                .getOrNull()
            withContext(Dispatchers.Main.immediate) {
                if (state == null || !state.hasProPlusAccess) {
                    accessResolved = true
                    hasAudioAccess = false
                    stopForMissingAudioAccess("access denied for $actionName")
                    return@withContext
                }
                applyResolvedAccessState(state)
                action()
            }
        }
    }

    /**
     * Variante réservée aux demandes de lecture. Chaque clic reçoit une génération ; si plusieurs
     * demandes arrivent pendant la toute première résolution d'accès, seule la plus récente est
     * exécutée. Une ancienne demande ne peut donc pas démarrer plusieurs secondes plus tard et
     * écraser le dernier choix de l'utilisateur.
     */
    private fun runPlaybackActionWhenAllowed(actionName: String, action: () -> Unit) {
        val generation = playbackRequestGeneration.incrementAndGet()
        if (accessResolved && hasAudioAccess) {
            if (generation == playbackRequestGeneration.get()) action()
            refreshAccessWithoutBlocking()
            return
        }

        serviceScope.launch {
            val state = runCatching { userRepository.ensureTrialStarted() }
                .onFailure { error ->
                    fr.retrospare.blazeplayer.debug.CrashReporter.log(
                        applicationContext,
                        "Playback Pro+ access check failed for $actionName",
                        error
                    )
                }
                .getOrNull()
            withContext(Dispatchers.Main.immediate) {
                if (generation != playbackRequestGeneration.get()) return@withContext
                if (state == null || !state.hasProPlusAccess) {
                    accessResolved = true
                    hasAudioAccess = false
                    stopForMissingAudioAccess("playback access denied for $actionName")
                    return@withContext
                }
                applyResolvedAccessState(state)
                action()
            }
        }
    }

    private fun runSessionActionWhenAllowed(
        actionName: String,
        action: () -> SessionResult
    ): ListenableFuture<SessionResult> {
        if (accessResolved && hasAudioAccess) {
            refreshAccessWithoutBlocking()
            return Futures.immediateFuture(runCatching(action).getOrElse { error ->
                fr.retrospare.blazeplayer.debug.CrashReporter.log(
                    applicationContext,
                    "Audio session command failed: $actionName",
                    error
                )
                SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
            })
        }

        val future = SettableFuture.create<SessionResult>()
        serviceScope.launch {
            val state = runCatching { userRepository.ensureTrialStarted() }
                .onFailure { error ->
                    fr.retrospare.blazeplayer.debug.CrashReporter.log(
                        applicationContext,
                        "Session Pro+ access check failed for $actionName",
                        error
                    )
                }
                .getOrNull()
            withContext(Dispatchers.Main.immediate) {
                if (state == null || !state.hasProPlusAccess) {
                    accessResolved = true
                    hasAudioAccess = false
                    future.set(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
                    stopForMissingAudioAccess("session access denied for $actionName")
                } else {
                    applyResolvedAccessState(state)
                    future.set(runCatching(action).getOrElse { error ->
                        fr.retrospare.blazeplayer.debug.CrashReporter.log(
                            applicationContext,
                            "Audio session command failed: $actionName",
                            error
                        )
                        SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
                    })
                }
            }
        }
        return future
    }

    private fun runSessionPlaybackActionWhenAllowed(
        actionName: String,
        action: () -> SessionResult
    ): ListenableFuture<SessionResult> {
        val generation = playbackRequestGeneration.incrementAndGet()
        if (accessResolved && hasAudioAccess) {
            refreshAccessWithoutBlocking()
            if (generation != playbackRequestGeneration.get()) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(runCatching(action).getOrElse { error ->
                fr.retrospare.blazeplayer.debug.CrashReporter.log(
                    applicationContext,
                    "Audio playback session command failed: $actionName",
                    error
                )
                SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
            })
        }

        val future = SettableFuture.create<SessionResult>()
        serviceScope.launch {
            val state = runCatching { userRepository.ensureTrialStarted() }
                .onFailure { error ->
                    fr.retrospare.blazeplayer.debug.CrashReporter.log(
                        applicationContext,
                        "Playback session Pro+ access check failed for $actionName",
                        error
                    )
                }
                .getOrNull()
            withContext(Dispatchers.Main.immediate) {
                if (generation != playbackRequestGeneration.get()) {
                    future.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    return@withContext
                }
                if (state == null || !state.hasProPlusAccess) {
                    accessResolved = true
                    hasAudioAccess = false
                    future.set(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
                    stopForMissingAudioAccess("playback session access denied for $actionName")
                } else {
                    applyResolvedAccessState(state)
                    future.set(runCatching(action).getOrElse { error ->
                        fr.retrospare.blazeplayer.debug.CrashReporter.log(
                            applicationContext,
                            "Audio playback session command failed: $actionName",
                            error
                        )
                        SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
                    })
                }
            }
        }
        return future
    }

    private fun refreshAccessWithoutBlocking() {
        serviceScope.launch {
            val state = runCatching { userRepository.currentAccessState() }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                applyResolvedAccessState(state)
                if (!state.hasProPlusAccess) stopForMissingAudioAccess("background access refresh denied")
            }
        }
    }

    private fun createAudioPlayer(): AudioPlayerBundle {
        val soundSettingsEnabled = eqPrefs.getBoolean(EqualizerManager.KEY_EQ_ENABLED, true)
        val useFloatOutput = AudioProSettings.shouldUseFloatOutput(audioProValues, soundSettingsEnabled)
        val processor = if (soundSettingsEnabled && !useFloatOutput) {
            BlazePcmAudioProcessor(this)
        } else {
            null
        }

        val dataSourceFactory = BlazeDataSourceFactory(this, SmbDataSource.OWNER_AUDIO)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink? {
                val builder = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(useFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                if (processor != null) {
                    builder.setAudioProcessors(arrayOf<AudioProcessor>(processor))
                } else {
                    builder.setAudioProcessors(emptyArray<AudioProcessor>())
                }
                return builder.build()
            }
        }
            .setEnableAudioFloatOutput(useFloatOutput)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 64 * 1024))
            .setBufferDurationsMs(
                /* minBufferMs = */ 45_000,
                /* maxBufferMs = */ 180_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 10_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15_000, true)
            .build()

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        return AudioPlayerBundle(exoPlayer, processor)
    }

    /**
     * Annule tout travail de lecture précédent avant une sélection explicite de l'utilisateur.
     * Le point essentiel est l'interruption du DataSource SMB actif : sans elle, un read() smbj
     * bloqué pouvait conserver le Loader Media3 jusqu'au timeout de 120 secondes, puis lancer
     * tardivement le dernier titre cliqué.
     */
    private fun cancelPendingAudioLoad(reason: String) {
        android.util.Log.i("BlazePlayerService", "Cancelling pending audio load: $reason")
        autoAdvanceGeneration++
        autoAdvanceHandler.removeCallbacksAndMessages(null)
        smbRecoveryHandler.removeCallbacksAndMessages(null)
        smbStallGeneration++
        smbStallHandler.removeCallbacksAndMessages(null)
        ignoreSmbErrorsUntilMs = android.os.SystemClock.elapsedRealtime() + 25_000L
        SmbDataSource.cancelActiveReads(SmbDataSource.OWNER_AUDIO)
    }

    private fun isExpectedSmbCancellation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is java.io.InterruptedIOException) return true
            val message = current.message.orEmpty()
            if (message.contains("SMB playback read cancelled", ignoreCase = true) ||
                message.contains("SMB playback opening cancelled", ignoreCase = true)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun startMediaItemsImmediately(
        target: Player,
        mediaItems: List<MediaItem>,
        index: Int,
        positionMs: Long = 0L,
        reason: String
    ) {
        if (mediaItems.isEmpty()) return
        val safeIndex = index.coerceIn(0, mediaItems.lastIndex)
        cancelPendingAudioLoad(reason)
        smbRecoveryPath = originalPathFromItem(mediaItems[safeIndex])
        smbRecoveryAttempts = 0

        // Un stop + remplacement de timeline force Media3 à abandonner l'ancien MediaSource et à
        // créer un nouveau DataSource. C'est volontairement plus fort qu'un simple seekTo(), qui
        // ne suffit pas lorsqu'un Loader réseau est encore suspendu.
        target.playWhenReady = false
        runCatching { target.stop() }
        target.setMediaItems(mediaItems, safeIndex, positionMs.coerceAtLeast(0L))
        isAudioSessionActive = true
        target.prepare()
        target.playWhenReady = true
        target.play()
    }

    private fun playQueueIndexFromStart(index: Int): Boolean {
        val target = sessionPlayer ?: player ?: return false
        if (index !in 0 until target.mediaItemCount) return false
        val items = (0 until target.mediaItemCount).map { target.getMediaItemAt(it) }
        startMediaItemsImmediately(
            target = target,
            mediaItems = items,
            index = index,
            positionMs = 0L,
            reason = "manual queue selection"
        )
        persistAudioQueue()
        return true
    }

    private fun updateSmbStallWatchdog(exoPlayer: ExoPlayer, playbackState: Int) {
        smbStallGeneration++
        val generation = smbStallGeneration
        smbStallHandler.removeCallbacksAndMessages(null)

        if (playbackState != Player.STATE_BUFFERING || !exoPlayer.playWhenReady) return
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val path = originalPathFromItem(mediaItem)
        if (!path.startsWith("smb://", ignoreCase = true)) return

        val observedIndex = exoPlayer.currentMediaItemIndex
        val observedPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
        smbStallHandler.postDelayed({
            if (generation != smbStallGeneration || player !== exoPlayer) return@postDelayed
            if (exoPlayer.playbackState != Player.STATE_BUFFERING || !exoPlayer.playWhenReady) return@postDelayed
            if (exoPlayer.currentMediaItemIndex != observedIndex) return@postDelayed
            if (originalPathFromItem(exoPlayer.currentMediaItem ?: return@postDelayed) != path) return@postDelayed
            if (exoPlayer.currentPosition > observedPosition + 500L) return@postDelayed
            if (smbRecoveryAttempts >= MAX_SMB_PLAYBACK_RECOVERY_ATTEMPTS) return@postDelayed

            val attempt = ++smbRecoveryAttempts
            android.util.Log.w(
                "BlazePlayerService",
                "SMB stall watchdog recovery $attempt/$MAX_SMB_PLAYBACK_RECOVERY_ATTEMPTS at ${observedPosition}ms"
            )
            val items = (0 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it) }
            if (items.isEmpty()) return@postDelayed

            SmbDataSource.cancelActiveReads(SmbDataSource.OWNER_AUDIO)
            exoPlayer.playWhenReady = false
            runCatching { exoPlayer.stop() }
            exoPlayer.setMediaItems(
                items,
                observedIndex.coerceIn(0, items.lastIndex),
                observedPosition
            )
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }, SMB_STALL_WATCHDOG_MS)
    }

    private fun attachCorePlayerListener(exoPlayer: ExoPlayer) {
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val ignoredManualAbort = error.errorCodeName.startsWith("ERROR_CODE_IO_") &&
                    isExpectedSmbCancellation(error) &&
                    android.os.SystemClock.elapsedRealtime() < ignoreSmbErrorsUntilMs
                if (ignoredManualAbort) {
                    android.util.Log.i(
                        "BlazePlayerService",
                        "Ignoring expected SMB error caused by manual stream cancellation"
                    )
                    return
                }
                fr.retrospare.blazeplayer.debug.CrashReporter.log(
                    applicationContext,
                    "Audio player error: code=${error.errorCodeName}",
                    error
                )
                scheduleSmbPlaybackRecovery(exoPlayer, error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                    android.util.Log.i("BlazePlayerService", "Audio buffering…")
                } else if (playbackState == androidx.media3.common.Player.STATE_READY &&
                    exoPlayer.currentPosition > 5_000L) {
                    // Une lecture redevenue stable récupère son budget de réparation pour une
                    // éventuelle coupure réseau bien plus tard dans le même morceau.
                    smbRecoveryAttempts = 0
                }
                updateSmbStallWatchdog(exoPlayer, playbackState)
                isAudioPlaybackActive = exoPlayer.playWhenReady &&
                    playbackState != androidx.media3.common.Player.STATE_IDLE &&
                    playbackState != androidx.media3.common.Player.STATE_ENDED
            }

            override fun onEvents(
                player: androidx.media3.common.Player,
                events: androidx.media3.common.Player.Events
            ) {
                if (events.contains(androidx.media3.common.Player.EVENT_AUDIO_SESSION_ID)) {
                    restoreEqualizerForPlayer(exoPlayer)
                }
            }
        })
    }

    private fun scheduleSmbPlaybackRecovery(
        exoPlayer: ExoPlayer,
        error: androidx.media3.common.PlaybackException
    ) {
        if (!error.errorCodeName.startsWith("ERROR_CODE_IO_")) return
        smbStallGeneration++
        smbStallHandler.removeCallbacksAndMessages(null)
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val path = originalPathFromItem(mediaItem)
        if (!path.startsWith("smb://", ignoreCase = true)) return

        if (path != smbRecoveryPath) {
            smbRecoveryPath = path
            smbRecoveryAttempts = 0
        }
        if (smbRecoveryAttempts >= MAX_SMB_PLAYBACK_RECOVERY_ATTEMPTS) return

        val attempt = ++smbRecoveryAttempts
        val mediaIndex = exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
        val resumePosition = exoPlayer.currentPosition.coerceAtLeast(0L)
        val resumePlayback = exoPlayer.playWhenReady || isAudioPlaybackActive
        android.util.Log.w(
            "BlazePlayerService",
            "SMB playback recovery $attempt/$MAX_SMB_PLAYBACK_RECOVERY_ATTEMPTS at ${resumePosition}ms"
        )

        smbRecoveryHandler.postDelayed({
            val current = exoPlayer.currentMediaItem ?: return@postDelayed
            if (originalPathFromItem(current) != path) return@postDelayed
            runCatching {
                val items = (0 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it) }
                if (items.isEmpty()) return@runCatching
                SmbDataSource.cancelActiveReads(SmbDataSource.OWNER_AUDIO)
                exoPlayer.playWhenReady = false
                exoPlayer.stop()
                exoPlayer.setMediaItems(
                    items,
                    mediaIndex.coerceIn(0, items.lastIndex),
                    resumePosition
                )
                exoPlayer.prepare()
                exoPlayer.playWhenReady = resumePlayback
                if (resumePlayback) exoPlayer.play()
            }.onFailure { recoveryError ->
                fr.retrospare.blazeplayer.debug.CrashReporter.log(
                    applicationContext,
                    "SMB audio recovery failed",
                    recoveryError
                )
            }
        }, SMB_PLAYBACK_RECOVERY_DELAY_MS)
    }

    private fun attachSessionStateListener(exoPlayer: ExoPlayer) {
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                isAudioSessionActive = exoPlayer.mediaItemCount > 0
                autoAdvanceGeneration++
                autoAdvanceHandler.removeCallbacksAndMessages(null)
                val transitionedPath = mediaItem?.let { originalPathFromItem(it) }.orEmpty()
                if (transitionedPath != smbRecoveryPath) {
                    smbRecoveryPath = transitionedPath
                    smbRecoveryAttempts = 0
                }
                persistAudioQueue()
                prepareReplayGainForItem(mediaItem)
                val lyricsPath = mediaItem?.let { BlazePartyQueue.originalPathOf(it) }.orEmpty()
                if (lyricsPath.isNotBlank() && audioProValues.syncedLyrics) {
                    serviceScope.launch {
                        AudioLocalEnhancements.findLocalLyricsData(applicationContext, lyricsPath)
                    }
                }
                if (!crossfadeInProgress && audioProValues.crossfade) {
                    fadePlayerTo(
                        AudioProSettings.playerVolume(audioProValues, currentReplayGainDb),
                        (audioProValues.crossfadeDurationSec * 450L).coerceIn(180L, 1500L)
                    )
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isAudioPlaybackActive = exoPlayer.playWhenReady &&
                    exoPlayer.playbackState != androidx.media3.common.Player.STATE_IDLE &&
                    exoPlayer.playbackState != androidx.media3.common.Player.STATE_ENDED
                scheduleCrossfadeCheck()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                isAudioPlaybackActive = playWhenReady &&
                    exoPlayer.playbackState != androidx.media3.common.Player.STATE_IDLE &&
                    exoPlayer.playbackState != androidx.media3.common.Player.STATE_ENDED
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    scheduleAudioAutoAdvance(exoPlayer)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int
            ) {
                if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK) {
                    // Un clic manuel dans la file doit toujours prendre la priorité sur le
                    // garde-fou d'auto-avance éventuellement programmé à la fin du titre.
                    autoAdvanceGeneration++
                    autoAdvanceHandler.removeCallbacksAndMessages(null)
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val mediaItem = exoPlayer.currentMediaItem ?: return
                val path = BlazePartyQueue.originalPathOf(mediaItem)
                if (path.isBlank()) return

                var bitrate = 0L
                loop@ for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_AUDIO) continue
                    for (index in 0 until group.length) {
                        if (!group.isTrackSelected(index)) continue
                        val format = group.getTrackFormat(index)
                        bitrate = when {
                            format.averageBitrate > 0 -> format.averageBitrate.toLong()
                            format.peakBitrate > 0 -> format.peakBitrate.toLong()
                            else -> 0L
                        }
                        break@loop
                    }
                }

                val extension = mediaItem.mediaMetadata.extras
                    ?.getString(AudioRepository.EXTRA_CONTAINER_EXTENSION)
                    .orEmpty()
                    .ifBlank {
                        path.substringBefore('?').substringBefore('#')
                            .substringAfterLast('.', "").uppercase()
                    }
                val durationSeconds = exoPlayer.duration
                    .takeIf { it > 0L }
                    ?.let { (it + 500L) / 1000L }
                    ?: 0L
                val lossless = extension.uppercase() in setOf("FLAC", "WAV", "ALAC", "APE", "AIFF", "WV")
                if (bitrate <= 0L && durationSeconds <= 0L && !lossless) return

                val technicalInfo = AudioTechnicalInfo(
                    duration = durationSeconds,
                    bitrate = bitrate,
                    extension = extension,
                    isLossless = lossless
                )
                AudioMetadataExtractor.putMemoryCached(path, technicalInfo)
                serviceScope.launch(Dispatchers.IO) {
                    AudioMetadataExtractor.putCached(applicationContext, path, technicalInfo)
                }
            }

            override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                isAudioSessionActive = player.mediaItemCount > 0
                if (events.contains(androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(androidx.media3.common.Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                    events.contains(androidx.media3.common.Player.EVENT_REPEAT_MODE_CHANGED) ||
                    events.contains(androidx.media3.common.Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                    persistAudioQueue()
                    scheduleCrossfadeCheck()
                }
            }
        })
    }

    /**
     * Media3 enchaîne normalement une timeline tout seul. Ce garde-fou couvre les files qui sont
     * modifiées au moment précis où le morceau se termine, notamment Blaze Party qui recalcule
     * l'ordre suivant selon les votes. On laisse quelques centaines de millisecondes à la timeline
     * pour recevoir le prochain élément, puis on le lance explicitement si le réglage est actif.
     */
    private fun scheduleAudioAutoAdvance(exoPlayer: ExoPlayer) {
        if (!autoPlayNextEnabled) return
        val generation = ++autoAdvanceGeneration
        val task = object : Runnable {
            private var attempt = 0

            override fun run() {
                if (generation != autoAdvanceGeneration || player !== exoPlayer || !autoPlayNextEnabled) return
                if (exoPlayer.playbackState != androidx.media3.common.Player.STATE_ENDED) return

                val hasNext = runCatching { exoPlayer.hasNextMediaItem() }.getOrDefault(false)
                if (hasNext) {
                    runCatching {
                        exoPlayer.seekToNextMediaItem()
                        if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                            exoPlayer.prepare()
                        }
                        exoPlayer.playWhenReady = true
                        exoPlayer.play()
                    }.onFailure { error ->
                        fr.retrospare.blazeplayer.debug.CrashReporter.log(
                            applicationContext,
                            "Automatic audio queue advance failed",
                            error
                        )
                    }
                    return
                }

                attempt++
                if (attempt < 8) autoAdvanceHandler.postDelayed(this, 125L)
            }
        }
        autoAdvanceHandler.post(task)
    }

    private fun currentAudioPipelineSignature(): String {
        val soundSettingsEnabled = eqPrefs.getBoolean(EqualizerManager.KEY_EQ_ENABLED, true)
        return "${audioProValues.outputMode}|${audioProValues.hiRes}|$soundSettingsEnabled"
    }

    private fun scheduleAudioPipelineRebuild() {
        if (!::eqPrefs.isInitialized || !::audioProPrefs.isInitialized) return
        audioPipelineHandler.removeCallbacksAndMessages(null)
        audioPipelineHandler.postDelayed({ rebuildAudioPlayerForPipelineIfNeeded() }, 180L)
    }

    private fun rebuildAudioPlayerForPipelineIfNeeded() {
        if (rebuildingAudioPipeline) return
        val desiredSignature = currentAudioPipelineSignature()
        if (desiredSignature == activeAudioPipelineSignature) return
        val oldPlayer = player ?: run {
            activeAudioPipelineSignature = desiredSignature
            return
        }

        rebuildingAudioPipeline = true
        var newBundle: AudioPlayerBundle? = null
        try {
            val mediaItems = (0 until oldPlayer.mediaItemCount).map { oldPlayer.getMediaItemAt(it) }
            val oldIndex = oldPlayer.currentMediaItemIndex.coerceAtLeast(0)
            val oldPosition = oldPlayer.currentPosition.coerceAtLeast(0L)
            val oldPlayWhenReady = oldPlayer.playWhenReady
            val oldRepeatMode = oldPlayer.repeatMode
            val oldShuffle = oldPlayer.shuffleModeEnabled
            val oldPlaybackParameters = oldPlayer.playbackParameters
            val oldProcessor = pcmAudioProcessor

            // Construire et préparer la nouvelle chaîne avant de toucher à celle qui joue encore.
            // En cas d'échec matériel/codec, l'ancien Player reste donc entièrement opérationnel.
            newBundle = createAudioPlayer()
            val newPlayer = newBundle.player
            // L'offload/direct doit être demandé avant la préparation pour que la première
            // sélection de piste puisse choisir immédiatement le bon chemin de sortie.
            tryApplyHighQualityOutput(newPlayer)
            newPlayer.repeatMode = oldRepeatMode
            newPlayer.shuffleModeEnabled = oldShuffle
            newPlayer.playbackParameters = oldPlaybackParameters
            if (mediaItems.isNotEmpty()) {
                newPlayer.setMediaItems(
                    mediaItems,
                    oldIndex.coerceIn(0, mediaItems.lastIndex),
                    oldPosition
                )
            }
            newPlayer.playWhenReady = false
            if (mediaItems.isNotEmpty()) {
                // Préparer sans démarrer : les erreurs de format/sink surviennent avant le swap et
                // la lecture en cours reste intacte si ce mode n'est pas accepté par l'appareil.
                newPlayer.prepare()
            }

            // MediaSession valide notamment que les deux Players utilisent le même looper. Tant que
            // ce swap n'a pas réussi, aucune référence globale ni aucun effet de l'ancien moteur
            // n'est libéré.
            val session = mediaSession ?: error("Audio MediaSession unavailable during pipeline rebuild")
            session.setPlayer(newPlayer)
            player = newPlayer
            sessionPlayer = newPlayer
            isAudioSessionActive = newPlayer.mediaItemCount > 0
            pcmAudioProcessor = newBundle.processor
            attachCorePlayerListener(newPlayer)
            attachSessionStateListener(newPlayer)

            try { oldPlayer.pause() } catch (_: Exception) {}
            try { oldProcessor?.releaseSettings() } catch (_: Exception) {}
            try { loudnessEnhancer?.release() } catch (_: Exception) {}
            loudnessEnhancer = null
            loudnessEnhancerSessionId = 0
            try { eqManager?.release() } catch (_: Exception) {}
            eqManager = null
            equalizerSessionId = 0
            eqApplyHandler.removeCallbacksAndMessages(null)

            if (mediaItems.isNotEmpty()) {
                newPlayer.playWhenReady = oldPlayWhenReady
            }
            restoreEqualizerForPlayer(newPlayer)
            applyAudioProSettings(newPlayer)
            activeAudioPipelineSignature = desiredSignature
            try { oldPlayer.release() } catch (_: Exception) {}
            newBundle = null // Le nouveau bundle appartient désormais au service.
            refreshReplayGainForCurrentItem()
        } catch (e: Exception) {
            // Si le swap n'a pas eu lieu, libérer uniquement la tentative et garder l'ancien Player.
            val attemptedPlayer = newBundle?.player
            if (attemptedPlayer != null && attemptedPlayer !== player) {
                try { newBundle?.processor?.releaseSettings() } catch (_: Exception) {}
                try { attemptedPlayer.release() } catch (_: Exception) {}
            }
            fr.retrospare.blazeplayer.debug.CrashReporter.log(
                applicationContext,
                "Audio output pipeline rebuild failed",
                e
            )
        } finally {
            rebuildingAudioPipeline = false
        }
    }

    /** Démarre (ou redémarre) le serveur HTTP local Blaze Party. Vit dans ce service — et non dans
     *  AudioPlayerFragment — précisément pour continuer à répondre aux invités quand l'utilisateur
     *  quitte l'écran audio, tant que la lecture (donc ce service) reste active.
     *
     *  Point important : NanoHTTPD traite les requêtes sur ses propres threads, alors que Media3
     *  impose de lire ExoPlayer depuis son thread applicatif principal. Les versions précédentes
     *  construisaient l'état Party directement dans le callback HTTP, ce qui pouvait générer des
     *  erreurs intermittentes sur /state après un join pourtant réussi. On maintient désormais un
     *  snapshot rafraîchi côté main thread et le serveur ne fait que le sérialiser. */
    private fun startPartyHostServer(token: String) {
        stopPartyHostServer()
        // Démarre avec un snapshot sûr (sans accès au Player hors thread), puis le runnable main
        // ci-dessous injecte l'état complet dès que le serveur est lancé.
        partyStateSnapshot = BlazePartyQueue.buildState(applicationContext, null)
        try {
            partyHostServer = PartyHostServer(
                token = token,
                stateProvider = { currentPartyStateSnapshot() },
                onVoteReceived = { path, nickname, add ->
                    if (add) {
                        BlazePartyVoteManager.addVote(applicationContext, path, nickname)
                    } else {
                        BlazePartyVoteManager.removeVote(applicationContext, path, nickname)
                    }
                    refreshPartyStateSnapshotAsync()
                },
                onGuestJoined = {
                    BlazePartyVoteManager.markGuestConnected(applicationContext)
                    refreshPartyStateSnapshotAsync()
                }
            ).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            acquirePartyWifiLock()
            partyStateHandler.removeCallbacks(partyStateRefreshRunnable)
            partyStateHandler.post(partyStateRefreshRunnable)
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Impossible de démarrer PartyHostServer", e)
            partyHostServer = null
        }
    }

    private fun stopPartyHostServer() {
        partyStateHandler.removeCallbacks(partyStateRefreshRunnable)
        releasePartyWifiLock()
        try { partyHostServer?.stop() } catch (_: Exception) {}
        partyHostServer = null
        partyStateSnapshot = null
    }

    @Suppress("DEPRECATION")
    private fun compatWifiLockMode(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }

    private fun acquirePartyWifiLock() {
        try {
            if (partyWifiLock?.isHeld == true) return
            val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
            partyWifiLock = wifi.createWifiLock(compatWifiLockMode(), "BlazePartyHostLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Blaze Party Wi-Fi lock failed", e)
            partyWifiLock = null
        }
    }

    private fun releasePartyWifiLock() {
        try { if (partyWifiLock?.isHeld == true) partyWifiLock?.release() } catch (_: Exception) {}
        partyWifiLock = null
    }

    private fun refreshPartyStateSnapshotAsync() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshPartyStateSnapshotNow()
        } else {
            partyStateHandler.post { refreshPartyStateSnapshotNow() }
        }
    }

    private fun refreshPartyStateSnapshotNow() {
        try {
            partyStateSnapshot = BlazePartyQueue.buildState(applicationContext, sessionPlayer)
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Blaze Party state snapshot failed", e)
            // On garde le dernier snapshot valide plutôt que de faire échouer /state : côté client,
            // une réponse vide/invalide était interprétée comme une perte définitive de l'hôte.
            if (partyStateSnapshot == null) {
                partyStateSnapshot = BlazePartyQueue.buildState(applicationContext, null)
            }
        }
    }

    private fun currentPartyStateSnapshot(): PartyState {
        // Si la playlist Party dédiée est disponible, elle peut être reconstruite sans toucher
        // ExoPlayer et reflète immédiatement les votes écrits par /vote.
        val safeState = BlazePartyQueue.buildState(applicationContext, null)
        if (safeState.tracks.isNotEmpty()) {
            // buildState(..., null) est sûr depuis le thread HTTP mais ne peut pas lire le morceau
            // courant du Player. On réinjecte donc le currentPath du dernier snapshot pris sur le
            // thread principal, indispensable côté client pour afficher le contour et le mini-EQ.
            // On fusionne aussi les métadonnées éventuellement présentes dans ce snapshot main
            // thread, pour ne pas perdre artiste/conteneur/durée si le cache persistant n'était
            // pas encore complet au moment du /state.
            val snapshotByPath = partyStateSnapshot?.tracks?.associateBy { it.path }.orEmpty()
            val mergedTracks = safeState.tracks.map { track ->
                val snap = snapshotByPath[track.path]
                if (snap == null) track else track.copy(
                    artist = track.artist.ifBlank { snap.artist },
                    title = track.title.ifBlank { snap.title },
                    extension = track.extension.ifBlank { snap.extension },
                    bitrate = if (track.bitrate > 0L) track.bitrate else snap.bitrate,
                    isLossless = track.isLossless || snap.isLossless,
                    durationMs = if (track.durationMs > 0L) track.durationMs else snap.durationMs
                )
            }
            val liveSnapshot = partyStateSnapshot
            return safeState.copy(
                tracks = mergedTracks,
                currentPath = safeState.currentPath ?: liveSnapshot?.currentPath,
                currentPositionMs = liveSnapshot?.currentPositionMs ?: safeState.currentPositionMs,
                currentDurationMs = liveSnapshot?.currentDurationMs ?: safeState.currentDurationMs,
                isPlaying = liveSnapshot?.isPlaying ?: safeState.isPlaying
            )
        }

        // Sinon, on sert le dernier snapshot pris sur le thread principal du player, en recalculant
        // seulement les votes depuis les SharedPreferences pour ne pas exposer une liste figée.
        return partyStateSnapshot?.let { state ->
            state.copy(
                tracks = state.tracks.map { track ->
                    track.copy(
                        votes = BlazePartyVoteManager.voteCount(applicationContext, track.path),
                        voters = BlazePartyVoteManager.votersFor(applicationContext, track.path)
                    )
                }.sortedWith(
                    compareBy<PartyTrack> { if (it.playedOrder > 0) 1 else 0 }
                        .thenByDescending { if (it.playedOrder == 0) it.votes else 0 }
                        .thenBy { it.playedOrder }
                ),
                hostNickname = BlazePartyVoteManager.getNickname(applicationContext)
            )
        } ?: PartyState(emptyList(), null, BlazePartyVoteManager.getNickname(applicationContext))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Évite qu'Android ne recrée un ancien service START_STICKY sans commande ni session audio.
        if (intent == null && !isAudioSessionActive) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PLAY_EXTERNAL_AUDIO -> {
                val path = intent.getStringExtra(EXTRA_EXTERNAL_AUDIO_PATH).orEmpty()
                val name = intent.getStringExtra(EXTRA_EXTERNAL_AUDIO_NAME).orEmpty().ifBlank {
                    android.net.Uri.parse(path).lastPathSegment?.substringAfterLast('/') ?: "Audio"
                }
                runPlaybackActionWhenAllowed(ACTION_PLAY_EXTERNAL_AUDIO) { replaceWithExternalAudio(path, name) }
                return START_STICKY
            }
            ACTION_PLAY_AUDIO_QUEUE -> {
                val paths = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_PATHS).orEmpty()
                val names = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_NAMES).orEmpty()
                val artworkPaths = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_ARTWORK_PATHS).orEmpty()
                val index = intent.getIntExtra(EXTRA_AUDIO_QUEUE_INDEX, 0)
                runPlaybackActionWhenAllowed(ACTION_PLAY_AUDIO_QUEUE) { playAudioQueue(paths, names, artworkPaths, index) }
                return START_STICKY
            }
            ACTION_APPEND_AUDIO_QUEUE -> {
                val paths = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_PATHS).orEmpty()
                val names = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_NAMES).orEmpty()
                val artworkPaths = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_ARTWORK_PATHS).orEmpty()
                runAudioActionWhenAllowed(ACTION_APPEND_AUDIO_QUEUE) { appendAudioQueue(paths, names, artworkPaths) }
                return START_STICKY
            }
            ACTION_APPEND_AUDIO_QUEUE_AND_PLAY -> {
                val paths = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_PATHS).orEmpty()
                val names = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_NAMES).orEmpty()
                val artworkPaths = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_ARTWORK_PATHS).orEmpty()
                val index = intent.getIntExtra(EXTRA_AUDIO_QUEUE_INDEX, 0)
                runPlaybackActionWhenAllowed(ACTION_APPEND_AUDIO_QUEUE_AND_PLAY) {
                    appendAudioQueueAndPlay(paths, names, artworkPaths, index)
                }
                return START_STICKY
            }
            ACTION_PARTY_START_HOST -> {
                val token = intent.getStringExtra(EXTRA_PARTY_TOKEN).orEmpty()
                if (token.isNotBlank()) {
                    runAudioActionWhenAllowed(ACTION_PARTY_START_HOST) { startPartyHostServer(token) }
                }
                return START_STICKY
            }
            ACTION_PARTY_STOP_HOST -> {
                stopPartyHostServer()
                return START_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun replaceWithExternalAudio(path: String, name: String) {
        if (path.isBlank()) return
        try {
            val p = sessionPlayer ?: player ?: return
            val item = AudioRepository.buildSimpleMediaItem(applicationContext, path, name)

            // ACTION_VIEW audio externe = lecture immédiate et prioritaire.
            // Conforme Media3 : on conserve la même MediaSessionService et on remplace la queue
            // du Player, au lieu de tuer/rebinder le service ou de passer par le Fragment.
            startMediaItemsImmediately(
                target = p,
                mediaItems = listOf(item),
                index = 0,
                positionMs = 0L,
                reason = "external audio selection"
            )
            persistAudioQueue()
            enrichExternalAudioMetadataAsync(path, name)
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Replace external audio failed for $path", e)
        }
    }


    private fun playAudioQueue(paths: List<String>, names: List<String>, artworkPaths: List<String>, startIndex: Int) {
        val clean = paths.mapIndexedNotNull { index, rawPath ->
            val path = rawPath.trim()
            if (path.isBlank() || !AudioRepository.isAudioExtension(path)) return@mapIndexedNotNull null
            val name = names.getOrNull(index).orEmpty().ifBlank {
                android.net.Uri.parse(path).lastPathSegment?.substringAfterLast('/')
                    ?: path.substringBefore('?').substringAfterLast('/').ifBlank { "Audio" }
            }
            PlaylistItem(path, name, artworkPaths.getOrNull(index).orEmpty())
        }.distinctBy { it.path }
        if (clean.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, clean.size - 1)
        AudioRepository.save(applicationContext, clean, safeIndex, 0L, Player.REPEAT_MODE_OFF, false)

        try {
            val p = sessionPlayer ?: player ?: return
            val mediaItems = clean.map { AudioRepository.buildSimpleMediaItem(applicationContext, it.path, it.name, it.artworkPath) }
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.shuffleModeEnabled = false
            startMediaItemsImmediately(
                target = p,
                mediaItems = mediaItems,
                index = safeIndex,
                positionMs = 0L,
                reason = "play audio queue"
            )
            persistAudioQueue()
            // Chargement non bloquant de la pochette du morceau courant pour la notification/lockscreen.
            clean.getOrNull(safeIndex)?.let { enrichExternalAudioMetadataAsync(it.path, it.name, it.artworkPath) }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Play audio queue failed", e)
        }
    }


    private fun appendAudioQueueAndPlay(paths: List<String>, names: List<String>, artworkPaths: List<String>, startIndex: Int) {
        val clean = paths.mapIndexedNotNull { index, rawPath ->
            val path = rawPath.trim()
            if (path.isBlank() || !AudioRepository.isAudioExtension(path)) return@mapIndexedNotNull null
            val name = names.getOrNull(index).orEmpty().ifBlank {
                android.net.Uri.parse(path).lastPathSegment?.substringAfterLast('/')
                    ?: path.substringBefore('?').substringAfterLast('/').ifBlank { "Audio" }
            }
            PlaylistItem(path, name, artworkPaths.getOrNull(index).orEmpty())
        }.distinctBy { it.path }
        if (clean.isEmpty()) return
        val targetItem = clean[startIndex.coerceIn(0, clean.size - 1)]

        try {
            val p = sessionPlayer ?: player
            if (p != null && p.mediaItemCount > 0) {
                val existingPaths = (0 until p.mediaItemCount).map { originalPathFromItem(p.getMediaItemAt(it)) }
                val existingSet = existingPaths.toMutableSet()
                val additions = clean.filter { existingSet.add(it.path) }
                val insertionStart = p.mediaItemCount
                if (additions.isNotEmpty()) {
                    p.addMediaItems(additions.map { AudioRepository.buildSimpleMediaItem(applicationContext, it.path, it.name, it.artworkPath) })
                    isAudioSessionActive = p.mediaItemCount > 0
                }
                val targetIndex = existingPaths.indexOf(targetItem.path).takeIf { it >= 0 }
                    ?: additions.indexOfFirst { it.path == targetItem.path }.takeIf { it >= 0 }?.let { insertionStart + it }
                    ?: p.currentMediaItemIndex.coerceAtLeast(0)
                val currentItems = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }.toMutableList()
                if (targetItem.artworkPath.isNotBlank() && targetIndex in currentItems.indices) {
                    currentItems[targetIndex] = withPreferredArtworkPath(currentItems[targetIndex], targetItem.artworkPath)
                }
                startMediaItemsImmediately(
                    target = p,
                    mediaItems = currentItems,
                    index = targetIndex,
                    positionMs = 0L,
                    reason = "library track selection"
                )
                persistAudioQueue()
                enrichExternalAudioMetadataAsync(targetItem.path, targetItem.name, targetItem.artworkPath)
            } else {
                val savedState = AudioRepository.loadState(applicationContext)
                val existing = savedState.items.map { it.path }.toMutableSet()
                val additions = clean.filter { existing.add(it.path) }
                val merged = (savedState.items + additions).toMutableList()
                val existingTargetIndex = merged.indexOfFirst { it.path == targetItem.path }
                if (existingTargetIndex >= 0 && targetItem.artworkPath.isNotBlank()) {
                    val previous = merged[existingTargetIndex]
                    if (previous.artworkPath != targetItem.artworkPath) {
                        merged[existingTargetIndex] = previous.copy(artworkPath = targetItem.artworkPath)
                    }
                }
                if (merged.isEmpty()) return
                val targetIndex = savedState.items.indexOfFirst { it.path == targetItem.path }.takeIf { it >= 0 }
                    ?: additions.indexOfFirst { it.path == targetItem.path }.takeIf { it >= 0 }?.let { savedState.items.size + it }
                    ?: savedState.index.coerceIn(0, (merged.size - 1).coerceAtLeast(0))
                AudioRepository.save(applicationContext, merged, targetIndex, 0L, savedState.repeatMode, savedState.shuffle)
                val target = sessionPlayer ?: player
                if (target != null) {
                    val mediaItems = merged.map { AudioRepository.buildSimpleMediaItem(applicationContext, it.path, it.name, it.artworkPath) }
                    target.repeatMode = savedState.repeatMode
                    target.shuffleModeEnabled = savedState.shuffle
                    startMediaItemsImmediately(
                        target = target,
                        mediaItems = mediaItems,
                        index = targetIndex,
                        positionMs = 0L,
                        reason = "library queue restore and play"
                    )
                    persistAudioQueue()
                }
                enrichExternalAudioMetadataAsync(targetItem.path, targetItem.name, targetItem.artworkPath)
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Append and play audio queue failed", e)
        }
    }

    private fun appendAudioQueue(paths: List<String>, names: List<String>, artworkPaths: List<String>) {
        val clean = paths.mapIndexedNotNull { index, rawPath ->
            val path = rawPath.trim()
            if (path.isBlank() || !AudioRepository.isAudioExtension(path)) return@mapIndexedNotNull null
            val name = names.getOrNull(index).orEmpty().ifBlank {
                android.net.Uri.parse(path).lastPathSegment?.substringAfterLast('/')
                    ?: path.substringBefore('?').substringAfterLast('/').ifBlank { "Audio" }
            }
            PlaylistItem(path, name, artworkPaths.getOrNull(index).orEmpty())
        }.distinctBy { it.path }
        if (clean.isEmpty()) return

        try {
            val p = sessionPlayer ?: player
            if (p != null && p.mediaItemCount > 0) {
                val existingPaths = (0 until p.mediaItemCount)
                    .map { originalPathFromItem(p.getMediaItemAt(it)) }
                    .toMutableSet()
                val mediaItems = clean
                    .filter { existingPaths.add(it.path) }
                    .map { AudioRepository.buildSimpleMediaItem(applicationContext, it.path, it.name, it.artworkPath) }
                if (mediaItems.isNotEmpty()) {
                    p.addMediaItems(mediaItems)
                    isAudioSessionActive = p.mediaItemCount > 0
                    persistAudioQueue()
                    mediaItems.forEach { item ->
                        val itemPath = originalPathFromItem(item)
                        enrichExternalAudioMetadataAsync(
                            itemPath,
                            AudioLibraryHeuristics.fileNameFromPath(itemPath),
                            artworkPathFromItem(item)
                        )
                    }
                }
            } else {
                val savedState = AudioRepository.loadState(applicationContext)
                val existing = savedState.items.map { it.path }.toMutableSet()
                val merged = savedState.items + clean.filter { existing.add(it.path) }
                if (merged.isNotEmpty()) {
                    AudioRepository.save(
                        applicationContext,
                        merged,
                        savedState.index.coerceIn(0, (merged.size - 1).coerceAtLeast(0)),
                        savedState.positionMs,
                        savedState.repeatMode,
                        savedState.shuffle
                    )
                    val target = sessionPlayer ?: player
                    if (target != null && target.mediaItemCount == 0) {
                        val mediaItems = merged.map { AudioRepository.buildSimpleMediaItem(applicationContext, it.path, it.name, it.artworkPath) }
                        target.setMediaItems(mediaItems, savedState.index.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0)), savedState.positionMs)
                        isAudioSessionActive = mediaItems.isNotEmpty()
                        target.repeatMode = savedState.repeatMode
                        target.shuffleModeEnabled = savedState.shuffle
                        target.prepare()
                    }
                }
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Append audio queue failed", e)
            val savedState = AudioRepository.loadState(applicationContext)
            val existing = savedState.items.map { it.path }.toMutableSet()
            val merged = savedState.items + clean.filter { existing.add(it.path) }
            if (merged.isNotEmpty()) {
                AudioRepository.save(applicationContext, merged, savedState.index, savedState.positionMs, savedState.repeatMode, savedState.shuffle)
            }
        }
    }

    private fun enrichExternalAudioMetadataAsync(path: String, fallbackName: String, artworkPath: String = "") {
        if (path.isBlank()) return
        serviceScope.launch {
            val enriched = try {
                // Seule la pochette est extraite hors thread principal. Les textes de la
                // notification restent dérivés du nom de fichier et des dossiers.
                AudioRepository.buildMediaItemWithMetadata(applicationContext, path, fallbackName, artworkPath)
            } catch (e: Exception) {
                fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Audio notification metadata enrichment failed for $path", e)
                null
            } ?: return@launch

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val p = sessionPlayer ?: player ?: return@post
                    val current = p.currentMediaItem ?: return@post
                    if (current.mediaId != path) return@post
                    p.replaceMediaItem(p.currentMediaItemIndex, enriched)
                    persistAudioQueue()
                } catch (e: Exception) {
                    fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Apply audio notification metadata failed for $path", e)
                }
            }
        }
    }

    private fun restoreEqualizerForPlayer(exoPlayer: ExoPlayer, attempt: Int = 0) {
        val sessionId = exoPlayer.audioSessionId
        if (sessionId <= 0 && attempt < 10) {
            eqApplyHandler.postDelayed({ restoreEqualizerForPlayer(exoPlayer, attempt + 1) }, 250L)
            return
        }
        if (sessionId <= 0) return

        // Quand Réglages son est coupé, ne pas attacher d'Equalizer/BassBoost/Virtualizer à
        // l'AudioTrack. Même désactivés, ces AudioEffect peuvent empêcher certains appareils de
        // conserver une sortie directe/offload. ReplayGain reste appliqué
        // séparément via le volume du Player et, si nécessaire, LoudnessEnhancer.
        if (!eqPrefs.getBoolean(EqualizerManager.KEY_EQ_ENABLED, true)) {
            try { eqManager?.release() } catch (_: Exception) {}
            eqManager = null
            equalizerSessionId = 0
            applyLoudnessEnhancer(sessionId)
            return
        }

        if (eqManager != null && equalizerSessionId == sessionId) {
            applyLoudnessEnhancer(sessionId)
            return
        }
        try { eqManager?.release() } catch (_: Exception) {}
        eqManager = null
        equalizerSessionId = 0
        eqManager = try {
            EqualizerManager(sessionId, applicationContext).also {
                it.restoreLastSession()
                equalizerSessionId = sessionId
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Restore equalizer in audio service failed", e)
            null
        }
        applyLoudnessEnhancer(sessionId)
    }



    private fun applyAudioProSettings(target: ExoPlayer? = player) {
        val p = target ?: return
        try {
            val volume = AudioProSettings.playerVolume(audioProValues, currentReplayGainDb)
            if (!crossfadeInProgress) p.volume = volume
            trySetSkipSilence(p, !audioProValues.gapless)
            tryApplyHighQualityOutput(p)
            // Le préampli de l'égaliseur possède sa propre valeur persistante. Il ne doit pas
            // être écrasé à chaque changement d'un réglage Pro+ sans rapport avec l'EQ.
            applyLoudnessEnhancer(p.audioSessionId)
            scheduleCrossfadeCheck()
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Apply audio Pro+ settings failed", e)
        }
    }

    private fun trySetSkipSilence(exoPlayer: ExoPlayer, enabled: Boolean) {
        // Media3 expose setSkipSilenceEnabled selon les versions. Reflection = compat compilation.
        runCatching {
            exoPlayer.javaClass.methods.firstOrNull { it.name == "setSkipSilenceEnabled" && it.parameterTypes.size == 1 }
                ?.invoke(exoPlayer, enabled)
        }
    }

    private fun tryApplyHighQualityOutput(exoPlayer: ExoPlayer) {
        val soundSettingsEnabled = if (::eqPrefs.isInitialized) {
            eqPrefs.getBoolean(EqualizerManager.KEY_EQ_ENABLED, true)
        } else {
            true
        }
        val offloadMode = if (audioProValues.hiRes && !soundSettingsEnabled) {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        } else {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        }
        runCatching {
            val offloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(offloadMode)
                .setIsGaplessSupportRequired(audioProValues.gapless)
                .setIsSpeedChangeSupportRequired(false)
                .build()
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(offloadPreferences)
                .build()
        }.onFailure { error ->
            fr.retrospare.blazeplayer.debug.CrashReporter.log(
                applicationContext,
                "Audio offload preference unavailable",
                error
            )
        }
    }

    private fun refreshReplayGainForCurrentItem() {
        prepareReplayGainForItem((sessionPlayer ?: player)?.currentMediaItem)
    }

    private fun prepareReplayGainForItem(mediaItem: androidx.media3.common.MediaItem?) {
        if (mediaItem == null || audioProValues.replayGain == AudioProSettings.REPLAYGAIN_OFF) {
            currentReplayGainDb = 0f
            applyAudioProSettings()
            return
        }
        val path = originalPathFromItem(mediaItem)
        if (path.isBlank()) {
            currentReplayGainDb = 0f
            return
        }
        val albumKey = albumKeyFor(mediaItem)
        currentReplayGainDb = AudioLoudnessAnalyzer.cachedReplayGainDb(applicationContext, path, albumKey, audioProValues.replayGain)
        applyAudioProSettings()
        serviceScope.launch {
            val analyzed = AudioLoudnessAnalyzer.getOrAnalyze(applicationContext, path, albumKey)
            val gain = AudioLoudnessAnalyzer.replayGainDb(applicationContext, path, albumKey, audioProValues.replayGain, analyzed)
            Handler(Looper.getMainLooper()).post {
                val current = (sessionPlayer ?: player)?.currentMediaItem
                if (current != null && originalPathFromItem(current) == path) {
                    currentReplayGainDb = gain
                    applyAudioProSettings()
                }
            }
        }
    }

    private fun albumKeyFor(mediaItem: androidx.media3.common.MediaItem): String {
        val metadata = mediaItem.mediaMetadata
        val album = metadata.albumTitle?.toString().orEmpty().trim().lowercase()
        val artist = metadata.artist?.toString().orEmpty().trim().lowercase()
        return listOf(artist, album).filter { it.isNotBlank() }.joinToString("|")
    }

    private fun applyLoudnessEnhancer(sessionId: Int) {
        if (sessionId <= 0) return
        try {
            val eqLoudnessGain = eqManager
                ?.takeIf { it.isEnabled() && it.isLoudnessAvailable() }
                ?.getSavedLoudnessMillibels()
                ?: 0
            val targetGain = (
                AudioProSettings.loudnessTargetMillibels(audioProValues, currentReplayGainDb) +
                    eqLoudnessGain
                ).coerceIn(0, 1800)

            if (targetGain <= 0) {
                loudnessEnhancer?.enabled = false
                loudnessEnhancer?.release()
                loudnessEnhancer = null
                loudnessEnhancerSessionId = 0
                return
            }

            if (loudnessEnhancerSessionId != sessionId) {
                try { loudnessEnhancer?.release() } catch (_: Exception) {}
                loudnessEnhancer = null
                loudnessEnhancerSessionId = 0
            }
            val enhancer = loudnessEnhancer ?: LoudnessEnhancer(sessionId).also {
                loudnessEnhancer = it
                loudnessEnhancerSessionId = sessionId
            }
            enhancer.setTargetGain(targetGain)
            enhancer.enabled = true
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Loudness enhancer unavailable", e)
            try { loudnessEnhancer?.release() } catch (_: Exception) {}
            loudnessEnhancer = null
            loudnessEnhancerSessionId = 0
        }
    }

    private fun scheduleCrossfadeCheck() {
        crossfadeHandler.removeCallbacks(crossfadeRunnable)
        val p = player ?: return
        if (autoPlayNextEnabled && audioProValues.crossfade && audioProValues.crossfadeDurationSec > 0 && p.isPlaying) {
            crossfadeHandler.postDelayed(crossfadeRunnable, 350L)
        }
    }

    private fun checkCrossfadeWindow() {
        val p = player ?: return
        if (!autoPlayNextEnabled || crossfadeInProgress || !audioProValues.crossfade || !p.isPlaying) return
        val duration = p.duration
        if (duration <= 0L || duration == androidx.media3.common.C.TIME_UNSET) return
        if (!runCatching { p.hasNextMediaItem() }.getOrDefault(false)) return
        val fadeMs = (audioProValues.crossfadeDurationSec * 1000L).coerceIn(800L, 12_000L)
        val remaining = duration - p.currentPosition
        if (remaining in 1L..(fadeMs + 280L) && crossfadeIndex != p.currentMediaItemIndex) {
            startCrossfadeToNext(p, fadeMs)
        }
    }

    private fun startCrossfadeToNext(p: ExoPlayer, fadeMs: Long) {
        crossfadeInProgress = true
        crossfadeIndex = p.currentMediaItemIndex
        val half = (fadeMs / 2L).coerceAtLeast(320L)
        fadePlayerTo(0.03f, half)
        crossfadeHandler.postDelayed({
            try {
                if (player !== p) return@postDelayed
                if (runCatching { p.hasNextMediaItem() }.getOrDefault(false)) {
                    p.seekToNextMediaItem()
                    p.play()
                    fadePlayerTo(AudioProSettings.playerVolume(audioProValues, currentReplayGainDb), half)
                }
            } finally {
                crossfadeHandler.postDelayed({
                    crossfadeInProgress = false
                    scheduleCrossfadeCheck()
                }, half + 60L)
            }
        }, half)
    }

    private fun fadePlayerTo(targetVolume: Float, durationMs: Long) {
        val p = player ?: return
        try { playerVolumeAnimator?.cancel() } catch (_: Exception) {}
        val start = p.volume
        playerVolumeAnimator = ValueAnimator.ofFloat(start, targetVolume.coerceIn(0f, 1f)).apply {
            duration = durationMs.coerceAtLeast(120L)
            addUpdateListener { animator ->
                try { p.volume = animator.animatedValue as Float } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun isObsoleteLocalRelayUrl(value: String): Boolean =
        value.startsWith("http://") && value.contains(":8928/")

    private fun originalPathFromItem(mi: androidx.media3.common.MediaItem): String {
        val extras = mi.mediaMetadata.extras
        val fromExtras = extras?.getString("blaze_original_path")
            ?.takeIf { it.isNotBlank() && !isObsoleteLocalRelayUrl(it) && AudioRepository.isAudioExtension(it) }
        if (fromExtras != null) return fromExtras
        val fromMediaId = mi.mediaId.takeIf { it.isNotBlank() && !isObsoleteLocalRelayUrl(it) && AudioRepository.isAudioExtension(it) }
        if (fromMediaId != null) return fromMediaId
        return mi.localConfiguration?.uri?.toString()
            ?.takeIf { it.isNotBlank() && !isObsoleteLocalRelayUrl(it) && AudioRepository.isAudioExtension(it) }
            .orEmpty()
    }

    private fun artworkPathFromItem(mi: androidx.media3.common.MediaItem): String =
        mi.mediaMetadata.extras?.getString(AudioRepository.EXTRA_ARTWORK_PATH).orEmpty()

    private fun withPreferredArtworkPath(
        item: androidx.media3.common.MediaItem,
        artworkPath: String
    ): androidx.media3.common.MediaItem {
        if (artworkPath.isBlank() || artworkPathFromItem(item) == artworkPath) return item
        val extras = android.os.Bundle(item.mediaMetadata.extras ?: android.os.Bundle()).apply {
            putString(AudioRepository.EXTRA_ARTWORK_PATH, artworkPath)
        }
        val metadata = item.mediaMetadata.buildUpon().setExtras(extras).build()
        return item.buildUpon().setMediaMetadata(metadata).build()
    }

    private fun persistAudioQueue() {
        val p = sessionPlayer ?: player ?: return
        try {
            if (p.mediaItemCount <= 0) return
            val items = (0 until p.mediaItemCount).map { i ->
                val mi = p.getMediaItemAt(i)
                val path = originalPathFromItem(mi)
                val name = mi.mediaMetadata.extras?.getString("blaze_original_name").orEmpty().ifBlank {
                    AudioLibraryHeuristics.fileNameFromPath(path).ifBlank {
                        mi.localConfiguration?.uri?.lastPathSegment ?: path.substringAfterLast('/')
                    }
                }
                PlaylistItem(path, name, artworkPathFromItem(mi))
            }.filter { it.path.isNotBlank() && !isObsoleteLocalRelayUrl(it.path) && AudioRepository.isAudioExtension(it.path) }
            if (items.isNotEmpty()) {
                AudioRepository.save(
                    applicationContext,
                    items,
                    p.currentMediaItemIndex.coerceAtLeast(0),
                    p.currentPosition.coerceAtLeast(0L),
                    p.repeatMode,
                    p.shuffleModeEnabled
                )
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Persist audio queue failed", e)
        }
    }

    private inner class SessionCallback : MediaSession.Callback {

        // Balayer la notification déclenche automatiquement COMMAND_STOP côté Media3 (géré en
        // interne par DefaultMediaNotificationProvider), mais ça n'arrête PAS le service lui-même
        // (limitation connue de Media3) : la lecture pouvait donc continuer en arrière-plan alors
        // que la notification avait disparu. On intercepte précisément COMMAND_STOP ici plutôt que
        // de deviner via les transitions d'état du player (IDLE arrive aussi normalement pendant
        // le chargement, ce qui coupait le service à tort).
        @Deprecated("Deprecated by Media3, still needed to intercept COMMAND_STOP from the media notification.")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            if (playerCommand == androidx.media3.common.Player.COMMAND_STOP) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { stopSelf() }
                return SessionResult.RESULT_SUCCESS
            }
            if (accessResolved && !hasAudioAccess) {
                return SessionResult.RESULT_ERROR_PERMISSION_DENIED
            }
            // L'API Media3 impose une réponse synchrone ici. On autorise la commande avec le dernier
            // état connu, puis on relance immédiatement un contrôle asynchrone qui coupera le service
            // si l'essai vient d'expirer.
            refreshAccessWithoutBlocking()
            return SessionResult.RESULT_SUCCESS
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(COMMAND_GET_AUDIO_SESSION_ID, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_PLAY_EXTERNAL_AUDIO, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_PLAY_QUEUE_INDEX_FROM_START, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_PARTY_START_HOST, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_PARTY_STOP_HOST, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(
                sessionCommands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_GET_AUDIO_SESSION_ID) {
                return runSessionActionWhenAllowed(COMMAND_GET_AUDIO_SESSION_ID) {
                    val resultExtras = Bundle().apply {
                        putInt(EXTRA_AUDIO_SESSION_ID, player?.audioSessionId ?: 0)
                    }
                    SessionResult(SessionResult.RESULT_SUCCESS, resultExtras)
                }
            }
            if (customCommand.customAction == COMMAND_PLAY_EXTERNAL_AUDIO) {
                val path = args.getString(EXTRA_EXTERNAL_AUDIO_PATH).orEmpty()
                val name = args.getString(EXTRA_EXTERNAL_AUDIO_NAME).orEmpty().ifBlank {
                    android.net.Uri.parse(path).lastPathSegment?.substringAfterLast('/') ?: "Audio"
                }
                if (path.isBlank()) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))

                return runSessionPlaybackActionWhenAllowed(COMMAND_PLAY_EXTERNAL_AUDIO) {
                    replaceWithExternalAudio(path, name)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            if (customCommand.customAction == COMMAND_PLAY_QUEUE_INDEX_FROM_START) {
                val index = args.getInt(EXTRA_QUEUE_INDEX, -1)
                if (index < 0) {
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                }
                return runSessionPlaybackActionWhenAllowed(COMMAND_PLAY_QUEUE_INDEX_FROM_START) {
                    if (playQueueIndexFromStart(index)) {
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    } else {
                        SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                    }
                }
            }
            if (customCommand.customAction == COMMAND_PARTY_START_HOST) {
                val token = args.getString(EXTRA_PARTY_TOKEN).orEmpty()
                if (token.isBlank()) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                return runSessionActionWhenAllowed(COMMAND_PARTY_START_HOST) {
                    startPartyHostServer(token)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            if (customCommand.customAction == COMMAND_PARTY_STOP_HOST) {
                stopPartyHostServer()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Comportement attendu : bouton Home = lecture audio en arrière-plan avec notification ;
     * swipe de l'app dans les tâches récentes = fermeture explicite, donc arrêt immédiat de la
     * lecture et suppression de la notification Media3.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        persistAudioQueue()
        try {
            isAudioPlaybackActive = false
            isAudioSessionActive = false
            sessionPlayer?.stop()
            sessionPlayer?.clearMediaItems()
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Audio onTaskRemoved stop failed", e)
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isAudioPlaybackActive = false
        isAudioSessionActive = false
        accessExpiryJob?.cancel()
        accessExpiryJob = null
        try { eqApplyHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { audioPipelineHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { crossfadeHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { smbRecoveryHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { smbStallHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { autoAdvanceHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { SmbDataSource.cancelActiveReads(SmbDataSource.OWNER_AUDIO) } catch (_: Exception) {}
        try { playerVolumeAnimator?.cancel() } catch (_: Exception) {}
        try { if (::audioProPrefs.isInitialized) audioProPrefs.unregisterOnSharedPreferenceChangeListener(audioProListener) } catch (_: Exception) {}
        try { if (::eqPrefs.isInitialized) eqPrefs.unregisterOnSharedPreferenceChangeListener(eqPreferenceListener) } catch (_: Exception) {}
        try { pcmAudioProcessor?.releaseSettings() } catch (_: Exception) {}
        pcmAudioProcessor = null
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        loudnessEnhancer = null
        loudnessEnhancerSessionId = 0
        try { serviceScope.cancel() } catch (_: Exception) {}
        stopPartyHostServer()
        val sessionToRelease = mediaSession
        val eqToRelease = eqManager
        val playerToRelease = player
        mediaSession = null
        eqManager = null
        equalizerSessionId = 0
        sessionPlayer = null
        player = null
        try { sessionToRelease?.release() } catch (_: Exception) {}
        try { eqToRelease?.release() } catch (_: Exception) {}
        try { playerToRelease?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

}
