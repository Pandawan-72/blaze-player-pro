package fr.retrospare.blazeplayer.player

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType
import fr.retrospare.blazeplayer.data.repository.NetworkRepository
import fr.retrospare.blazeplayer.network.SmbBrowser
import fr.retrospare.blazeplayer.network.UpnpBrowser
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Point d'entrée unique de la bibliothèque audio :
 * - restauration Room une seule fois au démarrage ;
 * - snapshot complet et indexé conservé en mémoire pour tous les écrans ;
 * - MediaStore utilisé comme première passe locale instantanée ;
 * - scan approfondi local/réseau et enrichissements persistés en arrière-plan.
 *
 * Room reste le cache durable, mais n'est plus collecté directement par l'UI. Les écritures mettent
 * d'abord à jour le snapshot mémoire puis sont persistées, ce qui évite les relectures, jointures et
 * regroupements complets à chaque pochette ou durée enrichie.
 */
@Singleton
class AudioLibraryRepository @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val networkRepository: NetworkRepository,
    private val smbBrowser: SmbBrowser,
    private val upnpBrowser: UpnpBrowser
) {
    companion object {
        private const val MAX_LOCAL_SCAN_TRACKS = 50000
        private const val MAX_MANUAL_LOCAL_SCAN_TRACKS = 200000
        private const val MAX_NETWORK_SCAN_TRACKS = 500_000
        private const val MAX_MANUAL_NETWORK_SCAN_TRACKS = 500_000
        private const val MAX_NETWORK_DEPTH = 64
        private const val NETWORK_PROGRESS_FIRST_BATCH_SIZE = 24
        private const val NETWORK_PROGRESS_BATCH_SIZE = 160
        private const val NETWORK_PROGRESS_FIRST_DELAY_MS = 220L
        private const val NETWORK_PROGRESS_MAX_DELAY_MS = 900L
        private const val NETWORK_RETRY_MAX_BACKOFF_MS = 15L * 60L * 1000L
        private const val NETWORK_ARTWORK_RETRY_MAX_BACKOFF_MS = 10L * 60L * 1000L
        private const val MEDIASTORE_PREFS = "blaze_audio_mediastore_revision"
        private const val AUTOMATIC_TECHNICAL_RETRY_DELAY_MS = 15_000L
        private const val AUTOMATIC_TECHNICAL_MAX_RETRIES = 3
        private const val KEY_MEDIASTORE_VERSION = "version"
        private const val KEY_MEDIASTORE_GENERATION = "generation"

        /** Nombre de titres traités en parallèle pendant l'enrichissement optionnel/cover. Les
         *  latences réseau NAS se chevauchent au lieu de s'additionner titre par titre. */
        const val ENRICHMENT_CONCURRENCY = 4
    }

    private val interactiveLoading = AtomicBoolean(false)
    private val scanThreadTids = ConcurrentHashMap.newKeySet<Int>()
    private val roomThreadTids = ConcurrentHashMap.newKeySet<Int>()

    private fun currentLibraryThreadPriority(): Int =
        when {
            AudioLibraryWorkState.isPlaybackProtected() ->
                android.os.Process.THREAD_PRIORITY_BACKGROUND
            interactiveLoading.get() ->
                android.os.Process.THREAD_PRIORITY_DEFAULT
            else ->
                android.os.Process.THREAD_PRIORITY_BACKGROUND
        }

    private val scanDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(3) { runnable ->
        Thread {
            val tid = android.os.Process.myTid()
            scanThreadTids += tid
            try {
                android.os.Process.setThreadPriority(currentLibraryThreadPriority())
            } catch (_: Exception) {
            }
            runnable.run()
        }.apply {
            name = "BlazeLibraryScan"
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }.asCoroutineDispatcher()

    private val roomDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(2) { runnable ->
        Thread {
            val tid = android.os.Process.myTid()
            roomThreadTids += tid
            try {
                android.os.Process.setThreadPriority(currentLibraryThreadPriority())
            } catch (_: Exception) {
            }
            runnable.run()
        }.apply {
            name = "BlazeLibraryRoom"
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }.asCoroutineDispatcher()

    private val hydrationThreadTids = ConcurrentHashMap.newKeySet<Int>()
    private val hydrationRoomThreadTids = ConcurrentHashMap.newKeySet<Int>()
    private val hydrationHighPriority = AtomicBoolean(true)

    private fun currentHydrationPriority(): Int =
        if (hydrationHighPriority.get() && !AudioLibraryWorkState.isPlaybackProtected()) {
            // Priorité normale : suffisamment rapide quand le lecteur est inactif, sans jamais
            // dépasser le thread UI ni les threads internes AudioTrack/ExoPlayer.
            android.os.Process.THREAD_PRIORITY_DEFAULT
        } else {
            android.os.Process.THREAD_PRIORITY_BACKGROUND
        }

    private val hydrationDispatcher: CoroutineDispatcher =
        Executors.newFixedThreadPool(2) { runnable ->
            Thread {
                val tid = android.os.Process.myTid()
                hydrationThreadTids += tid
                runCatching {
                    android.os.Process.setThreadPriority(currentHydrationPriority())
                }
                runnable.run()
            }.apply {
                name = "BlazeLibraryHydration"
                isDaemon = true
                priority = (Thread.NORM_PRIORITY + 2).coerceAtMost(Thread.MAX_PRIORITY)
            }
        }.asCoroutineDispatcher()

    private val hydrationRoomDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread {
                val tid = android.os.Process.myTid()
                hydrationRoomThreadTids += tid
                runCatching {
                    android.os.Process.setThreadPriority(currentHydrationPriority())
                }
                runnable.run()
            }.apply {
                name = "BlazeLibraryHydrationRoom"
                isDaemon = true
                priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
            }
        }.asCoroutineDispatcher()

    private val repositoryScope = CoroutineScope(
        SupervisorJob() + AudioLibraryBackgroundDispatchers.coordinator
    )
    /** Sérialise le préchargement afin qu'un nouveau snapshot ne puisse pas annuler la file déjà
     *  commencée. Sans cela, collectLatest interrompait fréquemment les téléchargements pendant le
     *  scan progressif de la bibliothèque. */
    private val artistImagePrefetchMutex = Mutex()
    private val audioArtworkWarmupMutex = Mutex()
    private val fullRefreshMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val fullRefreshInProgress = AtomicBoolean(false)
    /** Invalide une synchronisation MediaStore commencée avant un scan complet plus récent. */
    private val libraryMutationEpoch = AtomicLong(0L)
    /** Chaque ajout/suppression incrémente cette révision. Elle empêche un scan qui se termine
     *  d'effacer la demande d'un dossier ajouté pendant qu'il travaillait. */
    private val watchedFolderChangeRevision = AtomicLong(0L)
    private val mediaStoreChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /**
     * Channel.CONFLATED conserve toujours la dernière demande, même si elle arrive pendant
     * l'initialisation du repository ou pendant un scan déjà en cours. MutableSharedFlow(replay=0)
     * pouvait perdre précisément le premier ajout réalisé depuis le navigateur.
     */
    private val watchedFolderChanges = Channel<Unit>(capacity = Channel.CONFLATED)
    /**
     * Chaque dossier coché est indexé directement avant le rescan global. Le canal illimité évite
     * qu'une sélection rapide de plusieurs dossiers ne remplace les demandes précédentes.
     */
    private val watchedFolderImports = Channel<AudioProSettings.WatchedFolder>(capacity = Channel.UNLIMITED)
    private val queuedWatchedFolderImports = ConcurrentHashMap.newKeySet<String>()

    /** Une seule demande en attente suffit : la passe relit toujours le snapshot le plus récent. */
    private val automaticHydrationRequests = Channel<Unit>(capacity = Channel.CONFLATED)
    @Volatile private var activeAutomaticHydrationJob: Job? = null
    private val hydrationRestartAfterCriticalScheduled = AtomicBoolean(false)

    /**
     * Évite qu'une même piste ou un même album soit hydraté deux fois simultanément.
     * Une entrée est retirée après échec afin d'autoriser une reprise réseau ultérieure.
     */
    private val automaticArtworkAttempts =
        ConcurrentHashMap.newKeySet<String>()
    private val automaticDurationAttempts =
        ConcurrentHashMap.newKeySet<String>()
    private val automaticTechnicalRetryCounts =
        ConcurrentHashMap<String, Int>()
    private val automaticTechnicalRetryScheduled = AtomicBoolean(false)

    /**
     * Horodatage du dernier lot de squelettes publié. Covers embedded et données techniques
     * attendent seulement une courte accalmie du scanner, pas la fin complète d'un NAS immense.
     */
    private val lastStructuralPublishAtMs = AtomicLong(0L)

    /**
     * Une seule piste représentative par album est envoyée dans cette file. Elle gère aussi bien
     * les covers de dossier que les pochettes embarquées lorsqu'aucune image externe n'existe.
     * Le scan ne reste jamais bloqué par le décodage ou la lecture du fichier audio.
     */
    private val networkArtworkRequests = Channel<LibraryTrack>(capacity = 8_192)
    private val queuedNetworkArtworkAlbums = ConcurrentHashMap.newKeySet<String>()
    private val networkArtworkRetryCounts = ConcurrentHashMap<String, Int>()
    private val scheduledNetworkArtworkRetries = ConcurrentHashMap.newKeySet<String>()

    /** Reprise automatique des dossiers SMB/UPnP dont l'énumération n'était pas complète. */
    private val partialNetworkRetryCounts = ConcurrentHashMap<String, Int>()
    private val scheduledPartialNetworkRetries = ConcurrentHashMap.newKeySet<String>()

    private var mediaStoreObserver: ContentObserver? = null
    private var watchedFolderReceiver: BroadcastReceiver? = null

    init {
        AudioLibraryWorkState.addIdlePriorityListener { idle ->
            setHydrationPriority(idle)
            // Les threads de scan/Room peuvent avoir été promus pendant l'ouverture interactive
            // de la bibliothèque. Dès que la lecture démarre, les rabaisser immédiatement évite
            // qu'ils concurrencent le rendu FFT ou le tampon audio jusqu'à leur prochain job.
            val libraryPriority = currentLibraryThreadPriority()
            (scanThreadTids + roomThreadTids).forEach { tid ->
                runCatching { android.os.Process.setThreadPriority(tid, libraryPriority) }
            }
            if (idle && started.get()) {
                automaticHydrationRequests.trySend(Unit)
            } else if (
                !idle &&
                AudioLibraryWorkState.isPlaybackCriticalWindowActive &&
                started.get()
            ) {
                // Une réouverture de Blaze Audio doit interrompre immédiatement la passe locale
                // éventuellement en cours. Elle reprendra après le rattachement Media3, toujours
                // sur son pool BACKGROUND séparé. Les clés d'essai sont libérées pour ne pas
                // considérer une piste interrompue comme définitivement hydratée.
                activeAutomaticHydrationJob?.cancel()
                automaticDurationAttempts.clear()
                automaticArtworkAttempts.clear()
                if (hydrationRestartAfterCriticalScheduled.compareAndSet(false, true)) {
                    repositoryScope.launch {
                        try {
                            AudioLibraryWorkState.awaitPlaybackCriticalWindowEnd()
                            automaticHydrationRequests.trySend(Unit)
                        } finally {
                            hydrationRestartAfterCriticalScheduled.set(false)
                        }
                    }
                }
            }
        }
    }

    fun requestHydration() {
        start()
        automaticHydrationRequests.trySend(Unit)
    }

    private suspend fun awaitHydrationTurn(
        quietPeriodMs: Long = 900L
    ) {
        // Covers embarquées et accès réseau restent strictement exclus de la lecture ET du scan
        // structurel. Le contrôle supplémentaire du service couvre la courte période où le
        // callback Media3 n'a pas encore propagé son nouvel état au StateFlow.
        @Suppress("UNUSED_VARIABLE")
        val ignoredQuietPeriod = quietPeriodMs
        while (currentCoroutineContext().isActive) {
            AudioLibraryWorkState.awaitEnrichmentWindow()
            if (!AudioLibraryWorkState.isPlaybackProtected()) return
            delay(80L)
        }
    }

    /**
     * Fenêtre plus souple réservée aux données techniques locales. La lecture de durée/bitrate ne
     * décode aucun échantillon : elle consulte MediaStore, les en-têtes MPEG ou le conteneur. Elle
     * peut donc progresser pendant un long scan réseau. Pendant une lecture stable elle reste
     * limitée à une piste locale à la fois sur un worker BACKGROUND dédié ; elle est totalement
     * suspendue pendant les fenêtres critiques de rattachement/reprise Media3.
     */
    private suspend fun awaitTechnicalHydrationTurn(
        quietPeriodMs: Long = 350L
    ) {
        while (currentCoroutineContext().isActive) {
            // Le rattachement du service, du contrôleur, de l'AudioTrack et du Visualizer reste
            // totalement exclusif. Une fois cette courte phase passée, l'analyse locale des seuls
            // en-têtes peut continuer sur BlazeLibraryHydration en priorité BACKGROUND, même si le
            // morceau joue. Elle ne partage ni pool, ni thread, ni accès réseau avec Media3.
            AudioLibraryWorkState.awaitPlaybackCriticalWindowEnd()
            val now = android.os.SystemClock.elapsedRealtime()
            val lastPublish = lastStructuralPublishAtMs.get()
            val remaining = quietPeriodMs - (now - lastPublish)
            if (remaining > 0L) delay(remaining)
            if (
                !AudioLibraryWorkState.isPlaybackCriticalWindowActive &&
                android.os.SystemClock.elapsedRealtime() -
                    lastStructuralPublishAtMs.get() >= quietPeriodMs
            ) {
                // Coopération supplémentaire durant la lecture : une piste à la fois est déjà
                // imposée par hydrateAutomaticTechnicalCandidates(), ce yield empêche néanmoins
                // une longue série de fichiers locaux de monopoliser un cœur.
                if (AudioLibraryWorkState.isPlaybackProtected()) {
                    delay(12L)
                } else {
                    kotlinx.coroutines.yield()
                }
                return
            }
        }
    }

    private fun setHydrationPriority(high: Boolean) {
        hydrationHighPriority.set(high)
        val priority = currentHydrationPriority()
        (hydrationThreadTids + hydrationRoomThreadTids).forEach { tid ->
            runCatching { android.os.Process.setThreadPriority(tid, priority) }
        }
        AudioMetadataExtractor.setHydrationPriority(high)
    }

    /**
     * Relève les workers de la bibliothèque au niveau normal du système dès que Blaze Audio est
     * réellement ouvert. Avant cela ils restent en priorité arrière-plan pour ne pas ralentir les
     * raccourcis Blaze Player et Blaze Gallery.
     */
    fun setInteractiveLoading(enabled: Boolean) {
        interactiveLoading.set(enabled)
        val priority = currentLibraryThreadPriority()
        (scanThreadTids + roomThreadTids).forEach { tid ->
            runCatching { android.os.Process.setThreadPriority(tid, priority) }
        }
    }

    data class NetworkFolderScanResult(
        val tracks: List<LibraryTrack>,
        val confirmedFolders: List<AudioProSettings.WatchedFolder>
    )

    private data class LocalFolderScanResult(
        val tracks: List<LibraryTrack>,
        val confirmedFolders: List<AudioProSettings.WatchedFolder>
    )

    data class RefreshResult(
        val activeTracks: List<LibraryTrack>,
        val scannedTrackCount: Int,
        val prunedCount: Int
    )

    private data class MediaStoreTrackQuery(
        val tracks: List<LibraryTrack>,
        val success: Boolean
    )

    private class NetworkProgressEmitter(
        private val onBatch: suspend (List<LibraryTrack>) -> Unit
    ) {
        private var emittedCount = 0
        private var lastEmitAt = android.os.SystemClock.elapsedRealtime()

        suspend fun emitIfNeeded(source: List<LibraryTrack>, force: Boolean = false) {
            val pendingCount = source.size - emittedCount
            if (pendingCount <= 0) return
            val now = android.os.SystemClock.elapsedRealtime()
            val playbackActive = AudioLibraryWorkState.isPlaybackProtected()
            val firstEmission = emittedCount == 0
            val baseBatchSize = if (firstEmission) {
                NETWORK_PROGRESS_FIRST_BATCH_SIZE
            } else {
                NETWORK_PROGRESS_BATCH_SIZE
            }
            val baseDelay = if (firstEmission) {
                NETWORK_PROGRESS_FIRST_DELAY_MS
            } else {
                NETWORK_PROGRESS_MAX_DELAY_MS
            }
            val requiredBatchSize = if (playbackActive) {
                baseBatchSize * 3
            } else {
                baseBatchSize
            }
            val maximumDelay = if (playbackActive) {
                baseDelay * 2
            } else {
                baseDelay
            }
            val delayedEnough = now - lastEmitAt >= maximumDelay
            if (!force && pendingCount < requiredBatchSize && !delayedEnough) return
            val batch = source.subList(emittedCount, source.size).toList()
            emittedCount = source.size
            lastEmitAt = now
            onBatch(batch)
        }
    }

    /**
     * Démarre une seule fois la restauration du cache Room et l'observation de MediaStore. Appelée
     * dès Application.onCreate(), elle peut aussi être invoquée sans risque depuis un écran/service.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        registerMediaStoreObserver()
        registerWatchedFolderReceiver()

        // Les ajouts/suppressions de dossiers surveillés sont traités au niveau Application :
        // le scan démarre même si l'écran Bibliothèque est en pause derrière le navigateur de
        // dossiers. Le Mutex de refresh garantit qu'une demande reçue pendant un scan n'est pas
        // perdue : elle attend puis relance une passe avec la nouvelle liste de dossiers.
        repositoryScope.launch {
            watchedFolderChanges
                .receiveAsFlow()
                .debounce(250L)
                .collect {
                    val revisionAtStart = watchedFolderChangeRevision.get()
                    runCatching {
                        setInteractiveLoading(true)
                        refresh(applicationContext, manual = true)
                    }.onSuccess {
                        if (watchedFolderChangeRevision.get() == revisionAtStart) {
                            if (partialNetworkRetryCounts.isEmpty()) {
                                AudioProSettings.consumeLibraryRefreshPending(applicationContext)
                            } else {
                                // Un scan réseau partiel doit survivre à une destruction du process.
                                AudioProSettings.markLibraryRefreshPending(applicationContext)
                            }
                            // Ferme la très petite fenêtre de course où un nouveau dossier serait
                            // ajouté entre le contrôle de révision et l'effacement du drapeau.
                            if (watchedFolderChangeRevision.get() != revisionAtStart) {
                                AudioProSettings.markLibraryRefreshPending(applicationContext)
                                watchedFolderChanges.trySend(Unit)
                            }
                        } else {
                            // Un autre dossier a été modifié pendant le scan : la passe suivante
                            // doit repartir avec la nouvelle configuration complète.
                            watchedFolderChanges.trySend(Unit)
                        }
                    }.onFailure { error ->
                        fr.retrospare.blazeplayer.debug.CrashReporter.log(
                            applicationContext,
                            "Automatic watched-folder refresh failed",
                            error
                        )
                    }
                }
        }

        repositoryScope.launch {
            watchedFolderImports
                .receiveAsFlow()
                .collect { folder ->
                    val key = AudioWatchedLibraryCache.key(folder)
                    try {
                        importWatchedFolderImmediately(folder)
                    } catch (error: Throwable) {
                        fr.retrospare.blazeplayer.debug.CrashReporter.log(
                            applicationContext,
                            "Immediate watched-folder import failed: ${folder.path}",
                            error
                        )
                    } finally {
                        queuedWatchedFolderImports.remove(key)
                    }
                }
        }

        repositoryScope.launch {
            // Lorsqu'une session audio est déjà en cours au retour dans l'application, laisse à
            // Media3 quelques instants pour rattacher le contrôleur, l'AudioTrack et le Visualizer.
            // Le coordinateur reste sur son pool dédié ; aucune lecture Room/snapshot ne partage
            // alors les pools du lecteur.
            if (AudioLibraryWorkState.isPlaybackProtected()) {
                AudioLibraryWorkState.awaitPlaybackCriticalWindowEnd()
            }

            // 1) Projection Albums persistante : très petite, elle peut publier les tuiles avant la
            // restauration complète. Le verrou interne évite une double lecture avec le warmup app.
            if (AudioLibraryMemoryStore.current().tracks.isEmpty()) {
                runCatching {
                    AudioLibraryBootstrapStore.restoreAlbumBootstrapBlocking(applicationContext)
                }
            }

            // 2) Premier lancement de cette version : la projection binaire peut ne pas encore
            // exister. Room ne lit alors qu'une piste représentative par album, jamais toute la table.
            if (AudioLibraryMemoryStore.current().tracks.isEmpty()) {
                runCatching { AudioLibraryRoomStore.loadAlbumBootstrap(applicationContext) }
                    .getOrDefault(emptyList())
                    .takeIf { it.isNotEmpty() }
                    ?.let { quickAlbums ->
                        publishFull(quickAlbums, AudioLibrarySnapshotOrigin.ROOM_BOOTSTRAP, ready = false)
                    }
            }

            // 3) Snapshot complet binaire. S'il réussit, la lecture Room complète est totalement
            // évitée. Cette étape reste sur BlazeLibraryCoordinator et ne bloque aucun écran.
            if (!AudioLibraryMemoryStore.current().ready) {
                runCatching { AudioLibraryBootstrapStore.restoreBlocking(applicationContext) }
                    .onFailure { error ->
                        fr.retrospare.blazeplayer.debug.CrashReporter.log(
                            applicationContext,
                            "Audio binary bootstrap failed",
                            error
                        )
                    }
            }

            // 4) Room complet n'est plus qu'un ultime secours pour un cache binaire absent/corrompu.
            if (!AudioLibraryMemoryStore.current().ready) {
                val restored = runCatching {
                    AudioLibraryRoomStore.loadActive(applicationContext)
                        .map { it.toLibraryTrack(applicationContext) }
                }.getOrElse { error ->
                    fr.retrospare.blazeplayer.debug.CrashReporter.log(
                        applicationContext,
                        "Audio library Room bootstrap failed",
                        error
                    )
                    emptyList()
                }
                if (!AudioLibraryMemoryStore.current().ready) {
                    publishFull(restored, AudioLibrarySnapshotOrigin.ROOM_BOOTSTRAP, ready = true)
                }
            }

            // Vérification locale non bloquante : sur Android 11+, la génération MediaStore évite
            // tout travail lorsque rien n'a changé depuis la dernière synchronisation.
            runCatching { syncMediaStoreIndex(force = false) }
                .onFailure { error ->
                    fr.retrospare.blazeplayer.debug.CrashReporter.log(
                        applicationContext,
                        "MediaStore bootstrap sync failed",
                        error
                    )
                }

            // Couvre un ajout réalisé juste avant une destruction du processus ou une diffusion
            // manquée : le drapeau persistant n'est effacé qu'après un scan réussi.
            if (AudioProSettings.isLibraryRefreshPending(applicationContext)) {
                requestWatchedFoldersRefresh()
            }
        }
        repositoryScope.launch {
            mediaStoreChanges
                .debounce(800L)
                .collectLatest {
                    runCatching { syncMediaStoreIndex(force = true) }
                        .onFailure { error ->
                            fr.retrospare.blazeplayer.debug.CrashReporter.log(
                                applicationContext,
                                "MediaStore incremental sync failed",
                                error
                            )
                        }
                }
        }
        // Trois workers séparés traitent une piste représentative par album. Ils attendent la fin
        // de l'indexation : l'extraction embedded ne peut donc jamais ralentir la découverte.
        repeat(3) {
            repositoryScope.launch(hydrationDispatcher) {
                for (requestedTrack in networkArtworkRequests) {
                    val requestedAlbumKey =
                        AudioLibraryHeuristics.albumKey(requestedTrack)
                    var resolved = false
                    try {
                        val albumTracks = AudioLibraryMemoryStore.current()
                            .albumTracksByKey[requestedAlbumKey]
                            .orEmpty()
                        val hasExternalCover = albumTracks.any {
                            AudioLibraryHeuristics.isArtworkReference(
                                it.artworkPath
                            )
                        }
                        if (hasExternalCover) {
                            AudioLibraryWorkState.awaitPlaybackIdle()
                        } else {
                            awaitHydrationTurn(1_200L)
                        }

                        resolved = resolveAlbumArtwork(requestedTrack)
                    } finally {
                        queuedNetworkArtworkAlbums.remove(requestedAlbumKey)
                        if (resolved) {
                            networkArtworkRetryCounts.remove(
                                requestedAlbumKey
                            )
                            scheduledNetworkArtworkRetries.remove(
                                requestedAlbumKey
                            )
                        } else {
                            scheduleNetworkArtworkRetry(requestedTrack)
                        }
                    }
                }
            }
        }

        // Toute nouvelle version du snapshot demande une passe d'hydratation. Le canal conflated
        // empêche l'empilement pendant un scan progressif.
        repositoryScope.launch {
            AudioLibraryMemoryStore.snapshot
                .map { it.structureRevision }
                .distinctUntilChanged()
                .collect {
                    automaticHydrationRequests.trySend(Unit)
                }
        }

        // Worker applicatif indépendant de l'écran Bibliothèque. Les données techniques locales
        // continuent à faible priorité pendant une lecture stable ; réseau, covers et checkpoints
        // restent suspendus. Toute phase critique de reprise annule puis relance proprement la passe.
        repositoryScope.launch {
            for (ignored in automaticHydrationRequests) {
                val pass = launch {
                    try {
                        runAutomaticHydrationPass()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        fr.retrospare.blazeplayer.debug.CrashReporter.log(
                            applicationContext,
                            "Automatic audio library hydration failed",
                            error
                        )
                    }
                }
                activeAutomaticHydrationJob = pass
                try {
                    pass.join()
                } finally {
                    if (activeAutomaticHydrationJob === pass) {
                        activeAutomaticHydrationJob = null
                    }
                }
            }
        }

        // Persiste le dernier snapshot complet dans un format binaire séquentiel. Le debounce
        // absorbe les enrichissements successifs de durée/pochette et évite les écritures répétées.
        repositoryScope.launch {
            AudioLibraryMemoryStore.snapshot
                .filter { it.ready && it.tracks.isNotEmpty() }
                .map { it.revision }
                .distinctUntilChanged()
                .debounce(1_800L)
                .collect {
                    // L'écriture du gros snapshot binaire est différée pendant la lecture : elle
                    // ne doit jamais concurrencer le tampon Media3 ni l'égaliseur FFT.
                    AudioLibraryWorkState.awaitPlaybackIdle()
                    AudioLibraryBootstrapStore.save(
                        applicationContext,
                        AudioLibraryMemoryStore.current()
                    )
                }
        }

        // Réhydrate les pochettes persistées dans le cache RAM dès que le snapshot existe. Les
        // écrans n'attendent ainsi jamais le bind d'une tuile pour relire le JPEG sur disque.
        repositoryScope.launch {
            AudioLibraryMemoryStore.snapshot
                .map { snapshot -> snapshot.structureRevision to snapshot.artworkRevision }
                .distinctUntilChanged()
                .debounce(1_500L)
                .collect {
                    AudioLibraryWorkState.awaitPlaybackIdle()
                    warmAudioArtworkCache(AudioLibraryMemoryStore.current().tracks)
                }
        }

        // Les photos d'artistes commencent à être recherchées immédiatement dès que les artistes
        // existent dans le snapshot, sans attendre l'ouverture de l'onglet Artistes ni une pause du
        // scan. collect (et non collectLatest) évite d'annuler une acquisition déjà commencée.
        repositoryScope.launch {
            AudioLibraryMemoryStore.snapshot
                .map { snapshot -> snapshot.artistNamesSorted }
                .distinctUntilChanged()
                .collect { artistNames ->
                    AudioLibraryWorkState.awaitPlaybackIdle()
                    prefetchArtistImages(artistNames)
                }
        }

        // Une absence temporaire de réseau ou une réponse distante incomplète ne doit pas condamner
        // définitivement un artiste. Une passe légère réessaie périodiquement uniquement les images
        // encore absentes ; le cache et les fichiers locaux rendent les artistes déjà résolus gratuits.
        repositoryScope.launch {
            delay(30_000L)
            while (isActive) {
                AudioLibraryWorkState.awaitPlaybackIdle()
                prefetchArtistImages(AudioLibraryMemoryStore.current().artistNamesSorted)
                delay(5L * 60L * 1000L)
            }
        }
    }

    /**
     * Résolution déterministe d'une pochette d'album.
     *
     * 1. toutes les références externes déjà découvertes ;
     * 2. toutes les images JPG/PNG/JPEG/WebP du dossier et du dossier parent d'un CD/Disc ;
     * 3. chaque fichier audio de l'album jusqu'à trouver une image embarquée valide.
     *
     * Aucun plafond arbitraire de six titres : certains encodeurs n'intègrent la pochette que dans
     * une piste précise, parfois la dernière.
     */
    private suspend fun resolveAlbumArtwork(
        requestedTrack: LibraryTrack
    ): Boolean {
        val albumKey =
            AudioLibraryHeuristics.albumKey(requestedTrack)
        val snapshot = AudioLibraryMemoryStore.current()
        val albumTracks = snapshot.albumTracksByKey[albumKey]
            .orEmpty()
            .ifEmpty { listOf(requestedTrack) }

        // Une image déjà persistée doit être propagée immédiatement au bucket complet.
        albumTracks.firstNotNullOfOrNull { track ->
            AudioArtworkPersistence.existingPath(
                applicationContext,
                track.path
            )?.let { track to it }
        }?.let { (track, persisted) ->
            withContext(AudioLibraryBackgroundDispatchers.compute) {
                AudioLibraryMemoryStore.updateArtwork(
                    track.path,
                    persisted
                )
            }
            withContext(roomDispatcher) {
                AudioLibraryRoomStore.updateArtworkPaths(
                    applicationContext,
                    albumTracks.map(LibraryTrack::path),
                    persisted
                )
            }
            return true
        }

        val representative =
            albumTracks.firstOrNull() ?: requestedTrack
        val externalCandidates = LinkedHashSet<String>()

        albumTracks.forEach { track ->
            track.artworkPath
                .takeIf {
                    AudioLibraryHeuristics
                        .isArtworkReference(it) &&
                        AudioLibraryHeuristics
                            .canonicalPathKey(it) !=
                        AudioLibraryHeuristics
                            .canonicalPathKey(track.path)
                }
                ?.let(externalCandidates::add)
        }

        // Une seule exploration par dossier physique/logique, même pour un album de 80 pistes.
        val folderRepresentatives = albumTracks
            .distinctBy { track ->
                AudioLibraryHeuristics
                    .structuralPath(track)
                    .replace('\\', '/')
                    .substringBeforeLast('/', "")
            }

        folderRepresentatives.forEach { track ->
            val candidates = withTimeoutOrNull(
                if (
                    track.source ==
                    LibraryTrackSource.NETWORK
                ) 45_000L else 15_000L
            ) {
                ThumbnailUtils.folderArtworkCandidatesForAudioPath(
                    applicationContext,
                    track.path
                )
            }.orEmpty()
            externalCandidates += candidates
        }

        for (external in externalCandidates) {
            AudioLibraryWorkState.awaitPlaybackIdle()
            val network =
                external.startsWith("smb://", true) ||
                    external.startsWith("http://", true) ||
                    external.startsWith("https://", true)
            val maxAttempts = if (network) 3 else 2
            repeat(maxAttempts) { attempt ->
                val bitmap = withTimeoutOrNull(
                    if (network) 55_000L else 20_000L
                ) {
                    AudioArtworkResolver
                        .resolveExternalArtworkOnly(
                            applicationContext,
                            representative.path,
                            external
                        )
                }
                if (bitmap != null) return true
                if (attempt + 1 < maxAttempts) {
                    delay(
                        if (network) {
                            700L * (attempt + 1)
                        } else {
                            180L
                        }
                    )
                }
            }
        }

        // Track 1 d'abord, puis tous les autres. Les petits fichiers sont testés avant les gros
        // lorsque le numéro de piste ne permet pas de départager.
        val embeddedCandidates = albumTracks
            .asSequence()
            .filter { it.path.isNotBlank() }
            .distinctBy {
                AudioLibraryHeuristics
                    .canonicalPathKey(it.path)
            }
            .sortedWith(
                compareBy<LibraryTrack> {
                    if (it.trackNo == 1) 0 else 1
                }.thenBy {
                    it.trackNo.takeIf { number ->
                        number > 0
                    } ?: Int.MAX_VALUE
                }.thenBy {
                    it.sizeBytes.takeIf { size ->
                        size > 0L
                    } ?: Long.MAX_VALUE
                }
            )
            .toList()

        for (track in embeddedCandidates) {
            AudioLibraryWorkState.awaitPlaybackIdle()
            val declaredExtension = track.container
                .ifBlank {
                    AudioLibraryHeuristics
                        .structuralPath(track)
                        .substringBefore('?')
                        .substringBefore('#')
                        .substringAfterLast('.', "")
                }
            val network =
                track.source == LibraryTrackSource.NETWORK
            val maxAttempts = if (network) 2 else 1

            repeat(maxAttempts) { attempt ->
                val bitmap = withTimeoutOrNull(
                    if (network) 110_000L else 45_000L
                ) {
                    AudioArtworkResolver
                        .resolveEmbeddedArtworkOnly(
                            applicationContext,
                            track.path,
                            declaredExtension
                        )
                }
                if (bitmap != null) return true
                if (attempt + 1 < maxAttempts) {
                    delay(900L)
                }
            }
        }

        return false
    }

    private fun enqueueNetworkArtwork(tracks: List<LibraryTrack>) {
        tracks.asSequence()
            .filter { it.path.isNotBlank() }
            .distinctBy(AudioLibraryHeuristics::albumKey)
            .forEach(::enqueueNetworkArtwork)
    }

    private fun enqueueNetworkArtwork(track: LibraryTrack) {
        val albumKey = AudioLibraryHeuristics.albumKey(track)
        if (!queuedNetworkArtworkAlbums.add(albumKey)) return

        val sent = networkArtworkRequests.trySend(track).isSuccess
        if (!sent) {
            queuedNetworkArtworkAlbums.remove(albumKey)
            // Le canal est borné pour éviter une croissance mémoire illimitée. Une passe globale
            // conflated reprendra les albums non envoyés dès que le scan sera terminé.
            automaticHydrationRequests.trySend(Unit)
        }
    }

    private fun scheduleNetworkArtworkRetry(track: LibraryTrack) {
        val albumKey = AudioLibraryHeuristics.albumKey(track)
        if (!scheduledNetworkArtworkRetries.add(albumKey)) return

        val attempt = networkArtworkRetryCounts.merge(albumKey, 1) { previous, _ ->
            (previous + 1).coerceAtMost(12)
        } ?: 1
        val delayMs = minOf(
            NETWORK_ARTWORK_RETRY_MAX_BACKOFF_MS,
            5_000L * (1L shl (attempt - 1).coerceAtMost(7))
        )

        repositoryScope.launch {
            delay(delayMs)
            scheduledNetworkArtworkRetries.remove(albumKey)
            val candidate = AudioLibraryMemoryStore.current().tracks
                .firstOrNull {
                    AudioLibraryHeuristics.albumKey(it) == albumKey &&
                        needsAutomaticArtworkHydration(it)
                }
            if (candidate != null) {
                enqueueNetworkArtwork(candidate)
            } else {
                networkArtworkRetryCounts.remove(albumKey)
            }
        }
    }

    private fun networkFolderRetryKey(folder: AudioProSettings.WatchedFolder): String =
        AudioWatchedLibraryCache.key(AudioProSettings.normalizeFolder(folder))

    private fun clearPartialNetworkRetry(folder: AudioProSettings.WatchedFolder) {
        val key = networkFolderRetryKey(folder)
        partialNetworkRetryCounts.remove(key)
        scheduledPartialNetworkRetries.remove(key)
    }

    private fun schedulePartialNetworkRetry(folder: AudioProSettings.WatchedFolder) {
        val clean = AudioProSettings.normalizeFolder(folder)
        AudioProSettings.markLibraryRefreshPending(applicationContext)
        val key = networkFolderRetryKey(clean)
        if (!scheduledPartialNetworkRetries.add(key)) return

        val attempt = partialNetworkRetryCounts.merge(key, 1) { previous, _ ->
            (previous + 1).coerceAtMost(12)
        } ?: 1
        val delayMs = minOf(
            NETWORK_RETRY_MAX_BACKOFF_MS,
            5_000L * (1L shl (attempt - 1).coerceAtMost(8))
        )

        repositoryScope.launch {
            delay(delayMs)
            scheduledPartialNetworkRetries.remove(key)
            // Un scan complet plus récent a pu annuler cette reprise pendant le délai.
            if (!partialNetworkRetryCounts.containsKey(key)) return@launch
            val stillWatched = AudioProSettings.watchedFolders(applicationContext).any {
                networkFolderRetryKey(it) == key
            }
            if (stillWatched) {
                requestWatchedFolderImport(clean)
            } else {
                partialNetworkRetryCounts.remove(key)
            }
        }
    }

    private suspend fun warmAudioArtworkCache(
        tracks: List<LibraryTrack>
    ) {
        if (tracks.isEmpty()) return
        AudioLibraryWorkState.awaitPlaybackIdle()

        audioArtworkWarmupMutex.withLock {
            val candidates = tracks.asSequence()
                .filter { it.path.isNotBlank() }
                .groupBy {
                    AudioLibraryHeuristics.albumKey(it)
                }
                .values
                .mapNotNull { albumTracks ->
                    albumTracks.firstOrNull {
                        AudioArtworkPersistence.existingPath(
                            applicationContext,
                            it.path
                        ) != null
                    } ?: albumTracks.firstOrNull {
                        AudioLibraryHeuristics
                            .isArtworkReference(
                                it.artworkPath
                            )
                    }
                }
                .toList()

            for (batch in candidates.chunked(12)) {
                AudioLibraryWorkState.awaitPlaybackIdle()
                coroutineScope {
                    batch.map { track ->
                        async(hydrationDispatcher) {
                            val preferred =
                                track.artworkPath.takeIf(
                                    AudioLibraryHeuristics::
                                        isArtworkReference
                                )
                            AudioArtworkResolver.cachedBitmap(
                                applicationContext,
                                track.path,
                                preferred
                            )
                        }
                    }.awaitAll()
                }
            }

            withContext(hydrationDispatcher) {
                AudioAlbumArtworkAtlas.rebuildIfNeeded(
                    applicationContext,
                    AudioLibraryMemoryStore.current()
                )
            }
        }
    }

    private suspend fun prefetchArtistImages(artistNames: List<String>) {
        if (artistNames.isEmpty()) return
        AudioLibraryWorkState.awaitEnrichmentWindow()
        artistImagePrefetchMutex.withLock {
            AudioLibraryWorkState.awaitEnrichmentWindow()
            val snapshot = AudioLibraryMemoryStore.current()
            val artists = artistNames.mapNotNull { name -> snapshot.artistsByName[name] }
            if (artists.isEmpty()) return@withLock
            runCatching {
                withContext(hydrationDispatcher) {
                    ArtistImageRepository.prefetch(applicationContext, artists)
                }
            }.onFailure { error ->
                fr.retrospare.blazeplayer.debug.CrashReporter.log(
                    applicationContext,
                    "Artist image prefetch failed",
                    error
                )
            }
        }
    }

    /** Snapshot déjà indexé en mémoire : aucune requête Room n'est exécutée par les écrans. */
    fun observeSnapshot(): StateFlow<AudioLibrarySnapshot> {
        start()
        return AudioLibraryMemoryStore.snapshot
    }

    /** API de compatibilité utilisée par Android Auto et le service Media3. */
    fun observeLibrary(context: Context): Flow<List<LibraryTrack>> {
        start()
        return AudioLibraryMemoryStore.snapshot
            .map { it.tracks }
            .distinctUntilChanged()
    }

    suspend fun loadLibrarySnapshot(context: Context, limit: Int = Int.MAX_VALUE): List<LibraryTrack> {
        start()
        val current = AudioLibraryMemoryStore.current()
        if (current.ready) return if (limit == Int.MAX_VALUE) current.tracks else current.tracks.take(limit)
        return AudioLibraryRoomStore.loadActive(context.applicationContext, limit)
            .map { it.toLibraryTrack(context) }
    }

    /**
     * Demande une synchronisation des dossiers surveillés. Les demandes rapprochées sont fusionnées,
     * tandis qu'une demande reçue pendant un scan reste en attente et sera exécutée ensuite.
     */
    fun requestWatchedFoldersRefresh() {
        start()
        watchedFolderChangeRevision.incrementAndGet()
        watchedFolderChanges.trySend(Unit)
    }

    /**
     * Indexe immédiatement un dossier nouvellement coché. Cette passe ciblée ne dépend ni de
     * MediaStore IS_MUSIC, ni de l'ordre des autres dossiers, ni de la fin d'un scan global.
     */
    fun requestWatchedFolderImport(folder: AudioProSettings.WatchedFolder) {
        start()
        val clean = AudioProSettings.normalizeFolder(folder)
        if (clean.path.isBlank()) return
        val key = AudioWatchedLibraryCache.key(clean)
        if (queuedWatchedFolderImports.add(key)) {
            watchedFolderImports.trySend(clean)
        }
    }

    private suspend fun importWatchedFolderImmediately(
        requestedFolder: AudioProSettings.WatchedFolder
    ) = withContext(scanDispatcher) {
        AudioLibraryWorkState.beginIndexing()
        try {
        setInteractiveLoading(true)
        val folder = AudioProSettings.normalizeFolder(requestedFolder)
        val stillWatched = AudioProSettings.watchedFolders(applicationContext).any {
            AudioWatchedLibraryCache.key(it) == AudioWatchedLibraryCache.key(folder)
        }
        if (!stillWatched) return@withContext

        setInteractiveLoading(true)
        val generation = System.currentTimeMillis()
        val watched = AudioProSettings.watchedFolders(applicationContext)
        val tracks = if (folder.isNetwork) {
            scanSingleNetworkWatchedFolder(
                applicationContext,
                folder,
                manual = true,
                isPlaybackCritical = { false },
                onProgress = { batch ->
                    mergeIntoMemory(batch, AudioLibrarySnapshotOrigin.FOLDER_CHANGE)
                    persistProgressiveSkeletonBatch(
                        applicationContext,
                        batch,
                        generation,
                        watched
                    )
                }
            ).tracks
        } else {
            val mediaStore = queryMediaStoreTracksForFolders(
                applicationContext,
                listOf(folder)
            )
            val direct = scanSingleLocalFolderDirect(
                applicationContext,
                folder,
                MAX_MANUAL_LOCAL_SCAN_TRACKS
            )
            if (direct.pathsToIndex.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    applicationContext,
                    direct.pathsToIndex.take(4000).toTypedArray(),
                    null,
                    null
                )
            }
            AudioLibraryHeuristics.mergeTracks(
                mediaStore.tracks,
                direct.tracks,
                applicationContext
            )
        }

        if (tracks.isNotEmpty()) {
            val canonical = AudioLibraryHeuristics.canonicalLibraryTracks(
                applicationContext,
                tracks
            )
            mergeIntoMemory(canonical, AudioLibrarySnapshotOrigin.FOLDER_CHANGE)
            persistProgressiveSkeletonBatch(
                applicationContext,
                canonical,
                generation,
                watched
            )
            enqueueNetworkArtwork(canonical)
            AudioLibraryBootstrapStore.save(
                applicationContext,
                AudioLibraryMemoryStore.current()
            )
        }

        // Le rescan global réconcilie ensuite suppressions et dossiers déjà existants. Il est
        // distinct de l'import ciblé : même s'il échoue, les titres trouvés restent visibles.
        watchedFolderChanges.trySend(Unit)
        } finally {
            AudioLibraryWorkState.endIndexing()
        }
    }

    private suspend fun scanSingleNetworkWatchedFolder(
        context: Context,
        folder: AudioProSettings.WatchedFolder,
        manual: Boolean,
        isPlaybackCritical: () -> Boolean,
        onProgress: suspend (List<LibraryTrack>) -> Unit
    ): NetworkFolderScanResult = coroutineScope {
        val shares = runCatching { networkRepository.getShares().first() }
            .getOrDefault(emptyList())
        val share = shares.firstOrNull { it.id == folder.shareId }
            ?: shares.firstOrNull {
                it.name.equals(folder.shareName, ignoreCase = true) ||
                    it.shareName.equals(folder.shareName, ignoreCase = true)
            }
        if (share == null) {
            schedulePartialNetworkRetry(folder)
            return@coroutineScope NetworkFolderScanResult(
                emptyList(),
                emptyList()
            )
        }

        val scanLimit = if (manual) {
            MAX_MANUAL_NETWORK_SCAN_TRACKS
        } else {
            MAX_NETWORK_SCAN_TRACKS
        }
        val reuseCachedDurations =
            AudioProSettings.read(context).ignoreShort

        val result = mutableListOf<LibraryTrack>()
        val seen = mutableSetOf<String>()
        val itemBatches = Channel<List<fr.retrospare.blazeplayer.data.model.MediaItem>>(
            capacity = 128
        )

        suspend fun publishProgressWithRetry(batch: List<LibraryTrack>) {
            var delivered = false
            for (attempt in 0 until 3) {
                val success = runCatching {
                    onProgress(batch)
                }.isSuccess
                if (success) {
                    delivered = true
                    break
                }
                if (attempt < 2) delay(longArrayOf(120L, 500L)[attempt])
            }
            if (!delivered) {
                fr.retrospare.blazeplayer.debug.CrashReporter.log(
                    applicationContext,
                    "Network progressive persistence deferred for ${folder.path}",
                    IllegalStateException("Batch persistence failed")
                )
            }
        }

        val writer = launch {
            val pending = LinkedHashMap<String, LibraryTrack>()
            var lastFlushAt = android.os.SystemClock.elapsedRealtime()

            fun targetBatchSize(totalCount: Int): Int = when {
                totalCount < 1_000 -> 48
                totalCount < 5_000 -> 128
                totalCount < 20_000 -> 384
                totalCount < 75_000 -> 1_024
                else -> 2_048
            }

            fun maximumFlushDelayMs(totalCount: Int): Long = when {
                totalCount < 1_000 -> 220L
                totalCount < 5_000 -> 450L
                totalCount < 20_000 -> 850L
                totalCount < 75_000 -> 1_400L
                else -> 2_200L
            }

            suspend fun flushPending(force: Boolean = false) {
                if (pending.isEmpty()) return
                val totalCount =
                    AudioLibraryMemoryStore.current().tracks.size +
                        pending.size
                val elapsed =
                    android.os.SystemClock.elapsedRealtime() - lastFlushAt
                if (
                    !force &&
                    pending.size < targetBatchSize(totalCount) &&
                    elapsed < maximumFlushDelayMs(totalCount)
                ) {
                    return
                }

                val batch = pending.values.toList()
                pending.clear()
                lastFlushAt = android.os.SystemClock.elapsedRealtime()

                // Une seule reconstruction mémoire/Room pour plusieurs dossiers. La fréquence
                // diminue progressivement quand la bibliothèque grossit, sans retarder le début.
                publishProgressWithRetry(batch)
                enqueueNetworkArtwork(batch)
            }

            for (items in itemBatches) {
                val converted = networkItemsToTracks(
                    context = context,
                    watchedFolder = folder,
                    share = share,
                    items = items,
                    reuseCachedDurations = reuseCachedDurations
                )
                val fresh = converted.filter { track ->
                    result.size < scanLimit && seen.add(track.path)
                }
                if (fresh.isEmpty()) continue

                result += fresh
                fresh.forEach { track ->
                    pending[
                        AudioLibraryHeuristics.canonicalPathKey(track.path)
                    ] = track
                }
                flushPending()
            }
            flushPending(force = true)
        }

        // Une exploration SMB/UPnP partage parfois le même NAS et la même pile réseau que le
        // morceau en cours. Elle est donc suspendue entre deux dossiers pendant toute lecture,
        // au lieu de continuer avec un simple délai de quelques millisecondes.
        val beforeDirectory: suspend () -> Unit = {
            AudioLibraryWorkState.awaitPlaybackIdle(isPlaybackCritical)
            kotlinx.coroutines.yield()
        }

        val scanResult = try {
            when (share.type) {
                ShareType.UPNP -> upnpBrowser.scanAudioLibrary(
                    share = share,
                    startObjectId = folder.path.ifBlank { "0" },
                    startFolderName = folder.name,
                    maxTracks = scanLimit,
                    maxDepth = MAX_NETWORK_DEPTH,
                    concurrency = 4,
                    beforeDirectory = beforeDirectory,
                    onBatch = { itemBatches.send(it) }
                )
                ShareType.SMB -> smbBrowser.scanAudioLibrary(
                    share = share,
                    startPath = folder.path,
                    maxTracks = scanLimit,
                    maxDepth = MAX_NETWORK_DEPTH,
                    beforeDirectory = beforeDirectory,
                    onBatch = { itemBatches.send(it) }
                )
                else -> Result.failure(
                    IllegalArgumentException(
                        "Type réseau audio non pris en charge"
                    )
                )
            }
        } finally {
            itemBatches.close()
            writer.join()
        }

        val report = scanResult.getOrNull()
        if (report?.complete == true) {
            clearPartialNetworkRetry(folder)
        } else {
            schedulePartialNetworkRetry(folder)
            scanResult.exceptionOrNull()?.let { error ->
                fr.retrospare.blazeplayer.debug.CrashReporter.log(
                    applicationContext,
                    "Partial network scan scheduled for retry: ${folder.path}",
                    error
                )
            }
        }

        NetworkFolderScanResult(
            tracks = result,
            // Purge autorisée uniquement après une énumération certifiée complète.
            confirmedFolders = if (report?.complete == true) {
                listOf(folder)
            } else {
                emptyList()
            }
        )
    }

    private fun networkItemsToTracks(
        context: Context,
        watchedFolder: AudioProSettings.WatchedFolder,
        share: NetworkShare,
        items: List<fr.retrospare.blazeplayer.data.model.MediaItem>,
        reuseCachedDurations: Boolean
    ): List<LibraryTrack> {
        val networkLabel = share.name.ifBlank {
            share.shareName.ifBlank { context.getString(R.string.tab_network) }
        }

        return items.mapNotNull { item ->
            if (
                item.path.isBlank() ||
                !AudioLibraryHeuristics.isAudioItem(
                    item.extension,
                    item.mimeType,
                    item.path
                )
            ) {
                return@mapNotNull null
            }

            val name = item.name.ifBlank {
                AudioLibraryHeuristics.fileNameFromPath(item.path)
            }
            val structurePath = item.libraryPath.ifBlank { item.path }
            val folderMeta = AudioLibraryHeuristics.folderMetadata(
                structurePath,
                name
            )
            val listedDurationMs = item.duration
                .takeIf { it > 0L }
                ?.times(1000L)
                ?: 0L
            val cachedDurationMs = if (
                listedDurationMs <= 0L &&
                reuseCachedDurations
            ) {
                AudioMetadataExtractor.getCached(context, item.path)
                    ?.duration
                    ?.takeIf { it > 0L }
                    ?.times(1000L)
                    ?: 0L
            } else {
                0L
            }
            val effectiveDurationMs = listedDurationMs
                .takeIf { it > 0L }
                ?: cachedDurationMs
            val listedBitrate = if (
                item.size > 0L &&
                effectiveDurationMs > 0L
            ) {
                (item.size * 8_000L) / effectiveDurationMs
            } else {
                AudioMetadataExtractor.getCached(context, item.path)
                    ?.bitrate
                    ?.coerceAtLeast(0L)
                    ?: 0L
            }

            // Ordre strict du squelette, sans ouvrir le fichier :
            // 1. dossier artiste, 2. dossier album, 3. nom du morceau.
            // Les métadonnées UPnP/ID3 ne servent qu'en repli si l'arborescence est incomplète.
            val title = folderMeta.title.ifBlank { name }
            val artist = folderMeta.artist
                .ifBlank { item.artist }
                .ifBlank { watchedFolder.name }
            val album = folderMeta.album
                .ifBlank { item.album }
                .ifBlank { watchedFolder.name }
            val artworkPath = item.previewUris.firstOrNull()
                .orEmpty()

            LibraryTrack(
                id = -abs(item.path.hashCode()).toLong(),
                title = title,
                artist = artist,
                album = album,
                durationMs = effectiveDurationMs,
                bitrate = listedBitrate,
                trackNo = item.trackNumber.takeIf { it > 0 }
                    ?: AudioLibraryHeuristics.inferTrackNo(name),
                path = item.path,
                addedAt = System.currentTimeMillis() / 1000L,
                artworkPath = artworkPath,
                source = LibraryTrackSource.NETWORK,
                sourceLabel = networkLabel,
                titleFromTag = false,
                albumFromTag = false,
                artistFromTag = false,
                container = AudioLibraryHeuristics.containerFrom(
                    item.extension,
                    item.path
                ),
                sizeBytes = item.size,
                modifiedAt = item.modifiedAt,
                libraryPath = structurePath
            )
        }
    }

    private fun registerWatchedFolderReceiver() {
        if (watchedFolderReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AudioProSettings.ACTION_WATCHED_FOLDERS_CHANGED) return
                val change = intent.getStringExtra(
                    AudioProSettings.EXTRA_WATCHED_FOLDER_CHANGE
                ).orEmpty()
                if (change == AudioProSettings.WATCHED_FOLDER_ADDED) {
                    val folder = AudioProSettings.WatchedFolder(
                        name = intent.getStringExtra(
                            AudioProSettings.EXTRA_WATCHED_FOLDER_NAME
                        ).orEmpty(),
                        path = intent.getStringExtra(
                            AudioProSettings.EXTRA_WATCHED_FOLDER_PATH
                        ).orEmpty(),
                        isNetwork = intent.getBooleanExtra(
                            AudioProSettings.EXTRA_WATCHED_FOLDER_NETWORK,
                            false
                        ),
                        shareId = intent.getStringExtra(
                            AudioProSettings.EXTRA_WATCHED_FOLDER_SHARE_ID
                        ).orEmpty(),
                        shareName = intent.getStringExtra(
                            AudioProSettings.EXTRA_WATCHED_FOLDER_SHARE_NAME
                        ).orEmpty()
                    )
                    if (folder.path.isNotBlank()) {
                        requestWatchedFolderImport(folder)
                    } else {
                        requestWatchedFoldersRefresh()
                    }
                } else {
                    requestWatchedFoldersRefresh()
                }
            }
        }
        val filter = IntentFilter(AudioProSettings.ACTION_WATCHED_FOLDERS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(receiver, filter)
        }
        watchedFolderReceiver = receiver
    }

    private fun registerMediaStoreObserver() {
        if (mediaStoreObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                mediaStoreChanges.tryEmit(Unit)
            }
        }
        runCatching {
            applicationContext.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            mediaStoreObserver = observer
        }
    }

    private suspend fun publishFull(
        tracks: List<LibraryTrack>,
        origin: AudioLibrarySnapshotOrigin,
        ready: Boolean = true
    ) = withContext(AudioLibraryBackgroundDispatchers.compute) {
        val canonical = AudioLibraryHeuristics.canonicalLibraryTracks(
            applicationContext,
            tracks.distinctBy { it.path }
        )
        val hydrated = if (origin in setOf(
                AudioLibrarySnapshotOrigin.MEDIASTORE,
                AudioLibrarySnapshotOrigin.LOCAL_SCAN,
                AudioLibrarySnapshotOrigin.NETWORK_SCAN,
                AudioLibrarySnapshotOrigin.FULL_SCAN,
                AudioLibrarySnapshotOrigin.FOLDER_CHANGE,
                AudioLibrarySnapshotOrigin.ROOM_BOOTSTRAP
            )
        ) {
            AudioLibraryMemoryStore.preserveHydratedFields(canonical)
        } else {
            canonical
        }
        if (origin in setOf(
                AudioLibrarySnapshotOrigin.MEDIASTORE,
                AudioLibrarySnapshotOrigin.LOCAL_SCAN,
                AudioLibrarySnapshotOrigin.NETWORK_SCAN,
                AudioLibrarySnapshotOrigin.FULL_SCAN,
                AudioLibrarySnapshotOrigin.FOLDER_CHANGE
            )
        ) {
            lastStructuralPublishAtMs.set(
                android.os.SystemClock.elapsedRealtime()
            )
        }
        AudioLibraryMemoryStore.replace(hydrated, origin, ready)
    }

    private suspend fun mergeIntoMemory(
        tracks: List<LibraryTrack>,
        origin: AudioLibrarySnapshotOrigin
    ) = withContext(AudioLibraryBackgroundDispatchers.compute) {
        if (tracks.isEmpty()) return@withContext
        val progressive =
            origin == AudioLibrarySnapshotOrigin.NETWORK_SCAN ||
                origin == AudioLibrarySnapshotOrigin.LOCAL_SCAN ||
                origin == AudioLibrarySnapshotOrigin.FOLDER_CHANGE

        val canonical = if (progressive) {
            // Ces lots proviennent déjà d'un dossier surveillé confirmé. Éviter ici la relecture
            // des préférences et le filtrage complet de canonicalLibraryTracks économise deux
            // parcours supplémentaires par publication réseau.
            tracks.asSequence()
                .filter { it.path.isNotBlank() }
                .map(AudioLibraryHeuristics::applyFolderMetadata)
                .distinctBy {
                    AudioLibraryHeuristics.canonicalPathKey(it.path)
                }
                .toList()
        } else {
            AudioLibraryHeuristics.canonicalLibraryTracks(
                applicationContext,
                tracks.distinctBy { it.path }
            )
        }

        if (progressive) {
            lastStructuralPublishAtMs.set(
                android.os.SystemClock.elapsedRealtime()
            )
            AudioLibraryMemoryStore.appendSkeletons(
                canonical,
                origin,
                ready = true
            )
        } else {
            AudioLibraryMemoryStore.merge(
                canonical,
                origin,
                ready = true
            )
        }
    }

    /**
     * Injecte la passe rapide MediaStore sans toucher aux pistes réseau. Les fichiers locaux
     * découverts uniquement par le parcours direct restent disponibles tant qu'ils existent encore.
     */
    private suspend fun publishMediaStoreTracks(
        tracks: List<LibraryTrack>,
        removeMissing: Boolean,
        activeMediaStorePaths: Set<String> = tracks.mapTo(linkedSetOf()) { it.path }
    ) = withContext(AudioLibraryBackgroundDispatchers.compute) {
        val watched = AudioProSettings.watchedFolders(applicationContext).filterNot { it.isNetwork }
        val canonicalIncoming = AudioLibraryHeuristics.canonicalLibraryTracks(
            applicationContext,
            tracks.distinctBy { it.path }
        )

        // Une synchronisation MediaStore ne doit jamais remplacer une piste déjà enrichie par un
        // squelette plus pauvre. merge() conserve les durées, tags et pochettes persistées, tout en
        // ajoutant ou actualisant uniquement les éléments réellement renvoyés par Android.
        AudioLibraryMemoryStore.merge(
            canonicalIncoming,
            AudioLibrarySnapshotOrigin.MEDIASTORE,
            ready = true
        )

        if (removeMissing) {
            val removedPaths = AudioLibraryMemoryStore.current().tracks.asSequence()
                .filter { old ->
                    val isWatchedLocalTrack = old.source == LibraryTrackSource.LOCAL &&
                        watched.any { AudioLibraryHeuristics.belongsToLocalFolder(old.path, it) }
                    val missingFromDevice = old.id >= 0L ||
                        !File(old.path.removePrefix("file://")).exists()
                    isWatchedLocalTrack && old.path !in activeMediaStorePaths && missingFromDevice
                }
                .map { it.path }
                .toSet()
            if (removedPaths.isNotEmpty()) {
                AudioLibraryMemoryStore.removePaths(
                    removedPaths,
                    AudioLibrarySnapshotOrigin.MEDIASTORE
                )
            }
        }
    }

    /**
     * Synchronisation locale légère déclenchée par ContentObserver. Android 11+ ne demande que les
     * lignes dont GENERATION_MODIFIED a évolué, puis une requête path-only identifie les suppressions.
     */
    private suspend fun syncMediaStoreIndex(force: Boolean) = withContext(scanDispatcher) {
        val mutationEpochAtStart = libraryMutationEpoch.get()
        if (fullRefreshInProgress.get() || !canReadLocalAudio()) return@withContext
        val watched = AudioProSettings.watchedFolders(applicationContext).filterNot { it.isNetwork }
        if (watched.isEmpty()) return@withContext

        val prefs = applicationContext.getSharedPreferences(MEDIASTORE_PREFS, Context.MODE_PRIVATE)
        val currentVersion = currentMediaStoreVersion()
        val currentGeneration = currentMediaStoreGeneration()
        val previousVersion = prefs.getString(KEY_MEDIASTORE_VERSION, "").orEmpty()
        val previousGeneration = prefs.getLong(KEY_MEDIASTORE_GENERATION, 0L)
        val sameDatabase = currentVersion.isNotBlank() && currentVersion == previousVersion

        if (!force && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            sameDatabase && currentGeneration > 0L && currentGeneration <= previousGeneration
        ) {
            return@withContext
        }

        val minGeneration = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            sameDatabase &&
            previousGeneration > 0L
        ) previousGeneration else null

        val changedQuery = queryMediaStoreWatchedTracksResult(applicationContext, minGeneration)
        if (!changedQuery.success) return@withContext
        val changed = changedQuery.tracks
        val activePaths = queryMediaStoreWatchedPaths(applicationContext) ?: return@withContext
        val oldMediaStorePaths = AudioLibraryMemoryStore.current().tracks.asSequence()
            .filter { track ->
                track.source == LibraryTrackSource.LOCAL &&
                    watched.any { AudioLibraryHeuristics.belongsToLocalFolder(track.path, it) } &&
                    (track.id >= 0L || !File(track.path.removePrefix("file://")).exists())
            }
            .map { it.path }
            .toSet()
        val removedPaths = oldMediaStorePaths - activePaths

        // Si un scan complet a démarré pendant les requêtes, son résultat est prioritaire.
        if (fullRefreshInProgress.get() || libraryMutationEpoch.get() != mutationEpochAtStart) {
            return@withContext
        }

        publishMediaStoreTracks(
            tracks = changed,
            removeMissing = true,
            activeMediaStorePaths = activePaths
        )

        val generation = System.currentTimeMillis()
        if (changed.isNotEmpty()) {
            persistProgressiveSkeletonBatch(
                applicationContext,
                changed,
                generation,
                AudioProSettings.watchedFolders(applicationContext)
            )
            enqueueNetworkArtwork(changed)
        }
        if (removedPaths.isNotEmpty()) {
            withContext(roomDispatcher) {
                AudioLibraryRoomStore.deletePaths(applicationContext, removedPaths)
            }
        }
        saveMediaStoreRevision(currentVersion, currentGeneration)
    }

    private fun canReadLocalAudio(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun currentMediaStoreVersion(): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.getVersion(applicationContext, MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.getVersion(applicationContext)
        }
    }.getOrDefault("")

    private fun currentMediaStoreGeneration(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                MediaStore.getGeneration(applicationContext, MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }.getOrDefault(0L)
        } else 0L

    private fun saveMediaStoreRevision(
        version: String = currentMediaStoreVersion(),
        generation: Long = currentMediaStoreGeneration()
    ) {
        applicationContext.getSharedPreferences(MEDIASTORE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MEDIASTORE_VERSION, version)
            .putLong(KEY_MEDIASTORE_GENERATION, generation)
            .apply()
    }

    /**
     * Scan complet : MediaStore + dossiers locaux + réseau (SMB/UPnP), puis remplacement du
     * squelette Room. Les dossiers dont le scan réseau a échoué (timeout, Wi-Fi coupé) ne sont
     * jamais purgés : on ne perd jamais la bibliothèque à cause d'une coupure réseau passagère.
     */
    suspend fun refresh(
        context: Context,
        manual: Boolean,
        isPlaybackCritical: () -> Boolean = { false }
    ): RefreshResult = withContext(scanDispatcher) {
        setInteractiveLoading(true)
        AudioLibraryWorkState.beginIndexing()
        try {
        fullRefreshMutex.withLock {
            fullRefreshInProgress.set(true)
            libraryMutationEpoch.incrementAndGet()
            try {
            start()
            val appContext = context.applicationContext
            val watched = AudioProSettings.watchedFolders(appContext)
            val localFolders = watched.filterNot { it.isNetwork }
            val generation = System.currentTimeMillis()
            // La révision doit correspondre exactement au moment de la requête locale. Une copie
            // ajoutée pendant le scan réseau sera ainsi rattrapée par la synchro finale.
            val mediaStoreVersionAtScan = currentMediaStoreVersion()
            val mediaStoreGenerationAtScan = currentMediaStoreGeneration()

            // Première passe : MediaStore est déjà indexé par Android. Elle est publiée en mémoire
            // avant tout parcours récursif local ou toute connexion NAS.
            val mediaStoreTracks = queryMediaStoreWatchedTracks(appContext)
            publishMediaStoreTracks(mediaStoreTracks, removeMissing = false)
            persistProgressiveSkeletonBatch(
                appContext,
                mediaStoreTracks,
                generation,
                watched
            )
            enqueueNetworkArtwork(mediaStoreTracks)

            val (localTracks, networkScan) = coroutineScope {
                val localDeferred = async { scanWatchedLocalFolders(appContext, manual, mediaStoreTracks) }
                val networkDeferred = async {
                    scanWatchedNetworkFolders(
                        appContext,
                        manual,
                        isPlaybackCritical,
                        onProgress = { batch ->
                            mergeIntoMemory(batch, AudioLibrarySnapshotOrigin.NETWORK_SCAN)
                            persistProgressiveSkeletonBatch(appContext, batch, generation, watched)
                        }
                    )
                }

                val local = localDeferred.await()
                mergeIntoMemory(local.tracks, AudioLibrarySnapshotOrigin.LOCAL_SCAN)
                persistProgressiveSkeletonBatch(
                    appContext,
                    local.tracks,
                    generation,
                    watched
                )
                enqueueNetworkArtwork(local.tracks)
                local to networkDeferred.await()
            }

            val merged = AudioLibraryHeuristics.mergeTracks(
                mediaStoreTracks + localTracks.tracks,
                networkScan.tracks,
                appContext
            )
            val scanned = AudioLibraryHeuristics.canonicalLibraryTracks(appContext, merged)
            val confirmedFolderObjects =
                (localTracks.confirmedFolders + networkScan.confirmedFolders)
                    .distinctBy { AudioWatchedLibraryCache.key(it) }
            val confirmedFolders = confirmedFolderObjects
                .map { AudioWatchedLibraryCache.key(it) }
                .toSet()

            // Conserver les titres des dossiers qui n'ont pas pu être confirmés (NAS hors ligne,
            // stockage momentanément indisponible). Une passe ratée ne doit jamais effacer un
            // dossier déjà indexé ni masquer l'import ciblé réalisé à la coche.
            val confirmedKeys = confirmedFolderObjects
                .mapTo(hashSetOf()) { AudioWatchedLibraryCache.key(it) }
            val retainedUnconfirmed = AudioLibraryMemoryStore.current().tracks.filter { track ->
                val owner = watched.firstOrNull { folder ->
                    if (folder.isNetwork) {
                        AudioLibraryHeuristics.belongsToNetworkFolder(track.path, folder) ||
                            AudioLibraryHeuristics.belongsToNetworkFolder(
                                AudioLibraryHeuristics.structuralPath(track),
                                folder
                            )
                    } else {
                        AudioLibraryHeuristics.belongsToLocalFolder(track.path, folder)
                    }
                }
                owner != null && AudioWatchedLibraryCache.key(owner) !in confirmedKeys
            }
            val latestWatched = AudioProSettings.watchedFolders(appContext)
            val watchedAtStartKeys = watched.mapTo(hashSetOf()) {
                AudioWatchedLibraryCache.key(it)
            }
            val newlyAddedFolders = latestWatched.filter {
                AudioWatchedLibraryCache.key(it) !in watchedAtStartKeys
            }
            val retainedNewlyAdded = AudioLibraryMemoryStore.current().tracks.filter { track ->
                newlyAddedFolders.any { folder ->
                    if (folder.isNetwork) {
                        AudioLibraryHeuristics.belongsToNetworkFolder(track.path, folder) ||
                            AudioLibraryHeuristics.belongsToNetworkFolder(
                                AudioLibraryHeuristics.structuralPath(track),
                                folder
                            )
                    } else {
                        AudioLibraryHeuristics.belongsToLocalFolder(track.path, folder)
                    }
                }
            }
            val finalTracks = AudioLibraryHeuristics.mergeTracks(
                scanned,
                retainedUnconfirmed + retainedNewlyAdded,
                appContext
            )

            // Le rendu final est disponible avant l'écriture durable. Room ne peut donc plus
            // retarder l'affichage ni provoquer une reconstruction lors de ses invalidations.
            publishFull(finalTracks, AudioLibrarySnapshotOrigin.FULL_SCAN, ready = true)

            val entities = finalTracks.mapNotNull {
                it.toRoomEntity(appContext, generation, watched)
            }
            val replace = withContext(roomDispatcher) {
                AudioLibraryRoomStore.replaceSkeletonPass(
                    appContext,
                    entities,
                    confirmedFolders,
                    generation
                )
            }
            replace.changedPaths.forEach { path ->
                runCatching { AudioMediaCache.invalidatePath(appContext, path) }
            }
            saveMediaStoreRevision(
                version = mediaStoreVersionAtScan,
                generation = mediaStoreGenerationAtScan
            )

            RefreshResult(
                activeTracks = AudioLibraryMemoryStore.current().tracks,
                scannedTrackCount = entities.size,
                prunedCount = replace.removedCount
            )
            } finally {
                fullRefreshInProgress.set(false)
                // Rattrape les ajouts/suppressions survenus pendant un long scan réseau.
                mediaStoreChanges.tryEmit(Unit)
            }
        }
        } finally {
            AudioLibraryWorkState.endIndexing()
        }
    }

    /** Écriture non destructive utilisée pendant le scan progressif. */
    private suspend fun persistProgressiveSkeletonBatch(
        context: Context,
        tracks: List<LibraryTrack>,
        generation: Long,
        watchedFolders: List<AudioProSettings.WatchedFolder>
    ) {
        if (tracks.isEmpty()) return
        val canonical = tracks.asSequence()
            .filter { it.path.isNotBlank() }
            .map(AudioLibraryHeuristics::applyFolderMetadata)
            .distinctBy {
                AudioLibraryHeuristics.canonicalPathKey(it.path)
            }
            .toList()
        val entities = canonical.mapNotNull {
            it.toRoomEntity(
                context,
                generation,
                watchedFolders
            )
        }
        if (entities.isEmpty()) return
        withContext(roomDispatcher) {
            AudioLibraryRoomStore.upsertSkeletonBatch(context, entities, generation)
        }
        // Les invalidations de caches sont volontairement regroupées à la passe finale. Les faire
        // pour chaque lot réseau doublerait les accès disque pendant le premier indexage.
    }

    /**
     * API conservée pour les seules données techniques optionnelles. Les champs texte écrits en
     * base restent systématiquement recalculés depuis l'arborescence et aucun écran ne déclenche
     * cette passe automatiquement.
     */
    suspend fun enrichMetadata(
        context: Context,
        candidates: List<LibraryTrack>,
        extractMetadata: suspend (LibraryTrack) -> AudioTechnicalInfo?,
        concurrency: Int = ENRICHMENT_CONCURRENCY,
        batchSize: Int = 4,
        allowDuringIndexing: Boolean = false,
        onBatchWritten: suspend (writtenCount: Int) -> Unit = {}
    ) {
        val appContext = context.applicationContext
        var pendingMetadata = mutableListOf<AudioLibraryTrackEntity>()
        var pendingMemoryUpdates = linkedMapOf<String, AudioTechnicalInfo>()
        var bootstrapDirtySessionStarted = false

        suspend fun commitPendingBatch() {
            if (pendingMetadata.isEmpty()) return
            if (allowDuringIndexing) {
                awaitTechnicalHydrationTurn()
            } else {
                awaitHydrationTurn()
            }
            if (!bootstrapDirtySessionStarted) {
                AudioLibraryBootstrapStore.beginDirtySession(appContext)
                bootstrapDirtySessionStarted = true
            }
            withContext(hydrationDispatcher) {
                AudioLibraryMemoryStore.updateMetadata(pendingMemoryUpdates)
            }
            withContext(hydrationRoomDispatcher) {
                AudioLibraryRoomStore.upsertMetadata(appContext, pendingMetadata)
            }
            onBatchWritten(pendingMetadata.size)
            pendingMetadata = mutableListOf()
            pendingMemoryUpdates = linkedMapOf()
        }

        try {
            for (batch in candidates.chunked(concurrency.coerceAtLeast(1))) {
                if (!currentCoroutineContext().isActive) break
                if (allowDuringIndexing) {
                    awaitTechnicalHydrationTurn()
                } else {
                    awaitHydrationTurn()
                }
                if (!currentCoroutineContext().isActive) break
                val results = coroutineScope {
                    batch.map { track ->
                        async(hydrationDispatcher) {
                            track to runCatching { extractMetadata(track) }.getOrNull()
                        }
                    }.awaitAll()
                }
                results.forEach { (track, info) ->
                    if (info != null) {
                        pendingMetadata += track.toMetadataUpdateEntity(info)
                        pendingMemoryUpdates[track.path] = info
                    }
                }
                if (pendingMetadata.size >= batchSize.coerceAtLeast(1)) {
                    commitPendingBatch()
                }
            }
            commitPendingBatch()
        } finally {
            if (bootstrapDirtySessionStarted) {
                AudioLibraryBootstrapStore.endDirtySession()
            }
        }
    }

    /** Préchargement optionnel des pochettes, indépendant des noms d'album et d'artiste. */
    suspend fun enrichArtwork(
        candidates: List<LibraryTrack>,
        loadArtwork: suspend (LibraryTrack) -> Boolean,
        concurrency: Int = 2
    ) {
        for (batch in candidates.chunked(concurrency.coerceAtLeast(1))) {
            if (!currentCoroutineContext().isActive) break
            awaitHydrationTurn()
            if (!currentCoroutineContext().isActive) break
            coroutineScope {
                batch.map { track -> async(hydrationDispatcher) { runCatching { loadArtwork(track) } } }.awaitAll()
            }
        }
    }

    /** Compatibilité interne : données techniques facultatives, puis pochettes. */
    suspend fun enrichMetadataAndArtwork(
        context: Context,
        candidates: List<LibraryTrack>,
        loadArtwork: suspend (LibraryTrack) -> Boolean,
        extractMetadata: suspend (LibraryTrack) -> AudioTechnicalInfo?,
        concurrency: Int = ENRICHMENT_CONCURRENCY,
        batchSize: Int = 4,
        onBatchWritten: suspend (writtenCount: Int) -> Unit = {}
    ) {
        enrichArtwork(candidates, loadArtwork, concurrency = 2)
        enrichMetadata(
            context = context,
            candidates = candidates,
            extractMetadata = extractMetadata,
            concurrency = concurrency,
            batchSize = batchSize,
            onBatchWritten = onBatchWritten
        )
    }

    /**
     * Hydratation automatique indépendante des écrans et de Media3.
     *
     * Ordre volontaire :
     * 1. durées/bitrates locaux, disponibles rapidement même pendant un long inventaire réseau ;
     * 2. durées/bitrates réseau lorsque l'indexation est au repos ;
     * 3. pochettes, plus coûteuses et non nécessaires au calcul des durées d'albums.
     *
     * Chaque phase nourrit d'abord le snapshot mémoire, puis Room, puis écrit un checkpoint binaire.
     * Ouvrir ou lire une piste n'est donc jamais nécessaire pour obtenir sa durée.
     */
    private suspend fun runAutomaticHydrationPass() {
        val initialSnapshot = AudioLibraryMemoryStore.current()
        if (initialSnapshot.tracks.isEmpty()) return

        val localCandidates = initialSnapshot.tracks.filter {
            it.source != LibraryTrackSource.NETWORK && needsAutomaticTechnicalHydration(it)
        }
        val localRetryNeeded = hydrateAutomaticTechnicalCandidates(
            candidates = localCandidates,
            allowDuringIndexing = true
        )
        checkpointHydratedSnapshot()

        // Les accès SMB/UPnP restent totalement séparés du scan et de la lecture afin de ne pas
        // concurrencer le réseau, Media3 ou l'égaliseur visuel.
        val networkCandidates = AudioLibraryMemoryStore.current().tracks.filter {
            it.source == LibraryTrackSource.NETWORK && needsAutomaticTechnicalHydration(it)
        }
        val networkRetryNeeded = hydrateAutomaticTechnicalCandidates(
            candidates = networkCandidates,
            allowDuringIndexing = false
        )
        checkpointHydratedSnapshot()

        if (localRetryNeeded || networkRetryNeeded) {
            scheduleAutomaticTechnicalRetry()
        }

        val snapshotAfterTechnical = AudioLibraryMemoryStore.current()
        val artworkAlbumKeys = snapshotAfterTechnical.albumKeysSorted
            .filter { albumKey ->
                snapshotAfterTechnical.albumsByKey[albumKey]
                    ?.tracks
                    ?.firstOrNull()
                    ?.let(::needsAutomaticArtworkHydration)
                    ?: false
            }

        if (artworkAlbumKeys.isNotEmpty()) {
            // Les résolveurs de pochettes patchent le snapshot puis Room. Le marqueur empêche un
            // arrêt du process entre ces deux écritures et le checkpoint final de restaurer ensuite
            // un ancien fichier binaire sans la cover déjà extraite.
            AudioLibraryBootstrapStore.beginDirtySession(applicationContext)
            try {
                for (batchKeys in artworkAlbumKeys.chunked(3)) {
                    awaitHydrationTurn()
                    coroutineScope {
                        batchKeys.mapNotNull { albumKey ->
                            val album = AudioLibraryMemoryStore.current()
                                .albumsByKey[albumKey]
                                ?: return@mapNotNull null
                            val representative = album.tracks.firstOrNull()
                                ?: return@mapNotNull null
                            async(hydrationDispatcher) {
                                val attemptKey = album.key
                                if (automaticArtworkAttempts.add(attemptKey)) {
                                    val resolved = runCatching {
                                        resolveAlbumArtwork(representative)
                                    }.getOrDefault(false)
                                    if (!resolved) {
                                        automaticArtworkAttempts.remove(attemptKey)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            } finally {
                AudioLibraryBootstrapStore.endDirtySession()
            }
        }

        checkpointHydratedSnapshot()

        if (
            AudioLibraryMemoryStore.current().structureRevision !=
            initialSnapshot.structureRevision
        ) {
            automaticHydrationRequests.trySend(Unit)
        }
    }

    private fun needsAutomaticTechnicalHydration(track: LibraryTrack): Boolean {
        if (track.path.isBlank()) return false
        val lossless = track.container.uppercase() in setOf(
            "FLAC", "WAV", "ALAC", "APE", "AIFF", "WV"
        )
        return track.durationMs <= 0L || (!lossless && track.bitrate <= 0L)
    }

    private fun automaticTechnicalAttemptKey(track: LibraryTrack): String = buildString {
        append(AudioLibraryHeuristics.canonicalPathKey(track.path))
        append('|')
        append(track.sizeBytes.coerceAtLeast(0L))
        append('|')
        append(track.modifiedAt.coerceAtLeast(0L))
    }

    private suspend fun hydrateAutomaticTechnicalCandidates(
        candidates: List<LibraryTrack>,
        allowDuringIndexing: Boolean
    ): Boolean {
        val retryNeeded = AtomicBoolean(false)
        val technicalCandidates = candidates
            .sortedWith(
                compareBy<LibraryTrack> { it.durationMs > 0L }
                    .thenBy { it.bitrate > 0L }
                    .thenBy { AudioLibraryHeuristics.canonicalPathKey(it.path) }
            )
            .filter { track ->
                automaticDurationAttempts.add(
                    automaticTechnicalAttemptKey(track)
                )
            }
        if (technicalCandidates.isEmpty()) return false

        enrichMetadata(
            context = applicationContext,
            candidates = technicalCandidates,
            extractMetadata = { candidate ->
                // Toujours repartir de la piste la plus récente du snapshot : un lot structurel
                // peut avoir enrichi MediaStore ou remplacé l'identité du fichier entre-temps.
                val track = AudioLibraryMemoryStore.current()
                    .tracksByPath[candidate.path]
                    ?: AudioLibraryMemoryStore.current()
                        .trackIndexByCanonicalPath[
                            AudioLibraryHeuristics.canonicalPathKey(candidate.path)
                        ]
                        ?.let { AudioLibraryMemoryStore.current().tracks.getOrNull(it) }
                    ?: candidate
                val attemptKey = automaticTechnicalAttemptKey(track)
                val info = runCatching {
                    AudioMetadataExtractor.extractTechnicalOnly(
                        context = applicationContext,
                        path = track.path,
                        name = AudioLibraryHeuristics.fileNameFromPath(
                            AudioLibraryHeuristics.structuralPath(track)
                        ),
                        knownDurationMs = track.durationMs,
                        knownSizeBytes = track.sizeBytes,
                        highPriority = false
                    )
                }.getOrNull()

                if (info == null) {
                    val candidateKey = automaticTechnicalAttemptKey(candidate)
                    val retryCount = automaticTechnicalRetryCounts.merge(
                        candidateKey,
                        1,
                        Int::plus
                    ) ?: 1
                    if (retryCount <= AUTOMATIC_TECHNICAL_MAX_RETRIES) {
                        automaticDurationAttempts.remove(candidateKey)
                        automaticDurationAttempts.remove(attemptKey)
                        retryNeeded.set(true)
                    }
                    return@enrichMetadata null
                }

                val hasDuration = track.durationMs > 0L || info.duration > 0L
                val isLossless = info.isLossless ||
                    track.container.uppercase() in setOf(
                        "FLAC", "WAV", "ALAC", "APE", "AIFF", "WV"
                    )
                val hasQuality = isLossless || track.bitrate > 0L || info.bitrate > 0L
                val addsUsefulData =
                    (track.durationMs <= 0L && info.duration > 0L) ||
                        (track.bitrate <= 0L && info.bitrate > 0L) ||
                        (track.container.isBlank() && info.extension.isNotBlank())

                // Une extraction partielle est tout de même publiée. Elle ne verrouille pas la
                // piste : le champ encore manquant sera retenté lors de la prochaine accalmie.
                val candidateKey = automaticTechnicalAttemptKey(candidate)
                if (!hasDuration || !hasQuality) {
                    val retryCount = automaticTechnicalRetryCounts.merge(
                        candidateKey,
                        1,
                        Int::plus
                    ) ?: 1
                    if (retryCount <= AUTOMATIC_TECHNICAL_MAX_RETRIES) {
                        automaticDurationAttempts.remove(candidateKey)
                        automaticDurationAttempts.remove(attemptKey)
                        retryNeeded.set(true)
                    }
                } else {
                    automaticTechnicalRetryCounts.remove(candidateKey)
                    automaticTechnicalRetryCounts.remove(attemptKey)
                }
                info.takeIf { addsUsefulData }
            },
            concurrency = 1,
            batchSize = 32,
            allowDuringIndexing = allowDuringIndexing
        )
        return retryNeeded.get()
    }

    private fun scheduleAutomaticTechnicalRetry() {
        if (!automaticTechnicalRetryScheduled.compareAndSet(false, true)) return
        repositoryScope.launch {
            delay(AUTOMATIC_TECHNICAL_RETRY_DELAY_MS)
            automaticTechnicalRetryScheduled.set(false)
            automaticHydrationRequests.trySend(Unit)
        }
    }

    private suspend fun checkpointHydratedSnapshot() {
        val snapshot = AudioLibraryMemoryStore.current()
        if (!snapshot.ready || snapshot.tracks.isEmpty()) return
        AudioLibraryWorkState.awaitPlaybackIdle {
            BlazePlayerService.isAudioPlaybackActive
        }
        AudioLibraryBootstrapStore.save(
            applicationContext,
            AudioLibraryMemoryStore.current()
        )
    }

    private fun preferredAutomaticArtworkPath(track: LibraryTrack): String? {
        val indexedName = AudioLibraryHeuristics.fileNameFromPath(track.artworkPath)
        if (
            AudioLibraryHeuristics.isArtworkReference(track.artworkPath) &&
            (
                track.artworkPath.startsWith("http://", true) ||
                    track.artworkPath.startsWith("https://", true) ||
                    AudioLibraryHeuristics.isPreferredCoverName(indexedName)
            )
        ) {
            return track.artworkPath
        }
        if (track.source != LibraryTrackSource.NETWORK) {
            ThumbnailUtils.fastPreferredFolderCoverPathForAudioPath(track.path)?.let {
                return it
            }
        }
        return track.artworkPath.takeIf(
            AudioLibraryHeuristics::isArtworkReference
        )
    }

    private fun needsAutomaticArtworkHydration(track: LibraryTrack): Boolean {
        AudioArtworkPersistence.existingPath(applicationContext, track.path)?.let { persisted ->
            // Une cover peut déjà avoir été extraite sur disque alors qu'un ancien squelette a
            // restauré artworkPath vide. Dans ce cas il faut réadopter le chemin dans le snapshot,
            // et non considérer l'album comme terminé simplement parce que le JPEG existe.
            return track.artworkPath != persisted
        }
        val preferred = preferredAutomaticArtworkPath(track)
        val primary = preferred ?: track.path
        if (ThumbnailUtils.getMemoryCachedAudioArtworkBitmapNoIo(primary) != null) {
            return false
        }
        if (
            ThumbnailUtils.getCachedAudioArtworkBitmapNoFolderProbe(
                applicationContext,
                primary
            ) != null
        ) {
            return false
        }
        return true
    }

    suspend fun updateArtworkPath(context: Context, path: String, artworkPath: String) {
        val albumPaths = withContext(AudioLibraryBackgroundDispatchers.compute) {
            AudioLibraryMemoryStore.updateArtwork(path, artworkPath)
            val snapshot = AudioLibraryMemoryStore.current()
            val track = snapshot.tracksByPath[path]
                ?: snapshot.trackIndexByCanonicalPath[
                    AudioLibraryHeuristics.canonicalPathKey(path)
                ]?.let(snapshot.tracks::get)
            track?.let {
                snapshot.albumTracksByKey[AudioLibraryHeuristics.albumKey(it)]
                    ?.map(LibraryTrack::path)
            }.orEmpty()
        }
        AudioArtworkResolver.rememberPersistedArtworkPaths(
            albumPaths.ifEmpty { listOf(path) },
            artworkPath
        )
        withContext(roomDispatcher) {
            if (albumPaths.isNotEmpty()) {
                AudioLibraryRoomStore.updateArtworkPaths(
                    context.applicationContext,
                    albumPaths,
                    artworkPath
                )
            } else {
                AudioLibraryRoomStore.updateArtworkPath(
                    context.applicationContext,
                    path,
                    artworkPath
                )
            }
        }
    }

    suspend fun deleteFolders(context: Context, folderKeys: Set<String>) {
        withContext(roomDispatcher) {
            AudioLibraryRoomStore.deleteFolders(context.applicationContext, folderKeys)
            val remaining = AudioLibraryRoomStore.loadActive(context.applicationContext)
                .map { it.toLibraryTrack(context) }
            publishFull(remaining, AudioLibrarySnapshotOrigin.FOLDER_CHANGE, ready = true)
        }
    }

    suspend fun clear(context: Context) {
        AudioLibraryMemoryStore.clear()
        withContext(roomDispatcher) {
            AudioLibraryRoomStore.clear(context.applicationContext)
        }
    }

    // ---------------------------------------------------------------------
    // Scan MediaStore (local, indexé par Android)
    // ---------------------------------------------------------------------

    private fun queryMediaStoreWatchedTracks(
        context: Context,
        minGenerationExclusive: Long? = null
    ): List<LibraryTrack> =
        queryMediaStoreTracksForFolders(
            context,
            AudioProSettings.watchedFolders(context).filterNot { it.isNetwork },
            minGenerationExclusive
        ).tracks

    private fun queryMediaStoreWatchedTracksResult(
        context: Context,
        minGenerationExclusive: Long? = null
    ): MediaStoreTrackQuery =
        queryMediaStoreTracksForFolders(
            context,
            AudioProSettings.watchedFolders(context).filterNot { it.isNetwork },
            minGenerationExclusive
        )

    /**
     * Requête MediaStore indépendante de IS_MUSIC. Certains encodeurs, fichiers courts ou albums
     * copiés récemment sont classés par Android avec IS_MUSIC=0 alors qu'ils sont parfaitement
     * lisibles. Le dossier surveillé et l'extension/MIME sont les filtres fiables.
     */
    private fun queryMediaStoreTracksForFolders(
        context: Context,
        watched: List<AudioProSettings.WatchedFolder>,
        minGenerationExclusive: Long? = null
    ): MediaStoreTrackQuery {
        if (watched.isEmpty()) return MediaStoreTrackQuery(emptyList(), success = true)

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.GENERATION_MODIFIED)
            }
        }.toTypedArray()

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            minGenerationExclusive != null &&
            minGenerationExclusive > 0L
        ) {
            selectionParts += "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?"
            selectionArgs += minGenerationExclusive.toString()
        }

        val out = ArrayList<LibraryTrack>()
        val success = runCatching {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
                selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray(),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            ) ?: return@runCatching false
            cursor.use {
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val displayNameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val relativePathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                val addedIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val modifiedIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val trackIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val mimeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)

                while (cursor.moveToNext() && out.size < MAX_MANUAL_LOCAL_SCAN_TRACKS) {
                    val displayName = cursor.stringOrEmpty(displayNameIdx)
                    val relativePath = cursor.stringOrEmpty(relativePathIdx)
                    val rawDataPath = cursor.stringOrEmpty(dataIdx)
                    val path = rawDataPath.ifBlank {
                        buildPrimaryStoragePath(relativePath, displayName)
                    }
                    val mime = cursor.stringOrEmpty(mimeIdx)
                    if (
                        path.isBlank() ||
                        !AudioLibraryHeuristics.isAudioItem(
                            displayName.substringAfterLast('.', ""),
                            mime,
                            path
                        ) ||
                        watched.none { AudioLibraryHeuristics.belongsToLocalFolder(path, it) }
                    ) {
                        continue
                    }

                    val folderMeta = AudioLibraryHeuristics.folderMetadata(
                        path,
                        displayName.ifBlank { AudioLibraryHeuristics.fileNameFromPath(path) }
                    )
                    val mediaTitle = cursor.stringOrEmpty(titleIdx)
                    val mediaArtist = cursor.stringOrEmpty(artistIdx)
                    val mediaAlbum = cursor.stringOrEmpty(albumIdx)
                    val mediaTrackNumber = cursor.intOrZero(trackIdx)
                    val normalizedTrackNumber = when {
                        mediaTrackNumber <= 0 -> 0
                        mediaTrackNumber >= 1000 -> mediaTrackNumber % 1000
                        else -> mediaTrackNumber
                    }

                    out += LibraryTrack(
                        id = cursor.getLong(idIdx),
                        title = folderMeta.title.ifBlank { mediaTitle },
                        artist = folderMeta.artist.ifBlank { mediaArtist },
                        album = folderMeta.album.ifBlank { mediaAlbum },
                        durationMs = cursor.getLong(durationIdx).coerceAtLeast(0L),
                        bitrate = run {
                            val duration = cursor.getLong(durationIdx)
                                .coerceAtLeast(0L)
                            val size = cursor.longOrZero(sizeIdx)
                            if (duration > 0L && size > 0L) {
                                (size * 8_000L) / duration
                            } else {
                                0L
                            }
                        },
                        trackNo = normalizedTrackNumber.takeIf { it > 0 }
                            ?: AudioLibraryHeuristics.inferTrackNo(
                                displayName.ifBlank { AudioLibraryHeuristics.fileNameFromPath(path) }
                            ),
                        path = path,
                        addedAt = cursor.getLong(addedIdx),
                        artworkPath = ThumbnailUtils.fastPreferredFolderCoverPathForAudioPath(path)
                            ?: path,
                        source = LibraryTrackSource.LOCAL,
                        sourceLabel = watched.firstOrNull {
                            AudioLibraryHeuristics.belongsToLocalFolder(path, it)
                        }?.name?.ifBlank { "Local" } ?: "Local",
                        titleFromTag = folderMeta.title.isBlank() && mediaTitle.isNotBlank(),
                        albumFromTag = folderMeta.album.isBlank() && mediaAlbum.isNotBlank(),
                        artistFromTag = folderMeta.artist.isBlank() && mediaArtist.isNotBlank(),
                        container = AudioLibraryHeuristics.containerFrom(mime, path),
                        sizeBytes = cursor.longOrZero(sizeIdx),
                        modifiedAt = cursor.longOrZero(modifiedIdx).let { seconds ->
                            if (seconds > 0L) seconds * 1000L else 0L
                        }
                    )
                }
            }
            true
        }.getOrDefault(false)
        return MediaStoreTrackQuery(out.distinctBy { it.path }, success)
    }

    private fun buildPrimaryStoragePath(relativePath: String, displayName: String): String {
        if (displayName.isBlank()) return ""
        val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val relative = relativePath.replace('\\', '/').trim('/')
        return if (relative.isBlank()) "$root/$displayName" else "$root/$relative/$displayName"
    }

    private fun queryMediaStoreWatchedPaths(context: Context): Set<String>? {
        val watched = AudioProSettings.watchedFolders(context).filterNot { it.isNetwork }
        if (watched.isEmpty()) return emptySet()
        val out = linkedSetOf<String>()
        val projection = buildList {
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
        }.toTypedArray()
        return runCatching {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            ) ?: return@runCatching null
            cursor.use {
                val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val displayNameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val relativePathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                val mimeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val displayName = cursor.stringOrEmpty(displayNameIdx)
                    val path = cursor.stringOrEmpty(dataIdx).ifBlank {
                        buildPrimaryStoragePath(
                            cursor.stringOrEmpty(relativePathIdx),
                            displayName
                        )
                    }
                    val mime = cursor.stringOrEmpty(mimeIdx)
                    if (
                        path.isNotBlank() &&
                        AudioLibraryHeuristics.isAudioItem(
                            displayName.substringAfterLast('.', ""),
                            mime,
                            path
                        ) &&
                        watched.any { AudioLibraryHeuristics.belongsToLocalFolder(path, it) }
                    ) {
                        out += path
                    }
                }
            }
            out
        }.getOrNull()
    }

    private fun android.database.Cursor.stringOrEmpty(index: Int): String =
        if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""

    private fun android.database.Cursor.longOrZero(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    private fun android.database.Cursor.intOrZero(index: Int): Int =
        if (index >= 0 && !isNull(index)) getInt(index) else 0

    // ---------------------------------------------------------------------
    // Scan local (parcours de dossiers, hors index MediaStore)
    // ---------------------------------------------------------------------

    private fun scanWatchedLocalFolders(
        context: Context,
        manual: Boolean,
        mediaStoreTracks: List<LibraryTrack>
    ): LocalFolderScanResult {
        val watched = AudioProSettings.watchedFolders(context).filterNot { it.isNetwork }
        if (watched.isEmpty()) return LocalFolderScanResult(emptyList(), emptyList())

        val limit = if (manual) MAX_MANUAL_LOCAL_SCAN_TRACKS else MAX_LOCAL_SCAN_TRACKS
        val allTracks = ArrayList<LibraryTrack>()
        val confirmed = ArrayList<AudioProSettings.WatchedFolder>()

        // Les derniers dossiers cochés passent en premier : ils ne peuvent plus être affamés par
        // une ancienne bibliothèque volumineuse ayant déjà atteint la limite globale.
        for (folder in watched.asReversed()) {
            if (allTracks.size >= limit) break
            val mediaStoreForFolder = mediaStoreTracks.filter {
                AudioLibraryHeuristics.belongsToLocalFolder(it.path, folder)
            }
            val directResult = scanSingleLocalFolderDirect(
                context,
                folder,
                limit - allTracks.size
            )
            val merged = AudioLibraryHeuristics.mergeTracks(
                mediaStoreForFolder,
                directResult.tracks,
                context
            )
            allTracks += merged
            // La requête MediaStore globale ayant réussi avant cette passe, le dossier est
            // confirmable même si le parcours File est limité par le stockage cloisonné.
            if (directResult.confirmed || canReadLocalAudio()) confirmed += folder

            if (directResult.pathsToIndex.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    context.applicationContext,
                    directResult.pathsToIndex.take(4000).toTypedArray(),
                    null,
                    null
                )
            }
        }

        return LocalFolderScanResult(
            tracks = allTracks.distinctBy { it.path },
            confirmedFolders = confirmed.distinctBy { AudioWatchedLibraryCache.key(it) }
        )
    }

    private data class DirectLocalFolderScan(
        val tracks: List<LibraryTrack>,
        val confirmed: Boolean,
        val pathsToIndex: List<String>
    )

    private fun scanSingleLocalFolderDirect(
        context: Context,
        folder: AudioProSettings.WatchedFolder,
        limit: Int
    ): DirectLocalFolderScan {
        if (limit <= 0) return DirectLocalFolderScan(emptyList(), false, emptyList())
        val rootFile = File(folder.path)
        if (!rootFile.exists() || !rootFile.isDirectory) {
            return DirectLocalFolderScan(emptyList(), false, emptyList())
        }

        val reuseCachedDurations = AudioProSettings.read(context).ignoreShort
        val tracks = ArrayList<LibraryTrack>()
        val pathsToIndex = ArrayList<String>()
        val seen = HashSet<String>()
        var traversalSucceeded = false

        runCatching {
            rootFile.walkTopDown()
                .onEnter { directory ->
                    !directory.name.startsWith(".") && tracks.size < limit
                }
                .forEach { file ->
                    if (
                        tracks.size >= limit ||
                        !file.isFile ||
                        file.name.startsWith(".") ||
                        file.extension.lowercase(Locale.getDefault()) !in
                            AudioLibraryHeuristics.audioExtensions ||
                        !seen.add(file.absolutePath)
                    ) {
                        return@forEach
                    }

                    val folderMeta = AudioLibraryHeuristics.folderMetadata(
                        file.absolutePath,
                        file.name
                    )
                    val cachedDurationMs = if (reuseCachedDurations) {
                        AudioMetadataExtractor.getCached(context, file.absolutePath)
                            ?.duration
                            ?.takeIf { it > 0L }
                            ?.times(1000L)
                            ?: 0L
                    } else {
                        0L
                    }
                    tracks += LibraryTrack(
                        id = -abs(file.absolutePath.hashCode()).toLong(),
                        title = folderMeta.title,
                        artist = folderMeta.artist,
                        album = folderMeta.album,
                        durationMs = cachedDurationMs,
                        bitrate = if (
                            cachedDurationMs > 0L &&
                            file.length() > 0L
                        ) {
                            (file.length() * 8_000L) /
                                cachedDurationMs
                        } else {
                            AudioMetadataExtractor
                                .getCached(
                                    context,
                                    file.absolutePath
                                )
                                ?.bitrate
                                ?: 0L
                        },
                        trackNo = AudioLibraryHeuristics.inferTrackNo(file.name),
                        path = file.absolutePath,
                        addedAt = file.lastModified() / 1000L,
                        artworkPath = ThumbnailUtils.preferredFolderCoverPathForAudioPath(
                            file.absolutePath
                        ) ?: file.absolutePath,
                        source = LibraryTrackSource.LOCAL,
                        sourceLabel = folder.name.ifBlank { "Local" },
                        titleFromTag = false,
                        albumFromTag = false,
                        artistFromTag = false,
                        container = AudioLibraryHeuristics.containerFrom(
                            "",
                            file.absolutePath
                        ),
                        sizeBytes = file.length(),
                        modifiedAt = file.lastModified()
                    )
                    pathsToIndex += file.absolutePath
                }
            traversalSucceeded = true
        }

        return DirectLocalFolderScan(
            tracks = tracks,
            confirmed = traversalSucceeded,
            pathsToIndex = pathsToIndex
        )
    }

    // ---------------------------------------------------------------------
    // Scan réseau (SMB / UPnP)
    // ---------------------------------------------------------------------

    private suspend fun scanWatchedNetworkFolders(
        context: Context,
        manual: Boolean,
        isPlaybackCritical: () -> Boolean,
        onProgress: suspend (List<LibraryTrack>) -> Unit
    ): NetworkFolderScanResult {
        val watched = AudioProSettings.watchedFolders(context).filter { it.isNetwork }
        if (watched.isEmpty()) return NetworkFolderScanResult(emptyList(), emptyList())

        val allTracks = mutableListOf<LibraryTrack>()
        val confirmedFolders = mutableListOf<AudioProSettings.WatchedFolder>()
        for (folder in watched.asReversed()) {
            if (!currentCoroutineContext().isActive) break
            val result = scanSingleNetworkWatchedFolder(
                context,
                folder,
                manual,
                isPlaybackCritical,
                onProgress
            )
            allTracks += result.tracks
            confirmedFolders += result.confirmedFolders
        }
        return NetworkFolderScanResult(
            tracks = allTracks.distinctBy { it.path },
            confirmedFolders = confirmedFolders.distinctBy {
                AudioWatchedLibraryCache.key(it)
            }
        )
    }


}

