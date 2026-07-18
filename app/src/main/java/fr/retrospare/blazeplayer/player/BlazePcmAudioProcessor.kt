package fr.retrospare.blazeplayer.player

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Traitement PCM central du lecteur audio.
 *
 * Ce processeur reste attaché à l'AudioSink du service. Il permet d'appliquer en temps réel les
 * réglages qui ne sont pas exposés de manière fiable par les AudioEffect constructeurs : balance,
 * mono, largeur stéréo, limiteur et réverbération. Les préférences sont lues une seule fois puis
 * maintenues dans des champs volatils ; aucun accès disque n'est effectué dans [queueInput].
 */
class BlazePcmAudioProcessor(context: Context) : BaseAudioProcessor() {

    private val prefs = context.applicationContext.getSharedPreferences(EqualizerManager.PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile private var enabled = true
    @Volatile private var balance = 0f          // -1 = gauche, +1 = droite
    @Volatile private var stereoWidth = 1f      // 0 = mono, 1 = normal, 1.5 = large
    @Volatile private var mono = false
    @Volatile private var surroundEnabled = false
    @Volatile private var surroundStrength = 0.55f
    @Volatile private var limiter = true
    @Volatile private var reverbEnabled = false
    @Volatile private var reverbMix = 0f
    @Volatile private var reverbPreset = 0

    private var sampleRate = 0
    private var configuredChannels = 0
    private var activeReverbPreset = -1
    private var leftDelay = FloatArray(1)
    private var rightDelay = FloatArray(1)
    private var leftDelayIndex = 0
    private var rightDelayIndex = 0
    private var secondTapLeft = 0
    private var secondTapRight = 0
    private var dampedWetLeft = 0f
    private var dampedWetRight = 0f
    private var surroundLeftDelay = FloatArray(1)
    private var surroundRightDelay = FloatArray(1)
    private var surroundDelayIndex = 0
    private var surroundReadOffset = 1
    private var surroundSideLowPass = 0f
    private var surroundProcessedLeft = 0f
    private var surroundProcessedRight = 0f
    private var surroundMix = 0f
    private var surroundSmoothedStrength = 0.55f
    private var surroundCompensationGain = 1f

    // Paramètres mis en cache lors d'un changement de profil : aucune branche complexe ni
    // allocation n'est nécessaire dans la boucle audio.
    private var activeFeedback = 0.16f
    private var activeCrossfeed = 0.06f
    private var activeDamping = 0.30f
    private var activeEarlyReflection = 0.18f
    private var activeWetGain = 0.75f

    private var processedLeft = 0f
    private var processedRight = 0f
    @Volatile private var resetReverbRequested = false

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        readPreferences()
    }

