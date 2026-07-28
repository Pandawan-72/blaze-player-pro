package fr.retrospare.blazeplayer.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import fr.retrospare.blazeplayer.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Potentiomètre tactile à geste réellement rotatif.
 *
 * Le doigt tourne autour du centre comme sur un bouton matériel : dans le sens horaire la valeur
 * augmente, dans le sens antihoraire elle diminue. Le calcul est relatif au point de départ, ce qui
 * évite tout saut de valeur au premier contact. Un double appui restaure la valeur neutre.
 *
 * Le rendu visuel rappelle un potentiomètre métallique d'ampli, mais reste volontairement sobre :
 * une bague usinée, une face légèrement bombée et un repère lumineux.
 */
class RotaryKnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var minValue: Float = 0f
        set(value) {
            field = value
            currentValue = currentValue.coerceIn(field, maxValue)
            gestureValue = currentValue
            invalidate()
        }

    var maxValue: Float = 100f
        set(value) {
            field = value
            currentValue = currentValue.coerceIn(minValue, field)
            gestureValue = currentValue
            invalidate()
        }

    var step: Float = 1f
    var onValueChanged: ((Float, Boolean) -> Unit)? = null

    private var currentValue = 0f
    private var defaultValue = 0f
    private var gestureValue = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastTouchAngle = Float.NaN
    private var angleTrackingReady = false
    private var hasDragged = false
    private var lastHapticBucket = Int.MIN_VALUE

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val density = resources.displayMetrics.density
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5f * density
        color = ContextCompat.getColor(context, R.color.on_surface_variant)
        alpha = 95
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5f * density
        color = ContextCompat.getColor(context, R.color.green_accent)
    }

    // Rendu métallique volontairement épuré : une ombre, une bague et une face bombée. On évite
    // les stries, les petits anneaux et les liserés lumineux qui surchargeaient le bouton.
    private val knobShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(205, 4, 7, 11)
    }
    private val outerBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bezelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        color = Color.argb(185, 205, 220, 232)
    }
    private val metalBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bodyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(105, 225, 238, 246)
    }

    private val markerGroovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5.2f * density
        color = Color.argb(175, 5, 9, 13)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.2f * density
        color = ContextCompat.getColor(context, R.color.on_background)
    }
    private var shaderWidth = -1
    private var shaderHeight = -1

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            setValue(defaultValue, notify = true, fromUser = true)
            gestureValue = currentValue
            fr.retrospare.blazeplayer.ui.HapticFeedbackManager.perform(this@RotaryKnobView, HapticFeedbackConstants.LONG_PRESS)
            return true
        }
    })

    init {
        isClickable = true
        isFocusable = true
        // Ne jamais forcer neuf calques logiciels à l'ouverture des paramètres son : leur
        // allocation et leur rasterisation simultanées pouvaient bloquer plusieurs secondes.
        setLayerType(LAYER_TYPE_NONE, null)
    }

    fun value(): Float = currentValue

    fun setDefaultValue(value: Float) {
        defaultValue = normalize(value)
    }

    fun setValue(value: Float, notify: Boolean = false, fromUser: Boolean = false) {
        val normalized = normalize(value)
        if (normalized == currentValue) return
        currentValue = normalized
        invalidate()
        if (notify) onValueChanged?.invoke(currentValue, fromUser)
    }

    fun setAccentColor(color: Int) {
        activePaint.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (112f * density).roundToInt()
        setMeasuredDimension(
            resolveSize(desired, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val alphaFactor = if (isEnabled) 1f else 0.35f
        val cx = width / 2f
        val cy = height / 2f
        val outerRadius = (minOf(width, height) / 2f) - 9f * density
        val bezelRadius = outerRadius - 8.2f * density
        val bodyRadius = bezelRadius - 4.2f * density
        val arcRect = RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)
        ensureMetalShaders(cx, cy, bezelRadius, bodyRadius)

        canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * alphaFactor).toInt())
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, inactivePaint)

        val fraction = if (maxValue == minValue) 0f else ((currentValue - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
        val defaultFraction = if (maxValue == minValue) 0f else ((defaultValue - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
        val currentSweep = SWEEP_ANGLE * fraction
        val defaultSweep = SWEEP_ANGLE * defaultFraction
        val activeSweep = currentSweep - defaultSweep
        if (abs(activeSweep) > 0.2f) {
            canvas.drawArc(arcRect, START_ANGLE + defaultSweep, activeSweep, false, activePaint)
        }

        // Bague extérieure usinée, avec un seul chanfrein lisible.
        canvas.drawCircle(cx, cy + 1.4f * density, bezelRadius + 1f * density, knobShadowPaint)
        canvas.drawCircle(cx, cy, bezelRadius, outerBezelPaint)
        canvas.drawCircle(cx, cy, bezelRadius, bezelBorderPaint)

        // Face métallique légèrement bombée.
        canvas.drawCircle(cx, cy, bodyRadius, metalBodyPaint)
        canvas.drawCircle(cx, cy, bodyRadius, bodyBorderPaint)
        // Repère incrusté, contrasté mais simple.
        val angleRadians = Math.toRadians((START_ANGLE + currentSweep).toDouble())
        val markerInner = bodyRadius * 0.46f
        val markerOuter = bodyRadius * 0.78f
        val markerStartX = cx + cos(angleRadians).toFloat() * markerInner
        val markerStartY = cy + sin(angleRadians).toFloat() * markerInner
        val markerEndX = cx + cos(angleRadians).toFloat() * markerOuter
        val markerEndY = cy + sin(angleRadians).toFloat() * markerOuter
        canvas.drawLine(markerStartX, markerStartY, markerEndX, markerEndY, markerGroovePaint)
        canvas.drawLine(markerStartX, markerStartY, markerEndX, markerEndY, markerPaint)

        canvas.restore()
    }

    private fun ensureMetalShaders(cx: Float, cy: Float, bezelRadius: Float, bodyRadius: Float) {
        if (shaderWidth == width && shaderHeight == height) return
        shaderWidth = width
        shaderHeight = height

        outerBezelPaint.shader = LinearGradient(
            cx - bezelRadius,
            cy - bezelRadius,
            cx + bezelRadius,
            cy + bezelRadius,
            intArrayOf(
                Color.rgb(220, 230, 236),
                Color.rgb(96, 108, 118),
                Color.rgb(42, 50, 57),
                Color.rgb(140, 153, 162)
            ),
            floatArrayOf(0f, 0.34f, 0.64f, 1f),
            Shader.TileMode.CLAMP
        )
        metalBodyPaint.shader = RadialGradient(
            cx - bodyRadius * 0.30f,
            cy - bodyRadius * 0.34f,
            bodyRadius * 1.35f,
            intArrayOf(
                Color.rgb(232, 239, 243),
                Color.rgb(155, 167, 175),
                Color.rgb(76, 88, 97),
                Color.rgb(35, 43, 50)
            ),
            floatArrayOf(0f, 0.35f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                gestureValue = currentValue
                hasDragged = false
                lastHapticBucket = hapticBucket(currentValue)
                angleTrackingReady = isStableTouchRadius(event.x, event.y)
                lastTouchAngle = if (angleTrackingReady) touchAngle(event.x, event.y) else Float.NaN
                isPressed = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val moved = hypot(event.x - downX, event.y - downY)
                if (!hasDragged && moved < touchSlop) return true
                hasDragged = true

                // Près du centre, quelques pixels peuvent représenter un très grand angle. On met
                // donc le suivi en pause jusqu'à ce que le doigt retrouve la couronne du bouton.
                if (!isStableTouchRadius(event.x, event.y)) {
                    angleTrackingReady = false
                    lastTouchAngle = Float.NaN
                    return true
                }

                val angle = touchAngle(event.x, event.y)
                if (!angleTrackingReady || lastTouchAngle.isNaN()) {
                    lastTouchAngle = angle
                    angleTrackingReady = true
                    return true
                }

                var angleDelta = shortestAngleDelta(lastTouchAngle, angle)
                lastTouchAngle = angle

                // Un évènement tactile anormal ne doit jamais envoyer brutalement le bouton d'une
                // extrémité à l'autre. Les mouvements normaux restent strictement 1:1 en angle.
                angleDelta = angleDelta.coerceIn(-MAX_ANGLE_DELTA_PER_EVENT, MAX_ANGLE_DELTA_PER_EVENT)
                val range = maxValue - minValue
                gestureValue = (gestureValue + angleDelta / SWEEP_ANGLE * range).coerceIn(minValue, maxValue)

                val before = currentValue
                setValue(gestureValue, notify = true, fromUser = true)
                if (currentValue != before) provideStepHaptic()
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isPressed = false
                angleTrackingReady = false
                lastTouchAngle = Float.NaN
                if (!hasDragged) performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isPressed = false
                angleTrackingReady = false
                lastTouchAngle = Float.NaN
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun touchAngle(x: Float, y: Float): Float {
        val cx = width / 2f
        val cy = height / 2f
        // Avec l'axe Y Android orienté vers le bas, l'angle augmente naturellement dans le sens
        // horaire, exactement comme la rotation visuelle du potentiomètre.
        return Math.toDegrees(atan2(y - cy, x - cx).toDouble()).toFloat()
    }

    private fun isStableTouchRadius(x: Float, y: Float): Boolean {
        val radius = hypot(x - width / 2f, y - height / 2f)
        return radius >= minOf(width, height) * MIN_TRACKING_RADIUS_RATIO
    }

    private fun shortestAngleDelta(from: Float, to: Float): Float {
        var delta = to - from
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return delta
    }

    private fun provideStepHaptic() {
        val bucket = hapticBucket(currentValue)
        if (bucket == lastHapticBucket) return
        lastHapticBucket = bucket
        fr.retrospare.blazeplayer.ui.HapticFeedbackManager.perform(this, HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun hapticBucket(value: Float): Int {
        val range = (maxValue - minValue).coerceAtLeast(step)
        val hapticStep = maxOf(step, range / 24f)
        return ((value - minValue) / hapticStep).roundToInt()
    }

    private fun normalize(value: Float): Float {
        val safeStep = step.takeIf { it > 0f } ?: 1f
        val stepped = ((value - minValue) / safeStep).roundToInt() * safeStep + minValue
        return stepped.coerceIn(minValue, maxValue)
    }

    companion object {
        private const val START_ANGLE = 135f
        private const val SWEEP_ANGLE = 270f
        private const val MIN_TRACKING_RADIUS_RATIO = 0.16f
        private const val MAX_ANGLE_DELTA_PER_EVENT = 52f
    }
}
