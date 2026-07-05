package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
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
            rebuildClipPath(width, height)
            invalidateOutline()
            invalidate()
        }

    private val clipPath = Path()
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        // Ne pas forcer LAYER_TYPE_SOFTWARE ici : si un ImageView enfant contient
        // un Bitmap.Config.HARDWARE (Coil/Glide), Android plante avec
        // "Software rendering doesn't support hardware bitmaps". Le clipping par
        // outline garde les coins arrondis sans repasser tout le conteneur en rendu logiciel.
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radiusPx = radiusDp * view.resources.displayMetrics.density
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildClipPath(w, h)
    }

    private fun rebuildClipPath(w: Int, h: Int) {
        val radiusPx = radiusDp * resources.displayMetrics.density
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        clipPath.close()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            super.dispatchDraw(canvas)
        } else {
            val save = canvas.save()
            canvas.clipPath(clipPath)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        }
    }

    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            super.draw(canvas)
        } else {
            val save = canvas.save()
            canvas.clipPath(clipPath)
            super.draw(canvas)
            canvas.restoreToCount(save)
        }
    }
}