    init {
        readPreferences()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        configuredChannels = inputAudioFormat.channelCount
        rebuildReverbIfNeeded(force = true)
        rebuildSurroundDelay()
        surroundMix = if (surroundEnabled && surroundStrength > 0f) 1f else 0f
        surroundSmoothedStrength = surroundStrength.coerceIn(0f, 1f)
        surroundCompensationGain = 1f
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, output)
            else -> processPcm16(inputBuffer, output)
        }
        output.flip()
    }

    fun releaseSettings() {
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
    }

    private fun readPreferences() {
        val previousReverbEnabled = reverbEnabled
        val previousPreset = reverbPreset
        enabled = prefs.getBoolean(EqualizerManager.KEY_EQ_ENABLED, true)
        balance = prefs.getInt(EqualizerManager.KEY_BALANCE, 0).coerceIn(-100, 100) / 100f
        stereoWidth = prefs.getInt(EqualizerManager.KEY_STEREO_WIDTH, 100).coerceIn(0, 150) / 100f
        mono = prefs.getBoolean(EqualizerManager.KEY_MONO, false)
        surroundEnabled = prefs.getBoolean(EqualizerManager.KEY_SURROUND_ENABLED, false)
        surroundStrength = prefs.getInt(EqualizerManager.KEY_SURROUND_STRENGTH, 55)
            .coerceIn(0, EqualizerManager.SURROUND_STRENGTH_MAX) / 100f
        limiter = prefs.getBoolean(EqualizerManager.KEY_LIMITER, true)
        reverbEnabled = prefs.getBoolean(EqualizerManager.KEY_REVERB_ENABLED, false)
        reverbMix = prefs.getInt(EqualizerManager.KEY_REVERB_MIX, 0)
            .coerceIn(0, EqualizerManager.REVERB_MIX_MAX) / 100f
        reverbPreset = prefs.getInt(EqualizerManager.KEY_REVERB_PRESET, 0)
            .coerceIn(0, EqualizerManager.REVERB_PRESET_COUNT - 1)
        if (previousPreset != reverbPreset || (previousReverbEnabled && !reverbEnabled)) {
            resetReverbRequested = true
        }
    }

    private fun processPcm16(input: ByteBuffer, output: ByteBuffer) {
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        while (input.remaining() >= channels * 2) {
            if (channels == 1) {
                val sample = input.getShort() / 32768f
                output.putShort(floatToPcm16(processMonoSample(sample)))
            } else {
                val left = input.getShort() / 32768f
                val right = input.getShort() / 32768f
                processStereoFrame(left, right)
                output.putShort(floatToPcm16(processedLeft))
                output.putShort(floatToPcm16(processedRight))
                for (channel in 2 until channels) {
                    val untouched = input.getShort() / 32768f
                    output.putShort(floatToPcm16(if (enabled && limiter) softLimit(untouched) else untouched))
                }
            }
        }
        while (input.hasRemaining()) output.put(input.get())
    }

    private fun processFloat(input: ByteBuffer, output: ByteBuffer) {
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        while (input.remaining() >= channels * 4) {
            if (channels == 1) {
                output.putFloat(processMonoSample(input.getFloat()))
            } else {
                processStereoFrame(input.getFloat(), input.getFloat())
                output.putFloat(processedLeft)
                output.putFloat(processedRight)
                for (channel in 2 until channels) {
                    val untouched = input.getFloat()
                    output.putFloat(if (enabled && limiter) softLimit(untouched) else untouched)
                }
            }
        }
        while (input.hasRemaining()) output.put(input.get())
    }

    private fun processMonoSample(sample: Float): Float {
        resetReverbIfRequested()
        if (!enabled) return sample
        var value = sample
        if (reverbEnabled && reverbMix > 0f) {
            rebuildReverbIfNeeded()
            val primary = leftDelay[leftDelayIndex]
            val early = delayedTap(leftDelay, leftDelayIndex, secondTapLeft)
            val rawWet = primary + early * activeEarlyReflection
            dampedWetLeft = dampedWetLeft * activeDamping + rawWet * (1f - activeDamping)
            leftDelay[leftDelayIndex] = (value + dampedWetLeft * activeFeedback).coerceIn(-1.45f, 1.45f)
            leftDelayIndex = (leftDelayIndex + 1) % leftDelay.size
            value = mixReverb(value, dampedWetLeft)
        }
        return if (limiter) softLimit(value) else value.coerceIn(-1f, 1f)
    }

    private fun processStereoFrame(inputLeft: Float, inputRight: Float) {
        resetReverbIfRequested()
        if (!enabled) {
            processedLeft = inputLeft
            processedRight = inputRight
            return
        }
        var left = inputLeft
        var right = inputRight

        if (mono) {
            val merged = (left + right) * 0.5f
            left = merged
            right = merged
        } else {
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f * stereoWidth
            left = mid + side
            right = mid - side
        }

        if (!mono) {
            // Le délai continue d'être alimenté même lorsque l'effet est désactivé. À l'activation,
            // le signal retardé est donc déjà valide et aucun buffer rempli de zéros ne provoque
            // de creux sonore.
            processSurround(left, right)
            left = surroundProcessedLeft
            right = surroundProcessedRight
        }

        if (balance > 0f) left *= (1f - balance)
        if (balance < 0f) right *= (1f + balance)

        if (reverbEnabled && reverbMix > 0f) {
            rebuildReverbIfNeeded()
            val primaryLeft = leftDelay[leftDelayIndex]
            val primaryRight = rightDelay[rightDelayIndex]
            val earlyLeft = delayedTap(leftDelay, leftDelayIndex, secondTapLeft)
            val earlyRight = delayedTap(rightDelay, rightDelayIndex, secondTapRight)

            val rawWetLeft = primaryLeft + earlyRight * activeEarlyReflection
            val rawWetRight = primaryRight + earlyLeft * activeEarlyReflection
            dampedWetLeft = dampedWetLeft * activeDamping + rawWetLeft * (1f - activeDamping)
            dampedWetRight = dampedWetRight * activeDamping + rawWetRight * (1f - activeDamping)

            leftDelay[leftDelayIndex] = (
                left + dampedWetLeft * activeFeedback + dampedWetRight * activeFeedback * activeCrossfeed
            ).coerceIn(-1.45f, 1.45f)
            rightDelay[rightDelayIndex] = (
                right + dampedWetRight * activeFeedback + dampedWetLeft * activeFeedback * activeCrossfeed
            ).coerceIn(-1.45f, 1.45f)

            leftDelayIndex = (leftDelayIndex + 1) % leftDelay.size
            rightDelayIndex = (rightDelayIndex + 1) % rightDelay.size
            left = mixReverb(left, dampedWetLeft)
            right = mixReverb(right, dampedWetRight)
        }

        if (limiter) {
            left = softLimit(left)
            right = softLimit(right)
        }
        processedLeft = left.coerceIn(-1f, 1f)
        processedRight = right.coerceIn(-1f, 1f)
    }

    /**
     * Spatialisation casque/enceintes en deux canaux : élargissement mid/side, accentuation douce
     * des informations latérales et micro-retard croisé (effet Haas).
     *
     * L'effet est mélangé progressivement sur environ 180 ms. Le délai est alimenté en permanence,
     * même à 0 %, et un léger gain de compensation ne s'applique que si le traitement ferait
     * réellement baisser l'énergie instantanée. Cela supprime la marche de volume et le sursaut
     * observés lors de l'activation.
     */
    private fun processSurround(left: Float, right: Float) {
        if (surroundLeftDelay.size <= 1 || surroundRightDelay.size <= 1 || sampleRate <= 0) {
            surroundProcessedLeft = left
            surroundProcessedRight = right
            return
        }

        val targetStrength = surroundStrength.coerceIn(0f, 1f)
        val targetMix = if (surroundEnabled && targetStrength > 0f) 1f else 0f
        surroundMix = moveTowards(
            surroundMix,
            targetMix,
            1f / (sampleRate * SURROUND_MIX_RAMP_SECONDS).coerceAtLeast(1f)
        )
        surroundSmoothedStrength = moveTowards(
            surroundSmoothedStrength,
            targetStrength,
            1f / (sampleRate * SURROUND_STRENGTH_RAMP_SECONDS).coerceAtLeast(1f)
        )

        val strength = surroundSmoothedStrength.coerceIn(0f, 1f)
        val delayMs = 5.5f + strength * 7.5f
        surroundReadOffset = (sampleRate * delayMs / 1000f).toInt()
            .coerceIn(1, surroundLeftDelay.size - 1)
        val readIndex = (surroundDelayIndex - surroundReadOffset + surroundLeftDelay.size) % surroundLeftDelay.size
        val delayedLeft = surroundLeftDelay[readIndex]
        val delayedRight = surroundRightDelay[readIndex]
        surroundLeftDelay[surroundDelayIndex] = left
        surroundRightDelay[surroundDelayIndex] = right
        surroundDelayIndex = (surroundDelayIndex + 1) % surroundLeftDelay.size

        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f
        val smoothing = 0.035f + (1f - strength) * 0.045f
        surroundSideLowPass += (side - surroundSideLowPass) * smoothing
        val sidePresence = side - surroundSideLowPass
        val sideGain = 1f + 0.42f * strength
        val presenceGain = 0.22f * strength
        val crossDelayGain = 0.085f * strength

        val spatialSide = side * sideGain + sidePresence * presenceGain
        var wetLeft = mid + spatialSide - delayedRight * crossDelayGain
        var wetRight = mid - spatialSide - delayedLeft * crossDelayGain

        // Compensation uniquement ascendante : elle corrige une éventuelle perte d'énergie sans
        // créer de baisse artificielle quand l'effet élargit déjà suffisamment le signal.
        val dryEnergy = kotlin.math.sqrt((left * left + right * right) * 0.5f + 1e-7f)
        val wetEnergy = kotlin.math.sqrt((wetLeft * wetLeft + wetRight * wetRight) * 0.5f + 1e-7f)
        val targetCompensation = if (wetEnergy < dryEnergy) {
            (dryEnergy / wetEnergy).coerceIn(1f, SURROUND_MAX_COMPENSATION)
        } else {
            1f
        }
        val compensationSmoothing = if (targetCompensation > surroundCompensationGain) 0.012f else 0.0025f
        surroundCompensationGain += (targetCompensation - surroundCompensationGain) * compensationSmoothing
        wetLeft *= surroundCompensationGain
        wetRight *= surroundCompensationGain

        val mix = smoothStep(surroundMix)
        surroundProcessedLeft = left + (wetLeft - left) * mix
        surroundProcessedRight = right + (wetRight - right) * mix
    }

    private fun rebuildSurroundDelay() {
        val rate = sampleRate
        if (rate <= 0) return
        val maxDelaySamples = (rate * 0.018f).toInt().coerceAtLeast(64)
        surroundLeftDelay = FloatArray(maxDelaySamples)
        surroundRightDelay = FloatArray(maxDelaySamples)
        val delayMs = 5.5f + surroundSmoothedStrength.coerceIn(0f, 1f) * 7.5f
        surroundReadOffset = (rate * delayMs / 1000f).toInt().coerceIn(1, maxDelaySamples - 1)
        surroundDelayIndex = 0
        surroundSideLowPass = 0f
        surroundCompensationGain = 1f
    }

    private fun moveTowards(current: Float, target: Float, maxDelta: Float): Float = when {
        current < target -> (current + maxDelta).coerceAtMost(target)
        current > target -> (current - maxDelta).coerceAtLeast(target)
        else -> target
    }

    private fun smoothStep(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun mixReverb(dry: Float, wet: Float): Float {
        // Le signal sec reste présent même à forte intensité. Le gain propre à chaque profil rend
        // les différences Studio / Pièce / Salle / Plaque immédiatement audibles.
        val dryGain = 1f - reverbMix * 0.72f
        val wetGain = (reverbMix * activeWetGain).coerceAtMost(0.92f)
        return dry * dryGain + wet * wetGain
    }

    private fun delayedTap(buffer: FloatArray, writeIndex: Int, offset: Int): Float {
        if (buffer.size <= 1) return buffer[0]
        var index = writeIndex + offset
        if (index >= buffer.size) index %= buffer.size
        return buffer[index]
    }

    private fun resetReverbIfRequested() {
        if (!resetReverbRequested) return
        resetReverbRequested = false
        activeReverbPreset = -1
        if (leftDelay.isNotEmpty()) leftDelay.fill(0f)
        if (rightDelay.isNotEmpty()) rightDelay.fill(0f)
        leftDelayIndex = 0
        rightDelayIndex = 0
        dampedWetLeft = 0f
        dampedWetRight = 0f
    }

    private fun rebuildReverbIfNeeded(force: Boolean = false) {
        val currentRate = sampleRate
        if (currentRate <= 0 || configuredChannels <= 0) return
        if (!force && activeReverbPreset == reverbPreset) return
        activeReverbPreset = reverbPreset

        val profile = when (reverbPreset) {
            1 -> ReverbProfile(
                delayMs = 47,
                stereoRatio = 1.21f,
                feedback = 0.34f,
                crossfeed = 0.14f,
                damping = 0.40f,
                earlyReflection = 0.32f,
                wetGain = 0.95f,
                secondTapRatio = 0.39f
            )
            2 -> ReverbProfile(
                delayMs = 123,
                stereoRatio = 1.41f,
                feedback = 0.56f,
                crossfeed = 0.24f,
                damping = 0.62f,
                earlyReflection = 0.45f,
                wetGain = 1.25f,
                secondTapRatio = 0.28f
            )
            3 -> ReverbProfile(
                delayMs = 71,
                stereoRatio = 1.08f,
                feedback = 0.34f,
                crossfeed = 0.20f,
                damping = 0.16f,
                earlyReflection = 0.42f,
                wetGain = 1.10f,
                secondTapRatio = 0.61f
            )
            else -> ReverbProfile(
                delayMs = 24,
                stereoRatio = 1.13f,
                feedback = 0.16f,
                crossfeed = 0.06f,
                damping = 0.30f,
                earlyReflection = 0.18f,
                wetGain = 0.75f,
                secondTapRatio = 0.47f
            )
        }

        activeFeedback = profile.feedback
        activeCrossfeed = profile.crossfeed
        activeDamping = profile.damping
        activeEarlyReflection = profile.earlyReflection
        activeWetGain = profile.wetGain

        val leftLength = (currentRate * profile.delayMs / 1000).coerceAtLeast(64)
        val rightLength = (leftLength * profile.stereoRatio).toInt().coerceAtLeast(72)
        leftDelay = FloatArray(leftLength)
        rightDelay = FloatArray(rightLength)
        secondTapLeft = (leftLength * profile.secondTapRatio).toInt().coerceIn(1, leftLength - 1)
        secondTapRight = (rightLength * profile.secondTapRatio).toInt().coerceIn(1, rightLength - 1)
        leftDelayIndex = 0
        rightDelayIndex = 0
        dampedWetLeft = 0f
        dampedWetRight = 0f
    }

    private fun softLimit(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= LIMITER_THRESHOLD) return value
        val over = (magnitude - LIMITER_THRESHOLD) / (1f - LIMITER_THRESHOLD)
        val compressed = LIMITER_THRESHOLD + (1f - LIMITER_THRESHOLD) * (1f - exp(-over * 2.4f))
        return sign(value) * compressed.coerceAtMost(1f)
    }

    private fun floatToPcm16(value: Float): Short =
        (value.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private data class ReverbProfile(
        val delayMs: Int,
        val stereoRatio: Float,
        val feedback: Float,
        val crossfeed: Float,
        val damping: Float,
        val earlyReflection: Float,
        val wetGain: Float,
        val secondTapRatio: Float
    )

    companion object {
        private const val LIMITER_THRESHOLD = 0.92f
        private const val SURROUND_MIX_RAMP_SECONDS = 0.18f
        private const val SURROUND_STRENGTH_RAMP_SECONDS = 0.12f
        private const val SURROUND_MAX_COMPENSATION = 1.10f
    }
}
