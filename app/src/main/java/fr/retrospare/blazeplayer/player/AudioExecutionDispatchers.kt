package fr.retrospare.blazeplayer.player

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pools d'exécution explicitement séparés pour Blaze Audio.
 *
 * L'objectif n'est pas de remplacer les threads temps-réel internes d'ExoPlayer/AudioTrack, mais
 * d'empêcher les tâches annexes du lecteur (restauration de file, pochettes, LRC, couleurs,
 * persistance de session) de partager les pools globaux Dispatchers.IO/Default avec le scan,
 * Room et l'hydratation de la bibliothèque.
 */
object AudioPlaybackDispatchers {
    val service: CoroutineDispatcher = fixedDispatcher(
        prefix = "BlazePlaybackService",
        threadCount = 2,
        processPriority = Process.THREAD_PRIORITY_DEFAULT,
        javaPriority = Thread.NORM_PRIORITY + 1
    )

    val io: CoroutineDispatcher = fixedDispatcher(
        prefix = "BlazePlaybackIo",
        threadCount = 2,
        processPriority = Process.THREAD_PRIORITY_DEFAULT,
        javaPriority = Thread.NORM_PRIORITY + 1
    )

    val lyrics: CoroutineDispatcher = fixedDispatcher(
        prefix = "BlazeLyrics",
        threadCount = 1,
        processPriority = Process.THREAD_PRIORITY_DEFAULT,
        javaPriority = Thread.NORM_PRIORITY
    )

    val compute: CoroutineDispatcher = fixedDispatcher(
        prefix = "BlazePlaybackCompute",
        threadCount = 1,
        processPriority = Process.THREAD_PRIORITY_DISPLAY,
        javaPriority = Thread.NORM_PRIORITY + 1
    )
}

/**
 * Pools auxiliaires de la bibliothèque. Ils sont volontairement distincts des pools du lecteur.
 * Les workers principaux de scan/hydratation conservent leurs dispatchers dynamiques dans
 * [AudioLibraryRepository] ; ces pools couvrent le coordinateur, les snapshots binaires et les
 * calculs annexes qui utilisaient encore les dispatchers globaux.
 */
object AudioLibraryBackgroundDispatchers {
    val coordinator: CoroutineDispatcher = fixedDispatcher(
        prefix = "BlazeLibraryCoordinator",
        threadCount = 1,
        processPriority = Process.THREAD_PRIORITY_BACKGROUND,
        javaPriority = Thread.NORM_PRIORITY
    )

    val io: CoroutineDispatcher = fixedDispatcher(
        prefix = "BlazeLibraryStore",
        threadCount = 1,
        processPriority = Process.THREAD_PRIORITY_BACKGROUND,
        javaPriority = Thread.NORM_PRIORITY
    )

    val compute: CoroutineDispatcher = fixedDispatcher(
        prefix = "BlazeLibraryCompute",
        threadCount = 2,
        processPriority = Process.THREAD_PRIORITY_BACKGROUND,
        javaPriority = Thread.NORM_PRIORITY
    )

    val visibleArtwork: CoroutineDispatcher = fixedDispatcher(
        prefix = "BlazeLibraryVisibleArtwork",
        threadCount = 2,
        processPriority = Process.THREAD_PRIORITY_BACKGROUND,
        javaPriority = Thread.NORM_PRIORITY
    )

    val network: CoroutineDispatcher = fixedDispatcher(
        prefix = "BlazeLibraryNetwork",
        threadCount = 4,
        processPriority = Process.THREAD_PRIORITY_BACKGROUND,
        javaPriority = Thread.NORM_PRIORITY
    )
}

private fun fixedDispatcher(
    prefix: String,
    threadCount: Int,
    processPriority: Int,
    javaPriority: Int
): CoroutineDispatcher {
    val counter = AtomicInteger(0)
    val factory = ThreadFactory { runnable ->
        Thread {
            runCatching { Process.setThreadPriority(processPriority) }
            runnable.run()
        }.apply {
            name = "$prefix-${counter.incrementAndGet()}"
            isDaemon = true
            priority = javaPriority.coerceIn(Thread.MIN_PRIORITY, Thread.MAX_PRIORITY)
        }
    }
    return Executors.newFixedThreadPool(threadCount.coerceAtLeast(1), factory)
        .asCoroutineDispatcher()
}
