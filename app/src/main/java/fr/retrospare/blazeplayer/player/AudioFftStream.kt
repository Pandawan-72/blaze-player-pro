package fr.retrospare.blazeplayer.player

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Capture FFT audio partagée pour les visualiseurs Blaze Audio.
 *
 * Android n'aime pas avoir plusieurs android.media.audiofx.Visualizer actifs sur la même session
 * audio : le mini-player peut alors couper le visualiseur du grand player, ou inversement. Cette
 * source unique capture la FFT une seule fois puis la distribue à toutes les vues visibles. Chaque
 * vue garde son propre rendu, son accent et son animation, mais aucune ne vole la session audio.
 *
 * Certains appareils interrompent silencieusement les callbacks FFT lors d'un changement de piste
 * ou d'une recréation de l'AudioTrack, tout en conservant le même audioSessionId. Le paramètre
 * forceRestart permet donc de recréer réellement le Visualizer même si l'identifiant n'a pas changé.
 */
object AudioFftStream {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashMap<String, (ByteArray?) -> Unit>()

    private var visualizer: Visualizer? = null
    private var currentSessionId: Int = 0

    @Volatile
    private var lastCaptureAtMs: Long = 0L

    fun attach(
        tag: String,
        sessionId: Int,
        onFft: (ByteArray?) -> Unit,
        forceRestart: Boolean = false
    ) {
        if (sessionId == 0) {
            detach(tag)
            onFft(null)
            return
        }

        listeners[tag] = onFft
        val sameSessionStillEnabled = visualizer != null &&
            currentSessionId == sessionId &&
            runCatching { visualizer?.enabled == true }.getOrDefault(false)
        if (!forceRestart && sameSessionStillEnabled) return

        releaseVisualizerOnly()
        currentSessionId = sessionId
        try {
            val range = Visualizer.getCaptureSizeRange()
            val captureSize = range.lastOrNull() ?: 1024
            visualizer = Visualizer(sessionId).apply {
                enabled = false
                setCaptureSize(captureSize)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        lastCaptureAtMs = SystemClock.elapsedRealtime()
                        val safeFft = fft?.copyOf()
                        mainHandler.post {
                            val callbacks = listeners.values.toList()
                            callbacks.forEach { callback -> runCatching { callback(safeFft) } }
                        }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (e: Exception) {
            releaseVisualizerOnly()
            val callbacks = listeners.values.toList()
            callbacks.forEach { callback -> runCatching { callback(null) } }
            throw e
        }
    }

    fun isRunning(sessionId: Int): Boolean =
        sessionId != 0 &&
            currentSessionId == sessionId &&
            visualizer != null &&
            runCatching { visualizer?.enabled == true }.getOrDefault(false)

    fun millisSinceLastCapture(nowMs: Long = SystemClock.elapsedRealtime()): Long {
        val capturedAt = lastCaptureAtMs
        return if (capturedAt <= 0L) Long.MAX_VALUE else (nowMs - capturedAt).coerceAtLeast(0L)
    }

    fun detach(tag: String) {
        listeners.remove(tag)
        if (listeners.isEmpty()) releaseVisualizerOnly()
    }

    fun detachAll() {
        listeners.clear()
        releaseVisualizerOnly()
    }

    private fun releaseVisualizerOnly() {
        try { visualizer?.enabled = false } catch (_: Exception) {}
        try { visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        currentSessionId = 0
        lastCaptureAtMs = 0L
    }
}
