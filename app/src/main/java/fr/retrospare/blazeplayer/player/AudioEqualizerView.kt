package fr.retrospare.blazeplayer.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.os.SystemClock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Visualiseur discret pour Blaze Audio : il ne lit aucun fichier et ne pilote pas le son,
 * il dessine dix bandes FFT fournies par AudioPlayerFragment/Visualizer.
 */
class AudioEqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 10
    private val values = FloatArray(barCount) { 0.08f }
    /** Valeurs FFT réelles reçues du moteur audio. */
    private val targetValues = FloatArray(barCount) { 0.08f }
    /** Cibles finales dessinées : FFT réelle + mouvement algorithmique musical. */
    private val algorithmicTargets = FloatArray(barCount) { 0.12f }
    private val rollingPeaks = FloatArray(barCount) { 0.22f }
    private val bandPhaseOffsets = floatArrayOf(
        0.00f, 0.71f, 1.49f, 2.26f, 3.08f,
        3.91f, 4.63f, 5.38f, 6.11f, 6.84f
    )
    private val bandSpeedMultipliers = floatArrayOf(
        0.86f, 1.04f, 0.93f, 1.17f, 1.01f,
        1.24f, 0.96f, 1.31f, 1.10f, 1.39f
    )
    private val bandEnergyWeights = floatArrayOf(
        1.00f, 1.02f, 1.04f, 1.02f, 1.00f,
        1.03f, 1.07f, 1.12f, 1.17f, 1.22f
    )
    private val activeLedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inactiveLedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowLedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var ledLowColor: Int = Color.rgb(32, 132, 214)
    private var ledMidColor: Int = Color.rgb(72, 174, 238)
    private var ledHighColor: Int = Color.rgb(157, 222, 255)
    private var inactiveLedTint: Int = 0xFF071018.toInt()
    private val peakHoldUntilMs = LongArray(barCount)
    private var lastLedDrawAtMs: Long = 0L
    private var karaoKastPerformanceMode: Boolean = false
    private var playbackActive: Boolean = false
    private var lastSpectrumUpdateAtMs: Long = 0L
    private var restoredPlaybackBoostUntilMs: Long = 0L
    private var trackTransitionHoldUntilMs: Long = 0L
    private var adaptiveDisplayGain: Float = 1f
    private var coldResumeCalibrationPending: Boolean = false
    private var coldResumeFramesRemaining: Int = 0
    private var coldResumeBoostUntilMs: Long = 0L
    private var frameScheduled: Boolean = false
    private var animationPhase: Float = 0f
    private var lastAnimationFrameAtMs: Long = 0L
    private var incomingMusicEnergy: Float = 0.24f
    private var smoothedMusicEnergy: Float = 0.24f
    private var previousMusicEnergy: Float = 0.24f
    private var transientPulse: Float = 0f
    private val frameRunnable = Runnable {
        frameScheduled = false
        if (isShown) invalidate()
    }

    init {
        alpha = 1f
        setWillNotDraw(false)
    }

    fun setKaraoKastPerformanceMode(enabled: Boolean) {
        if (karaoKastPerformanceMode == enabled) return
        karaoKastPerformanceMode = enabled
        removeCallbacks(frameRunnable)
        frameScheduled = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
        scheduleFrame()
    }

    fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) {
            if (active) scheduleFrame()
            return
        }
        playbackActive = active
        if (active) {
            val now = SystemClock.elapsedRealtime()
            if (lastSpectrumUpdateAtMs <= 0L) {
                lastSpectrumUpdateAtMs = now
            }
            // Après recréation complète de l'application, les premiers callbacks FFT peuvent être
            // faibles pendant que l'AudioTrack restauré se stabilise. Ce boost bref évite un
            // visualiseur réduit à quelques pixels sans inventer une animation permanente.
            restoredPlaybackBoostUntilMs = now + RESTORED_PLAYBACK_BOOST_MS
            trackTransitionHoldUntilMs = now + TRACK_TRANSITION_HOLD_MS
            adaptiveDisplayGain = TRACK_START_DISPLAY_GAIN
            incomingMusicEnergy = maxOf(incomingMusicEnergy, 0.30f)
            smoothedMusicEnergy = maxOf(smoothedMusicEnergy, 0.26f)
            transientPulse = maxOf(transientPulse, 0.18f)
            lastAnimationFrameAtMs = now
            for (i in 0 until barCount) {
                values[i] = values[i].coerceAtLeast(0.16f)
                targetValues[i] = targetValues[i].coerceAtLeast(0.16f)
            }
            scheduleFrame()
        } else {
            lastSpectrumUpdateAtMs = 0L
            setIdle()
        }
    }

    /**
     * Prépare visuellement une nouvelle piste sans recréer le Visualizer. Le conteneur conserve sa
     * hauteur KaraoKast et l'enveloppe d'affichage repart sur une échelle neutre : un titre calme
     * ne peut donc plus être tassé par le pic mémorisé du titre précédent.
     */
    fun prepareForTrackTransition() {
        val now = SystemClock.elapsedRealtime()
        adaptiveDisplayGain = TRACK_START_DISPLAY_GAIN
        incomingMusicEnergy = 0.34f
        smoothedMusicEnergy = 0.30f
        previousMusicEnergy = 0.26f
        transientPulse = 0.28f
        lastAnimationFrameAtMs = now
        restoredPlaybackBoostUntilMs = now + TRACK_START_BOOST_MS
        trackTransitionHoldUntilMs = now + TRACK_TRANSITION_HOLD_MS
        for (i in 0 until barCount) {
            val baseline = TRACK_TRANSITION_BASE_VALUE + (i % 4) * 0.012f
            values[i] = values[i].coerceAtLeast(baseline)
            targetValues[i] = targetValues[i].coerceAtLeast(baseline)
        }
        scheduleFrame()
    }


    /**
     * Réinitialise intégralement l'enveloppe visuelle pour une nouvelle instance Visualizer.
     * La zone conserve strictement la hauteur définie par son conteneur (84 dp utiles dans le
     * player et KaraoKast), tandis que les premières vraies trames repartent d'un gain neuf.
     */
    fun prepareForFreshVisualizerSession() {
        val now = SystemClock.elapsedRealtime()
        playbackActive = true
        lastSpectrumUpdateAtMs = 0L
        coldResumeCalibrationPending = true
        coldResumeFramesRemaining = 0
        coldResumeBoostUntilMs = 0L
        adaptiveDisplayGain = FRESH_SESSION_INITIAL_DISPLAY_GAIN
        incomingMusicEnergy = 0.36f
        smoothedMusicEnergy = 0.31f
        previousMusicEnergy = 0.25f
        transientPulse = 0.32f
        lastAnimationFrameAtMs = now
        restoredPlaybackBoostUntilMs = now + FRESH_SESSION_BOOST_MS
        trackTransitionHoldUntilMs = now + FRESH_SESSION_HOLD_MS
        for (i in 0 until barCount) {
            val baseline = FRESH_SESSION_BASE_VALUE + (i % 4) * 0.014f
            values[i] = baseline
            targetValues[i] = baseline
        }
        // Le layout XML reste la source de vérité de la hauteur. Ce requestLayout force seulement
        // sa réapplication après une recréation d'activité ou un changement de morceau.
        requestLayout()
        post {
            rebuildGradient()
            scheduleFrame()
        }
    }

    /**
     * Arme une calibration spéciale pour une lecture déjà active lors de la recréation de l'UI.
     * La fenêtre de calibration ne démarre qu'à la première vraie trame FFT : elle ne peut donc
     * plus expirer pendant que Media3 rattache encore le contrôleur et la session AudioTrack.
     */
    fun beginColdResumeCalibration() {
        val now = SystemClock.elapsedRealtime()
        coldResumeCalibrationPending = true
        coldResumeFramesRemaining = 0
        coldResumeBoostUntilMs = 0L
        // Repartir d'un état visuel déterministe. Lors d'une recréation d'activité, la vue peut
        // avoir conservé une enveloppe minuscule alors que le flux FFT est parfaitement valide.
        // Un simple maxOf() ne suffisait pas : il laissait certaines barres tassées jusqu'au
        // prochain détachement/rattachement provoqué par un changement d'onglet.
        adaptiveDisplayGain = COLD_RESUME_INITIAL_DISPLAY_GAIN
        incomingMusicEnergy = maxOf(incomingMusicEnergy, 0.34f)
        smoothedMusicEnergy = maxOf(smoothedMusicEnergy, 0.30f)
        previousMusicEnergy = 0.24f
        transientPulse = maxOf(transientPulse, 0.30f)
        lastAnimationFrameAtMs = now
        restoredPlaybackBoostUntilMs = maxOf(restoredPlaybackBoostUntilMs, now + COLD_RESUME_WAITING_BOOST_MS)
        trackTransitionHoldUntilMs = maxOf(trackTransitionHoldUntilMs, now + COLD_RESUME_WAITING_HOLD_MS)
        for (i in 0 until barCount) {
            val baseline = COLD_RESUME_WAITING_BASE_VALUE + (i % 4) * 0.014f
            values[i] = baseline
            targetValues[i] = baseline
        }
        scheduleFrame()
    }

    fun setAccentColor(accentColor: Int) {
        // Une seule famille chromatique, extraite de la cover, alimente tout le VU-mètre.
        // Les trois zones utilisent exactement la même teinte avec trois niveaux de lumière :
        // profond en bas, couleur principale au centre, variante claire pour les crêtes.
        val base = AudioDynamicColor.ensureReadableAccent(accentColor)
        ledMidColor = tuneLedVariant(base, saturationScale = 1.04f, valueScale = 0.98f, minValue = 0.64f)
        ledLowColor = tuneLedVariant(
            mix(ledMidColor, Color.BLACK, 0.24f),
            saturationScale = 1.06f,
            valueScale = 0.94f,
            minValue = 0.46f
        )
        ledHighColor = tuneLedVariant(
            mix(ledMidColor, Color.WHITE, 0.46f),
            saturationScale = 0.88f,
            valueScale = 1.04f,
            minValue = 0.82f
        )
        inactiveLedTint = mix(ledLowColor, Color.BLACK, 0.91f)
        requestRender()
    }

    /**
     * Reçoit dix valeurs déjà calculées sur le thread FFT dédié.
     */
    fun updateSpectrum(spectrum: FloatArray?) {
        if (spectrum == null || spectrum.isEmpty()) {
            if (playbackActive) {
                holdVisiblePlaybackFallback()
            } else {
                setIdle()
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        lastSpectrumUpdateAtMs = now
        val count = min(barCount, spectrum.size)
        var framePeak = 0f
        for (index in 0 until count) {
            framePeak = maxOf(framePeak, spectrum[index].coerceAtLeast(0f))
        }

        // Deux couches de normalisation sont volontaires : AudioFftStream préserve le relief des
        // bandes, tandis que cette enveloppe stabilise seulement leur hauteur visuelle. Au retour
        // dans l'application, la calibration démarre ici, sur la première vraie trame, et non au
        // moment où l'écran commence à se rattacher au player.
        if (coldResumeCalibrationPending && framePeak > SILENCE_FRAME_PEAK) {
            coldResumeCalibrationPending = false
            coldResumeFramesRemaining = COLD_RESUME_CALIBRATION_FRAMES
            coldResumeBoostUntilMs = now + COLD_RESUME_MIN_CALIBRATION_MS
            // La première vraie trame fixe immédiatement l'échelle. L'interpolation normale
            // reprend ensuite, mais la première image utile ne peut plus rester à quelques pixels.
            adaptiveDisplayGain = (COLD_RESUME_TARGET_VISIBLE_PEAK / framePeak)
                .coerceIn(COLD_RESUME_MIN_DISPLAY_GAIN, COLD_RESUME_MAX_DISPLAY_GAIN)
        }

        val coldResumeCalibrationActive =
            coldResumeFramesRemaining > 0 || now < coldResumeBoostUntilMs
        if (framePeak > SILENCE_FRAME_PEAK) {
            val targetPeak = if (coldResumeCalibrationActive) {
                COLD_RESUME_TARGET_VISIBLE_PEAK
            } else {
                TARGET_VISIBLE_FRAME_PEAK
            }
            val maxGain = if (coldResumeCalibrationActive) {
                COLD_RESUME_MAX_DISPLAY_GAIN
            } else {
                MAX_DISPLAY_GAIN
            }
            val desiredGain = (targetPeak / framePeak)
                .coerceIn(MIN_DISPLAY_GAIN, maxGain)
            val interpolation = if (desiredGain > adaptiveDisplayGain) {
                if (coldResumeCalibrationActive) COLD_RESUME_DISPLAY_GAIN_ATTACK else DISPLAY_GAIN_ATTACK
            } else {
                if (coldResumeCalibrationActive) COLD_RESUME_DISPLAY_GAIN_RELEASE else DISPLAY_GAIN_RELEASE
            }
            adaptiveDisplayGain += (desiredGain - adaptiveDisplayGain) * interpolation
            if (coldResumeFramesRemaining > 0) coldResumeFramesRemaining--
        }

        val transitionFloor = when {
            coldResumeCalibrationActive -> COLD_RESUME_MIN_VALUE
            now < trackTransitionHoldUntilMs -> TRACK_TRANSITION_MIN_VALUE
            else -> NORMAL_MIN_VALUE
        }

        // Certains appareils fournissent, après rattachement tardif à une session déjà active,
        // des valeurs uniformément faibles pendant plusieurs secondes. Même avec l'AGC, leur pic
        // peut rester sous la hauteur normale. Pendant la calibration de reprise, on applique donc
        // une enveloppe de frame strictement visuelle : elle relève tout le spectre de façon
        // proportionnelle jusqu'à un pic minimal, sans toucher au son ni recréer le Visualizer.
        val scaledFramePeak = framePeak * adaptiveDisplayGain
        val coldResumeFrameGain = if (
            coldResumeCalibrationActive &&
            scaledFramePeak > SILENCE_FRAME_PEAK &&
            scaledFramePeak < COLD_RESUME_MIN_RENDER_PEAK
        ) {
            (COLD_RESUME_MIN_RENDER_PEAK / scaledFramePeak)
                .coerceAtMost(COLD_RESUME_MAX_FRAME_COMPENSATION)
        } else {
            1f
        }
        for (index in 0 until count) {
            val incoming = (spectrum[index] * adaptiveDisplayGain * coldResumeFrameGain)
                .coerceIn(transitionFloor, 1f)
            targetValues[index] = incoming
        }
        for (index in count until barCount) {
            targetValues[index] = transitionFloor
        }

        // L'énergie réelle du morceau pilote la vitesse et l'amplitude de l'animation. Une montée
        // rapide crée une impulsion transitoire ; la décroissance reste progressive afin d'éviter
        // une animation saccadée entre deux callbacks FFT.
        var energySum = 0f
        var energyPeak = 0f
        for (index in 0 until barCount) {
            val energy = targetValues[index].coerceIn(0f, 1f)
            energySum += energy
            energyPeak = maxOf(energyPeak, energy)
        }
        val measuredEnergy = ((energySum / barCount) * 0.58f + energyPeak * 0.42f)
            .coerceIn(0f, 1f)
        val positiveTransient = (measuredEnergy - previousMusicEnergy).coerceAtLeast(0f)
        transientPulse = maxOf(transientPulse, positiveTransient * 3.4f).coerceAtMost(1f)
        previousMusicEnergy += (measuredEnergy - previousMusicEnergy) * 0.42f
        incomingMusicEnergy = measuredEnergy
        requestRender()
    }

    private fun holdVisiblePlaybackFallback() {
        val now = SystemClock.elapsedRealtime()
        trackTransitionHoldUntilMs = maxOf(trackTransitionHoldUntilMs, now + NULL_CAPTURE_HOLD_MS)
        for (i in 0 until barCount) {
            val phaseValue = PLAYBACK_FALLBACK_BASE_VALUE + (i % 4) * 0.028f
            targetValues[i] = targetValues[i].coerceAtLeast(phaseValue)
            values[i] = values[i].coerceAtLeast(phaseValue * 0.92f)
        }
        requestRender()
    }

    fun setIdle() {
        incomingMusicEnergy = 0.08f
        smoothedMusicEnergy = 0.08f
        previousMusicEnergy = 0.08f
        transientPulse = 0f
        lastAnimationFrameAtMs = 0L
        for (i in 0 until barCount) {
            val wave = 0.08f + ((i % 5) * 0.018f)
            targetValues[i] = wave
        }
        requestRender()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGradient()
    }

    private fun rebuildGradient() {
        // Conservé pour les appels existants : le VU-mètre LED n'utilise plus de gradient.
        invalidate()
    }

    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val r = (Color.red(a) + ((Color.red(b) - Color.red(a)) * t)).toInt().coerceIn(0, 255)
        val g = (Color.green(a) + ((Color.green(b) - Color.green(a)) * t)).toInt().coerceIn(0, 255)
        val bl = (Color.blue(a) + ((Color.blue(b) - Color.blue(a)) * t)).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, bl)
    }

    private fun tuneLedVariant(
        color: Int,
        saturationScale: Float,
        valueScale: Float,
        minValue: Float
    ): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * saturationScale).coerceIn(0.10f, 1f)
        hsv[2] = (hsv[2] * valueScale).coerceIn(minValue, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun ledColor(segmentFromBottom: Int): Int {
        val normalizedHeight = (segmentFromBottom + 0.5f) / LED_SEGMENT_COUNT.toFloat()
        return when {
            normalizedHeight >= HIGH_ZONE_START -> ledHighColor
            normalizedHeight >= MID_ZONE_START -> ledMidColor
            else -> ledLowColor
        }
    }

    private fun inactiveLedColor(activeColor: Int): Int {
        val darkened = mix(activeColor, inactiveLedTint, 0.86f)
        return withAlpha(darkened, INACTIVE_LED_ALPHA)
    }

    /**
     * Produit un mouvement continu pour les dix bandes à partir de quatre composantes :
     * - la valeur FFT propre à la bande ;
     * - une diffusion faible depuis ses voisines ;
     * - l'énergie et les transitoires globaux du morceau ;
     * - deux oscillateurs déterministes, déphasés et de vitesses différentes par bande.
     *
     * Aucune bande ne reste figée pendant la lecture, mais une vraie montée FFT garde toujours la
     * priorité. Les coefficients sont déterministes : il n'y a ni hasard ni tremblement parasite.
     */
    private fun updateAlgorithmicTargets(nowMs: Long, captureTemporarilyLate: Boolean) {
        val deltaSeconds = if (lastAnimationFrameAtMs <= 0L) {
            1f / 60f
        } else {
            ((nowMs - lastAnimationFrameAtMs).coerceIn(8L, 50L) / 1_000f)
        }
        lastAnimationFrameAtMs = nowMs

        if (captureTemporarilyLate) {
            incomingMusicEnergy += (0.22f - incomingMusicEnergy) * 0.035f
        }
        val energyInterpolation = if (incomingMusicEnergy > smoothedMusicEnergy) 0.30f else 0.11f
        smoothedMusicEnergy += (incomingMusicEnergy - smoothedMusicEnergy) * energyInterpolation
        smoothedMusicEnergy = smoothedMusicEnergy.coerceIn(0.06f, 1f)
        transientPulse = (transientPulse - deltaSeconds * 1.75f).coerceAtLeast(0f)

        val phaseVelocity = 3.85f + smoothedMusicEnergy * 4.20f + transientPulse * 2.10f
        animationPhase += deltaSeconds * phaseVelocity
        if (animationPhase > TWO_PI * 64f) animationPhase %= TWO_PI

        for (index in 0 until barCount) {
            val own = targetValues[index]
            val left = targetValues[(index - 1).coerceAtLeast(0)]
            val right = targetValues[(index + 1).coerceAtMost(barCount - 1)]
            val neighbourEnergy = (left + right) * 0.5f

            // Le signal propre reste dominant ; la diffusion empêche seulement une bande très peu
            // alimentée de sembler morte alors que les fréquences voisines sont actives.
            val spectralBody = (
                own * 0.62f +
                    neighbourEnergy * 0.20f +
                    smoothedMusicEnergy * 0.18f
                ) * bandEnergyWeights[index]

            val phase = animationPhase * bandSpeedMultipliers[index] + bandPhaseOffsets[index]
            val primaryWave = positiveSine(phase)
            val secondaryWave = positiveSine(
                animationPhase * (0.61f + index * 0.018f) - bandPhaseOffsets[index] * 1.43f
            )
            val counterWave = positiveSine(
                animationPhase * (1.27f - index * 0.012f) + index * 0.94f
            )

            val continuousMotion =
                (0.045f + smoothedMusicEnergy * 0.095f) * (0.30f + primaryWave * 0.70f) +
                    (0.018f + smoothedMusicEnergy * 0.040f) * secondaryWave
            val transientMotion = transientPulse * (0.045f + counterWave * 0.105f)
            val livingFloor = if (playbackActive) {
                0.135f + smoothedMusicEnergy * 0.055f + secondaryWave * 0.030f
            } else {
                0.08f + secondaryWave * 0.018f
            }

            algorithmicTargets[index] = maxOf(
                livingFloor,
                spectralBody * 0.70f + continuousMotion * 0.92f + transientMotion * 0.90f
            ).coerceIn(0.09f, LED_TARGET_MAX)
        }
    }

    private fun positiveSine(phase: Float): Float =
        (sin(phase.toDouble()).toFloat() + 1f) * 0.5f

    private fun compressLedAmplitude(level: Float): Float {
        val x = level.coerceIn(0f, 1f)
        return if (x <= LED_COMPRESSION_KNEE) {
            x * LED_LOW_RANGE_GAIN
        } else {
            val kneeOutput = LED_COMPRESSION_KNEE * LED_LOW_RANGE_GAIN
            kneeOutput + (x - LED_COMPRESSION_KNEE) * LED_HIGH_RANGE_GAIN
        }.coerceIn(0f, LED_VISUAL_MAX)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = SystemClock.elapsedRealtime()
        val captureTemporarilyLate = playbackActive &&
            lastSpectrumUpdateAtMs > 0L &&
            now - lastSpectrumUpdateAtMs > FFT_FALLBACK_AFTER_MS
        updateAlgorithmicTargets(now, captureTemporarilyLate)

        val frameDeltaSeconds = if (lastLedDrawAtMs <= 0L) {
            1f / 60f
        } else {
            ((now - lastLedDrawAtMs).coerceIn(8L, 80L) / 1_000f)
        }
        lastLedDrawAtMs = now

        // Fond très léger : les segments noirs restent lisibles sur une cover claire
        // sans masquer l'image de l'album.
        panelPaint.color = PANEL_COLOR
        val panelRadius = min(w, h) * 0.045f
        canvas.drawRoundRect(0f, 0f, w, h, panelRadius, panelRadius, panelPaint)

        val horizontalGap = max(w * 0.012f, 2f)
        val columnWidth = ((w - horizontalGap * (barCount - 1)) / barCount)
            .coerceAtLeast(1f)
        val verticalGap = max(h * 0.013f, 1f)
        val segmentHeight = (
            (h - verticalGap * (LED_SEGMENT_COUNT - 1)) / LED_SEGMENT_COUNT
        ).coerceAtLeast(1f)
        val segmentRadius = min(columnWidth * 0.08f, segmentHeight * 0.24f)
        val glowPad = min(columnWidth * 0.045f, segmentHeight * 0.22f)


        for (bandIndex in 0 until barCount) {
            val renderTarget = algorithmicTargets[bandIndex]
            val startupGain = when {
                now < coldResumeBoostUntilMs -> COLD_RESUME_DRAW_GAIN
                now < restoredPlaybackBoostUntilMs -> RESTORED_PLAYBACK_DRAW_GAIN
                else -> 1f
            }
            val expandedTarget = (renderTarget * startupGain).coerceAtMost(1f)
            val movementInterpolation = if (expandedTarget > values[bandIndex]) {
                0.50f + transientPulse * 0.16f
            } else {
                0.24f + smoothedMusicEnergy * 0.08f
            }
            values[bandIndex] +=
                (expandedTarget - values[bandIndex]) *
                    movementInterpolation.coerceIn(0.20f, 0.72f)

            // Compression douce de la partie haute du VU-mètre : la zone verte reste vive,
            // le jaune reste accessible, mais les deux segments rouges sont réservés aux vrais pics.
            val normalizedLevel = values[bandIndex].coerceIn(0f, 1f)
            val visualLevel = compressLedAmplitude(normalizedLevel)

            val litSegments = when {
                !playbackActive -> floor(visualLevel * LED_SEGMENT_COUNT).toInt()
                visualLevel <= 0f -> 0
                else -> ceil(visualLevel * LED_SEGMENT_COUNT).toInt()
            }.coerceIn(0, LED_SEGMENT_COUNT)

            if (visualLevel >= rollingPeaks[bandIndex]) {
                rollingPeaks[bandIndex] = visualLevel
                peakHoldUntilMs[bandIndex] = now + PEAK_HOLD_MS
            } else if (now > peakHoldUntilMs[bandIndex]) {
                rollingPeaks[bandIndex] = (
                    rollingPeaks[bandIndex] - PEAK_FALL_PER_SECOND * frameDeltaSeconds
                    ).coerceAtLeast(visualLevel)
            }
            val peakSegment = (
                floor(rollingPeaks[bandIndex] * LED_SEGMENT_COUNT).toInt() - 1
                ).coerceIn(0, LED_SEGMENT_COUNT - 1)

            val left = bandIndex * (columnWidth + horizontalGap)
            val right = left + columnWidth

            for (segmentIndex in 0 until LED_SEGMENT_COUNT) {
                val bottom = h - segmentIndex * (segmentHeight + verticalGap)
                val top = bottom - segmentHeight
                val zoneColor = ledColor(segmentIndex)
                val isLit = segmentIndex < litSegments
                val isPeak = playbackActive &&
                    segmentIndex == peakSegment &&
                    segmentIndex >= litSegments

                if (isLit || isPeak) {
                    glowLedPaint.color = withAlpha(
                        zoneColor,
                        if (isPeak) PEAK_GLOW_ALPHA else ACTIVE_GLOW_ALPHA
                    )
                    canvas.drawRoundRect(
                        left - glowPad,
                        top - glowPad,
                        right + glowPad,
                        bottom + glowPad,
                        segmentRadius + glowPad,
                        segmentRadius + glowPad,
                        glowLedPaint
                    )

                    activeLedPaint.color = if (isPeak) {
                        mix(zoneColor, Color.WHITE, PEAK_BRIGHTEN_AMOUNT)
                    } else {
                        zoneColor
                    }
                    canvas.drawRoundRect(
                        left,
                        top,
                        right,
                        bottom,
                        segmentRadius,
                        segmentRadius,
                        activeLedPaint
                    )
                } else {
                    inactiveLedPaint.color = inactiveLedColor(zoneColor)
                    canvas.drawRoundRect(
                        left,
                        top,
                        right,
                        bottom,
                        segmentRadius,
                        segmentRadius,
                        inactiveLedPaint
                    )
                }
            }

            val activeHeight = if (litSegments <= 0) {
                0f
            } else {
                litSegments * segmentHeight + (litSegments - 1) * verticalGap
            }
        }


        if (isShown) scheduleFrame()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playbackActive || isShown) scheduleFrame()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && playbackActive) scheduleFrame()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && playbackActive) scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameRunnable)
        frameScheduled = false
        super.onDetachedFromWindow()
    }

    /**
     * En mode KaraoKast, plusieurs callbacks FFT peuvent arriver entre deux images. Ils sont
     * regroupés en une seule invalidation pour respecter réellement le plafond de 30 i/s.
     */
    private fun requestRender() {
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        postDelayed(frameRunnable, FRAME_DELAY_MS)
    }

    private companion object {
        const val FRAME_DELAY_MS = 16L
        const val FFT_FALLBACK_AFTER_MS = 550L
        const val RESTORED_PLAYBACK_BOOST_MS = 2_800L
        const val TRACK_START_BOOST_MS = 1_800L
        const val TRACK_TRANSITION_HOLD_MS = 650L
        const val NULL_CAPTURE_HOLD_MS = 1_100L
        const val TRACK_START_DISPLAY_GAIN = 1.35f
        const val COLD_RESUME_INITIAL_DISPLAY_GAIN = 2.20f
        const val FRESH_SESSION_INITIAL_DISPLAY_GAIN = 2.60f
        const val FRESH_SESSION_BASE_VALUE = 0.28f
        const val FRESH_SESSION_BOOST_MS = 8_000L
        const val FRESH_SESSION_HOLD_MS = 1_200L
        const val TRACK_TRANSITION_BASE_VALUE = 0.24f
        const val TRACK_TRANSITION_MIN_VALUE = 0.18f
        const val PLAYBACK_FALLBACK_BASE_VALUE = 0.20f
        const val NORMAL_MIN_VALUE = 0.11f
        const val COLD_RESUME_WAITING_BASE_VALUE = 0.25f
        const val COLD_RESUME_MIN_VALUE = 0.22f
        const val SILENCE_FRAME_PEAK = 0.015f
        const val TARGET_VISIBLE_FRAME_PEAK = 0.68f
        const val MIN_DISPLAY_GAIN = 0.82f
        const val MAX_DISPLAY_GAIN = 3.20f
        const val DISPLAY_GAIN_ATTACK = 0.48f
        const val DISPLAY_GAIN_RELEASE = 0.10f
        const val COLD_RESUME_TARGET_VISIBLE_PEAK = 0.74f
        const val COLD_RESUME_MIN_DISPLAY_GAIN = 1.0f
        const val COLD_RESUME_MAX_DISPLAY_GAIN = 8.0f
        const val COLD_RESUME_DISPLAY_GAIN_ATTACK = 0.78f
        const val COLD_RESUME_DISPLAY_GAIN_RELEASE = 0.035f
        const val COLD_RESUME_DRAW_GAIN = 1.03f
        const val COLD_RESUME_MIN_RENDER_PEAK = 0.66f
        const val COLD_RESUME_MAX_FRAME_COMPENSATION = 6.0f
        const val COLD_RESUME_CALIBRATION_FRAMES = 180
        const val COLD_RESUME_MIN_CALIBRATION_MS = 6_000L
        const val COLD_RESUME_WAITING_BOOST_MS = 8_000L
        const val COLD_RESUME_WAITING_HOLD_MS = 8_000L

        // Design VU-mètre LED inspiré des égaliseurs matériels.
        const val LED_SEGMENT_COUNT = 20
        const val MID_ZONE_START = 0.62f
        const val HIGH_ZONE_START = 0.90f
        const val LED_TARGET_MAX = 0.94f
        const val LED_COMPRESSION_KNEE = 0.70f
        const val LED_LOW_RANGE_GAIN = 0.98f
        const val LED_HIGH_RANGE_GAIN = 0.86f
        const val LED_VISUAL_MAX = 0.95f
        const val RESTORED_PLAYBACK_DRAW_GAIN = 1.05f
        const val PEAK_HOLD_MS = 360L
        const val PEAK_FALL_PER_SECOND = 0.78f
        const val ACTIVE_GLOW_ALPHA = 72
        const val PEAK_GLOW_ALPHA = 104
        const val INACTIVE_LED_ALPHA = 128
        const val PEAK_BRIGHTEN_AMOUNT = 0.30f
        val PANEL_COLOR: Int = Color.argb(42, 0, 0, 0)
        const val TWO_PI = 6.2831855f
    }
}
