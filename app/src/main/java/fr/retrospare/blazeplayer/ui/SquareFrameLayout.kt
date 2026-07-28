package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Conteneur carré strict du lecteur audio.
 *
 * Il ne possède volontairement ni rayon, ni outline, ni masque de clipping. Le fond dynamique du
 * lecteur ne peut donc plus imposer ses anciens coins supérieurs arrondis à la pochette.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        clipToOutline = false
        outlineProvider = null
        elevation = 0f
        translationZ = 0f
        foreground = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clipToOutline = false
        outlineProvider = null
        elevation = 0f
        translationZ = 0f
        foreground = null
        invalidateOutline()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        val size = measuredWidth
        setMeasuredDimension(size, size)
    }
}
