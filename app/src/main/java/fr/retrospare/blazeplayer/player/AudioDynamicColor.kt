package fr.retrospare.blazeplayer.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Calcul de la couleur dynamique (accent + fond) à partir de la pochette d'un morceau.
 *
 * Le même moteur est utilisé par le lecteur complet, la bibliothèque et le mini-player. Il doit
 * donc fonctionner avec toutes les provenances de pochettes : fichier externe, cache disque,
 * albumArtURI réseau et image embarquée dans le fichier audio.
 */
object AudioDynamicColor {

    val DEFAULT_ACCENT: Int = Color.rgb(63, 215, 143)
    val DEFAULT_BACKGROUND: Int = Color.rgb(10, 12, 14)

    private const val MIN_ALPHA = 28
    private const val SAMPLE_MAX_SIDE = 72
    private const val HUE_BUCKETS = 30
    private const val SATURATION_BUCKETS = 4
    private const val VALUE_BUCKETS = 4
    private const val GLACIER_HUE = 205f
    private const val MIN_SOURCE_VALUE = 0.34f
    private const val MIN_SOURCE_BRIGHTNESS = 0.24f
    private const val MIN_OUTPUT_VALUE = 0.66f
    private const val MIN_OUTPUT_BRIGHTNESS = 0.42f

    /**
     * Extrait une couleur dominante stable de la pochette.
     *
     * Deux problèmes sont traités explicitement :
     * - les images externes chargées par les moteurs d'image peuvent être des bitmaps HARDWARE ;
     *   getPixels() et le rendu sur un Canvas logiciel y échouent. Une copie ARGB_8888 est donc
     *   créée avant toute analyse ;
     * - une moyenne globale produit souvent un brun/gris terne à cause des fonds noirs, blancs ou
     *   des bordures. Une quantification HSV pondérée sélectionne plutôt la famille de couleur
     *   réellement dominante, puis un repli tonal garantit une couleur pour toute image valide.
     */
    fun accentFromBitmap(bitmap: Bitmap): Int {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return DEFAULT_ACCENT

        val sample = runCatching { createSoftwareSample(bitmap) }.getOrNull()
            ?: return DEFAULT_ACCENT
        return try {
            val width = sample.width
            val height = sample.height
            val pixels = IntArray(width * height)
            sample.getPixels(pixels, 0, width, 0, 0, width, height)

            val colorful = Array(HUE_BUCKETS * SATURATION_BUCKETS * VALUE_BUCKETS) { ColorCluster() }
            val muted = ColorCluster()
            val neutral = ColorCluster()
            val allVisible = ColorCluster()
            val hsv = FloatArray(3)

            var visibleCount = 0
            var index = 0
            for (y in 0 until height) {
                val normalizedY = ((y + 0.5f) / height.toFloat()) - 0.5f
                for (x in 0 until width) {
                    val px = pixels[index++]
                    val alpha = Color.alpha(px)
                    if (alpha < MIN_ALPHA) continue
                    visibleCount++

                    Color.colorToHSV(px, hsv)
                    val saturation = hsv[1].coerceIn(0f, 1f)
                    val value = hsv[2].coerceIn(0f, 1f)
                    val normalizedX = ((x + 0.5f) / width.toFloat()) - 0.5f
                    val distance = (sqrt(normalizedX * normalizedX + normalizedY * normalizedY) / 0.7072f)
                        .coerceIn(0f, 1f)
                    val centerWeight = 1.16f - (distance * 0.34f)
                    val alphaWeight = (alpha / 255f).coerceIn(0.15f, 1f)
                    val toneBalance = (1f - abs(value - 0.58f) * 1.55f).coerceIn(0.08f, 1f)
                    val baseWeight = centerWeight * alphaWeight

                    // Repli toujours disponible, y compris pour une cover presque noire/blanche.
                    allVisible.add(px, baseWeight * (0.30f + toneBalance * 0.70f))

                    when {
                        saturation >= 0.10f && value >= 0.10f -> {
                            val hue = hsv[0].let { if (it >= 360f) 0f else it.coerceAtLeast(0f) }
                            val hueBucket = ((hue / 360f) * HUE_BUCKETS).toInt()
                                .coerceIn(0, HUE_BUCKETS - 1)
                            val saturationBucket = (saturation * SATURATION_BUCKETS).toInt()
                                .coerceIn(0, SATURATION_BUCKETS - 1)
                            val valueBucket = (value * VALUE_BUCKETS).toInt()
                                .coerceIn(0, VALUE_BUCKETS - 1)
                            val bucketIndex =
                                (hueBucket * SATURATION_BUCKETS * VALUE_BUCKETS) +
                                    (saturationBucket * VALUE_BUCKETS) + valueBucket

                            // La saturation doit compter davantage que la luminosité afin qu'un
                            // petit sujet coloré ne soit pas noyé par un grand fond noir ou blanc.
                            val chromaWeight = 0.34f + saturation * 1.86f
                            val valueWeight = 0.32f + toneBalance * 0.92f + value * 0.18f
                            colorful[bucketIndex].add(px, baseWeight * chromaWeight * valueWeight)
                        }

                        saturation >= 0.035f && value >= 0.07f -> {
                            muted.add(px, baseWeight * (0.28f + saturation * 1.20f) * (0.42f + toneBalance))
                        }

                        else -> {
                            // Les pixels totalement noirs/blancs restent des replis de dernier
                            // niveau ; les gris moyens sont plus représentatifs d'une vraie cover.
                            val neutralToneWeight = when {
                                value in 0.16f..0.88f -> 0.72f + toneBalance
                                else -> 0.18f + toneBalance * 0.35f
                            }
                            neutral.add(px, baseWeight * neutralToneWeight)
                        }
                    }
                }
            }

            if (visibleCount == 0) return DEFAULT_ACCENT

            val colorfulClusters = colorful
                .asSequence()
                .filter { it.count > 0 }
                .toList()

            // Une grande zone noire, bleu marine ou brun très sombre ne doit jamais gagner face à
            // un élément plus petit mais réellement coloré et lisible. On privilégie donc d'abord
            // les familles dont la luminosité est suffisante. Les couleurs sombres ne servent que
            // de dernier repli pour récupérer une teinte, qui sera ensuite éclaircie.
            val dominantColor = colorfulClusters
                .asSequence()
                .filter { isUsableSourceAccent(it.color()) }
                .maxByOrNull(::clusterScore)
                ?.takeIf { it.totalWeight >= 0.38f }
                ?.color()
                ?: muted.takeIf { it.count > 0 && isUsableSourceAccent(it.color()) }?.color()
                ?: neutral.takeIf { it.count > 0 && isUsableSourceAccent(it.color()) }?.color()
                ?: colorfulClusters.maxByOrNull(::clusterScore)?.color()
                ?: muted.takeIf { it.count > 0 }?.color()
                ?: neutral.takeIf { it.count > 0 }?.color()
                ?: allVisible.takeIf { it.count > 0 }?.color()
                ?: DEFAULT_ACCENT

            boostAccent(dominantColor)
        } catch (_: Throwable) {
            DEFAULT_ACCENT
        } finally {
            if (sample !== bitmap && !sample.isRecycled) sample.recycle()
        }
    }

