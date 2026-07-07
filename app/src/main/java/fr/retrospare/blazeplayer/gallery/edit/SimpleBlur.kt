package fr.retrospare.blazeplayer.gallery.edit

import android.graphics.Bitmap

/**
 * Flou rapide par boîte glissante (moyenne mobile), plusieurs passes horizontales/verticales
 * pour se rapprocher d'un flou gaussien (théorème central limite) — sans RenderScript (obsolète)
 * ni `RenderEffect` (API 31+ seulement, alors que le minSdk de l'app est 28). Coût en O(largeur ×
 * hauteur) par passe, indépendant du rayon grâce à la fenêtre glissante (somme mise à jour par
 * addition/soustraction plutôt que recalculée à chaque pixel).
 */
object SimpleBlur {

    /** Effet mosaïque : réduit l'image puis la ré-agrandit sans lissage (filter=false), ce qui
     *  produit le classique effet de gros blocs — bien plus rapide qu'une implémentation pixel
     *  par pixel, et Android s'en charge nativement via un simple resize. */
    fun pixelate(bitmap: Bitmap, blockSize: Int): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        if (blockSize <= 1 || w <= 0 || h <= 0) return bitmap
        val smallW = (w / blockSize).coerceAtLeast(1)
        val smallH = (h / blockSize).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, smallW, smallH, false)
        return Bitmap.createScaledBitmap(small, w, h, false)
    }

    fun blur(bitmap: Bitmap, radius: Int, passes: Int = 3): Bitmap {
        if (radius <= 0) return bitmap
        val w = bitmap.width; val h = bitmap.height
        if (w <= 0 || h <= 0) return bitmap
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(passes) {
            boxBlurHorizontal(pixels, w, h, radius)
            boxBlurVertical(pixels, w, h, radius)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun boxBlurHorizontal(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val temp = IntArray(pixels.size)
        val windowSize = radius * 2 + 1
        for (y in 0 until h) {
            val rowOffset = y * w
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (dx in -radius..radius) {
                val xi = dx.coerceIn(0, w - 1)
                val p = pixels[rowOffset + xi]
                aSum += (p ushr 24) and 0xFF
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (x in 0 until w) {
                temp[rowOffset + x] = ((aSum / windowSize) shl 24) or ((rSum / windowSize) shl 16) or
                    ((gSum / windowSize) shl 8) or (bSum / windowSize)
                val removeX = (x - radius).coerceIn(0, w - 1)
                val addX = (x + radius + 1).coerceIn(0, w - 1)
                val removeP = pixels[rowOffset + removeX]
                val addP = pixels[rowOffset + addX]
                aSum += ((addP ushr 24) and 0xFF) - ((removeP ushr 24) and 0xFF)
                rSum += ((addP ushr 16) and 0xFF) - ((removeP ushr 16) and 0xFF)
                gSum += ((addP ushr 8) and 0xFF) - ((removeP ushr 8) and 0xFF)
                bSum += (addP and 0xFF) - (removeP and 0xFF)
            }
        }
        System.arraycopy(temp, 0, pixels, 0, pixels.size)
    }

    private fun boxBlurVertical(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val temp = IntArray(pixels.size)
        val windowSize = radius * 2 + 1
        for (x in 0 until w) {
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (dy in -radius..radius) {
                val yi = dy.coerceIn(0, h - 1)
                val p = pixels[yi * w + x]
                aSum += (p ushr 24) and 0xFF
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (y in 0 until h) {
                temp[y * w + x] = ((aSum / windowSize) shl 24) or ((rSum / windowSize) shl 16) or
                    ((gSum / windowSize) shl 8) or (bSum / windowSize)
                val removeY = (y - radius).coerceIn(0, h - 1)
                val addY = (y + radius + 1).coerceIn(0, h - 1)
                val removeP = pixels[removeY * w + x]
                val addP = pixels[addY * w + x]
                aSum += ((addP ushr 24) and 0xFF) - ((removeP ushr 24) and 0xFF)
                rSum += ((addP ushr 16) and 0xFF) - ((removeP ushr 16) and 0xFF)
                gSum += ((addP ushr 8) and 0xFF) - ((removeP ushr 8) and 0xFF)
                bSum += (addP and 0xFF) - (removeP and 0xFF)
            }
        }
        System.arraycopy(temp, 0, pixels, 0, pixels.size)
    }
}
