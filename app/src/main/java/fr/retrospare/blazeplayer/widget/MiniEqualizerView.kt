package fr.retrospare.blazeplayer.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import fr.retrospare.blazeplayer.R
import kotlin.math.sin

class MiniEqualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.green_accent)
    }

    private var running = false
    private var startTime = 0L

    fun start() {
        if (running) return
        running = true
        startTime = System.currentTimeMillis()
        visibility = VISIBLE
        invalidate()
        scheduleNext()
    }

    fun stop() {
        running = false
        removeCallbacks(drawRunnable)
        visibility = GONE
    }

    private val drawRunnable = object : Runnable {
        override fun run() {
            if (running) {
                invalidate()
                postDelayed(this, 16)
            }
        }
    }

    private fun scheduleNext() {
        removeCallbacks(drawRunnable)
        if (running) postDelayed(drawRunnable, 16)
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val t = (System.currentTimeMillis() - startTime) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val barW = w / 5f
        val gap = barW / 3f
        val radius = barW / 2f

        val heights = floatArrayOf(
            (0.3f + 0.5f * ((sin(t * 3.0) + 1) / 2).toFloat()).coerceIn(0.15f, 0.95f),
            (0.3f + 0.5f * ((sin(t * 4.5 + 1.0) + 1) / 2).toFloat()).coerceIn(0.15f, 0.95f),
            (0.3f + 0.5f * ((sin(t * 3.8 + 2.0) + 1) / 2).toFloat()).coerceIn(0.15f, 0.95f)
        )

        for (i in 0 until 3) {
            val barH = heights[i] * h
            val left = i * (barW + gap)
            val top = h - barH
            canvas.drawRoundRect(left, top, left + barW, h, radius, radius, paint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (18 * context.resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(size, heightMeasureSpec)
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        removeCallbacks(drawRunnable)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && running) {
            running = false
            removeCallbacks(drawRunnable)
        }
    }
}
