package fr.retrospare.blazeplayer.player

import android.content.Context
import android.util.AttributeSet
import android.view.View
import fr.retrospare.blazeplayer.ui.RoundedFrameLayout
import kotlin.math.min

/** Cadre carré qui reste contenu dans la hauteur disponible des écrans paysage très larges. */
class KaraokeArtworkFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RoundedFrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val rawHeight = View.MeasureSpec.getSize(heightMeasureSpec)
        val maxHeight = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) maxWidth else rawHeight
        val size = min(maxWidth, maxHeight).coerceAtLeast(1)
        val exact = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        super.onMeasure(exact, exact)
    }
}
