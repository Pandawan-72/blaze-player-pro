@file:Suppress("DEPRECATION")

package fr.retrospare.blazeplayer.player

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Equalizer facade used by the app UI.
 *
 * We expose a stable 10-band model (-10 dB..+10 dB) whatever the Android native Equalizer exposes.
 * The important persistence rule is now:
 * - Custom bands are stored separately and are NEVER overwritten by built-in presets.
 * - The active preset key is stored independently.
 * - Manual movement copies the currently displayed curve into Custom, changes the touched band, then
 *   persists all 10 custom bands synchronously. This avoids losing the custom curve after navigation
 *   or after a full app close.
 *
 * Preamp and compressor are persisted with the same synchronous path. The preamp shifts the whole
 * 10-band curve before it is projected to the native equalizer. The compressor is a lightweight
 * peak-taming curve applied before projection: the stronger it is, the more positive boosts are
 * compressed to avoid clipping/rebuffer artefacts on the native engine.
 */
class EqualizerManager(
    private val audioSessionId: Int,
    context: Context,
    private val attachToAudioSession: Boolean = true
) {

    companion object {
        const val SOFTWARE_BAND_COUNT = 10
        const val UI_MIN_LEVEL = -1000 // -10 dB, in millibels
        const val UI_MAX_LEVEL = 1000  // +10 dB, in millibels
        const val PREAMP_MIN = -1000
        const val PREAMP_MAX = 1000
        const val COMPRESSOR_MIN = 0
        const val COMPRESSOR_MAX = 100
        const val PREFS_NAME = "eq_prefs"
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_TONE_BASS = "tone_bass"
        const val KEY_TONE_TREBLE = "tone_treble"
        const val KEY_AUTO_HEADROOM = "auto_headroom"
        const val KEY_LIMITER = "limiter_enabled"
        const val KEY_LOUDNESS = "loudness_strength"
        const val KEY_BALANCE = "channel_balance"
        const val KEY_STEREO_WIDTH = "stereo_width"
        const val KEY_MONO = "mono_enabled"
        const val KEY_SURROUND_ENABLED = "surround_enabled"
        const val KEY_SURROUND_STRENGTH = "surround_strength"
        const val SURROUND_STRENGTH_MAX = 100
        const val KEY_REVERB_ENABLED = "reverb_enabled"
        const val KEY_REVERB_PRESET = "reverb_preset"
        const val KEY_REVERB_MIX = "reverb_mix"
        const val REVERB_PRESET_COUNT = 4
        const val REVERB_MIX_MAX = 80
        private val SOFTWARE_FREQS_HZ = intArrayOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        private const val PREF_VERSION = 7
        private const val KEY_LAST_PRESET = "last_preset"
        private const val KEY_CACHE_VERSION = "eq_cache_version"
        private const val KEY_ACTIVE_PREFIX = "active_band_"
        private const val KEY_CUSTOM_PREFIX = "custom_band_"
        private const val KEY_PRESET_PREFIX = "preset_band_"

        // Auto-headroom is intentionally gentle: the limiter remains the last safety net, while
        // ordinary tone/EQ adjustments should not sound like a global volume control.
        private const val AUTO_HEADROOM_FREE_MARGIN = 200       // +2 dB before attenuation starts
        private const val AUTO_HEADROOM_MAX_REDUCTION = 300     // never remove more than 3 dB
        private const val AUTO_HEADROOM_REDUCTION_RATIO = 0.32f // compensate only part of the excess
        private const val AUTO_HEADROOM_SMOOTHING = 0.28f
        private const val AUTO_HEADROOM_SETTLED_EPSILON = 4f
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Must be initialized before the init block: migrateIfNeeded() reads this map.
    // A delegated/lazy property declared below init would leave its delegate field null here.
    val presets: LinkedHashMap<String, List<Int>> = linkedMapOf(
        "Custom"     to emptyList(),
        "Flat"       to listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        "Rock"       to listOf(400, 250, -100, -250, 100, 300, 500, 650, 650, 550),
        "Pop"        to listOf(-100, 200, 350, 450, 300, 0, 250, 450, 250, -100),
        "Jazz"       to listOf(300, 250, 150, 250, -100, -150, 0, 200, 350, 450),
        "Classical"  to listOf(350, 250, 150, 0, -150, -100, 100, 250, 400, 500),
        "Hip-Hop"    to listOf(650, 550, 350, 100, -100, 0, 150, 250, 350, 450),
        "Electronic" to listOf(550, 450, 150, -100, -150, 100, 250, 500, 650, 650),
        "Funk"       to listOf(450, 350, 100, -100, 150, 300, 150, 200, 350, 400),
        "Bass Boost" to listOf(800, 650, 450, 250, 100, 0, 0, 0, 0, 0),
        "Treble"     to listOf(0, 0, 0, 0, 0, 150, 300, 500, 700, 850),
        "Vocal"      to listOf(-250, -150, 0, 250, 450, 600, 450, 250, 0, -150),
        "Acoustic"   to listOf(350, 300, 200, 150, 100, 150, 250, 350, 450, 500)
    )
    private val applyHandler = Handler(Looper.getMainLooper())
    private val applyCurveRunnable = Runnable { applyCustomToNative() }
    private var smoothedAutoHeadroomReduction = 0f
    private var targetAutoHeadroomReduction = 0f

    val equalizer: Equalizer? = if (!attachToAudioSession) {
        null
    } else try {
        Equalizer(0, audioSessionId).apply { enabled = true }
    } catch (e: Exception) {
        fr.retrospare.blazeplayer.debug.CrashReporter.log(appContext, "Native equalizer unavailable", e)
        null
    }

    // BassBoost and Virtualizer are optional Android audio effects. Some devices/routes (notably
    // Bluetooth outputs) do not expose them; constructing them unconditionally makes AudioFlinger
    // print initCheck errors at startup. Keep them lazy and only instantiate them when the platform
    // advertises support and the user actually has a non-zero saved strength / changes a slider.
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    private val availableEffectTypes: Set<java.util.UUID> by lazy {
        try {
            AudioEffect.queryEffects()
                ?.mapNotNull { descriptor -> descriptor.type }
                ?.toSet()
                .orEmpty()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (attachToAudioSession) {
            when {
                key == KEY_EQ_ENABLED -> {
                    val active = isEnabled()
                    safeSetEnabled(equalizer, active)
                    safeSetEnabled(bassBoost, active && getSavedBassBoost() > 0)
                    safeSetEnabled(virtualizer, active && effectiveVirtualizerStrength() > 0)
                    scheduleNativeCurveApply()
                }
                key == KEY_TONE_BASS || key == KEY_TONE_TREBLE || key == KEY_AUTO_HEADROOM ||
                    key == "preamp_level" || key == "compressor_strength" ||
                    key?.startsWith(KEY_ACTIVE_PREFIX) == true || key?.startsWith(KEY_CUSTOM_PREFIX) == true ||
                    key?.startsWith(KEY_PRESET_PREFIX) == true || key == KEY_LAST_PRESET -> scheduleNativeCurveApply()
                key == "bass_boost" -> applySavedBassBoost()
                key == "virtualizer" -> applySavedVirtualizer()
            }
        }
    }

    init {
        migrateIfNeeded()
        if (attachToAudioSession) prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun isEqualizerAvailable(): Boolean = AudioEffect.EFFECT_TYPE_EQUALIZER in availableEffectTypes
    fun isBassBoostAvailable(): Boolean = AudioEffect.EFFECT_TYPE_BASS_BOOST in availableEffectTypes
    fun isVirtualizerAvailable(): Boolean = AudioEffect.EFFECT_TYPE_VIRTUALIZER in availableEffectTypes
    fun isLoudnessAvailable(): Boolean = AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER in availableEffectTypes
    fun isDynamicsProcessingAvailable(): Boolean = AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING in availableEffectTypes

    private fun ensureBassBoost(): BassBoost? {
        if (!attachToAudioSession) return null
        bassBoost?.let { return it }
        if (!isBassBoostAvailable()) return null
        return try {
            BassBoost(0, audioSessionId).also { bassBoost = it }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(appContext, "Bass boost unavailable for audio session $audioSessionId", e)
            null
        }
    }

    private fun ensureVirtualizer(): Virtualizer? {
        if (!attachToAudioSession) return null
        virtualizer?.let { return it }
        if (!isVirtualizerAvailable()) return null
        return try {
            Virtualizer(0, audioSessionId).also { virtualizer = it }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(appContext, "Virtualizer unavailable for audio session $audioSessionId", e)
            null
        }
    }

    private fun scheduleNativeCurveApply() {
        if (!attachToAudioSession) return
        applyHandler.removeCallbacks(applyCurveRunnable)
        applyHandler.postDelayed(applyCurveRunnable, 18L)
    }

    private fun safeSetEnabled(effect: AudioEffect?, enabled: Boolean) {
        try { effect?.enabled = enabled } catch (_: Exception) {}
    }

    val nativeBandCount: Int get() = equalizer?.numberOfBands?.toInt() ?: 0
    val numBands: Int get() = SOFTWARE_BAND_COUNT
    val minLevel: Int get() = UI_MIN_LEVEL
    val maxLevel: Int get() = UI_MAX_LEVEL

    private fun migrateIfNeeded() {
        val version = prefs.getInt(KEY_CACHE_VERSION, 1)
        if (version >= PREF_VERSION && hasAllBands(KEY_ACTIVE_PREFIX)) return

        val last = prefs.getString(KEY_LAST_PRESET, "Flat") ?: "Flat"
        val stablePreset = when {
            presets.containsKey(last) -> last
            last.equals("Personnalisé", true) || last.equals("Custom", true) -> "Custom"
            else -> "Flat"
        }

        val edit = prefs.edit()

        // Migration 5 -> 10 bandes : conserve les anciennes valeurs quand elles existent
        // et complète toutes les bandes manquantes avec la courbe complète du préréglage.
        val fallback = presetDefaults(stablePreset)
        for (i in 0 until SOFTWARE_BAND_COUNT) {
            val customKey = "$KEY_CUSTOM_PREFIX$i"
            if (!prefs.contains(customKey)) {
                edit.putInt(customKey, fallback.getOrElse(i) { 0 }.coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL))
            }
        }

        presets.forEach { (presetName, levels) ->
            if (presetName != "Custom" && levels.size == SOFTWARE_BAND_COUNT) {
                for (i in 0 until SOFTWARE_BAND_COUNT) {
                    val key = presetBandKey(presetName, i)
                    if (!prefs.contains(key)) {
                        edit.putInt(key, levels[i].coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL))
                    }
                }
            }
        }

        val active = if (stablePreset == "Custom") {
            (0 until SOFTWARE_BAND_COUNT).map { i ->
                prefs.getInt("$KEY_CUSTOM_PREFIX$i", fallback.getOrElse(i) { 0 }).coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)
            }
        } else {
            fallback
        }
        for (i in 0 until SOFTWARE_BAND_COUNT) {
            edit.putInt("$KEY_ACTIVE_PREFIX$i", active.getOrElse(i) { 0 }.coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL))
        }

        edit.putString(KEY_LAST_PRESET, stablePreset)
        edit.putInt("preamp_level", prefs.getInt("preamp_level", 0).coerceIn(PREAMP_MIN, PREAMP_MAX))
        edit.putInt("compressor_strength", prefs.getInt("compressor_strength", 0).coerceIn(COMPRESSOR_MIN, COMPRESSOR_MAX))
        edit.putInt(KEY_TONE_BASS, prefs.getInt(KEY_TONE_BASS, 0).coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL))
        edit.putInt(KEY_TONE_TREBLE, prefs.getInt(KEY_TONE_TREBLE, 0).coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL))
        edit.putBoolean(KEY_AUTO_HEADROOM, prefs.getBoolean(KEY_AUTO_HEADROOM, true))
        edit.putBoolean(KEY_LIMITER, prefs.getBoolean(KEY_LIMITER, true))
        edit.putInt(KEY_LOUDNESS, prefs.getInt(KEY_LOUDNESS, 0).coerceIn(0, 100))
        edit.putInt(KEY_BALANCE, prefs.getInt(KEY_BALANCE, 0).coerceIn(-100, 100))
        edit.putInt(KEY_STEREO_WIDTH, prefs.getInt(KEY_STEREO_WIDTH, 100).coerceIn(0, 150))
        edit.putBoolean(KEY_MONO, prefs.getBoolean(KEY_MONO, false))
        edit.putBoolean(KEY_SURROUND_ENABLED, prefs.getBoolean(KEY_SURROUND_ENABLED, false))
        edit.putInt(KEY_SURROUND_STRENGTH, prefs.getInt(KEY_SURROUND_STRENGTH, 55).coerceIn(0, SURROUND_STRENGTH_MAX))
        edit.putBoolean(KEY_REVERB_ENABLED, prefs.getBoolean(KEY_REVERB_ENABLED, false))
        edit.putInt(KEY_REVERB_PRESET, prefs.getInt(KEY_REVERB_PRESET, 0).coerceIn(0, REVERB_PRESET_COUNT - 1))
        edit.putInt(KEY_REVERB_MIX, prefs.getInt(KEY_REVERB_MIX, 0).coerceIn(0, REVERB_MIX_MAX))
        edit.putInt(KEY_CACHE_VERSION, PREF_VERSION)
        edit.commit()
    }

    fun getBandFreq(band: Int): Int = SOFTWARE_FREQS_HZ.getOrElse(band) { 0 }

    fun getBandLevel(band: Int): Int {
        migrateIfNeeded()
        return getCurrentLevels().getOrElse(band) { 0 }.coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)
    }

    fun setBandLevel(band: Int, level: Int) {
        if (band !in 0 until SOFTWARE_BAND_COUNT) return
        migrateIfNeeded()

        val levels = getCurrentLevels().toMutableList()
        levels[band] = level.coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)
        saveCustomBands(levels)
        savePreset("Custom")
        applyCustomToNative()
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_EQ_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EQ_ENABLED, enabled).commit()
        if (attachToAudioSession) {
            safeSetEnabled(equalizer, enabled)
            if (enabled) {
                applySavedBassBoost()
                applySavedVirtualizer()
            } else {
                safeSetEnabled(bassBoost, false)
                safeSetEnabled(virtualizer, false)
            }
        }
    }

    private fun applySavedBassBoost() {
        val safe = getSavedBassBoost()
        val effect = if (safe > 0 && isEnabled()) ensureBassBoost() else bassBoost
        runCatching {
            effect?.let {
                if (safe > 0 && isEnabled()) it.setStrength(safe.toShort())
                it.enabled = safe > 0 && isEnabled()
            }
        }
    }

    private fun applySavedVirtualizer() {
        val safe = effectiveVirtualizerStrength()
        val effect = if (safe > 0 && isEnabled()) ensureVirtualizer() else virtualizer
        runCatching {
            effect?.let {
                if (safe > 0 && isEnabled()) it.setStrength(safe.toShort())
                it.enabled = safe > 0 && isEnabled()
            }
        }
    }

    fun setBassBoost(strength: Int) {
        val safe = strength.coerceIn(0, 1000)
        prefs.edit().putInt("bass_boost", safe).commit()
        if (!attachToAudioSession) return
        val effect = if (safe > 0 && isEnabled()) ensureBassBoost() else bassBoost
        try {
            effect?.let {
                if (safe > 0 && isEnabled()) it.setStrength(safe.toShort())
                it.enabled = safe > 0 && isEnabled()
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(appContext, "Apply bass boost failed", e)
        }
    }

    fun setVirtualizer(strength: Int) {
        val safe = strength.coerceIn(0, 1000)
        prefs.edit().putInt("virtualizer", safe).commit()
        if (!attachToAudioSession) return
        val effect = if (safe > 0 && isEnabled()) ensureVirtualizer() else virtualizer
        try {
            effect?.let {
                if (safe > 0 && isEnabled()) it.setStrength(safe.toShort())
                it.enabled = safe > 0 && isEnabled()
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(appContext, "Apply virtualizer failed", e)
        }
    }

    fun setPreamp(level: Int) {
        val safe = level.coerceIn(PREAMP_MIN, PREAMP_MAX)
        prefs.edit().putInt("preamp_level", safe).apply()
        applyCustomToNative()
    }

    fun getSavedPreamp(): Int {
        migrateIfNeeded()
        return prefs.getInt("preamp_level", 0).coerceIn(PREAMP_MIN, PREAMP_MAX)
    }

    fun setCompressor(strength: Int) {
        val safe = strength.coerceIn(COMPRESSOR_MIN, COMPRESSOR_MAX)
        prefs.edit().putInt("compressor_strength", safe).apply()
        applyCustomToNative()
    }

    fun getSavedCompressor(): Int {
        migrateIfNeeded()
        return prefs.getInt("compressor_strength", 0).coerceIn(COMPRESSOR_MIN, COMPRESSOR_MAX)
    }

    fun setToneBass(level: Int) {
        prefs.edit().putInt(KEY_TONE_BASS, level.coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)).apply()
        applyCustomToNative()
    }
    fun getSavedToneBass(): Int = prefs.getInt(KEY_TONE_BASS, 0).coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)

    fun setToneTreble(level: Int) {
        prefs.edit().putInt(KEY_TONE_TREBLE, level.coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)).apply()
        applyCustomToNative()
    }
    fun getSavedToneTreble(): Int = prefs.getInt(KEY_TONE_TREBLE, 0).coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)

    fun setAutoHeadroom(enabled: Boolean) { prefs.edit().putBoolean(KEY_AUTO_HEADROOM, enabled).apply(); applyCustomToNative() }
    fun isAutoHeadroomEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_HEADROOM, true)

    fun setLimiterEnabled(enabled: Boolean) { prefs.edit().putBoolean(KEY_LIMITER, enabled).apply() }
    fun isLimiterEnabled(): Boolean = prefs.getBoolean(KEY_LIMITER, true)

    fun setLoudness(strength: Int) { prefs.edit().putInt(KEY_LOUDNESS, strength.coerceIn(0, 100)).apply() }
    fun getSavedLoudness(): Int = prefs.getInt(KEY_LOUDNESS, 0).coerceIn(0, 100)
    fun getSavedLoudnessMillibels(): Int = (getSavedLoudness() * 12).coerceIn(0, 1200)

    fun setBalance(value: Int) { prefs.edit().putInt(KEY_BALANCE, value.coerceIn(-100, 100)).apply() }
    fun getSavedBalance(): Int = prefs.getInt(KEY_BALANCE, 0).coerceIn(-100, 100)

    fun setStereoWidth(value: Int) { prefs.edit().putInt(KEY_STEREO_WIDTH, value.coerceIn(0, 150)).apply() }
    fun getSavedStereoWidth(): Int = prefs.getInt(KEY_STEREO_WIDTH, 100).coerceIn(0, 150)

    fun setMonoEnabled(enabled: Boolean) { prefs.edit().putBoolean(KEY_MONO, enabled).apply() }
    fun isMonoEnabled(): Boolean = prefs.getBoolean(KEY_MONO, false)

    fun setSurroundEnabled(enabled: Boolean) {
        // Le surround Blaze est traité par le processeur PCM avec un fondu progressif. Ne pas
        // activer simultanément le Virtualizer constructeur : certains appareils changent alors
        // brutalement leur gain ou leur route audio.
        prefs.edit().putBoolean(KEY_SURROUND_ENABLED, enabled).apply()
    }

    fun isSurroundEnabled(): Boolean = prefs.getBoolean(KEY_SURROUND_ENABLED, false)

    fun setSurroundStrength(value: Int) {
        prefs.edit().putInt(KEY_SURROUND_STRENGTH, value.coerceIn(0, SURROUND_STRENGTH_MAX)).apply()
    }

    fun getSavedSurroundStrength(): Int =
        prefs.getInt(KEY_SURROUND_STRENGTH, 55).coerceIn(0, SURROUND_STRENGTH_MAX)

    private fun effectiveVirtualizerStrength(): Int = getSavedVirtualizer()

    fun setReverbEnabled(enabled: Boolean) { prefs.edit().putBoolean(KEY_REVERB_ENABLED, enabled).apply() }
    fun isReverbEnabled(): Boolean = prefs.getBoolean(KEY_REVERB_ENABLED, false)

    fun setReverbPreset(index: Int) { prefs.edit().putInt(KEY_REVERB_PRESET, index.coerceIn(0, REVERB_PRESET_COUNT - 1)).apply() }
    fun getSavedReverbPreset(): Int = prefs.getInt(KEY_REVERB_PRESET, 0).coerceIn(0, REVERB_PRESET_COUNT - 1)

    fun setReverbMix(value: Int) { prefs.edit().putInt(KEY_REVERB_MIX, value.coerceIn(0, REVERB_MIX_MAX)).apply() }
    fun getSavedReverbMix(): Int = prefs.getInt(KEY_REVERB_MIX, 0).coerceIn(0, REVERB_MIX_MAX)

    fun getSavedBassBoost(): Int = prefs.getInt("bass_boost", 0).coerceIn(0, 1000)
    fun getSavedVirtualizer(): Int = prefs.getInt("virtualizer", 0).coerceIn(0, 1000)
    fun getSavedPreset(): String {
        migrateIfNeeded()
        return prefs.getString(KEY_LAST_PRESET, "Flat") ?: "Flat"
    }
    fun savePreset(name: String) {
        migrateIfNeeded()
        val stable = if (presets.containsKey(name)) name else "Flat"
        val levels = if (stable == "Custom") getCustomBands() else getSavedPresetLevels(stable)
        saveActiveBands(levels, stable)
    }

    private fun saveCustomBands(levels: List<Int>) {
        val edit = prefs.edit()
        for (i in 0 until SOFTWARE_BAND_COUNT) {
            edit.putInt("$KEY_CUSTOM_PREFIX$i", levels.getOrElse(i) { 0 }.coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL))
        }
        edit.putString(KEY_LAST_PRESET, "Custom")
        edit.putInt(KEY_CACHE_VERSION, PREF_VERSION)
        edit.commit()
        saveActiveBands(levels, "Custom")
    }

    fun getCustomBands(): List<Int> {
        migrateIfNeeded()
        return (0 until SOFTWARE_BAND_COUNT).map { band -> prefs.getInt("$KEY_CUSTOM_PREFIX$band", 0).coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL) }
    }

    private fun hasAllBands(prefix: String): Boolean =
        (0 until SOFTWARE_BAND_COUNT).all { prefs.contains("$prefix$it") }

    private fun presetBandKey(presetName: String, band: Int): String =
        "$KEY_PRESET_PREFIX${presetName.replace(" ", "_")}_$band"

    private fun presetDefaults(name: String): List<Int> {
        val defaults = if (name == "Custom") presets["Flat"] else presets[name]
        return (defaults ?: presets["Flat"]!!).let { levels ->
            (0 until SOFTWARE_BAND_COUNT).map { i -> levels.getOrElse(i) { 0 }.coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL) }
        }
    }

    private fun getSavedPresetLevels(name: String): List<Int> {
        val fallback = presetDefaults(name)
        if (name == "Custom") return getCustomBands()
        return (0 until SOFTWARE_BAND_COUNT).map { i ->
            prefs.getInt(presetBandKey(name, i), fallback.getOrElse(i) { 0 }).coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)
        }
    }

    private fun savePresetLevels(name: String, levels: List<Int>) {
        if (name == "Custom") return
        val edit = prefs.edit()
        for (i in 0 until SOFTWARE_BAND_COUNT) {
            edit.putInt(presetBandKey(name, i), levels.getOrElse(i) { 0 }.coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL))
        }
        edit.putInt(KEY_CACHE_VERSION, PREF_VERSION)
        edit.commit()
    }

    private fun saveActiveBands(levels: List<Int>, presetName: String) {
        val edit = prefs.edit()
        for (i in 0 until SOFTWARE_BAND_COUNT) {
            edit.putInt("$KEY_ACTIVE_PREFIX$i", levels.getOrElse(i) { 0 }.coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL))
        }
        edit.putString(KEY_LAST_PRESET, presetName)
        edit.putInt(KEY_CACHE_VERSION, PREF_VERSION)
        edit.commit()
    }

    private fun getCurrentLevels(): List<Int> {
        migrateIfNeeded()
        val preset = prefs.getString(KEY_LAST_PRESET, "Flat") ?: "Flat"
        val fallback = if (preset == "Custom") getCustomBands() else getSavedPresetLevels(preset)
        return (0 until SOFTWARE_BAND_COUNT).map { i ->
            prefs.getInt("$KEY_ACTIVE_PREFIX$i", fallback.getOrElse(i) { 0 }).coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)
        }
    }

    fun restoreLastSession() {
        migrateIfNeeded()
        if (!attachToAudioSession) return
        val enabled = isEnabled()
        safeSetEnabled(equalizer, enabled)
        if (enabled) {
            applySavedBassBoost()
            applySavedVirtualizer()
        } else {
            safeSetEnabled(bassBoost, false)
            safeSetEnabled(virtualizer, false)
        }
        applyCustomToNative()
        safeSetEnabled(equalizer, enabled)
        safeSetEnabled(bassBoost, enabled && getSavedBassBoost() > 0)
        safeSetEnabled(virtualizer, enabled && effectiveVirtualizerStrength() > 0)
    }

    fun applyCustom() {
        migrateIfNeeded()
        savePreset("Custom")
        applyCustomToNative()
    }

    /** Nom traduit à afficher pour un préréglage, à partir de sa clé interne stable. */
    fun getPresetDisplayName(context: Context, key: String): String {
        val resId = when (key) {
            "Custom" -> fr.retrospare.blazeplayer.R.string.preset_custom
            "Flat" -> fr.retrospare.blazeplayer.R.string.preset_flat
            "Rock" -> fr.retrospare.blazeplayer.R.string.preset_rock
            "Pop" -> fr.retrospare.blazeplayer.R.string.preset_pop
            "Jazz" -> fr.retrospare.blazeplayer.R.string.preset_jazz
            "Classical" -> fr.retrospare.blazeplayer.R.string.preset_classical
            "Hip-Hop" -> fr.retrospare.blazeplayer.R.string.preset_hiphop
            "Electronic" -> fr.retrospare.blazeplayer.R.string.preset_electronic
            "Funk" -> fr.retrospare.blazeplayer.R.string.preset_funk
            "Bass Boost" -> fr.retrospare.blazeplayer.R.string.preset_bassboost
            "Treble" -> fr.retrospare.blazeplayer.R.string.preset_treble
            "Vocal" -> fr.retrospare.blazeplayer.R.string.preset_vocal
            "Acoustic" -> fr.retrospare.blazeplayer.R.string.preset_acoustic
            else -> null
        }
        return if (resId != null) context.getString(resId) else key
    }

    fun applyPreset(name: String) {
        migrateIfNeeded()
        if (name == "Custom") {
            val levels = getCustomBands()
            saveActiveBands(levels, "Custom")
            applyCustomToNative()
            return
        }
        if (!presets.containsKey(name)) return
        val levels = presetDefaults(name)
        // Enregistre explicitement les 10 bandes du préréglage, même si l'Equalizer Android natif
        // du téléphone ne possède que 5 bandes matérielles. L'UI et la restauration restent donc
        // toujours en 10 bandes.
        savePresetLevels(name, levels)
        saveActiveBands(levels, name)
        applyCustomToNative()
    }

    private fun nativeBandCenterHz(index: Int): Int {
        return try { (equalizer?.getCenterFreq(index.toShort()) ?: 0) / 1000 } catch (_: Exception) { 0 }
    }

    private fun nativeRange(): IntRange {
        val range = equalizer?.bandLevelRange
        val min = range?.getOrNull(0)?.toInt() ?: -1500
        val max = range?.getOrNull(1)?.toInt() ?: 1500
        return min..max
    }

    private fun toneAdjustmentForFrequency(frequencyHz: Int): Int {
        val bass = getSavedToneBass()
        val treble = getSavedToneTreble()
        val bassWeight = when {
            frequencyHz <= 63 -> 1f
            frequencyHz <= 125 -> 0.72f
            frequencyHz <= 250 -> 0.34f
            else -> 0f
        }
        val trebleWeight = when {
            frequencyHz >= 16000 -> 1f
            frequencyHz >= 8000 -> 0.72f
            frequencyHz >= 4000 -> 0.34f
            else -> 0f
        }
        return (bass * bassWeight + treble * trebleWeight).roundToInt()
    }

    private fun effectivePreamp(levels: List<Int>): Int {
        val requested = getSavedPreamp()
        if (!isAutoHeadroomEnabled()) {
            targetAutoHeadroomReduction = 0f
            smoothedAutoHeadroomReduction = 0f
            return requested
        }

        val peak = SOFTWARE_FREQS_HZ.indices.maxOfOrNull { index ->
            levels.getOrElse(index) { 0 } + toneAdjustmentForFrequency(SOFTWARE_FREQS_HZ[index])
        } ?: 0
        val positivePeak = (peak + requested).coerceAtLeast(0)
        val excess = (positivePeak - AUTO_HEADROOM_FREE_MARGIN).coerceAtLeast(0)

        // Previously the whole positive peak was subtracted (for example +6 dB => -6 dB globally),
        // which made bass/treble adjustments sound like a large volume drop. Keep a 2 dB free
        // margin, compensate only 32% of the excess and cap the reduction at 3 dB.
        targetAutoHeadroomReduction = (excess * AUTO_HEADROOM_REDUCTION_RATIO)
            .coerceAtMost(AUTO_HEADROOM_MAX_REDUCTION.toFloat())

        val delta = targetAutoHeadroomReduction - smoothedAutoHeadroomReduction
        smoothedAutoHeadroomReduction = if (abs(delta) <= AUTO_HEADROOM_SETTLED_EPSILON) {
            targetAutoHeadroomReduction
        } else {
            smoothedAutoHeadroomReduction + delta * AUTO_HEADROOM_SMOOTHING
        }

        return (requested - smoothedAutoHeadroomReduction.roundToInt())
            .coerceIn(PREAMP_MIN, PREAMP_MAX)
    }

    private fun applyPreampAndCompression(level: Int, effectivePreamp: Int): Int {
        val preamped = (level + effectivePreamp).coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)
        val compressor = getSavedCompressor().coerceIn(COMPRESSOR_MIN, COMPRESSOR_MAX)
        if (compressor <= 0 || preamped <= 0) return preamped
        val ratio = 1f + (compressor / 100f) * 3f // 1:1 to about 4:1 on positive boosts.
        return (preamped / ratio).roundToInt().coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)
    }

    private fun mapUiLevelToNative(level: Int, effectivePreamp: Int): Int {
        val range = nativeRange()
        val processed = applyPreampAndCompression(level, effectivePreamp)
        return if (processed >= 0) {
            ((processed / UI_MAX_LEVEL.toFloat()) * range.last).toInt()
        } else {
            ((abs(processed) / abs(UI_MIN_LEVEL).toFloat()) * range.first).toInt()
        }.coerceIn(range.first, range.last)
    }

    /** Projects the active 10-band curve to the native hardware equalizer. */
    private fun applyCustomToNative() {
        val eq = equalizer ?: return
        val nativeBands = nativeBandCount
        if (nativeBands <= 0) return
        val levels = getCurrentLevels()
        val preamp = effectivePreamp(levels)
        try {
            for (nativeIndex in 0 until nativeBands) {
                val nativeFreq = nativeBandCenterHz(nativeIndex)
                val targetUiLevel = if (nativeFreq > 0) {
                    val sorted = SOFTWARE_FREQS_HZ.indices.sortedBy { abs(SOFTWARE_FREQS_HZ[it] - nativeFreq) }
                    val first = sorted.getOrNull(0) ?: 0
                    val second = sorted.getOrNull(1) ?: first
                    ((levels[first] * 0.7f) + (levels[second] * 0.3f)).toInt()
                } else {
                    levels.getOrElse((nativeIndex * SOFTWARE_BAND_COUNT) / nativeBands) { 0 }
                }
                val frequencyForTone = if (nativeFreq > 0) nativeFreq else SOFTWARE_FREQS_HZ.getOrElse((nativeIndex * SOFTWARE_BAND_COUNT) / nativeBands) { 1000 }
                val tonedLevel = (targetUiLevel + toneAdjustmentForFrequency(frequencyForTone)).coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)
                eq.setBandLevel(nativeIndex.toShort(), mapUiLevelToNative(tonedLevel, preamp).toShort())
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(appContext, "Apply 10-band equalizer curve failed", e)
        }

        // Continue a few short frames after the gesture so the small safety correction settles
        // smoothly instead of changing the perceived volume in one abrupt step.
        if (abs(targetAutoHeadroomReduction - smoothedAutoHeadroomReduction) > AUTO_HEADROOM_SETTLED_EPSILON) {
            applyHandler.removeCallbacks(applyCurveRunnable)
            applyHandler.postDelayed(applyCurveRunnable, 16L)
        }
    }

    fun release() {
        applyHandler.removeCallbacks(applyCurveRunnable)
        if (attachToAudioSession) runCatching { prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        try { virtualizer?.release() } catch (_: Exception) {}
        bassBoost = null
        virtualizer = null
    }
}
