package fr.retrospare.blazeplayer.player

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Calcul de la couleur dynamique (accent + fond) à partir de la pochette d'un morceau.
 * Extrait d'AudioPlayerFragment pour être partagé avec le mini player (MiniPlayerViewModel),
 * afin que le mini player affiche exactement la même couleur que l'écran Blaze Audio pour un
 * même morceau, sans dépendre du fait que l'écran complet ait déjà été ouvert ou non.
 */
object AudioDynamicColor {

    val DEFAULT_ACCENT: Int = Color.rgb(63, 215, 143)
    val DEFAULT_BACKGROUND: Int = Color.rgb(10, 12, 14)

    private const val MIN_ALPHA = 48
    private const val STRICT_MIN_SATURATION = 0.30f
    private const val STRICT_MIN_VALUE = 0.50f
    private const val RELAXED_MIN_SATURATION = 0.18f
    private const val RELAXED_MIN_VALUE = 0.42f
    private const val MIN_ACCEPTED_PIXELS = 8

    /** Couleur dominante robuste de la pochette.
     *
     * Le filtre ignore volontairement les pixels vraiment trop sombres. Les gris et les bruns ne
     * sont plus exclus par principe : ils sont acceptés quand ils restent assez lumineux pour ne
     * pas produire une interface noire/marron illisible. Les couleurs plus vives restent
     * prioritaires quand la pochette en contient. */
    fun accentFromBitmap(bitmap: Bitmap): Int {
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 40, 40, true)
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)

            val strict = Accumulator()
            val relaxed = Accumulator()
            var bestColor = DEFAULT_ACCENT
            var bestScore = -1f
            val hsv = FloatArray(3)

            for (px in pixels) {
                if (Color.alpha(px) < MIN_ALPHA) continue
                Color.colorToHSV(px, hsv)
                if (isRejectedTooDark(hsv)) continue

                val score = vividScore(hsv)
                if (score > bestScore && isRelaxedCandidate(hsv)) {
                    bestScore = score
                    bestColor = px
                }

                when {
                    isStrictCandidate(hsv) -> strict.add(px, score)
                    isRelaxedCandidate(hsv) -> relaxed.add(px, score)
                }
            }

            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()

            val base = when {
                strict.count >= MIN_ACCEPTED_PIXELS -> strict.color()
                relaxed.count >= MIN_ACCEPTED_PIXELS -> relaxed.color()
                bestScore > 0f -> bestColor
                else -> DEFAULT_ACCENT
            }
            boostAccent(base)
        } catch (_: Exception) {
            DEFAULT_ACCENT
        }
    }

    private fun isStrictCandidate(hsv: FloatArray): Boolean = when {
        isNeutralGrey(hsv) -> hsv[2] >= 0.58f
        isBrownHue(hsv[0]) -> hsv[2] >= 0.58f && hsv[1] >= 0.20f
        else -> hsv[1] >= STRICT_MIN_SATURATION && hsv[2] >= STRICT_MIN_VALUE
    }

    private fun isRelaxedCandidate(hsv: FloatArray): Boolean = when {
        isNeutralGrey(hsv) -> hsv[2] >= 0.50f
        isBrownHue(hsv[0]) -> hsv[2] >= 0.50f && hsv[1] >= 0.15f
        else -> hsv[1] >= RELAXED_MIN_SATURATION && hsv[2] >= RELAXED_MIN_VALUE
    }

    private fun isRejectedTooDark(hsv: FloatArray): Boolean = hsv[2] < 0.36f

    private fun isNeutralGrey(hsv: FloatArray): Boolean = hsv[1] < 0.14f

    private fun isBrownHue(hue: Float): Boolean = hue in 12f..50f

    private fun vividScore(hsv: FloatArray): Float {
        val neutralPenalty = if (isNeutralGrey(hsv)) 0.18f else 0f
        val brownPenalty = if (isBrownHue(hsv[0]) && hsv[2] < 0.62f) 0.10f else 0f
        return (hsv[1] * 1.25f) + (hsv[2] * 1.10f) - neutralPenalty - brownPenalty
    }

    fun boostAccent(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        if (isRejectedTooDark(hsv) || !isRelaxedCandidate(hsv)) return DEFAULT_ACCENT

        if (isNeutralGrey(hsv)) {
            // Gris autorisé s'il n'est pas trop sombre : on garde un gris propre, sans le transformer
            // artificiellement en rouge/marron via une saturation minimale.
            hsv[1] = hsv[1].coerceIn(0f, 0.10f)
            hsv[2] = (hsv[2] * 1.12f + 0.08f).coerceIn(0.58f, 0.86f)
            return Color.HSVToColor(hsv)
        }

        if (isBrownHue(hsv[0])) {
            // Marron/brun autorisé quand il reste lisible : léger boost, mais pas de rejet brutal.
            hsv[1] = (hsv[1] * 1.12f + 0.06f).coerceIn(0.22f, 0.82f)
            hsv[2] = (hsv[2] * 1.16f + 0.10f).coerceIn(0.58f, 0.90f)
            return Color.HSVToColor(hsv)
        }

        hsv[1] = (hsv[1] * 1.28f + 0.12f).coerceIn(0.38f, 1f)
        hsv[2] = (hsv[2] * 1.20f + 0.12f).coerceIn(0.60f, 1f)
        return Color.HSVToColor(hsv)
    }

    /**
     * Fond sombre teinté par l'accent, avec une intensité volontairement plus douce.
     *
     * L'accent extrait de la pochette peut être très vif pour les contrôles, mais le fond doit
     * rester confortable sur toute la durée d'écoute. On désature donc légèrement la couleur avant
     * de la mélanger au socle sombre, puis on réduit le taux de mélange.
     */
    fun backgroundFromAccent(accent: Int): Int {
        val softAccent = softenAccentForBackground(accent)
        return mix(Color.rgb(5, 13, 17), softAccent, 0.22f)
    }

    private fun softenAccentForBackground(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 0.62f).coerceIn(0f, 0.58f)
        hsv[2] = (hsv[2] * 0.86f).coerceIn(0.30f, 0.74f)
        return Color.HSVToColor(hsv)
    }

    fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt().coerceIn(0, 255),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt().coerceIn(0, 255),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt().coerceIn(0, 255)
        )
    }

    private class Accumulator {
        var count: Int = 0
            private set
        private var totalWeight = 0.0
        private var r = 0.0
        private var g = 0.0
        private var b = 0.0

        fun add(color: Int, score: Float) {
            val weight = score.coerceAtLeast(0.15f).toDouble()
            r += Color.red(color) * weight
            g += Color.green(color) * weight
            b += Color.blue(color) * weight
            totalWeight += weight
            count++
        }

        fun color(): Int {
            if (count <= 0 || totalWeight <= 0.0) return DEFAULT_ACCENT
            return Color.rgb(
                (r / totalWeight).toInt().coerceIn(0, 255),
                (g / totalWeight).toInt().coerceIn(0, 255),
                (b / totalWeight).toInt().coerceIn(0, 255)
            )
        }
    }
}
