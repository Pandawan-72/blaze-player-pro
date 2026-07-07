package fr.retrospare.blazeplayer.gallery.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Overlay de sélection pour l'outil "Flou" : une zone déplaçable/redimensionnable (carré ou rond,
 * selon [isCircle]) désignant la partie de la photo à flouter. Mêmes mécanismes de glisser que
 * [CropOverlayView] (poignées aux 4 coins) mais en plus simple : pas de ratio verrouillable, la
 * forme elle-même (carré/rond) suffit à contraindre la zone.
 */
class BlurSelectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var imageRect = RectF()
    private var selectionRect = RectF()

    var isCircle: Boolean = true
        set(value) { field = value; invalidate(); onSelectionChanged?.invoke() }

    /** Appelé à chaque changement pertinent pour l'aperçu en direct (déplacement/redimensionnement
     *  de la sélection, changement de forme) — voir PhotoEditorActivity.refreshBlurPreview(). */
    var onSelectionChanged: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val handleTouchRadius = 40f * density
    private val minSizePx = 40f * density
    private val handleLen = 18f * density

    private enum class DragMode { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val dimPaint = Paint().apply { color = Color.argb(130, 0, 0, 0) }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    fun setImageRect(rect: RectF, preserveSelection: Boolean = false) {
        if (preserveSelection && imageRect.width() > 0f && imageRect.height() > 0f) {
            // Même principe que CropOverlayView : reprojette la sélection existante
            // proportionnellement plutôt que de la réinitialiser au centre.
            val fx1 = (selectionRect.left - imageRect.left) / imageRect.width()
            val fy1 = (selectionRect.top - imageRect.top) / imageRect.height()
            val fx2 = (selectionRect.right - imageRect.left) / imageRect.width()
            val fy2 = (selectionRect.bottom - imageRect.top) / imageRect.height()
            imageRect = RectF(rect)
            selectionRect = RectF(
                imageRect.left + fx1 * imageRect.width(),
                imageRect.top + fy1 * imageRect.height(),
                imageRect.left + fx2 * imageRect.width(),
                imageRect.top + fy2 * imageRect.height()
            )
            invalidate()
            return
        }
        imageRect = RectF(rect)
        // Sélection par défaut : centrée, environ un tiers de l'image.
        val size = minOf(imageRect.width(), imageRect.height()) * 0.35f
        val cx = imageRect.centerX(); val cy = imageRect.centerY()
        selectionRect = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = detectDragMode(event.x, event.y)
                lastTouchX = event.x; lastTouchY = event.y
                return dragMode != DragMode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) return false
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x; lastTouchY = event.y
                applyDrag(dx, dy)
                invalidate()
                onSelectionChanged?.invoke()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragMode = DragMode.NONE; return true }
        }
        return super.onTouchEvent(event)
    }

    private fun detectDragMode(x: Float, y: Float): DragMode {
        fun near(px: Float, py: Float) = abs(x - px) < handleTouchRadius && abs(y - py) < handleTouchRadius
        return when {
            near(selectionRect.left, selectionRect.top) -> DragMode.TOP_LEFT
            near(selectionRect.right, selectionRect.top) -> DragMode.TOP_RIGHT
            near(selectionRect.left, selectionRect.bottom) -> DragMode.BOTTOM_LEFT
            near(selectionRect.right, selectionRect.bottom) -> DragMode.BOTTOM_RIGHT
            selectionRect.contains(x, y) -> DragMode.MOVE
            else -> DragMode.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        when (dragMode) {
            DragMode.MOVE -> {
                selectionRect.offset(dx, dy)
                clampToImage()
            }
            DragMode.TOP_LEFT, DragMode.TOP_RIGHT, DragMode.BOTTOM_LEFT, DragMode.BOTTOM_RIGHT -> {
                val r = RectF(selectionRect)
                when (dragMode) {
                    DragMode.TOP_LEFT -> { r.left += dx; r.top += dy }
                    DragMode.TOP_RIGHT -> { r.right += dx; r.top += dy }
                    DragMode.BOTTOM_LEFT -> { r.left += dx; r.bottom += dy }
                    DragMode.BOTTOM_RIGHT -> { r.right += dx; r.bottom += dy }
                    else -> {}
                }
                if (r.width() >= minSizePx && r.height() >= minSizePx &&
                    r.left >= imageRect.left && r.top >= imageRect.top &&
                    r.right <= imageRect.right && r.bottom <= imageRect.bottom
                ) {
                    selectionRect = r
                }
            }
            else -> {}
        }
    }

    private fun clampToImage() {
        if (selectionRect.left < imageRect.left) selectionRect.offset(imageRect.left - selectionRect.left, 0f)
        if (selectionRect.top < imageRect.top) selectionRect.offset(0f, imageRect.top - selectionRect.top)
        if (selectionRect.right > imageRect.right) selectionRect.offset(imageRect.right - selectionRect.right, 0f)
        if (selectionRect.bottom > imageRect.bottom) selectionRect.offset(0f, imageRect.bottom - selectionRect.bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageRect.width() <= 0f) return

        val layer = canvas.saveLayer(imageRect, null)
        canvas.drawRect(imageRect, dimPaint)
        if (isCircle) canvas.drawOval(selectionRect, clearPaint) else canvas.drawRect(selectionRect, clearPaint)
        canvas.restoreToCount(layer)
        if (isCircle) canvas.drawOval(selectionRect, borderPaint) else canvas.drawRect(selectionRect, borderPaint)

        drawCornerHandle(canvas, selectionRect.left, selectionRect.top, 1, 1)
        drawCornerHandle(canvas, selectionRect.right, selectionRect.top, -1, 1)
        drawCornerHandle(canvas, selectionRect.left, selectionRect.bottom, 1, -1)
        drawCornerHandle(canvas, selectionRect.right, selectionRect.bottom, -1, -1)
    }

    private fun drawCornerHandle(canvas: Canvas, x: Float, y: Float, dirX: Int, dirY: Int) {
        canvas.drawLine(x, y, x + handleLen * dirX, y, handlePaint)
        canvas.drawLine(x, y, x, y + handleLen * dirY, handlePaint)
    }

    /** Sélection courante en fractions (0..1) de la zone image — indépendant de la résolution,
     *  pour être ré-appliqué sur le bitmap source en pleine résolution au moment d'enregistrer. */
    fun selectionRectFraction(): RectF {
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return RectF(0.3f, 0.3f, 0.7f, 0.7f)
        return RectF(
            ((selectionRect.left - imageRect.left) / imageRect.width()).coerceIn(0f, 1f),
            ((selectionRect.top - imageRect.top) / imageRect.height()).coerceIn(0f, 1f),
            ((selectionRect.right - imageRect.left) / imageRect.width()).coerceIn(0f, 1f),
            ((selectionRect.bottom - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
        )
    }
}