// ---------------------------------------------------------------------
// Conversions Room <-> domaine, extraites de AudioLibraryActivity.
// ---------------------------------------------------------------------

private fun LibraryTrack.toMetadataUpdateEntity(info: AudioTechnicalInfo): AudioLibraryTrackEntity {
    val structurePath = AudioLibraryHeuristics.structuralPath(this)
    val folderMeta = AudioLibraryHeuristics.folderMetadata(
        structurePath,
        AudioLibraryHeuristics.fileNameFromPath(structurePath)
    )
    return AudioLibraryTrackEntity(
        path = path,
        libraryPath = structurePath,
        name = AudioLibraryHeuristics.fileNameFromPath(structurePath),
        title = folderMeta.title,
        artist = folderMeta.artist,
        album = folderMeta.album,
        durationMs = if (info.duration > 0L) info.duration * 1000L else durationMs,
        bitrate = info.bitrate.takeIf { it > 0L } ?: bitrate,
        trackNumber = AudioLibraryHeuristics.inferTrackNo(AudioLibraryHeuristics.fileNameFromPath(structurePath)),
        addedAt = addedAt,
        extension = info.extension,
        isNetwork = source == LibraryTrackSource.NETWORK,
        shareId = "",
        sourceLabel = sourceLabel,
        // Volontairement vide : les données techniques ne doivent jamais toucher la pochette déjà
        // connue en base. Elle est mise à jour séparément, après résolution embedded/cover.jpg/png.
        artworkPath = "",
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        folderKey = "",
        seenGeneration = 0L,
        titleFromTag = false,
        artistFromTag = false,
        albumFromTag = false,
        metadataVersion = 0,
        artworkVersion = 0,
        deleted = false,
        albumSortKey = "",
        artistSortKey = "",
        titleSortKey = ""
    )
}

