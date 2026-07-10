package fr.retrospare.blazeplayer.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import fr.retrospare.blazeplayer.R
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Mini visualiseur audio pour les mini-players.
 *
 * Il utilise le même principe que AudioEqualizerView du grand lecteur : les valeurs viennent du
 * FFT réel du Visualizer associé à la session audio. start()/stop() restent conservés pour les
 * anciens appels UI, mais ne génèrent plus de fausse animation autonome.
 */
class MiniEqualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barCount = 14
    private val values = FloatArray(barCount) { 0.08f }
    private val targetValues = FloatArray(barCount) { 0.08f }
    private val rollingPeaks = FloatArray(barCount) { 0.22f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gradient: LinearGradient? = null
    private var bottomColor: Int = context.getColor(R.color.green_accent)
    private var middleColor: Int = context.getColor(R.color.green_accent)
    private var topColor: Int = Color.WHITE

    init {
        alpha = 0.96f
        setWillNotDraw(false)
        setAccentColor(context.getColor(R.color.green_accent))
    }

    fun setAccentColor(accentColor: Int) {
        bottomColor = mix(accentColor, Color.BLACK, 0.18f)
        middleColor = accentColor
        topColor = mix(accentColor, Color.WHITE, 0.28f)
        rebuildGradient()
        invalidate()
    }

    fun start() {
        visibility = VISIBLE
        invalidate()
    }

    fun stop() {
        visibility = GONE
        setIdle()
    }

    fun updateFft(fft: ByteArray?) {
        if (fft == null || fft.size < 4) {
            setIdle()
            return
        }
        val usableBins = max(2, (fft.size / 2) - 1)
        val minBin = 1f
        val maxBin = usableBins.toFloat()

        for (i in 0 until barCount) {
            val startRatio = i.toFloat() / barCount
            val endRatio = (i + 1).toFloat() / barCount
            val start = max(1, logBin(minBin, maxBin, startRatio))
            val end = max(start + 1, logBin(minBin, maxBin, endRatio))

            var sum = 0f
            var peak = 0f
            var count = 0
            var bin = start
            while (bin < end && (bin * 2 + 1) < fft.size) {
                val real = fft[bin * 2].toInt().toFloat()
                val imag = fft[bin * 2 + 1].toInt().toFloat()
                val mag = kotlin.math.sqrt(real * real + imag * imag) / 128f
                sum += mag
                if (mag > peak) peak = mag
                count++
                bin++
            }

            val avg = if (count > 0) sum / count else 0f
            val raw = (peak * 0.72f) + (avg * 0.28f)
            val highBandGain = 1f + (i * 0.20f)
            val boosted = raw * highBandGain
            rollingPeaks[i] = max(boosted, rollingPeaks[i] * 0.965f)
            val normalized = boosted / max(0.08f, rollingPeaks[i])
            targetValues[i] = min(1f, max(0.07f, kotlin.math.sqrt(normalized) * 0.92f))
        }
        invalidate()
    }

    fun setIdle() {
        for (i in 0 until barCount) {
            targetValues[i] = 0.08f + ((i % 5) * 0.018f)
        }
        invalidate()
    }

    private fun logBin(minBin: Float, maxBin: Float, ratio: Float): Int {
        val safeRatio = min(1f, max(0f, ratio))
        val value = minBin * kotlin.math.exp(ln(maxBin / minBin) * safeRatio)
        return value.toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGradient()
    }

    private fun rebuildGradient() {
        val h = height.takeIf { it > 0 } ?: return
        gradient = LinearGradient(
            0f, h.toFloat(), 0f, 0f,
            intArrayOf(bottomColor, middleColor, topColor),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val r = (Color.red(a) + ((Color.red(b) - Color.red(a)) * t)).toInt().coerceIn(0, 255)
        val g = (Color.green(a) + ((Color.green(b) - Color.green(a)) * t)).toInt().coerceIn(0, 255)
        val bl = (Color.blue(a) + ((Color.blue(b) - Color.blue(a)) * t)).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, bl)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = gradient
        val gap = w * 0.012f
        val barW = ((w - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val radius = barW / 2f
        for (i in 0 until barCount) {
            values[i] += (targetValues[i] - values[i]) * 0.34f
            val minH = h * 0.26f
            val barH = min(h * 0.92f, minH + values[i] * (h - minH))
            val left = i * (barW + gap)
            val top = h - barH
            canvas.drawRoundRect(left, top, left + barW, h, radius, radius, paint)
        }
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        setIdle()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) setIdle()
    }
}
