package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val imageMatrixState = Matrix()
    private val values = FloatArray(9)
    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val nextScale = (currentScale * factor).coerceIn(minScale, maxScale)
            val applied = nextScale / currentScale
            imageMatrixState.postScale(applied, applied, detector.focusX, detector.focusY)
            currentScale = nextScale
            fixTranslation()
            imageMatrix = imageMatrixState
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetZoom() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { resetZoom() }
    }

    fun resetZoom() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        imageMatrixState.reset()
        val scale = min(width.toFloat() / d.intrinsicWidth.toFloat(), height.toFloat() / d.intrinsicHeight.toFloat())
        val dx = (width - d.intrinsicWidth * scale) / 2f
        val dy = (height - d.intrinsicHeight * scale) / 2f
        imageMatrixState.postScale(scale, scale)
        imageMatrixState.postTranslate(dx, dy)
        minScale = scale
        currentScale = scale
        maxScale = max(scale * 5f, 5f)
        imageMatrix = imageMatrixState
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress && currentScale > minScale) {
                    imageMatrixState.postTranslate(event.x - lastX, event.y - lastY)
                    fixTranslation()
                    imageMatrix = imageMatrixState
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun fixTranslation() {
        val d = drawable ?: return
        imageMatrixState.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        val imageWidth = d.intrinsicWidth * scale
        val imageHeight = d.intrinsicHeight * scale
        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]
        val fixedX = fixedTranslation(transX, width.toFloat(), imageWidth)
        val fixedY = fixedTranslation(transY, height.toFloat(), imageHeight)
        imageMatrixState.postTranslate(fixedX - transX, fixedY - transY)
    }

    private fun fixedTranslation(value: Float, viewSize: Float, contentSize: Float): Float {
        return if (contentSize <= viewSize) {
            (viewSize - contentSize) / 2f
        } else {
            min(0f, max(value, viewSize - contentSize))
        }
    }
}
