package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ImageView plein écran avec zoom/pan façon galerie native.
 *
 * Point important : pendant un pinch-to-zoom, on mémorise le point réel de l'image situé sous le
 * centre des deux doigts avant le changement d'échelle, puis on corrige la translation après le
 * zoom pour que ce même point reste sous les doigts. Un simple postScale(focusX/focusY) suffit en
 * théorie, mais en pratique la combinaison focus mobile + clamp à chaque frame faisait dériver la
 * photo vers la gauche/droite sur certaines images très larges ou très hautes.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val imageMatrixState = Matrix()
    private val inverseMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val tmpPoint = FloatArray(2)

    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f

    private var activePointerId = INVALID_POINTER_ID
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false
    private var touchSlopExceeded = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop


    private companion object {
        const val INVALID_POINTER_ID = -1
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            dragging = false
            touchSlopExceeded = true
            parent?.requestDisallowInterceptTouchEvent(true)
            return drawable != null
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val d = drawable ?: return false
            if (d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return false

            val focusX = detector.focusX
            val focusY = detector.focusY

            val imagePointX: Float
            val imagePointY: Float
            if (imageMatrixState.invert(inverseMatrix)) {
                tmpPoint[0] = focusX
                tmpPoint[1] = focusY
                inverseMatrix.mapPoints(tmpPoint)
                imagePointX = tmpPoint[0]
                imagePointY = tmpPoint[1]
            } else {
                imagePointX = focusX
                imagePointY = focusY
            }

            val targetScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val appliedScale = targetScale / currentScale
            if (appliedScale.isNaN() || appliedScale.isInfinite() || abs(appliedScale - 1f) < 0.0005f) {
                return true
            }

            imageMatrixState.postScale(appliedScale, appliedScale, focusX, focusY)
            currentScale = targetScale

            // Correction anti-dérive : le point de l'image qui était sous le centre du pinch doit
            // rester sous ce centre, tant que les limites de l'image le permettent.
            tmpPoint[0] = imagePointX
            tmpPoint[1] = imagePointY
            imageMatrixState.mapPoints(tmpPoint)
            imageMatrixState.postTranslate(focusX - tmpPoint[0], focusY - tmpPoint[1])

            fixTranslation()
            imageMatrix = imageMatrixState
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            fixTranslation()
            imageMatrix = imageMatrixState
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
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
        maxScale = max(scale * 6f, 5f)
        imageMatrix = imageMatrixState
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                dragging = true
                touchSlopExceeded = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Pendant un pinch, on ne mélange pas la translation mono-doigt avec le zoom.
                dragging = false
                touchSlopExceeded = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && dragging && event.pointerCount == 1 && currentScale > minScale) {
                    val pointerIndex = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY

                    if (!touchSlopExceeded && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        touchSlopExceeded = true
                    }
                    if (touchSlopExceeded) {
                        imageMatrixState.postTranslate(dx, dy)
                        fixTranslation()
                        imageMatrix = imageMatrixState
                    }
                    lastTouchX = x
                    lastTouchY = y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Si le pinch se termine avec un seul doigt encore posé, on recale immédiatement
                // le point de départ du pan sur ce doigt restant. Sinon le premier déplacement
                // mono-doigt pouvait reprendre une ancienne coordonnée et provoquer un saut.
                if (event.pointerCount - 1 == 1) {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(remainingIndex)
                    lastTouchX = event.getX(remainingIndex)
                    lastTouchY = event.getY(remainingIndex)
                    dragging = true
                    touchSlopExceeded = false
                } else {
                    dragging = false
                }
            }

            MotionEvent.ACTION_UP -> {
                performClick()
                endGesture()
            }

            MotionEvent.ACTION_CANCEL -> endGesture()
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun endGesture() {
        activePointerId = INVALID_POINTER_ID
        dragging = false
        touchSlopExceeded = false
        fixTranslation()
        imageMatrix = imageMatrixState
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun fixTranslation() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return

        imageMatrixState.getValues(matrixValues)
        val scale = matrixValues[Matrix.MSCALE_X]
        val imageWidth = d.intrinsicWidth * scale
        val imageHeight = d.intrinsicHeight * scale
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

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
