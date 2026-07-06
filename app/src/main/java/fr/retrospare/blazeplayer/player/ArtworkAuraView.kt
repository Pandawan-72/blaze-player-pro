package fr.retrospare.blazeplayer.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Glow premium, sobre et animé autour de la pochette audio.
 *
 * Le halo n'est plus une forme carrée/stroke épais : on dessine plusieurs passes
 * de rounded-rect floutés avec le même radius que la cover. Le centre est masqué
 * par la pochette, et seule une diffusion courte, douce et progressive reste visible
 * autour des bords. L'animation se limite à une rotation très lente du dégradé.
 */
class ArtworkAuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isDither = true
        style = Paint.Style.FILL
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isDither = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val coverRect = RectF()
    private var phase = 0f
    private var accent = Color.rgb(255, 45, 167)
    private var secondary = Color.rgb(40, 130, 255)
    private var warm = Color.rgb(255, 116, 45)
    private var animator: ValueAnimator? = null
    private var coverInsetPx: Float = -1f

    private val density: Float get() = resources.displayMetrics.density
    private val coverCornerRadius: Float get() = 23f * density

    init {
        // BlurMaskFilter nécessite le rendu software pour être fiable sur toutes les versions.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        alpha = 1.0f
    }

    fun setAccentColor(color: Int) {
        accent = boost(color, saturation = 1.06f, value = 1.08f)
        secondary = rotateHue(accent, 118f)
        warm = rotateHue(accent, 34f)
        invalidate()
    }

    /**
     * Distance réelle entre le bord de cette vue et le bord de la pochette.
     * Le layout peut agrandir seulement la surface de dessin du glow ; cet inset
     * garde le rounded-rect lumineux exactement aligné sur la cover, sans modifier
     * la taille ni les paddings du player.
     */
    fun setCoverInsetPx(insetPx: Float) {
        coverInsetPx = insetPx.coerceAtLeast(0f)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startAuraAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAuraAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView == this) {
            if (visibility == VISIBLE && isAttachedToWindow) startAuraAnimation() else stopAuraAnimation()
        }
    }

    private fun stopAuraAnimation() {
        animator?.cancel()
        animator = null
    }

    private fun startAuraAnimation() {
        if (animator?.isStarted == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            // Rotation très lente : visible si on regarde, jamais agressive.
            duration = 26_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedFraction
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val size = min(w, h)
        // Le Fragment fournit l'inset exact correspondant au débordement voulu.
        // Fallback conservateur si la vue est utilisée ailleurs.
        val haloPadding = if (coverInsetPx >= 0f) coverInsetPx else maxOf(12f * density, size * 0.058f)
        coverRect.set(
            (w - size) / 2f + haloPadding,
            (h - size) / 2f + haloPadding,
            (w + size) / 2f - haloPadding,
            (h + size) / 2f - haloPadding
        )

        // Glow volontairement plus présent : la zone de dessin est plus grande,
        // mais le rounded-rect source reste exactement aligné sur la cover.
        // Les passes externes gardent une chute douce pour se fondre dans le fond.
        drawBlurredFill(canvas, blurDp = 12f, alpha = breathe(86, 108))
        drawBlurredFill(canvas, blurDp = 28f, alpha = breathe(58, 76))
        drawBlurredFill(canvas, blurDp = 56f, alpha = breathe(28, 42))
        drawBlurredFill(canvas, blurDp = 84f, alpha = breathe(12, 22))
        drawVerySubtleRim(canvas)
    }

    private fun drawBlurredFill(canvas: Canvas, blurDp: Float, alpha: Int) {
        glowPaint.maskFilter = BlurMaskFilter(blurDp * density, BlurMaskFilter.Blur.NORMAL)
        glowPaint.shader = animatedGradient(alpha)
        canvas.drawRoundRect(coverRect, coverCornerRadius, coverCornerRadius, glowPaint)
        glowPaint.shader = null
        glowPaint.maskFilter = null
    }

    private fun drawVerySubtleRim(canvas: Canvas) {
        rimPaint.strokeWidth = maxOf(1.15f * density, width * 0.0032f)
        rimPaint.maskFilter = BlurMaskFilter(4.5f * density, BlurMaskFilter.Blur.NORMAL)
        rimPaint.shader = animatedGradient(breathe(84, 112))
        canvas.drawRoundRect(coverRect, coverCornerRadius, coverCornerRadius, rimPaint)
        rimPaint.shader = null
        rimPaint.maskFilter = null
    }

    private fun animatedGradient(alpha: Int): LinearGradient {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.56f
        val angle = phase * Math.PI * 2.0
        val dx = cos(angle).toFloat() * radius
        val dy = sin(angle).toFloat() * radius
        return LinearGradient(
            cx - dx, cy - dy, cx + dx, cy + dy,
            intArrayOf(
                withAlpha(accent, alpha),
                withAlpha(warm, (alpha * 0.78f).toInt()),
                withAlpha(secondary, (alpha * 0.86f).toInt()),
                withAlpha(accent, alpha)
            ),
            floatArrayOf(0f, 0.34f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun breathe(minAlpha: Int, maxAlpha: Int): Int {
        // Respiration très faible, pour éviter l'effet pulsation cheap.
        val t = ((sin(phase * Math.PI * 2.0).toFloat() + 1f) * 0.5f)
        return (minAlpha + (maxAlpha - minAlpha) * t).toInt().coerceIn(0, 255)
    }

    private fun rotateHue(color: Int, degrees: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + degrees) % 360f
        hsv[1] = (hsv[1] * 1.04f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * 1.05f).coerceIn(0.30f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun boost(color: Int, saturation: Float, value: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * saturation).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * value).coerceIn(0.32f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
