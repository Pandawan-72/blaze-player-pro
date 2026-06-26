package fr.retrospare.blazeplayer.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import fr.retrospare.blazeplayer.R

class EqBandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var minLevel = -1500
    var maxLevel = 1500
    var currentLevel = 0
        set(value) {
            field = value.coerceIn(minLevel, maxLevel)
            invalidate()
            if (!silent) onLevelChanged?.invoke(field)
        }
    var silent = false

    var onLevelChanged: ((Int) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFFFFF
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.FILL
        strokeWidth = 2f
    }

    private var isDragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val trackWidth = 8f
        val cx = w / 2f
        val thumbRadius = 14f
        val thumbY = levelToY(currentLevel)

        // Track fond
        val trackRect = RectF(cx - trackWidth/2, thumbRadius, cx + trackWidth/2, h - thumbRadius)
        canvas.drawRoundRect(trackRect, 4f, 4f, trackPaint)

        // Ligne centrale (0dB)
        val centerY = levelToY(0)
        canvas.drawRect(cx - trackWidth/2, centerY - 1f, cx + trackWidth/2, centerY + 1f, centerPaint)

        // Fill coloré
        fillPaint.color = getFillColor()
        if (currentLevel >= 0) {
            val fillRect = RectF(cx - trackWidth/2, thumbY, cx + trackWidth/2, centerY)
            canvas.drawRoundRect(fillRect, 4f, 4f, fillPaint)
        } else {
            val fillRect = RectF(cx - trackWidth/2, centerY, cx + trackWidth/2, thumbY)
            canvas.drawRoundRect(fillRect, 4f, 4f, fillPaint)
        }

        // Thumb
        thumbPaint.color = ContextCompat.getColor(context, R.color.green_accent)
        canvas.drawCircle(cx, thumbY, thumbRadius, thumbPaint)

        // Thumb inner
        thumbPaint.color = 0xFF0F1117.toInt()
        canvas.drawCircle(cx, thumbY, thumbRadius * 0.4f, thumbPaint)
    }

    private fun getFillColor(): Int {
        val db = currentLevel / 100
        return when {
            db > 8 -> 0xFFE05252.toInt()
            db > 4 -> 0xFFF0B429.toInt()
            db > 0 -> 0xFF3DD68C.toInt()
            db == 0 -> 0xFF888888.toInt()
            db > -4 -> 0xFF378ADD.toInt()
            else -> 0xFF8264DC.toInt()
        }
    }

    private fun levelToY(level: Int): Float {
        val h = height.toFloat()
        val thumbRadius = 14f
        val range = (maxLevel - minLevel).toFloat()
        val normalized = (maxLevel - level) / range
        return thumbRadius + normalized * (h - 2 * thumbRadius)
    }

    private fun yToLevel(y: Float): Int {
        val h = height.toFloat()
        val thumbRadius = 14f
        val normalized = (y - thumbRadius) / (h - 2 * thumbRadius)
        return (maxLevel - normalized * (maxLevel - minLevel)).toInt()
            .coerceIn(minLevel, maxLevel)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isDragging = true
                currentLevel = yToLevel(event.y)
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
