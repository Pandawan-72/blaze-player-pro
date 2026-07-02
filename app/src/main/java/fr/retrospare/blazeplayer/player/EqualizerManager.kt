package fr.retrospare.blazeplayer.player

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
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
class EqualizerManager(audioSessionId: Int, context: Context) {

    companion object {
        const val SOFTWARE_BAND_COUNT = 10
        const val UI_MIN_LEVEL = -1000 // -10 dB, in millibels
        const val UI_MAX_LEVEL = 1000  // +10 dB, in millibels
        const val PREAMP_MIN = -1000
        const val PREAMP_MAX = 1000
        const val COMPRESSOR_MIN = 0
        const val COMPRESSOR_MAX = 100
        private val SOFTWARE_FREQS_HZ = intArrayOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        private const val PREF_VERSION = 3
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("eq_prefs", Context.MODE_PRIVATE)

    val equalizer: Equalizer? = try {
        Equalizer(0, audioSessionId).apply { enabled = true }
    } catch (e: Exception) {
        fr.retrospare.blazeplayer.debug.CrashReporter.log(appContext, "Native equalizer unavailable", e)
        null
    }

    val bassBoost: BassBoost? = try {
        BassBoost(0, audioSessionId).apply { enabled = true }
    } catch (_: Exception) { null }

    val virtualizer: Virtualizer? = try {
        Virtualizer(0, audioSessionId).apply { enabled = true }
    } catch (_: Exception) { null }

    val nativeBandCount: Int get() = equalizer?.numberOfBands?.toInt() ?: 0
    val numBands: Int get() = SOFTWARE_BAND_COUNT
    val minLevel: Int get() = UI_MIN_LEVEL
    val maxLevel: Int get() = UI_MAX_LEVEL

    private fun migrateIfNeeded() {
        val version = prefs.getInt("eq_cache_version", 1)
        if (version >= PREF_VERSION) return
        val edit = prefs.edit()

        // If an older build stored only custom_band_X, keep it as the real custom curve.
        for (i in 0 until SOFTWARE_BAND_COUNT) {
            val oldCustom = prefs.getInt("custom_band_$i", 0).coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)
            edit.putInt("custom_band_$i", oldCustom)
        }

        // Normalize preset names that may have been translated or missing.
        val last = prefs.getString("last_preset", "Flat") ?: "Flat"
        val stablePreset = when {
            presets.containsKey(last) -> last
            last.equals("Personnalisé", true) || last.equals("Custom", true) -> "Custom"
            else -> "Flat"
        }
        edit.putString("last_preset", stablePreset)
        edit.putInt("preamp_level", prefs.getInt("preamp_level", 0).coerceIn(PREAMP_MIN, PREAMP_MAX))
        edit.putInt("compressor_strength", prefs.getInt("compressor_strength", 0).coerceIn(COMPRESSOR_MIN, COMPRESSOR_MAX))
        edit.putInt("eq_cache_version", PREF_VERSION).commit()
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

    fun isEnabled(): Boolean = prefs.getBoolean("eq_enabled", true)

    fun setEnabled(enabled: Boolean) {
        equalizer?.enabled = enabled
        bassBoost?.enabled = enabled
        virtualizer?.enabled = enabled
        prefs.edit().putBoolean("eq_enabled", enabled).commit()
    }

    fun setBassBoost(strength: Int) {
        val safe = strength.coerceIn(0, 1000)
        bassBoost?.setStrength(safe.toShort())
        prefs.edit().putInt("bass_boost", safe).commit()
    }

    fun setVirtualizer(strength: Int) {
        val safe = strength.coerceIn(0, 1000)
        virtualizer?.setStrength(safe.toShort())
        prefs.edit().putInt("virtualizer", safe).commit()
    }

    fun setPreamp(level: Int) {
        val safe = level.coerceIn(PREAMP_MIN, PREAMP_MAX)
        prefs.edit().putInt("preamp_level", safe).commit()
        applyCustomToNative()
    }

    fun getSavedPreamp(): Int {
        migrateIfNeeded()
        return prefs.getInt("preamp_level", 0).coerceIn(PREAMP_MIN, PREAMP_MAX)
    }

    fun setCompressor(strength: Int) {
        val safe = strength.coerceIn(COMPRESSOR_MIN, COMPRESSOR_MAX)
        prefs.edit().putInt("compressor_strength", safe).commit()
        applyCustomToNative()
    }

    fun getSavedCompressor(): Int {
        migrateIfNeeded()
        return prefs.getInt("compressor_strength", 0).coerceIn(COMPRESSOR_MIN, COMPRESSOR_MAX)
    }

    fun getSavedBassBoost(): Int = prefs.getInt("bass_boost", 0).coerceIn(0, 1000)
    fun getSavedVirtualizer(): Int = prefs.getInt("virtualizer", 0).coerceIn(0, 1000)
    fun getSavedPreset(): String {
        migrateIfNeeded()
        return prefs.getString("last_preset", "Flat") ?: "Flat"
    }
    fun savePreset(name: String) { prefs.edit().putString("last_preset", name).commit() }

    private fun saveCustomBands(levels: List<Int>) {
        val edit = prefs.edit()
        for (i in 0 until SOFTWARE_BAND_COUNT) {
            edit.putInt("custom_band_$i", levels.getOrElse(i) { 0 }.coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL))
        }
        edit.putString("last_preset", "Custom")
        edit.putInt("eq_cache_version", PREF_VERSION)
        edit.commit()
    }

