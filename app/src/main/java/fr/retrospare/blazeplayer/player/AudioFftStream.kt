package fr.retrospare.blazeplayer.player

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Capture FFT partagée du lecteur audio.
 *
 * La création du Visualizer, ses callbacks natifs et le calcul des dix bandes sont exécutés sur
 * un HandlerThread dédié de priorité affichage. Le thread principal ne reçoit qu'un FloatArray de
 * dix valeurs, condensé à 30 envois/s maximum : un scan de bibliothèque ne peut donc plus bloquer
 * les calculs FFT ni créer une file de callbacks UI en retard.
 */
object AudioFftStream {
    private const val BAND_COUNT = 10
    private const val UI_FRAME_DELAY_MS = 33L
    private const val TARGET_CAPTURE_RATE_MILLI_HZ = 15_000

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fftThread = HandlerThread(
        "BlazeVisualizerFft",
        Process.THREAD_PRIORITY_DISPLAY
    ).apply { start() }
    private val fftHandler = Handler(fftThread.looper)

    private val lock = Any()
    private val listeners = LinkedHashMap<String, (FloatArray?) -> Unit>()
    private val spectrumProcessor = TenBandSpectrumProcessor()

    private var visualizer: Visualizer? = null
    private var currentSessionId: Int = 0
    private var streamGeneration: Long = 0L

    private data class PendingSpectrum(
        val generation: Long,
        val sessionId: Int,
        val values: FloatArray
    )

    private var pendingSpectrum: PendingSpectrum? = null
    private var uiDeliveryScheduled = false
    private var lastUiDeliveryAtMs = 0L

    @Volatile
    private var lastCaptureAtMs: Long = 0L

    /** Dernier spectre produit sur le thread FFT. Il permet à une vue qui revient à l'écran de
     *  se repeindre immédiatement sans attendre le prochain callback natif du Visualizer. */
    @Volatile
    private var lastSpectrum: FloatArray? = null

    /**
     * Réinitialise uniquement l'AGC des dix bandes pour une nouvelle piste. Le Visualizer reste
     * attaché à l'AudioTrack : aucune libération d'effet, aucune coupure audio et aucune attente de
     * nouvelle session. L'opération est sérialisée sur le thread FFT afin de ne jamais concurrencer
     * un callback natif en cours.
     */
    fun prepareForTrackTransition() {
        val expectedGeneration = synchronized(lock) {
            lastSpectrum = null
            pendingSpectrum = null
            streamGeneration
        }
        fftHandler.post {
            if (!isExpectedGeneration(expectedGeneration)) return@post
            spectrumProcessor.resetForTrackTransition()
        }
    }


    /**
     * Calibration dédiée au rattachement sur une session déjà en lecture. Contrairement à une
     * transition normale, le gain d'entrée est recalculé depuis les premières trames réellement
     * reçues afin de compenser les amplitudes très faibles renvoyées par certains appareils lors
     * d'un attachement tardif du Visualizer.
     */
    fun prepareForColdResume() {
        val expectedGeneration = synchronized(lock) {
            lastSpectrum = null
            pendingSpectrum = null
            streamGeneration
        }
        fftHandler.post {
            if (!isExpectedGeneration(expectedGeneration)) return@post
            spectrumProcessor.resetForColdResume()
        }
    }