internal fun AudioLibraryTrackEntity.toLibraryTrack(context: Context): LibraryTrack {
    val structurePath = libraryPath.ifBlank { path }
    val folderMeta = AudioLibraryHeuristics.folderMetadata(
        structurePath,
        name.ifBlank { AudioLibraryHeuristics.fileNameFromPath(structurePath) }
    )
    return LibraryTrack(
        id = -abs(path.hashCode()).toLong(),
        title = folderMeta.title,
        artist = folderMeta.artist,
        album = folderMeta.album,
        durationMs = durationMs,
        bitrate = bitrate,
        trackNo = AudioLibraryHeuristics.inferTrackNo(name.ifBlank { AudioLibraryHeuristics.fileNameFromPath(structurePath) }),
        path = path,
        addedAt = addedAt,
        artworkPath = artworkPath.ifBlank { path },
        source = if (isNetwork) LibraryTrackSource.NETWORK else LibraryTrackSource.LOCAL,
        sourceLabel = sourceLabel.ifBlank { if (isNetwork) context.getString(R.string.tab_network) else "Local" },
        titleFromTag = false,
        albumFromTag = false,
        artistFromTag = false,
        container = AudioLibraryHeuristics.containerFrom(extension, path),
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        libraryPath = structurePath
    )
}