    fun getCustomBands(): List<Int> {
        migrateIfNeeded()
        return (0 until SOFTWARE_BAND_COUNT).map { band -> prefs.getInt("custom_band_$band", 0).coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL) }
    }

    private fun getCurrentLevels(): List<Int> {
        migrateIfNeeded()
        val preset = prefs.getString("last_preset", "Flat") ?: "Flat"
        return if (preset == "Custom") {
            getCustomBands()
        } else {
            presets[preset]?.map { it.coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL) } ?: presets["Flat"]!!
        }
    }

    fun restoreLastSession() {
        migrateIfNeeded()
        val enabled = isEnabled()
        equalizer?.enabled = enabled
        bassBoost?.enabled = enabled
        virtualizer?.enabled = enabled
        setBassBoost(getSavedBassBoost())
        setVirtualizer(getSavedVirtualizer())
        applyCustomToNative()
        equalizer?.enabled = enabled
        bassBoost?.enabled = enabled
        virtualizer?.enabled = enabled
    }

    fun applyCustom() {
        migrateIfNeeded()
        savePreset("Custom")
        applyCustomToNative()
    }

    val presets = linkedMapOf(
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
        if (name == "Custom") {
            savePreset("Custom")
            applyCustomToNative()
            return
        }
        if (!presets.containsKey(name)) return
        // Built-in presets must not overwrite the user custom curve.
        prefs.edit().putString("last_preset", name).putInt("eq_cache_version", PREF_VERSION).commit()
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

    private fun applyPreampAndCompression(level: Int): Int {
        val preamped = (level + getSavedPreamp()).coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)
        val compressor = getSavedCompressor().coerceIn(COMPRESSOR_MIN, COMPRESSOR_MAX)
        if (compressor <= 0 || preamped <= 0) return preamped
        val ratio = 1f + (compressor / 100f) * 3f // 1:1 to about 4:1 on positive boosts.
        return (preamped / ratio).roundToInt().coerceIn(UI_MIN_LEVEL, UI_MAX_LEVEL)
    }

    private fun mapUiLevelToNative(level: Int): Int {
        val range = nativeRange()
        val processed = applyPreampAndCompression(level)
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
                eq.setBandLevel(nativeIndex.toShort(), mapUiLevelToNative(targetUiLevel).toShort())
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(appContext, "Apply 10-band equalizer curve failed", e)
        }
    }

    fun release() {
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        try { virtualizer?.release() } catch (_: Exception) {}
    }
}
