package fr.retrospare.blazeplayer.gallery.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max

/**
 * ImageView de comparaison avant/après.
 *
 * La partie gauche affiche le bitmap sans filtre et la partie droite avec le filtre actif.
 * Le séparateur est limité à la zone réellement occupée par l'image (fitCenter) et peut être
 * déplacé horizontalement au doigt. Le bitmap n'est jamais dupliqué : le même Drawable est
 * simplement dessiné deux fois avec des clips et des ColorFilter différents.
 */
class BeforeAfterImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val imageRect = RectF()
    private val drawableRect = RectF()

    private val dividerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        strokeWidth = 4f * density
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1.5f * density
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3212529.toInt()
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.7f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var afterFilter: ColorFilter? = null
    private var comparisonEnabled = false
    private var splitFraction = 0.5f
    private var dragging = false

    init {
        isClickable = true
    }

    fun setAfterFilter(filter: ColorFilter?) {
        afterFilter = filter
        invalidate()
    }

    fun setComparisonEnabled(enabled: Boolean) {
        if (comparisonEnabled == enabled) return
        comparisonEnabled = enabled
        dragging = false
        invalidate()
    }

    fun resetComparisonPosition() {
        splitFraction = 0.5f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val currentDrawable = drawable ?: return
        updateImageRect(currentDrawable)

        val filter = afterFilter
        if (!comparisonEnabled || filter == null || imageRect.isEmpty) {
            drawDrawable(canvas, currentDrawable, filter)
            return
        }

        // Après : filtre actif sur toute l'image.
        drawDrawable(canvas, currentDrawable, filter)

        // Avant : même image sans filtre, révélée à gauche du séparateur.
        val splitX = imageRect.left + imageRect.width() * splitFraction
        val beforeSave = canvas.save()
        canvas.clipRect(imageRect.left, imageRect.top, splitX, imageRect.bottom)
        drawDrawable(canvas, currentDrawable, null)
        canvas.restoreToCount(beforeSave)

        drawDivider(canvas, splitX)
    }

    private fun drawDrawable(canvas: Canvas, target: Drawable, filter: ColorFilter?) {
        val previousFilter = target.colorFilter
        target.colorFilter = filter
        val save = canvas.save()
        canvas.concat(imageMatrix)
        target.draw(canvas)
        canvas.restoreToCount(save)
        target.colorFilter = previousFilter
    }

    private fun updateImageRect(target: Drawable) {
        drawableRect.set(target.bounds)
        imageRect.set(drawableRect)
        imageMatrix.mapRect(imageRect)
        // Sécurité pour les drawables dont les bounds ne seraient pas encore initialisés.
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) {
            imageRect.set(0f, 0f, width.toFloat(), height.toFloat())
        }
    }

    private fun drawDivider(canvas: Canvas, splitX: Float) {
        canvas.drawLine(splitX, imageRect.top, splitX, imageRect.bottom, dividerShadowPaint)
        canvas.drawLine(splitX, imageRect.top, splitX, imageRect.bottom, dividerPaint)

        val centerY = imageRect.centerY()
        val radius = 17f * density
        canvas.drawCircle(splitX, centerY, radius, handleFillPaint)
        canvas.drawCircle(splitX, centerY, radius, handleStrokePaint)

        val arrowHalfWidth = 4.5f * density
        val arrowHalfHeight = 5.5f * density
        val arrowGap = 3.5f * density

        // Chevron gauche.
        canvas.drawLine(
            splitX - arrowGap,
            centerY - arrowHalfHeight,
            splitX - arrowGap - arrowHalfWidth,
            centerY,
            chevronPaint
        )
        canvas.drawLine(
            splitX - arrowGap - arrowHalfWidth,
            centerY,
            splitX - arrowGap,
            centerY + arrowHalfHeight,
            chevronPaint
        )

        // Chevron droit.
        canvas.drawLine(
            splitX + arrowGap,
            centerY - arrowHalfHeight,
            splitX + arrowGap + arrowHalfWidth,
            centerY,
            chevronPaint
        )
        canvas.drawLine(
            splitX + arrowGap + arrowHalfWidth,
            centerY,
            splitX + arrowGap,
            centerY + arrowHalfHeight,
            chevronPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!comparisonEnabled || imageRect.isEmpty) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageRect.contains(event.x, event.y)) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                updateSplitFromTouch(event.x)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                updateSplitFromTouch(event.x)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                updateSplitFromTouch(event.x)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateSplitFromTouch(x: Float) {
        val usableWidth = max(1f, imageRect.width())
        splitFraction = ((x - imageRect.left) / usableWidth).coerceIn(0f, 1f)
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
