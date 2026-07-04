package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

open class RoundedFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var radiusDp: Float = 14f
        set(value) {
            field = value
            invalidateOutline()
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
}
