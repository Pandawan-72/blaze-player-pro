package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Tuile portrait 4:5 pour la vue photos de Blaze Gallery, proche d'une grille type Instagram.
 * La hauteur est calculée à la mesure pour éviter les ajustements tardifs pendant le scroll.
 */
class PortraitFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * 5f / 4f).toInt()
        val exactHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, exactHeight)
    }
}
