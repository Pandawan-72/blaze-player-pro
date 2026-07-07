package fr.retrospare.blazeplayer.gallery.edit

import android.graphics.ColorMatrix
import fr.retrospare.blazeplayer.R

data class PhotoFilter(val id: String, val labelRes: Int, val matrix: ColorMatrix)

/**
 * Filtres façon galerie premium, appliqués via [android.graphics.ColorMatrixColorFilter] : aperçu
 * temps réel gratuit en calcul sur l'ImageView, natif Android, aucune dépendance externe.
 *
 * Les filtres simples (teinte unique, sépia, niveaux de gris) restent des matrices écrites à la
 * main en un seul bloc, faciles à vérifier. Pour les filtres qui combinent plusieurs effets
 * (ex. "Dramatique" = désaturation + contraste), on compose via [multiply] — une implémentation
 * manuelle et déterministe de la multiplication de matrices 4x5 affines, plutôt que d'enchaîner
 * des `ColorMatrix.postConcat` dont l'ordre de composition est facile à mal évaluer.
 */
object PhotoFilters {

    /** Multiplication de deux ColorMatrix affines : équivaut à appliquer [b] puis [a].
     *  Implémentation directe (pas d'API Android ambiguë) : la partie linéaire (4x4) se
     *  multiplie normalement, et la colonne de translation se transforme par la partie linéaire
     *  de [a] avant de s'additionner à la translation propre de [a]. */
    private fun multiply(a: ColorMatrix, b: ColorMatrix): ColorMatrix {
        val ma = a.array; val mb = b.array
        val result = FloatArray(20)
        for (i in 0 until 4) {
            for (j in 0 until 5) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += ma[i * 5 + k] * mb[k * 5 + j]
                }
                if (j == 4) sum += ma[i * 5 + 4]
                result[i * 5 + j] = sum
            }
        }
        return ColorMatrix(result)
    }

    private fun contrastTint(c: Float, addR: Float = 0f, addG: Float = 0f, addB: Float = 0f): ColorMatrix {
        val t = (1f - c) / 2f * 255f
        return ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t + addR,
            0f, c, 0f, 0f, t + addG,
            0f, 0f, c, 0f, t + addB,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    private fun grayscale(contrast: Float = 1f): ColorMatrix {
        val lr = 0.213f * contrast; val lg = 0.715f * contrast; val lb = 0.072f * contrast
        val t = (1f - contrast) / 2f * 255f
        return ColorMatrix(floatArrayOf(
            lr, lg, lb, 0f, t,
            lr, lg, lb, 0f, t,
            lr, lg, lb, 0f, t,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    private fun saturation(sat: Float): ColorMatrix = ColorMatrix().apply { setSaturation(sat) }

    private fun sepia(): ColorMatrix = ColorMatrix(floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ))

    fun buildAll(): List<PhotoFilter> = listOf(
        PhotoFilter("normal", R.string.filter_normal, ColorMatrix()),
        PhotoFilter("vivid", R.string.filter_vivid, saturation(1.45f)),
        PhotoFilter("bw", R.string.filter_bw, grayscale()),
        PhotoFilter("noir", R.string.filter_noir, grayscale(contrast = 1.3f)),
        PhotoFilter("sepia", R.string.filter_sepia, sepia()),
        PhotoFilter("warm", R.string.filter_warm, contrastTint(1f, addR = 16f, addG = 4f, addB = -14f)),
        PhotoFilter("cool", R.string.filter_cool, contrastTint(1f, addR = -12f, addG = 0f, addB = 18f)),
        PhotoFilter("fade", R.string.filter_fade, contrastTint(0.82f)),
        // Contraste marqué + légère désaturation : look "dramatique" façon éditos.
        PhotoFilter("dramatic", R.string.filter_dramatic, multiply(contrastTint(1.35f), saturation(0.75f))),
        // Noirs relevés, contraste doux, très légère chaleur : effet "mat" tendance.
        PhotoFilter("matte", R.string.filter_matte, multiply(contrastTint(0.8f, addR = 8f, addG = 4f, addB = -2f), saturation(0.9f))),
        // Sépia atténué + contraste réduit + chaleur : look "vintage" distinct du Sépia pur.
        PhotoFilter("vintage", R.string.filter_vintage, multiply(contrastTint(0.88f, addR = 10f, addG = 2f), sepia())),
        PhotoFilter("rose", R.string.filter_rose, contrastTint(1.05f, addR = 20f, addG = -4f, addB = 6f)),
        // Bleu profond + légère désaturation : ambiance nocturne/froide plus marquée que "Froid".
        PhotoFilter("deep_blue", R.string.filter_deep_blue, multiply(contrastTint(1.05f, addR = -20f, addG = -6f, addB = 26f), saturation(0.85f))),
        // Niveaux de gris teintés froid : variante "argentée" du N&B.
        PhotoFilter("silver", R.string.filter_silver, multiply(contrastTint(1f, addR = -6f, addG = -2f, addB = 8f), grayscale(1.1f))),
        PhotoFilter("sunset", R.string.filter_sunset, contrastTint(1.08f, addR = 26f, addG = 6f, addB = -18f)),
        PhotoFilter("emerald", R.string.filter_emerald, contrastTint(1.05f, addR = -10f, addG = 14f, addB = -6f)),
        // Contraste doux + légère désaturation : rendu délicat, peu marqué.
        PhotoFilter("soft", R.string.filter_soft, multiply(contrastTint(0.9f), saturation(0.85f))),
        // Saturation et contraste tous deux poussés : l'inverse d'"Estompé".
        PhotoFilter("punch", R.string.filter_punch, multiply(saturation(1.7f), contrastTint(1.15f))),
        PhotoFilter("lavender", R.string.filter_lavender, contrastTint(1f, addR = 8f, addG = -4f, addB = 14f)),
        PhotoFilter("coral", R.string.filter_coral, contrastTint(1.03f, addR = 22f, addG = 8f, addB = 2f)),
        // Fortement désaturé plutôt que gris pur : garde une pointe de couleur, ambiance "acier".
        PhotoFilter("steel", R.string.filter_steel, multiply(saturation(0.4f), contrastTint(1.05f, addR = -8f, addG = -2f, addB = 6f))),
        PhotoFilter("amber", R.string.filter_amber, contrastTint(1.05f, addR = 24f, addG = 12f, addB = -20f)),
        // Contraste marqué + ombres légèrement bleutées : ambiance nocturne dramatique.
        PhotoFilter("midnight", R.string.filter_midnight, multiply(contrastTint(1.3f, addR = -10f, addG = -8f, addB = 2f), saturation(0.8f))),
        // Noirs relevés + chaleur + légère désaturation : look "photo instantanée" classique.
        PhotoFilter("polaroid", R.string.filter_polaroid, multiply(contrastTint(0.78f, addR = 14f, addG = 6f, addB = -6f), saturation(0.9f)))
    )
}
