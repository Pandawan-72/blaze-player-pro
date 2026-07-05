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

    /** Couleur dominante robuste de la pochette (moyenne des pixels non transparents/non trop
     *  sombres), puis légèrement boostée en saturation/luminosité pour rester lisible en accent. */
    fun accentFromBitmap(bitmap: Bitmap): Int {
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
            var r = 0L; var g = 0L; var b = 0L; var count = 0L
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            for (px in pixels) {
                val alpha = Color.alpha(px)
                if (alpha < 48) continue
                val hsv = FloatArray(3)
                Color.colorToHSV(px, hsv)
                if (hsv[2] < 0.10f) continue
                r += Color.red(px); g += Color.green(px); b += Color.blue(px); count++
            }
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            val base = if (count <= 0L) DEFAULT_ACCENT else Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
            boostAccent(base)
        } catch (_: Exception) {
            DEFAULT_ACCENT
        }
    }

    fun boostAccent(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 1.35f + 0.12f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * 1.22f + 0.08f).coerceIn(0.35f, 1f)
        return Color.HSVToColor(hsv)
    }

    /** Fond sombre teinté par l'accent (même dosage que l'écran Blaze Audio). */
    fun backgroundFromAccent(accent: Int): Int = mix(Color.rgb(4, 14, 16), accent, 0.30f)

    fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt().coerceIn(0, 255),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt().coerceIn(0, 255),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt().coerceIn(0, 255)
        )
    }
}
