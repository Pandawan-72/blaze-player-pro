package fr.retrospare.blazeplayer

import android.content.Context
import android.os.Process
import fr.retrospare.blazeplayer.debug.CrashReporter
import fr.retrospare.blazeplayer.player.AudioAlbumArtworkAtlas
import fr.retrospare.blazeplayer.player.AudioLibraryBootstrapStore
import fr.retrospare.blazeplayer.player.AudioLibraryWorkState
import fr.retrospare.blazeplayer.player.BlazePlayerService
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Réchauffement différé des caches lourds.
 *
 * Application.onCreate() doit rester presque vide : les trois raccourcis Android partagent le même
 * processus et ne doivent pas attendre la bibliothèque audio ni les miniatures Galerie. Audio et
 * Gallery disposent de workers distincts, à basse priorité, afin qu'un gros snapshot musical ne
 * bloque pas le préchargement MediaStore de la galerie.
 */
object BlazeStartupWarmup {
    // Sans demande explicite, la bibliothèque complète attend que le lancement soit largement fini.
    private const val DEFAULT_AUDIO_DELAY_MS = 8_000L
    private const val DEFAULT_GALLERY_DELAY_MS = 1_800L
    // Lors d'un appui sur l'icône Audio, laisse tout de même la fenêtre dessiner sa première frame.
    private const val PRIORITY_FIRST_FRAME_GRACE_MS = 180L
    private const val PLAYBACK_RESTORE_GRACE_MS = 2_400L

    private val audioWarmupTid = AtomicInteger(0)
    private val audioInteractiveRequested = AtomicBoolean(false)

    private fun deferredScope(
        threadName: String,
        tidSink: AtomicInteger? = null
    ): CoroutineScope {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread {
                tidSink?.set(Process.myTid())
                val priority = if (
                    threadName == "BlazeAudioDeferredStartup" &&
                    audioInteractiveRequested.get() &&
                    !AudioLibraryWorkState.isPlaybackProtected()
                ) {
                    Process.THREAD_PRIORITY_DEFAULT
                } else {
                    Process.THREAD_PRIORITY_BACKGROUND
                }
                runCatching { Process.setThreadPriority(priority) }
                runnable.run()
            }.apply {
                name = threadName
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }.asCoroutineDispatcher()
        return CoroutineScope(SupervisorJob() + dispatcher)
    }

    private val audioScope by lazy {
        deferredScope("BlazeAudioDeferredStartup", audioWarmupTid)
    }
    private val galleryScope by lazy {
        deferredScope("BlazeGalleryDeferredStartup")
    }
    private val scheduled = AtomicBoolean(false)
    private val audioWarmupStarted = AtomicBoolean(false)
    private val galleryWarmupStarted = AtomicBoolean(false)
    private val audioPrioritySignal = CompletableDeferred<Unit>()
    private val galleryPrioritySignal = CompletableDeferred<Unit>()

    fun schedule(application: BlazePlayerApp) {
        if (!scheduled.compareAndSet(false, true)) return
        val appContext = application.applicationContext

        audioScope.launch {
            val explicitlyRequested = withTimeoutOrNull(DEFAULT_AUDIO_DELAY_MS) {
                audioPrioritySignal.await()
                true
            } ?: false
            val playbackProtected = AudioLibraryWorkState.isPlaybackProtected() ||
                BlazePlayerService.isAudioSessionActive
            if (explicitlyRequested) {
                delay(if (playbackProtected) PLAYBACK_RESTORE_GRACE_MS else PRIORITY_FIRST_FRAME_GRACE_MS)
            }
            if ((explicitlyRequested || audioInteractiveRequested.get()) && !playbackProtected) {
                promoteAudioWarmupThread()
            }
            warmAudio(
                application,
                interactive = explicitlyRequested || audioInteractiveRequested.get()
            )
        }
        galleryScope.launch {
            withTimeoutOrNull(DEFAULT_GALLERY_DELAY_MS) { galleryPrioritySignal.await() }
            warmGallery(appContext)
        }
    }