    /** Extrait directement une couleur depuis artworkData Media3 / une image embarquée. */
    fun accentFromArtworkBytes(bytes: ByteArray?): Int? {
        if (bytes == null || bytes.isEmpty()) return null
        val bitmap = decodeArtworkBytes(bytes) ?: return null
        return try {
            accentFromBitmap(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Produit un petit bitmap logiciel, même lorsque la source est HARDWARE ou dans un espace de
     * couleur inhabituel. La source originale n'est jamais recyclée ni modifiée.
     */
    private fun createSoftwareSample(bitmap: Bitmap): Bitmap? {
        val softwareSource = when (bitmap.config) {
            Bitmap.Config.ARGB_8888 -> bitmap
            else -> runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        } ?: return null

        val scale = minOf(
            1f,
            SAMPLE_MAX_SIDE.toFloat() / maxOf(softwareSource.width, softwareSource.height).coerceAtLeast(1)
        )
        val targetWidth = (softwareSource.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (softwareSource.height * scale).toInt().coerceAtLeast(1)

        if (
            softwareSource.config == Bitmap.Config.ARGB_8888 &&
            softwareSource.width == targetWidth &&
            softwareSource.height == targetHeight &&
            softwareSource !== bitmap
        ) {
            return softwareSource
        }

        val scaled = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(scaled).drawBitmap(
                softwareSource,
                null,
                Rect(0, 0, targetWidth, targetHeight),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            scaled
        } catch (_: Throwable) {
            if (!scaled.isRecycled) scaled.recycle()
            null
        } finally {
            if (softwareSource !== bitmap && softwareSource !== scaled && !softwareSource.isRecycled) {
                softwareSource.recycle()
            }
        }
    }

    private fun decodeArtworkBytes(bytes: ByteArray): Bitmap? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                while (
                    bounds.outWidth / sample > SAMPLE_MAX_SIDE * 2 ||
                    bounds.outHeight / sample > SAMPLE_MAX_SIDE * 2
                ) {
                    sample *= 2
                }
                android.graphics.BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sample.coerceAtLeast(1)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun boostAccent(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        // Une cover achromatique ne possède pas de teinte exploitable. On conserve sa tonalité,
        // mais on lui donne une légère orientation glacier afin d'éviter le noir et le gris foncé.
        if (hsv[1] < 0.055f) {
            val originalValue = hsv[2]
            hsv[0] = GLACIER_HUE
            hsv[1] = (0.13f + (1f - originalValue) * 0.15f).coerceIn(0.13f, 0.28f)
            hsv[2] = (originalValue * 0.78f + 0.28f).coerceIn(0.50f, 0.84f)
            return ensureReadableAccent(Color.HSVToColor(hsv))
        }

        val isBrown = hsv[0] in 10f..55f
        hsv[1] = if (isBrown) {
            (hsv[1] * 1.10f + 0.06f).coerceIn(0.18f, 0.84f)
        } else {
            (hsv[1] * 1.18f + 0.07f).coerceIn(0.20f, 1f)
        }
        hsv[2] = (hsv[2] * 1.16f + 0.13f).coerceIn(0.50f, 0.96f)
        return ensureReadableAccent(Color.HSVToColor(hsv))
    }

    /**
     * Garantit que la couleur utilisée par l'interface n'est jamais noire ou trop sombre.
     *
     * La teinte extraite reste conservée, mais les bleus marine, bruns profonds, verts sombres et
     * gris foncés sont remontés vers une variante claire. Le contrôle de luminosité perceptuelle
     * est volontairement ajouté au simple canal HSV : un bleu très saturé peut avoir une valeur
     * HSV élevée tout en paraissant encore beaucoup trop sombre à l'écran.
     */
    fun ensureReadableAccent(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        if (hsv[1] < 0.055f) {
            hsv[0] = GLACIER_HUE
            hsv[1] = 0.18f
        }

        val hueMinimumValue = when (hsv[0]) {
            in 190f..285f -> 0.76f // bleus et violets : visuellement plus sombres
            in 285f..360f, in 0f..18f -> 0.72f
            else -> MIN_OUTPUT_VALUE
        }
        hsv[2] = hsv[2].coerceAtLeast(hueMinimumValue)

        var result = Color.HSVToColor(hsv)
        var attempts = 0
        while (perceivedBrightness(result) < MIN_OUTPUT_BRIGHTNESS && attempts < 8) {
            result = mix(result, Color.rgb(255, 255, 255), 0.12f)
            attempts++
        }
        return result
    }

    /** Fond sombre teinté par l'accent, avec une intensité confortable pendant l'écoute. */
    fun backgroundFromAccent(accent: Int): Int {
        val softAccent = softenAccentForBackground(ensureReadableAccent(accent))
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

    private fun clusterScore(cluster: ColorCluster): Float =
        cluster.totalWeight * (1f + cluster.count.coerceAtMost(20) * 0.035f)

    private fun isUsableSourceAccent(color: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return hsv[2] >= MIN_SOURCE_VALUE && perceivedBrightness(color) >= MIN_SOURCE_BRIGHTNESS
    }

    private fun perceivedBrightness(color: Int): Float = (
        0.299f * Color.red(color) +
            0.587f * Color.green(color) +
            0.114f * Color.blue(color)
        ) / 255f

    private class ColorCluster {
        var count: Int = 0
            private set
        var totalWeight: Float = 0f
            private set
        private var red = 0.0
        private var green = 0.0
        private var blue = 0.0

        fun add(color: Int, rawWeight: Float) {
            val weight = rawWeight.coerceAtLeast(0.01f)
            red += Color.red(color) * weight
            green += Color.green(color) * weight
            blue += Color.blue(color) * weight
            totalWeight += weight
            count++
        }

        fun color(): Int {
            if (count <= 0 || totalWeight <= 0f) return DEFAULT_ACCENT
            return Color.rgb(
                (red / totalWeight).toInt().coerceIn(0, 255),
                (green / totalWeight).toInt().coerceIn(0, 255),
                (blue / totalWeight).toInt().coerceIn(0, 255)
            )
        }
    }
}