private fun LibraryTrack.toRoomEntity(context: Context, generation: Long, watchedFolders: List<AudioProSettings.WatchedFolder>): AudioLibraryTrackEntity? {
    val structurePath = AudioLibraryHeuristics.structuralPath(this)
    val folder = watchedFolders.firstOrNull {
        if (it.isNetwork) {
            AudioLibraryHeuristics.belongsToNetworkFolder(path, it) ||
                AudioLibraryHeuristics.belongsToNetworkFolder(structurePath, it)
        } else {
            AudioLibraryHeuristics.belongsToLocalFolder(path, it)
        }
    } ?: return null
    val localFile = if (!folder.isNetwork) runCatching { File(path) }.getOrNull() else null
    val size = sizeBytes.takeIf { it > 0L } ?: localFile?.takeIf { it.exists() }?.length() ?: 0L
    val mtime = modifiedAt.takeIf { it > 0L } ?: localFile?.takeIf { it.exists() }?.lastModified() ?: 0L
    val ext = AudioLibraryHeuristics.containerLabel(this).ifBlank { path.substringBefore('?').substringAfterLast('.', "") }
    val folderMeta = AudioLibraryHeuristics.folderMetadata(
        structurePath,
        AudioLibraryHeuristics.fileNameFromPath(structurePath)
    )
    return AudioLibraryTrackEntity(
        path = path,
        libraryPath = structurePath,
        name = AudioLibraryHeuristics.fileNameFromPath(structurePath),
        title = folderMeta.title,
        artist = folderMeta.artist,
        album = folderMeta.album,
        durationMs = durationMs,
        bitrate = bitrate,
        trackNumber = AudioLibraryHeuristics.inferTrackNo(AudioLibraryHeuristics.fileNameFromPath(structurePath)),
        addedAt = addedAt.takeIf { it > 0L } ?: (System.currentTimeMillis() / 1000L),
        extension = ext,
        isNetwork = folder.isNetwork || source == LibraryTrackSource.NETWORK || AudioLibraryHeuristics.isNetworkPath(path),
        shareId = folder.shareId,
        sourceLabel = if (folder.isNetwork) context.getString(R.string.tab_network) else folder.name.ifBlank { "Local" },
        artworkPath = artworkPath,
        sizeBytes = size,
        modifiedAt = mtime,
        folderKey = AudioWatchedLibraryCache.key(folder),
        seenGeneration = generation,
        titleFromTag = false,
        artistFromTag = false,
        albumFromTag = false,
        metadataVersion = if (durationMs > 0L || bitrate > 0L || trackNo > 0) 1 else 0,
        artworkVersion = if (artworkPath.isNotBlank()) 1 else 0,
        deleted = false,
        albumSortKey = "",
        artistSortKey = "",
        titleSortKey = ""
    )
}
