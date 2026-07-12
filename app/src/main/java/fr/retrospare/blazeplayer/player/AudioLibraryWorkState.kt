package fr.retrospare.blazeplayer.player

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordination légère entre la lecture audio et les tâches lourdes de la bibliothèque.
 *
 * Les parcours NAS, extractions de durées, calculs de débit et lectures de pochettes peuvent tous
 * ouvrir le même partage réseau que le Player. Ils sont donc mis en pause pendant une lecture afin
 * de réserver la bande passante et les threads de décodage à Media3.
 */
object AudioLibraryWorkState {
    private val activeIndexingCount = AtomicInteger(0)

    val isIndexing: Boolean
        get() = activeIndexingCount.get() > 0

    fun beginIndexing() {
        activeIndexingCount.incrementAndGet()
    }

    fun endIndexing() {
        if (activeIndexingCount.decrementAndGet() < 0) activeIndexingCount.set(0)
    }

    fun isPlaybackProtected(extraCritical: () -> Boolean = { false }): Boolean =
        BlazePlayerService.isAudioPlaybackActive || runCatching { extraCritical() }.getOrDefault(false)

    fun shouldDeferEnrichment(): Boolean = isIndexing || BlazePlayerService.isAudioPlaybackActive

    suspend fun awaitPlaybackIdle(extraCritical: () -> Boolean = { false }) {
        while (currentCoroutineContext().isActive && isPlaybackProtected(extraCritical)) {
            delay(400L)
        }
    }

    suspend fun awaitEnrichmentWindow() {
        while (currentCoroutineContext().isActive && shouldDeferEnrichment()) {
            delay(450L)
        }
    }
}
