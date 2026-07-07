package fr.retrospare.blazeplayer.gallery.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Overlay de recadrage façon Google Photos : un rectangle déplaçable/redimensionnable par ses 4
 * coins, dessiné par-dessus l'image affichée, avec assombrissement de la zone hors-cadre et une
 * grille des tiers pendant le glisser. Fonctionne uniquement en coordonnées de VUE ; c'est à
 * l'appelant de fournir [setImageRect] (la zone réellement occupée par l'image affichée dans son
 * ImageView, en coordonnées de cette même vue).
 *
 * Le recadrage final est exposé en fractions (0..1 de la zone image, via [cropRectFraction]),
 * volontairement indépendant de la résolution d'aperçu : l'appelant peut ainsi ré-appliquer le
 * même cadrage relatif sur le bitmap source en pleine résolution au moment d'enregistrer, plutôt
 * que de recadrer l'aperçu (souvent sous-échantillonné pour rester fluide à l'écran).
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var imageRect = RectF()
    private var cropRect = RectF()
    private var aspectRatio: Float? = null // largeur/hauteur ; null = libre

    private val density = resources.displayMetrics.density
    private val handleTouchRadius = 40f * density
    private val minCropSizePx = 48f * density
    private val handleLen = 20f * density

    private enum class DragMode { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val scrimPaint = Paint().apply { color = Color.argb(165, 0, 0, 0) }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(140, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
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
            // Reprojette le cadrage existant proportionnellement vers le nouveau rect (ex: un
            // changement de hauteur du panneau du bas qui redimensionne la zone image partagée),
            // plutôt que de le réinitialiser — l'utilisateur ne doit pas perdre son cadrage juste
            // parce qu'il a changé d'onglet puis est revenu.
            val fx1 = (cropRect.left - imageRect.left) / imageRect.width()
            val fy1 = (cropRect.top - imageRect.top) / imageRect.height()
            val fx2 = (cropRect.right - imageRect.left) / imageRect.width()
            val fy2 = (cropRect.bottom - imageRect.top) / imageRect.height()
            imageRect = RectF(rect)
            cropRect = RectF(
                imageRect.left + fx1 * imageRect.width(),
                imageRect.top + fy1 * imageRect.height(),
                imageRect.left + fx2 * imageRect.width(),
                imageRect.top + fy2 * imageRect.height()
            )
        } else {
            imageRect = RectF(rect)
            cropRect = RectF(imageRect)
            aspectRatio?.let { applyAspectRatioAroundCenter() }
        }
        invalidate()
    }

    fun setAspectRatio(ratio: Float?) {
        aspectRatio = ratio
        if (imageRect.width() > 0f) {
            if (ratio == null) {
                // "Libre" doit rendre le plein cadre de l'image, pas garder la taille du dernier
                // ratio verrouillé — sans ça, revenir sur "Libre" après avoir essayé 1:1 laissait
                // le cadre coincé à la taille du carré précédent.
                cropRect = RectF(imageRect)
            } else {
                applyAspectRatioAroundCenter()
            }
            invalidate()
        }
    }

    fun reset() {
        cropRect = RectF(imageRect)
        aspectRatio?.let { applyAspectRatioAroundCenter() }
        invalidate()
    }

    private fun applyAspectRatioAroundCenter() {
        val ratio = aspectRatio ?: return
        val cx = cropRect.centerX(); val cy = cropRect.centerY()
        var w = imageRect.width(); var h = w / ratio
        if (h > imageRect.height()) { h = imageRect.height(); w = h * ratio }
        cropRect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        clampCropToImage()
    }

    private fun clampCropToImage() {
        if (cropRect.left < imageRect.left) cropRect.offset(imageRect.left - cropRect.left, 0f)
        if (cropRect.top < imageRect.top) cropRect.offset(0f, imageRect.top - cropRect.top)
        if (cropRect.right > imageRect.right) cropRect.offset(imageRect.right - cropRect.right, 0f)
        if (cropRect.bottom > imageRect.bottom) cropRect.offset(0f, imageRect.bottom - cropRect.bottom)
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
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun detectDragMode(x: Float, y: Float): DragMode {
        fun near(px: Float, py: Float) = abs(x - px) < handleTouchRadius && abs(y - py) < handleTouchRadius
        return when {
            near(cropRect.left, cropRect.top) -> DragMode.TOP_LEFT
            near(cropRect.right, cropRect.top) -> DragMode.TOP_RIGHT
            near(cropRect.left, cropRect.bottom) -> DragMode.BOTTOM_LEFT
            near(cropRect.right, cropRect.bottom) -> DragMode.BOTTOM_RIGHT
            cropRect.contains(x, y) -> DragMode.MOVE
            else -> DragMode.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        when (dragMode) {
            DragMode.MOVE -> {
                cropRect.offset(dx, dy)
                clampCropToImage()
            }
            DragMode.TOP_LEFT, DragMode.TOP_RIGHT, DragMode.BOTTOM_LEFT, DragMode.BOTTOM_RIGHT -> {
                val r = RectF(cropRect)
                when (dragMode) {
                    DragMode.TOP_LEFT -> { r.left += dx; r.top += dy }
                    DragMode.TOP_RIGHT -> { r.right += dx; r.top += dy }
                    DragMode.BOTTOM_LEFT -> { r.left += dx; r.bottom += dy }
                    DragMode.BOTTOM_RIGHT -> { r.right += dx; r.bottom += dy }
                    else -> {}
                }
                aspectRatio?.let { ratio ->
                    when (dragMode) {
                        DragMode.TOP_LEFT, DragMode.TOP_RIGHT -> r.top = r.bottom - (r.right - r.left) / ratio
                        DragMode.BOTTOM_LEFT, DragMode.BOTTOM_RIGHT -> r.bottom = r.top + (r.right - r.left) / ratio
                        else -> {}
                    }
                }
                if (r.width() >= minCropSizePx && r.height() >= minCropSizePx &&
                    r.left >= imageRect.left && r.top >= imageRect.top &&
                    r.right <= imageRect.right && r.bottom <= imageRect.bottom
                ) {
                    cropRect = r
                }
            }
            else -> {}
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageRect.width() <= 0f) return

        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropRect.top, scrimPaint)
        canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, scrimPaint)
        canvas.drawRect(imageRect.left, cropRect.top, cropRect.left, cropRect.bottom, scrimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, imageRect.right, cropRect.bottom, scrimPaint)

        canvas.drawRect(cropRect, borderPaint)

        val w3 = cropRect.width() / 3f; val h3 = cropRect.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(cropRect.left + w3 * i, cropRect.top, cropRect.left + w3 * i, cropRect.bottom, gridPaint)
            canvas.drawLine(cropRect.left, cropRect.top + h3 * i, cropRect.right, cropRect.top + h3 * i, gridPaint)
        }

        drawCornerHandle(canvas, cropRect.left, cropRect.top, 1, 1)
        drawCornerHandle(canvas, cropRect.right, cropRect.top, -1, 1)
        drawCornerHandle(canvas, cropRect.left, cropRect.bottom, 1, -1)
        drawCornerHandle(canvas, cropRect.right, cropRect.bottom, -1, -1)
    }

    private fun drawCornerHandle(canvas: Canvas, x: Float, y: Float, dirX: Int, dirY: Int) {
        canvas.drawLine(x, y, x + handleLen * dirX, y, handlePaint)
        canvas.drawLine(x, y, x, y + handleLen * dirY, handlePaint)
    }

    /** Recadrage courant en fractions (0..1) de la zone image — indépendant de la résolution,
     *  pour être ré-appliqué sur le bitmap source en pleine résolution au moment d'enregistrer. */
    fun cropRectFraction(): RectF {
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return RectF(0f, 0f, 1f, 1f)
        return RectF(
            ((cropRect.left - imageRect.left) / imageRect.width()).coerceIn(0f, 1f),
            ((cropRect.top - imageRect.top) / imageRect.height()).coerceIn(0f, 1f),
            ((cropRect.right - imageRect.left) / imageRect.width()).coerceIn(0f, 1f),
            ((cropRect.bottom - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
        )
    }
}
