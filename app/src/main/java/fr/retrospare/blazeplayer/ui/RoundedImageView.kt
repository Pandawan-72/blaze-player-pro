package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.AppCompatImageView

class RoundedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var radiusDp: Float = 12f
        set(value) {
            field = value
            invalidateOutline()
        }

    /**
     * Force une mesure carrée directement pendant onMeasure. Cette option est destinée aux
     * pochettes placées dans une grille RecyclerView : elle évite de modifier leur hauteur dans
     * un view.post après le premier layout, ce qui pouvait désynchroniser les lignes au retour
     * d'un écran de détail jusqu'au prochain scroll.
     */
    var forceSquare: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radiusPx = radiusDp * view.resources.displayMetrics.density
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!forceSquare) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // La largeur imposée par la colonne devient aussi la hauteur dès la première mesure.
        // Aucun callback différé n'est nécessaire, donc GridLayoutManager calcule toutes les
        // rangées avec une géométrie définitive et cohérente.
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        val size = measuredWidth
        setMeasuredDimension(size, size)
    }
}
