package fr.retrospare.blazeplayer.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordination réactive entre Media3 et les tâches lourdes de la bibliothèque.
 *
 * Deux états sont distincts :
 * - priorité d'hydratation élevée : ouverte dès qu'aucun morceau ne joue ou que le titre est en pause ;
 * - fenêtre d'enrichissement : ouverte lorsque la lecture est inactive ET qu'aucun scan structurel
 *   n'est en train de modifier la liste des fichiers.
 *
 * Les changements de lecture réveillent immédiatement les coroutines, sans polling de 400/450 ms.
 */
object AudioLibraryWorkState {
    private val activeIndexingCount = AtomicInteger(0)
    private val playbackActive = AtomicBoolean(false)
    private val criticalWindowUntilMs = AtomicLong(0L)
    private val criticalWindowGeneration = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val idlePriorityState = MutableStateFlow(true)
    private val enrichmentWindowState = MutableStateFlow(true)
    private val idlePriorityListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    val isIndexing: Boolean
        get() = activeIndexingCount.get() > 0

    val isIdleHydrationPriority: Boolean
        get() = idlePriorityState.value

    val isEnrichmentWindowOpen: Boolean
        get() = enrichmentWindowState.value

    fun beginIndexing() {
        activeIndexingCount.incrementAndGet()
        publishStates()
    }

    fun endIndexing() {
        if (activeIndexingCount.decrementAndGet() < 0) {
            activeIndexingCount.set(0)
        }
        publishStates()
    }

    /**
     * Appelé directement par BlazePlayerService à chaque play/pause/fin de lecture.
     */
    fun onPlaybackStateChanged(active: Boolean) {
        if (playbackActive.getAndSet(active) == active) return
        if (!active) {
            // Laisse Media3 terminer la transition play -> pause/ended avant de rendre les gros
            // workers à nouveau prioritaires. Cela évite une rafale Room/snapshot au moment exact
            // où l'AudioTrack libère ou reconfigure ses buffers.
            beginPlaybackCriticalWindow(900L)
        } else {
            publishStates()
        }
    }

    /**
     * Protège une courte phase de reprise du lecteur (rebind de l'Activity, restauration de file,
     * nouvelle session audio, retour KaraoKast). Même si le callback isPlaying n'a pas encore été
     * propagé, la bibliothèque reste en priorité arrière-plan et ses enrichissements lourds sont
     * suspendus jusqu'à l'expiration de cette fenêtre.
     */
    fun beginPlaybackCriticalWindow(durationMs: Long) {
        if (durationMs <= 0L) return
        val now = SystemClock.elapsedRealtime()
        val requestedUntil = now + durationMs
        criticalWindowUntilMs.updateAndGet { current -> maxOf(current, requestedUntil) }
        val generation = criticalWindowGeneration.incrementAndGet()
        val wasAlreadyNonIdle = !idlePriorityState.value
        publishStates()
        if (wasAlreadyNonIdle) {
            // Quand un morceau joue déjà, l'état idle est déjà false : un simple publishStates()
            // ne notifierait donc personne. Or la réouverture de l'app doit tout de même annuler
            // immédiatement une hydratation locale en cours avant le rebind Media3.
            idlePriorityListeners.forEach { listener ->
                runCatching { listener(false) }
            }
        }
        mainHandler.postDelayed({
            if (criticalWindowGeneration.get() == generation) {
                publishStates()
            } else if (!isCriticalWindowActive()) {
                publishStates()
            }
        }, durationMs + 40L)
    }

    val isPlaybackCriticalWindowActive: Boolean
        get() = isCriticalWindowActive()

    fun addIdlePriorityListener(listener: (Boolean) -> Unit) {
        idlePriorityListeners += listener
        runCatching { listener(idlePriorityState.value) }
    }

    fun removeIdlePriorityListener(listener: (Boolean) -> Unit) {
        idlePriorityListeners -= listener
    }

    fun isPlaybackProtected(extraCritical: () -> Boolean = { false }): Boolean =
        playbackActive.get() ||
            BlazePlayerService.isAudioPlaybackActive ||
            isCriticalWindowActive() ||
            runCatching { extraCritical() }.getOrDefault(false)

    fun shouldDeferEnrichment(): Boolean = !enrichmentWindowState.value

    suspend fun awaitPlaybackIdle(extraCritical: () -> Boolean = { false }) {
        while (currentCoroutineContext().isActive) {
            idlePriorityState.filter { it }.first()
            if (!runCatching { extraCritical() }.getOrDefault(false)) return
            delay(80L)
        }
    }

    /**
     * Attend uniquement la courte phase critique de rattachement/reprise Media3. Contrairement à
     * [awaitPlaybackIdle], cette fonction laisse ensuite l'hydratation locale continuer pendant une
     * lecture stable, sur ses threads arrière-plan dédiés. Les accès réseau et les extractions de
     * pochettes restent, eux, suspendus jusqu'à une vraie pause.
     */
    suspend fun awaitPlaybackCriticalWindowEnd() {
        while (currentCoroutineContext().isActive && isCriticalWindowActive()) {
            val remaining = (criticalWindowUntilMs.get() - SystemClock.elapsedRealtime())
                .coerceIn(20L, 180L)
            delay(remaining)
        }
    }

    suspend fun awaitEnrichmentWindow() {
        enrichmentWindowState.filter { it }.first()
    }

    private fun isCriticalWindowActive(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        val until = criticalWindowUntilMs.get()
        return until > 0L && nowMs < until
    }

    private fun publishStates() {
        val idle = !playbackActive.get() && !isCriticalWindowActive()
        val windowOpen = idle && !isIndexing

        if (idlePriorityState.value != idle) {
            idlePriorityState.value = idle
            idlePriorityListeners.forEach { listener ->
                runCatching { listener(idle) }
            }
        }

        if (enrichmentWindowState.value != windowOpen) {
            enrichmentWindowState.value = windowOpen
        }
    }
}