    fun requestAudioPriority(context: Context) {
        val application = context.applicationContext as? BlazePlayerApp ?: return
        schedule(application)
        audioInteractiveRequested.set(true)
        val playbackProtected = AudioLibraryWorkState.isPlaybackProtected() ||
            BlazePlayerService.isAudioSessionActive
        if (playbackProtected) {
            // Le retour dans Blaze Audio ne doit jamais promouvoir Room/snapshot au moment où
            // Media3 restaure sa session et le visualiseur. La bibliothèque reste néanmoins sur
            // ses propres threads et reprendra sa vitesse normale après cette courte fenêtre.
            AudioLibraryWorkState.beginPlaybackCriticalWindow(4_500L)
        } else {
            promoteAudioWarmupThread()
        }
        audioPrioritySignal.complete(Unit)

        if (audioWarmupStarted.get()) {
            runCatching {
                application.audioLibraryRepository.get().setInteractiveLoading(true)
            }
        }
    }

    private fun promoteAudioWarmupThread() {
        if (AudioLibraryWorkState.isPlaybackProtected()) return
        val tid = audioWarmupTid.get()
        if (tid > 0) {
            runCatching {
                Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT)
            }
        }
    }

    fun requestGalleryPriority(context: Context) {
        (context.applicationContext as? BlazePlayerApp)?.let(::schedule)
        galleryPrioritySignal.complete(Unit)
    }

    private suspend fun warmAudio(application: BlazePlayerApp, interactive: Boolean) {
        if (!audioWarmupStarted.compareAndSet(false, true)) {
            if (interactive) {
                runCatching {
                    application.audioLibraryRepository.get().setInteractiveLoading(true)
                }
            }
            return
        }
        val context = application.applicationContext
        if (interactive) promoteAudioWarmupThread()

        // À la réouverture de l'application, aucune lecture de snapshot/atlas ne doit coïncider
        // avec le rattachement du MediaController, la restauration de l'AudioTrack et du Visualizer.
        // Après cette fenêtre, le warmup reprend sur son thread BlazeAudioDeferredStartup dédié.
        AudioLibraryWorkState.awaitPlaybackCriticalWindowEnd()

        // La projection Albums est minuscule et peut être publiée avant le snapshot complet.
        runCatching { AudioLibraryBootstrapStore.restoreAlbumBootstrapBlocking(context) }
            .onFailure { CrashReporter.log(context, "Audio album bootstrap warmup failed", it) }

        // Décodage des pages d'atlas hors du thread UI.
        runCatching { AudioAlbumArtworkAtlas.loadBlocking(context) }
            .onFailure { CrashReporter.log(context, "Audio artwork atlas warmup failed", it) }

        // Restauration complète puis démarrage des vérifications Room/MediaStore en arrière-plan.
        runCatching { AudioLibraryBootstrapStore.restoreBlocking(context) }
            .onFailure { CrashReporter.log(context, "Audio full bootstrap warmup failed", it) }
        runCatching {
            application.audioLibraryRepository.get().apply {
                // Le flag interactif reste vrai même pendant la lecture : currentLibraryThreadPriority
                // maintient alors automatiquement les workers en BACKGROUND, puis les remonte au
                // niveau normal dès la pause sans nécessiter une nouvelle ouverture d'écran.
                setInteractiveLoading(interactive)
                start()
            }
        }.onFailure {
            CrashReporter.log(context, "Audio repository deferred start failed", it)
        }
    }

    private suspend fun warmGallery(context: Context) {
        if (!galleryWarmupStarted.compareAndSet(false, true)) return

        // Le warmup Galerie peut ouvrir MediaStore et lancer plusieurs décodages de miniatures.
        // Même s'il possède ses propres workers, il ne doit jamais démarrer pendant une lecture
        // audio : l'AudioTrack, le réseau SMB/UPnP et le rendu LRC/FFT restent prioritaires.
        AudioLibraryWorkState.awaitPlaybackIdle()

        // L'initialisation de ThumbnailUtils (caches, pools, dispatchers) se produit elle aussi ici,
        // et non plus pendant Application.onCreate().
        runCatching {
            ThumbnailUtils.scheduleGalleryMediaStoreWarmup(context, initialDelayMs = 0L)
        }.onFailure {
            CrashReporter.log(context, "Gallery deferred warmup failed", it)
        }
    }
}