    fun attach(
        tag: String,
        sessionId: Int,
        onSpectrum: (FloatArray?) -> Unit,
        forceRestart: Boolean = false
    ) {
        if (sessionId <= 0) {
            detach(tag)
            mainHandler.post { runCatching { onSpectrum(null) } }
            return
        }

        var generation = 0L
        var previous: Visualizer? = null
        var reuseExisting = false
        var replaySpectrum: FloatArray? = null
        var resetForFreshSession = false
        synchronized(lock) {
            listeners[tag] = onSpectrum
            val sameSessionStillEnabled =
                visualizer != null &&
                    currentSessionId == sessionId &&
                    runCatching { visualizer?.enabled == true }.getOrDefault(false)

            reuseExisting = !forceRestart && sameSessionStillEnabled
            if (reuseExisting) {
                generation = streamGeneration
                previous = null
                replaySpectrum = lastSpectrum?.copyOf()
            } else {
                generation = ++streamGeneration
                previous = visualizer
                visualizer = null
                currentSessionId = 0
                lastCaptureAtMs = 0L
                lastSpectrum = null
                pendingSpectrum = null
                resetForFreshSession = forceRestart
                replaySpectrum = null
            }
        }


        if (reuseExisting) {
            replaySpectrum?.let { spectrum ->
                mainHandler.post {
                    val callback = synchronized(lock) {
                        if (currentSessionId == sessionId) listeners[tag] else null
                    }
                    runCatching { callback?.invoke(spectrum) }
                }
            }
            return
        }

        fftHandler.post {
            releaseInstance(previous)
            if (!isExpectedGeneration(generation)) return@post
            if (resetForFreshSession) {
                spectrumProcessor.resetForFreshSession()
            } else {
                spectrumProcessor.reset()
            }

            var created: Visualizer? = null
            try {
                val range = Visualizer.getCaptureSizeRange()
                val minimumCaptureSize = range.firstOrNull() ?: 128
                val maximumCaptureSize = range.lastOrNull() ?: 1024
                val captureSize = 512.coerceIn(minimumCaptureSize, maximumCaptureSize)
                val maxCaptureRate = Visualizer.getMaxCaptureRate()
                val selectedCaptureRate = min(
                    maxCaptureRate,
                    TARGET_CAPTURE_RATE_MILLI_HZ
                ).coerceAtLeast(1)

                val instance = Visualizer(sessionId)
                created = instance
                // Capturer le signal réellement joué. Le mode NORMALIZED d'Android peut conserver
                // une échelle interne différente lorsqu'on rattache une activité à un AudioTrack
                // déjà actif, ce qui produit précisément des barres tassées. Notre AGC dédié fait
                // désormais toute la normalisation depuis une instance neuve par morceau.
                runCatching { instance.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED }
                    .onFailure {
                    }
                instance.enabled = false
                instance.setCaptureSize(captureSize)
                instance.setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
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
                            if (!isCurrentGeneration(generation, sessionId)) return
                            if (fft == null || fft.size < 4) return

                            val spectrum = spectrumProcessor.process(fft)
                            val capturedAt = SystemClock.elapsedRealtime()
                            lastCaptureAtMs = capturedAt
                            lastSpectrum = spectrum
                            scheduleUiDelivery(generation, sessionId, spectrum)
                        }
                    },
                    selectedCaptureRate,
                    false,
                    true
                )

                val accepted = synchronized(lock) {
                    if (generation != streamGeneration) {
                        false
                    } else {
                        visualizer = instance
                        currentSessionId = sessionId
                        true
                    }
                }
                if (!accepted) {
                    releaseInstance(instance)
                    return@post
                }

                instance.enabled = true
                if (!instance.enabled) {
                    throw IllegalStateException(
                        "Visualizer could not be enabled for session $sessionId"
                    )
                }

