package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Conteneur 16:9 mesuré en une seule passe pour les miniatures de l'historique vidéo.
 * RecyclerView reçoit ainsi une hauteur stable et ne recalcule pas les cartes pendant le scroll.
 */
class LandscapeThumbnailFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * 9f / 16f).toInt()
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}
