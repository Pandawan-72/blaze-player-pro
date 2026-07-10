package fr.retrospare.blazeplayer.ui

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import androidx.core.widget.TextViewCompat

/**
 * Rend les libellés de boutons robustes sur petits écrans / longues traductions :
 * une seule ligne, pas de troncature volontaire, et taille de texte auto-ajustée
 * dans la hauteur/largeur disponible.
 */
object ButtonTextFitter {
    fun fit(view: TextView, minSp: Int = 9, maxSp: Int = 13) {
        view.maxLines = 1
        view.setSingleLine(true)
        view.ellipsize = null
        view.includeFontPadding = false
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            view,
            minSp,
            maxSp,
            1,
            TypedValue.COMPLEX_UNIT_SP
        )
    }

    fun fitRecursively(root: View, minSp: Int = 9, maxSp: Int = 13) {
        when (root) {
            is CompoundButton -> Unit // Les cases à cocher gardent leurs libellés lisibles sur 2 lignes.
            is Button -> fit(root, minSp, maxSp)
            is TextView -> if (root.isClickable && root.text?.isNotBlank() == true) fit(root, minSp, maxSp)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) fitRecursively(root.getChildAt(i), minSp, maxSp)
        }
    }
}
