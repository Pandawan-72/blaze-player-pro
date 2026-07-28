package fr.retrospare.blazeplayer.player

import kotlin.math.sqrt

/**
 * Référentiel fréquentiel unique de Blaze Audio.
 *
 * L'égaliseur DSP et le visualiseur FFT doivent présenter exactement les mêmes dix bandes.
 * Les frontières sont les moyennes géométriques entre deux fréquences centrales successives,
 * ce qui est la découpe naturelle pour des bandes espacées par octave.
 */
internal object AudioFrequencyBands {
    const val BAND_COUNT = 10

    val CENTERS_HZ: IntArray = intArrayOf(
        31, 63, 125, 250, 500,
        1_000, 2_000, 4_000, 8_000, 16_000
    )

    fun centerHz(index: Int): Int = CENTERS_HZ.getOrElse(index) { 0 }

    fun edgesHz(): FloatArray {
        val edges = FloatArray(BAND_COUNT + 1)
        for (index in 1 until BAND_COUNT) {
            edges[index] = sqrt(CENTERS_HZ[index - 1].toFloat() * CENTERS_HZ[index].toFloat())
        }
        edges[0] = (CENTERS_HZ[0].toFloat() * CENTERS_HZ[0].toFloat()) / edges[1]
        edges[BAND_COUNT] =
            (CENTERS_HZ.last().toFloat() * CENTERS_HZ.last().toFloat()) / edges[BAND_COUNT - 1]
        return edges
    }
}
