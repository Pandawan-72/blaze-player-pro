package fr.retrospare.blazeplayer.player

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper

/**
 * Capture FFT audio partagée pour les visualiseurs Blaze Audio.
 *
 * Android n'aime pas avoir plusieurs android.media.audiofx.Visualizer actifs sur la même session
 * audio : le mini-player peut alors couper le visualiseur du grand player, ou inversement. Cette
 * source unique capture la FFT une seule fois puis la distribue à toutes les vues visibles. Chaque
 * vue garde son propre rendu, son accent et son animation, mais aucune ne vole la session audio.
 */
object AudioFftStream {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashMap<String, (ByteArray?) -> Unit>()

    private var visualizer: Visualizer? = null
    private var currentSessionId: Int = 0

    fun attach(tag: String, sessionId: Int, onFft: (ByteArray?) -> Unit) {
        if (sessionId == 0) {
            detach(tag)
            onFft(null)
            return
        }

        listeners[tag] = onFft
        if (visualizer != null && currentSessionId == sessionId) return

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
    }
}