                if (!isCurrentGeneration(generation, sessionId)) {
                    releaseInstance(instance)
                }
            } catch (error: Throwable) {
                releaseInstance(created)
                val shouldNotify = synchronized(lock) {
                    if (generation == streamGeneration) {
                        streamGeneration++
                        visualizer = null
                        currentSessionId = 0
                        lastCaptureAtMs = 0L
                        lastSpectrum = null
                        pendingSpectrum = null
                        true
                    } else {
                        false
                    }
                }
                if (shouldNotify) notifyListeners(null)
            }
        }
    }

    fun isRunning(sessionId: Int): Boolean = synchronized(lock) {
        sessionId > 0 &&
            currentSessionId == sessionId &&
            visualizer != null &&
            runCatching { visualizer?.enabled == true }.getOrDefault(false)
    }

    fun isHealthy(
        sessionId: Int,
        maxSilenceMs: Long,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): Boolean = isRunning(sessionId) && millisSinceLastCapture(nowMs) <= maxSilenceMs

    fun millisSinceLastCapture(nowMs: Long = SystemClock.elapsedRealtime()): Long {
        val capturedAt = lastCaptureAtMs
        return if (capturedAt <= 0L) {
            Long.MAX_VALUE
        } else {
            (nowMs - capturedAt).coerceAtLeast(0L)
        }
    }

    fun hasCapturedSince(timestampMs: Long): Boolean =
        timestampMs <= 0L || lastCaptureAtMs >= timestampMs

    fun detach(tag: String) {
        val toRelease: Visualizer?
        synchronized(lock) {
            listeners.remove(tag)
            if (listeners.isNotEmpty()) return

            streamGeneration++
            toRelease = visualizer
            visualizer = null
            currentSessionId = 0
            lastCaptureAtMs = 0L
            lastSpectrum = null
            pendingSpectrum = null
            spectrumProcessor.reset()
        }
        fftHandler.post { releaseInstance(toRelease) }
    }

    fun detachAll() {
        val toRelease: Visualizer?
        synchronized(lock) {
            listeners.clear()
            streamGeneration++
            toRelease = visualizer
            visualizer = null
            currentSessionId = 0
            lastCaptureAtMs = 0L
            lastSpectrum = null
            pendingSpectrum = null
            spectrumProcessor.reset()
        }
        fftHandler.post { releaseInstance(toRelease) }
    }

    private fun scheduleUiDelivery(
        generation: Long,
        sessionId: Int,
        spectrum: FloatArray
    ) {
        val delayMs: Long
        synchronized(lock) {
            if (generation != streamGeneration || sessionId != currentSessionId) return

            pendingSpectrum = PendingSpectrum(generation, sessionId, spectrum)
            if (uiDeliveryScheduled) return
            uiDeliveryScheduled = true

            val elapsed = SystemClock.elapsedRealtime() - lastUiDeliveryAtMs
            delayMs = (UI_FRAME_DELAY_MS - elapsed).coerceAtLeast(0L)
        }
        mainHandler.postDelayed(deliverLatestSpectrum, delayMs)
    }

    private val deliverLatestSpectrum = Runnable {
        val spectrum: FloatArray?
        val callbacks: List<(FloatArray?) -> Unit>
        synchronized(lock) {
            uiDeliveryScheduled = false
            val pending = pendingSpectrum
            pendingSpectrum = null

            if (
                pending == null ||
                pending.generation != streamGeneration ||
                pending.sessionId != currentSessionId
            ) {
                spectrum = null
                callbacks = emptyList()
            } else {
                lastUiDeliveryAtMs = SystemClock.elapsedRealtime()
                spectrum = pending.values
                callbacks = listeners.values.toList()
            }
        }

        if (spectrum != null) {
            val now = SystemClock.elapsedRealtime()
            callbacks.forEach { callback ->
                runCatching { callback(spectrum) }
            }
        }
    }

    private fun notifyListeners(values: FloatArray?) {
        mainHandler.post {
            val callbacks = synchronized(lock) { listeners.values.toList() }
            callbacks.forEach { callback -> runCatching { callback(values) } }
        }
    }

    private fun isExpectedGeneration(generation: Long): Boolean = synchronized(lock) {
        generation == streamGeneration
    }

    private fun isCurrentGeneration(
        generation: Long,
        sessionId: Int
    ): Boolean = synchronized(lock) {
        generation == streamGeneration &&
            sessionId == currentSessionId &&
            visualizer != null
    }

    private fun releaseInstance(instance: Visualizer?) {
        if (instance != null) {
        }
        runCatching { instance?.enabled = false }
        runCatching { instance?.setDataCaptureListener(null, 0, false, false) }
        runCatching { instance?.release() }
    }

    private class TenBandSpectrumProcessor {
        /**
         * Un plafond initial trop haut rendait le spectre presque invisible après recréation du
         * processus : les vraies amplitudes devaient attendre plusieurs secondes que ce plafond
         * décroisse. Les valeurs démarrent désormais près du bruit utile et suivent rapidement le
         * niveau réel du morceau, sans perdre les différences entre les dix bandes.
         */
        private val rollingPeaks = FloatArray(BAND_COUNT) { INITIAL_LOCAL_PEAK }
        private var globalPeak = INITIAL_GLOBAL_PEAK
        private var inputGain = 1f
        private var calibrationFramesRemaining = 0
        private var immediateFreshCalibrationPending = false

        fun reset() {
            rollingPeaks.fill(INITIAL_LOCAL_PEAK)
            globalPeak = INITIAL_GLOBAL_PEAK
            inputGain = 1f
            calibrationFramesRemaining = 0
            immediateFreshCalibrationPending = false
        }

        fun resetForFreshSession() {
            rollingPeaks.fill(FRESH_SESSION_LOCAL_PEAK)
            globalPeak = FRESH_SESSION_GLOBAL_PEAK
            inputGain = 1f
            calibrationFramesRemaining = FRESH_SESSION_CALIBRATION_FRAMES
            immediateFreshCalibrationPending = true
        }

        fun resetForTrackTransition() {
            // Plus bas que reset() afin que les premières trames d'un titre calme occupent tout de
            // suite une hauteur utile, sans reprendre le plafond du morceau précédent.
            rollingPeaks.fill(TRACK_TRANSITION_LOCAL_PEAK)
            globalPeak = TRACK_TRANSITION_GLOBAL_PEAK
            inputGain = max(1f, inputGain.coerceAtMost(TRACK_TRANSITION_MAX_INITIAL_GAIN))
            calibrationFramesRemaining = TRACK_TRANSITION_CALIBRATION_FRAMES
            immediateFreshCalibrationPending = false
        }

        fun resetForColdResume() {
            rollingPeaks.fill(COLD_RESUME_LOCAL_PEAK)
            globalPeak = COLD_RESUME_GLOBAL_PEAK
            inputGain = 1f
            calibrationFramesRemaining = COLD_RESUME_CALIBRATION_FRAMES
            immediateFreshCalibrationPending = true
        }

        fun process(fft: ByteArray): FloatArray {
            val boostedBands = FloatArray(BAND_COUNT)
            val usableBins = max(2, (fft.size / 2) - 1)
            val minBin = 1f
            val maxBin = usableBins.toFloat()
            var framePeak = 0f

            for (index in 0 until BAND_COUNT) {
                val startRatio = index.toFloat() / BAND_COUNT
                val endRatio = (index + 1).toFloat() / BAND_COUNT
                val start = max(1, logBin(minBin, maxBin, startRatio))
                val end = max(start + 1, logBin(minBin, maxBin, endRatio))

                var sum = 0f
                var peak = 0f
                var count = 0
                var bin = start
                while (bin < end && (bin * 2 + 1) < fft.size) {
                    val real = fft[bin * 2].toInt().toFloat()
                    val imaginary = fft[bin * 2 + 1].toInt().toFloat()
                    val magnitude = sqrt(real * real + imaginary * imaginary) / 128f
                    sum += magnitude
                    if (magnitude > peak) peak = magnitude
                    count++
                    bin++
                }

                val average = if (count > 0) sum / count else 0f
                val raw = (peak * 0.72f) + (average * 0.28f)
                val boosted = raw * (1f + index * 0.20f)
                boostedBands[index] = boosted
                if (boosted > framePeak) framePeak = boosted
            }

            // Une instance Visualizer neuve doit se calibrer sur sa toute première vraie trame,
            // et non hériter d'une montée progressive. Cela garantit la hauteur définie dès une
            // reprise d'application ou un clic direct sur un titre, même si l'audioSessionId est
            // identique à celui du morceau précédent.
            if (immediateFreshCalibrationPending && framePeak > RAW_SIGNAL_FLOOR) {
                inputGain = (FRESH_SESSION_RAW_TARGET_PEAK / framePeak)
                    .coerceIn(MIN_INPUT_GAIN, MAX_INPUT_GAIN)
                immediateFreshCalibrationPending = false
            }

            // AGC d'entrée : certains appareils livrent une FFT beaucoup plus faible quand le
            // Visualizer se rattache à un AudioTrack déjà en cours. On remet d'abord le pic brut
            // dans une plage stable, puis l'AGC local/global préserve le relief entre les bandes.
            if (framePeak > RAW_SIGNAL_FLOOR) {
                val calibrating = calibrationFramesRemaining > 0
                val targetRawPeak = if (calibrating) COLD_RESUME_RAW_TARGET_PEAK else RAW_TARGET_PEAK
                val desiredInputGain = (targetRawPeak / framePeak)
                    .coerceIn(MIN_INPUT_GAIN, MAX_INPUT_GAIN)
                val interpolation = if (desiredInputGain > inputGain) {
                    if (calibrating) COLD_RESUME_INPUT_GAIN_ATTACK else INPUT_GAIN_ATTACK
                } else {
                    if (calibrating) COLD_RESUME_INPUT_GAIN_RELEASE else INPUT_GAIN_RELEASE
                }
                inputGain += (desiredInputGain - inputGain) * interpolation
                if (calibrationFramesRemaining > 0) calibrationFramesRemaining--
            }

            val scaledFramePeak = framePeak * inputGain
            globalPeak = max(scaledFramePeak, max(MIN_PEAK, globalPeak * GLOBAL_PEAK_DECAY))

            val output = FloatArray(BAND_COUNT)
            for (index in 0 until BAND_COUNT) {
                val boosted = boostedBands[index] * inputGain
                rollingPeaks[index] = max(
                    boosted,
                    max(MIN_PEAK, rollingPeaks[index] * LOCAL_PEAK_DECAY)
                )

                val localNormalized = boosted / max(MIN_PEAK, rollingPeaks[index])
                val globalNormalized = boosted / max(MIN_PEAK, globalPeak)
                // Le local conserve le relief spectral ; le global empêche tout le visualiseur de
                // rester tassé au bas de la cover après une restauration de lecture.
                val normalized = max(
                    localNormalized * 0.78f,
                    globalNormalized * 1.12f
                )
                output[index] = min(
                    1f,
                    max(MIN_VISIBLE_VALUE, sqrt(normalized.coerceAtLeast(0f)) * 0.98f)
                )
            }

            return output
        }


        private fun logBin(minBin: Float, maxBin: Float, ratio: Float): Int {
            val safeRatio = min(1f, max(0f, ratio))
            return (
                minBin * exp(ln(maxBin / minBin) * safeRatio)
            ).toInt()
        }

        private companion object {
            const val INITIAL_LOCAL_PEAK = 0.055f
            const val INITIAL_GLOBAL_PEAK = 0.075f
            const val TRACK_TRANSITION_LOCAL_PEAK = 0.032f
            const val TRACK_TRANSITION_GLOBAL_PEAK = 0.042f
            const val COLD_RESUME_LOCAL_PEAK = 0.022f
            const val COLD_RESUME_GLOBAL_PEAK = 0.028f
            const val FRESH_SESSION_LOCAL_PEAK = 0.018f
            const val FRESH_SESSION_GLOBAL_PEAK = 0.022f
            const val MIN_PEAK = 0.018f
            const val LOCAL_PEAK_DECAY = 0.90f
            const val GLOBAL_PEAK_DECAY = 0.88f
            const val MIN_VISIBLE_VALUE = 0.10f
            const val RAW_SIGNAL_FLOOR = 0.0005f
            const val RAW_TARGET_PEAK = 0.30f
            const val COLD_RESUME_RAW_TARGET_PEAK = 0.42f
            const val FRESH_SESSION_RAW_TARGET_PEAK = 0.48f
            const val MIN_INPUT_GAIN = 0.35f
            const val MAX_INPUT_GAIN = 64f
            const val INPUT_GAIN_ATTACK = 0.52f
            const val INPUT_GAIN_RELEASE = 0.06f
            const val COLD_RESUME_INPUT_GAIN_ATTACK = 0.88f
            const val COLD_RESUME_INPUT_GAIN_RELEASE = 0.025f
            const val TRACK_TRANSITION_MAX_INITIAL_GAIN = 8f
            const val TRACK_TRANSITION_CALIBRATION_FRAMES = 45
            const val COLD_RESUME_CALIBRATION_FRAMES = 180
            const val FRESH_SESSION_CALIBRATION_FRAMES = 210
        }
    }
}
