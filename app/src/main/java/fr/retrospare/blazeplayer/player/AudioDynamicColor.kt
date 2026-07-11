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

    private const val MIN_ALPHA = 32
    private const val STRICT_MIN_SATURATION = 0.26f
    private const val STRICT_MIN_VALUE = 0.42f
    private const val RELAXED_MIN_SATURATION = 0.10f
    private const val RELAXED_MIN_VALUE = 0.24f
    private const val MIN_ACCEPTED_PIXELS = 3
    private const val GLACIER_HUE = 205f

    /** Couleur dominante robuste de la pochette.
     *
     * La version précédente rejetait encore certaines pochettes très sombres, très claires,
     * monochromes ou peu saturées. Ici on applique plusieurs passes : couleurs vives, couleurs
     * mutées, neutres/gris/bruns, puis repli tonal basé sur la moyenne de la pochette. Ainsi toute
     * pochette non vide produit une couleur exploitable au lieu de retomber sur l'accent par défaut.
     */
    fun accentFromBitmap(bitmap: Bitmap): Int {
        return try {
            val maxSide = 56
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)

            val strict = Accumulator()
            val relaxed = Accumulator()
            val muted = Accumulator()
            val neutral = Accumulator()
            val allVisible = Accumulator()
            var bestColor = 0
            var bestScore = -1f
            var visiblePixels = 0
            val hsv = FloatArray(3)

            for (px in pixels) {
                if (Color.alpha(px) < MIN_ALPHA) continue
                visiblePixels++
                Color.colorToHSV(px, hsv)
                val score = vividScore(hsv)
                allVisible.add(px, (score + 0.25f).coerceAtLeast(0.10f))

                if (score > bestScore) {
                    bestScore = score
                    bestColor = px
                }

                when {
                    isStrictCandidate(hsv) -> strict.add(px, score + 0.55f)
                    isRelaxedCandidate(hsv) -> relaxed.add(px, score + 0.34f)
                    isMutedCandidate(hsv) -> muted.add(px, score + 0.22f)
                    isUsableNeutralCandidate(hsv) -> neutral.add(px, score + 0.16f)
                }
            }

            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()

            val base = when {
                strict.count >= MIN_ACCEPTED_PIXELS -> strict.color()
                relaxed.count >= MIN_ACCEPTED_PIXELS -> relaxed.color()
                muted.count >= MIN_ACCEPTED_PIXELS -> muted.color()
                neutral.count >= MIN_ACCEPTED_PIXELS -> neutral.color()
                bestColor != 0 -> bestColor
                allVisible.count > 0 -> allVisible.color()
                visiblePixels > 0 -> tonalFallback(allVisible.color())
                else -> DEFAULT_ACCENT
            }
            boostAccent(base)
        } catch (_: Exception) {
            DEFAULT_ACCENT
        }
    }

    private fun isStrictCandidate(hsv: FloatArray): Boolean = when {
        isNeutralGrey(hsv) -> hsv[2] >= 0.52f
        isBrownHue(hsv[0]) -> hsv[2] >= 0.44f && hsv[1] >= 0.16f
        else -> hsv[1] >= STRICT_MIN_SATURATION && hsv[2] >= STRICT_MIN_VALUE
    }

    private fun isRelaxedCandidate(hsv: FloatArray): Boolean = when {
        isNeutralGrey(hsv) -> hsv[2] >= 0.38f
        isBrownHue(hsv[0]) -> hsv[2] >= 0.34f && hsv[1] >= 0.10f
        else -> hsv[1] >= RELAXED_MIN_SATURATION && hsv[2] >= RELAXED_MIN_VALUE
    }

    private fun isMutedCandidate(hsv: FloatArray): Boolean = hsv[2] >= 0.18f && hsv[1] >= 0.045f

    private fun isUsableNeutralCandidate(hsv: FloatArray): Boolean = hsv[2] >= 0.16f

    private fun isNeutralGrey(hsv: FloatArray): Boolean = hsv[1] < 0.10f

    private fun isBrownHue(hue: Float): Boolean = hue in 10f..55f

    private fun vividScore(hsv: FloatArray): Float {
        val neutralPenalty = if (isNeutralGrey(hsv)) 0.16f else 0f
        val brownPenalty = if (isBrownHue(hsv[0]) && hsv[2] < 0.56f) 0.06f else 0f
        // Les pochettes sombres mais colorées gardent une chance : la saturation compte un peu
        // plus que la luminosité, puis boostAccent() se charge de rendre la couleur lisible.
        return (hsv[1] * 1.42f) + (hsv[2] * 0.92f) - neutralPenalty - brownPenalty
    }

    fun boostAccent(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        // Repli pour les pochettes noires/blanches/grises : on conserve une sensation premium
        // froide au lieu de retourner l'accent vert par défaut.
        if (hsv[2] < 0.12f || (hsv[1] < 0.035f && hsv[2] < 0.34f)) {
            return Color.HSVToColor(floatArrayOf(GLACIER_HUE, 0.32f, 0.58f))
        }

        if (isNeutralGrey(hsv)) {
            hsv[0] = GLACIER_HUE
            hsv[1] = 0.16f + (1f - hsv[2]).coerceIn(0f, 1f) * 0.12f
            hsv[2] = (hsv[2] * 1.10f + 0.14f).coerceIn(0.52f, 0.86f)
            return Color.HSVToColor(hsv)
        }

        if (isBrownHue(hsv[0])) {
            hsv[1] = (hsv[1] * 1.10f + 0.05f).coerceIn(0.16f, 0.80f)
            hsv[2] = (hsv[2] * 1.18f + 0.12f).coerceIn(0.48f, 0.90f)
            return Color.HSVToColor(hsv)
        }

        // Couleurs réellement présentes mais trop sombres/mutées : on les rend lisibles sans les
        // remplacer par une couleur arbitraire.
        hsv[1] = (hsv[1] * 1.22f + 0.08f).coerceIn(0.18f, 1f)
        hsv[2] = (hsv[2] * 1.22f + 0.12f).coerceIn(0.48f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun tonalFallback(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.06f) {
            hsv[0] = GLACIER_HUE
            hsv[1] = 0.22f
        }
        hsv[2] = hsv[2].coerceIn(0.48f, 0.78f)
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
        return mix(Color.rgb(5, 13, 17), softAccent, 0.24f)
    }

    private fun softenAccentForBackground(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 0.68f).coerceIn(0.04f, 0.62f)
        hsv[2] = (hsv[2] * 0.88f).coerceIn(0.32f, 0.76f)
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
            val weight = score.coerceAtLeast(0.10f).toDouble()
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
