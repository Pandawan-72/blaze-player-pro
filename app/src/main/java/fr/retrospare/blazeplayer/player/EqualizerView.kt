package fr.retrospare.blazeplayer.player

import android.content.Context
import android.graphics.*
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val BAR_COUNT = 32
    private val barHeights = FloatArray(BAR_COUNT)
    private val targetHeights = FloatArray(BAR_COUNT)
    private val peakHeights = FloatArray(BAR_COUNT)
    private val peakFall = FloatArray(BAR_COUNT)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        alpha = 180
    }

    private var visualizer: Visualizer? = null
    private var isActive = false

    private val shader get() = LinearGradient(
        0f, height.toFloat(), 0f, 0f,
        intArrayOf(
            Color.parseColor("#00E676"),
            Color.parseColor("#00BCD4"),
            Color.parseColor("#1565C0")
        ),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP
    )

    fun attach(audioSessionId: Int) {
        try {
            visualizer?.release()
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) {
                        processFft(fft)
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
            isActive = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processFft(fft: ByteArray) {
        val bucketSize = fft.size / 2 / BAR_COUNT
        for (i in 0 until BAR_COUNT) {
            var sum = 0f
            for (j in 0 until bucketSize) {
                val idx = i * bucketSize + j
                if (idx * 2 + 1 < fft.size) {
                    val re = fft[idx * 2].toFloat()
                    val im = fft[idx * 2 + 1].toFloat()
                    sum += sqrt(re * re + im * im)
                }
            }
            targetHeights[i] = (sum / bucketSize).coerceIn(0f, 128f) / 128f
        }
        post { invalidate() }
    }

    fun detach() {
        isActive = false
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null
        barHeights.fill(0f)
        targetHeights.fill(0f)
        peakHeights.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val barWidth = width.toFloat() / (BAR_COUNT * 2 - 1)
        val gap = barWidth
        paint.shader = shader

        for (i in 0 until BAR_COUNT) {
            // Smooth animation
            barHeights[i] += (targetHeights[i] - barHeights[i]) * 0.3f
            val barH = (barHeights[i] * height).coerceAtLeast(4f)

            // Peak indicator
            if (barHeights[i] >= peakHeights[i]) {
                peakHeights[i] = barHeights[i]
                peakFall[i] = 0f
            } else {
                peakFall[i] += 0.02f
                peakHeights[i] = (peakHeights[i] - peakFall[i]).coerceAtLeast(0f)
            }

            val x = i * (barWidth + gap)
            val radius = barWidth / 2

            // Barre principale
            val rect = RectF(x, height - barH, x + barWidth, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)

            // Peak dot
            val peakY = height - (peakHeights[i] * height)
            canvas.drawCircle(x + barWidth / 2, peakY, radius * 0.6f, peakPaint)
        }

        if (isActive) postInvalidateOnAnimation()
    }
}
