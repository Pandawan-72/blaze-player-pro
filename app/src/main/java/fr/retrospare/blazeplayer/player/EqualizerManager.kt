package fr.retrospare.blazeplayer.player

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer

class EqualizerManager(audioSessionId: Int, context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("eq_prefs", Context.MODE_PRIVATE)

    val equalizer: Equalizer = Equalizer(0, audioSessionId).apply { enabled = true }
    val bassBoost: BassBoost = BassBoost(0, audioSessionId).apply { enabled = true }
    val virtualizer: Virtualizer = Virtualizer(0, audioSessionId).apply { enabled = true }

    val numBands: Int get() = equalizer.numberOfBands.toInt()
    val minLevel: Int get() = equalizer.bandLevelRange[0].toInt()
    val maxLevel: Int get() = equalizer.bandLevelRange[1].toInt()

    fun getBandFreq(band: Int): Int = equalizer.getCenterFreq(band.toShort()) / 1000
    fun getBandLevel(band: Int): Int = equalizer.getBandLevel(band.toShort()).toInt()
    fun setBandLevel(band: Int, level: Int) {
        equalizer.setBandLevel(band.toShort(), level.toShort())
        saveCustomBand(band, level)
    }

    fun setBassBoost(strength: Int) {
        bassBoost.setStrength(strength.toShort())
        prefs.edit().putInt("bass_boost", strength).apply()
    }

    fun setVirtualizer(strength: Int) {
        virtualizer.setStrength(strength.toShort())
        prefs.edit().putInt("virtualizer", strength).apply()
    }

    fun getSavedBassBoost(): Int = prefs.getInt("bass_boost", 0)
    fun getSavedVirtualizer(): Int = prefs.getInt("virtualizer", 0)
    fun getSavedPreset(): String = prefs.getString("last_preset", "Flat") ?: "Flat"
    fun savePreset(name: String) = prefs.edit().putString("last_preset", name).apply()

    private fun saveCustomBand(band: Int, level: Int) {
        prefs.edit().putInt("custom_band_$band", level).apply()
    }

    fun getCustomBands(): List<Int> {
        return (0 until numBands).map { band ->
            prefs.getInt("custom_band_$band", 0)
        }
    }

    fun restoreLastSession() {
        val preset = getSavedPreset()
        if (preset == "Custom") {
            applyCustom()
        } else {
            applyPreset(preset)
        }
        val bass = getSavedBassBoost()
        val virt = getSavedVirtualizer()
        if (bass > 0) setBassBoost(bass)
        if (virt > 0) setVirtualizer(virt)
    }

    fun applyCustom() {
        for (band in 0 until numBands) {
            val level = prefs.getInt("custom_band_$band", 0)
            equalizer.setBandLevel(band.toShort(), level.toShort())
        }
    }

    val presets = linkedMapOf(
        "Custom"     to emptyList(),
        "Flat"       to listOf(0, 0, 0, 0, 0),
        "Rock"       to listOf(-200, 400, 300, 400, 200),
        "Pop"        to listOf(-100, 200, 400, 200, -100),
        "Jazz"       to listOf(300, 200, 0, 200, 300),
        "Classical"  to listOf(500, 300, -200, 200, 400),
        "Hip-Hop"    to listOf(500, 400, 100, 100, 300),
        "Electronic" to listOf(400, 300, 0, 300, 400),
        "Funk"       to listOf(300, 200, -100, 200, 300),
        "Bass Boost" to listOf(600, 400, 0, 0, 0),
        "Treble"     to listOf(0, 0, 0, 400, 600),
        "Vocal"      to listOf(-200, 400, 600, 400, -200),
        "Acoustic"   to listOf(400, 200, 100, 200, 300)
    )

    fun applyPreset(name: String) {
        if (name == "Custom") {
            applyCustom()
            return
        }
        val levels = presets[name] ?: return
        val bandsToSet = minOf(levels.size, numBands)
        for (i in 0 until bandsToSet) {
            equalizer.setBandLevel(i.toShort(), levels[i].toShort())
        }
        savePreset(name)
    }

    fun release() {
        equalizer.release()
        bassBoost.release()
        virtualizer.release()
    }
}
