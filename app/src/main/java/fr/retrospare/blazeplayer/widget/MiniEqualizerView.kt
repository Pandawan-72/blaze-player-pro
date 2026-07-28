package fr.retrospare.blazeplayer.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import fr.retrospare.blazeplayer.R
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

/**
 * Indicateur purement décoratif utilisé dans les files d'attente et les listes de titres.
 *
 * L'état "doit être animé" est indépendant de l'attachement de la vue. Un détachement temporaire
 * causé par RecyclerView, un scroll, une navigation ou un changement de visibilité suspend
 * uniquement les frames. L'animation reprend automatiquement dès que la même vue redevient visible.
 */
class MiniEqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barCount = 3
    private val values = floatArrayOf(0.32f, 0.70f, 0.46f)
    private val targetValues = values.copyOf()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random(SystemClock.uptimeMillis() xor hashCode().toLong())

    private var gradient: LinearGradient? = null
    private var bottomColor: Int = context.getColor(R.color.green_accent)
    private var middleColor: Int = context.getColor(R.color.green_accent)
    private var topColor: Int = Color.WHITE

    /** Demande fonctionnelle persistante : ne doit être remise à false que par stop(). */
    private var animationRequested = false
    /** Évite d'empiler plusieurs callbacks de frame pour la même vue. */
    private var framePosted = false
    private var nextTargetAtMs = 0L
    private var lastFrameAtMs = 0L

    private val animationFrame = object : Runnable {
        override fun run() {
            framePosted = false
            if (!canAnimateNow()) return

            val now = SystemClock.uptimeMillis()
            if (nextTargetAtMs <= 0L || now >= nextTargetAtMs) {
                chooseRandomTargets(now)
            }

            val elapsedMs = if (lastFrameAtMs <= 0L) {
                FRAME_DELAY_MS
            } else {
                (now - lastFrameAtMs).coerceIn(1L, MAX_FRAME_GAP_MS)
            }
            lastFrameAtMs = now

            // Interpolation dépendante du temps : un retard ponctuel de l'UI ne fige pas les barres.
            val interpolation = (
                1f - exp(-elapsedMs.toFloat() / SMOOTHING_TIME_MS)
            ).coerceIn(0.10f, 0.48f)

            for (index in 0 until barCount) {
                values[index] += (
                    targetValues[index] - values[index]
                ) * interpolation
            }

            invalidate()
            scheduleNextFrame()
        }
    }

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
        animationRequested = true
        if (visibility != VISIBLE) visibility = VISIBLE
        nextTargetAtMs = 0L
        lastFrameAtMs = 0L
        ensureAnimationRunning()
    }

    fun stop() {
        animationRequested = false
        pauseFrameLoop()
        visibility = GONE
        resetBars()
        invalidate()
    }

    /**
     * Réveille explicitement la boucle sans modifier l'état demandé. Utile lors du rattachement
     * d'un ViewHolder qui n'a pas été rebindé par RecyclerView.
     */
    fun ensureAnimationRunning() {
        if (!animationRequested) return
        if (visibility != VISIBLE) visibility = VISIBLE
        scheduleNextFrame()
    }

    @Suppress("UNUSED_PARAMETER")
    fun updateFft(fft: ByteArray?) = Unit

    fun setIdle() {
        resetBars()
        invalidate()
    }

    private fun resetBars() {
        val idle = floatArrayOf(0.28f, 0.52f, 0.36f)
        for (index in 0 until barCount) {
            values[index] = idle[index]
            targetValues[index] = idle[index]
        }
        nextTargetAtMs = 0L
        lastFrameAtMs = 0L
    }

    private fun chooseRandomTargets(nowMs: Long) {
        for (index in 0 until barCount) {
            val base = 0.18f + random.nextFloat() * 0.68f
            val centreBoost = if (index == 1) 0.08f else 0f
            targetValues[index] = (base + centreBoost).coerceIn(0.16f, 0.96f)
        }
        nextTargetAtMs = nowMs + 90L + random.nextInt(100)
    }

    private fun canAnimateNow(): Boolean =
        animationRequested &&
            isAttachedToWindow &&
            visibility == VISIBLE &&
            windowVisibility == VISIBLE

    private fun scheduleNextFrame() {
        if (framePosted || !canAnimateNow()) return
        framePosted = true
        postOnAnimation(animationFrame)
    }

    private fun pauseFrameLoop() {
        removeCallbacks(animationFrame)
        framePosted = false
        lastFrameAtMs = 0L
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // RecyclerView peut rattacher une ligne sans rappeler onBindViewHolder.
        ensureAnimationRunning()
    }

    override fun onDetachedFromWindow() {
        // Ne surtout pas annuler animationRequested : ce détachement peut n'être qu'un scroll.
        pauseFrameLoop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            ensureAnimationRunning()
        } else {
            // Suspension seulement ; start() reste mémorisé pour le prochain retour à l'écran.
            pauseFrameLoop()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            ensureAnimationRunning()
        } else {
            pauseFrameLoop()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            ensureAnimationRunning()
        } else {
            pauseFrameLoop()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGradient()
        if (w > 0 && h > 0) ensureAnimationRunning()
    }

    private fun rebuildGradient() {
        val h = height.takeIf { it > 0 } ?: return
        gradient = LinearGradient(
            0f,
            h.toFloat(),
            0f,
            0f,
            intArrayOf(bottomColor, middleColor, topColor),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val red = (
            Color.red(a) + ((Color.red(b) - Color.red(a)) * t)
        ).toInt().coerceIn(0, 255)
        val green = (
            Color.green(a) + ((Color.green(b) - Color.green(a)) * t)
        ).toInt().coerceIn(0, 255)
        val blue = (
            Color.blue(a) + ((Color.blue(b) - Color.blue(a)) * t)
        ).toInt().coerceIn(0, 255)
        return Color.rgb(red, green, blue)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val widthPx = width.toFloat()
        val heightPx = height.toFloat()
        if (widthPx <= 0f || heightPx <= 0f) return

        paint.shader = gradient
        val gap = widthPx * 0.13f
        val barWidth = (
            (widthPx - gap * (barCount - 1)) / barCount
        ).coerceAtLeast(2f)
        val radius = barWidth / 2f

        for (index in 0 until barCount) {
            val minimumHeight = heightPx * 0.20f
            val barHeight = min(
                heightPx * 0.94f,
                minimumHeight + values[index] * (heightPx - minimumHeight)
            )
            val left = index * (barWidth + gap)
            val top = heightPx - barHeight
            canvas.drawRoundRect(
                left,
                top,
                left + barWidth,
                heightPx,
                radius,
                radius,
                paint
            )
        }
    }

    private companion object {
        const val FRAME_DELAY_MS = 16L
        const val MAX_FRAME_GAP_MS = 80L
        const val SMOOTHING_TIME_MS = 70f
    }
}
