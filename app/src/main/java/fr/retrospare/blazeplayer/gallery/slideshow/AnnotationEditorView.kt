package fr.retrospare.blazeplayer.gallery.slideshow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Prévisualisation plein écran d'une slide 16:9 avec annotation et GIF déplaçables. */
class AnnotationEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Drawable.Callback {

    companion object {
        const val SHAPE_PILL = 0
        const val SHAPE_PARALLELOGRAM = 1
        const val SHAPE_RECTANGLE = 2
    }

    var annotationText: String = ""
        set(value) { field = value; invalidate() }
    var annotationColor: Int = 0xD91A1D24.toInt()
        set(value) { field = value; invalidate() }
    var annotationShape: Int = SHAPE_PILL
        set(value) { field = value; invalidate() }
    var annotationTypeface: Typeface = Typeface.DEFAULT_BOLD
        set(value) { field = value; invalidate() }
    var annotationSizeFraction: Float = 0.060f
        set(value) { field = value.coerceIn(0.030f, 0.115f); invalidate() }
    var annotationCenterX: Float = 0.50f
        private set
    var annotationCenterY: Float = 0.78f
        private set

    var gifCenterX: Float = 0.50f
        private set
    var gifCenterY: Float = 0.45f
        private set
    var gifWidthFraction: Float = 0.28f
        private set

    private var bitmap: Bitmap? = null
    private var gifDrawable: Drawable? = null
    private var gifUri: Uri? = null
    private val frameBounds = RectF()
    private val annotationBounds = RectF()
    private val gifBounds = RectF()
    private var dragTarget = 0 // 1 annotation, 2 gif
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    fun setBitmap(value: Bitmap?) {
        if (bitmap !== value && bitmap?.isRecycled == false) bitmap?.recycle()
        bitmap = value
        invalidate()
    }

    fun setAnnotationPosition(x: Float, y: Float) {
        annotationCenterX = x.coerceIn(0f, 1f)
        annotationCenterY = y.coerceIn(0f, 1f)
        invalidate()
    }

    fun setGifPosition(x: Float, y: Float) {
        gifCenterX = x.coerceIn(0f, 1f)
        gifCenterY = y.coerceIn(0f, 1f)
        invalidate()
    }

    fun setGifWidthFraction(value: Float) {
        gifWidthFraction = value.coerceIn(0.12f, 0.55f)
        invalidate()
    }

    fun currentGifUri(): Uri? = gifUri

    fun setGifUri(uri: Uri?) {
        (gifDrawable as? Animatable)?.stop()
        gifDrawable?.callback = null
        gifDrawable = null
        gifUri = uri
        if (uri != null) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeDrawable(source).also { drawable ->
                    drawable.callback = this
                    gifDrawable = drawable
                    (drawable as? Animatable)?.start()
                }
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        updateFrameBounds()
        bitmap?.let { drawFitCenter(canvas, it) }
        drawGif(canvas)
        if (annotationText.isNotBlank()) drawAnnotation(canvas)
    }

    private fun updateFrameBounds() {
        if (width <= 0 || height <= 0) { frameBounds.setEmpty(); return }
        val targetRatio = 16f / 9f
        if (width.toFloat() / height >= targetRatio) {
            val frameW = height * targetRatio
            val left = (width - frameW) / 2f
            frameBounds.set(left, 0f, left + frameW, height.toFloat())
        } else {
            val frameH = width / targetRatio
            val top = (height - frameH) / 2f
            frameBounds.set(0f, top, width.toFloat(), top + frameH)
        }
    }

    private fun drawFitCenter(canvas: Canvas, source: Bitmap) {
        if (frameBounds.isEmpty || source.width <= 0 || source.height <= 0) return
        val scale = minOf(frameBounds.width() / source.width, frameBounds.height() / source.height)
        val w = source.width * scale
        val h = source.height * scale
        val dst = RectF(
            frameBounds.centerX() - w / 2f, frameBounds.centerY() - h / 2f,
            frameBounds.centerX() + w / 2f, frameBounds.centerY() + h / 2f
        )
        canvas.save()
        canvas.clipRect(frameBounds)
        canvas.drawBitmap(source, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    }

    private fun drawGif(canvas: Canvas) {
        val drawable = gifDrawable ?: run { gifBounds.setEmpty(); return }
        val intrinsicW = max(1, drawable.intrinsicWidth)
        val intrinsicH = max(1, drawable.intrinsicHeight)
        if (frameBounds.isEmpty) return
        val targetW = frameBounds.width() * gifWidthFraction
        val targetH = targetW * intrinsicH / intrinsicW.toFloat()
        val desiredLeft = frameBounds.left + frameBounds.width() * gifCenterX - targetW / 2f
        val desiredTop = frameBounds.top + frameBounds.height() * gifCenterY - targetH / 2f
        val left = desiredLeft.coerceIn(frameBounds.left, max(frameBounds.left, frameBounds.right - targetW))
        val top = desiredTop.coerceIn(frameBounds.top, max(frameBounds.top, frameBounds.bottom - targetH))
        gifBounds.set(left, top, left + targetW, top + targetH)
        drawable.bounds = android.graphics.Rect(
            gifBounds.left.toInt(), gifBounds.top.toInt(), gifBounds.right.toInt(), gifBounds.bottom.toInt()
        )
        drawable.draw(canvas)
    }

    private fun drawAnnotation(canvas: Canvas) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = frameBounds.height() * annotationSizeFraction
            typeface = annotationTypeface
            color = Color.WHITE
            setShadowLayer(max(2f, textSize * 0.06f), 0f, textSize * 0.035f, 0xCC000000.toInt())
        }
        val maxTextWidth = (frameBounds.width() * 0.72f).toInt().coerceAtLeast(120)
        val layout = buildAdaptiveLayout(annotationText, textPaint, maxTextWidth)
        val padX = max(20f, textPaint.textSize * 0.48f)
        val padY = max(12f, textPaint.textSize * 0.28f)
        val skewPad = if (annotationShape == SHAPE_PARALLELOGRAM) max(18f, textPaint.textSize * 0.34f) else 0f
        val boxW = layout.width + padX * 2f + skewPad * 2f
        val boxH = layout.height + padY * 2f
        var left = frameBounds.left + frameBounds.width() * annotationCenterX - boxW / 2f
        var top = frameBounds.top + frameBounds.height() * annotationCenterY - boxH / 2f
        left = left.coerceIn(frameBounds.left + 8f, max(frameBounds.left + 8f, frameBounds.right - boxW - 8f))
        top = top.coerceIn(frameBounds.top + 8f, max(frameBounds.top + 8f, frameBounds.bottom - boxH - 8f))
        annotationBounds.set(left, top, left + boxW, top + boxH)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = annotationColor }
        when (annotationShape) {
            SHAPE_PARALLELOGRAM -> {
                val skew = min(boxH * 0.42f, boxW * 0.10f)
                val p = Path().apply {
                    moveTo(left + skew, top)
                    lineTo(left + boxW, top)
                    lineTo(left + boxW - skew, top + boxH)
                    lineTo(left, top + boxH)
                    close()
                }
                canvas.drawPath(p, bg)
            }
            SHAPE_RECTANGLE -> canvas.drawRect(annotationBounds, bg)
            else -> canvas.drawRoundRect(annotationBounds, boxH / 2f, boxH / 2f, bg)
        }

        canvas.save()
        canvas.translate(left + padX + skewPad, top + padY)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildAdaptiveLayout(text: String, paint: TextPaint, maxWidth: Int): StaticLayout {
        val first = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setMaxLines(4)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        var actual = 1f
        for (i in 0 until first.lineCount) actual = max(actual, first.getLineWidth(i))
        val width = ceil(min(maxWidth.toFloat(), actual + 2f)).toInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setMaxLines(4)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragTarget = when {
                    !gifBounds.isEmpty && gifBounds.contains(event.x, event.y) -> 2
                    !annotationBounds.isEmpty && annotationBounds.contains(event.x, event.y) -> 1
                    else -> 0
                }
                if (dragTarget == 0) return true
                val cx = frameBounds.left + frameBounds.width() * (if (dragTarget == 1) annotationCenterX else gifCenterX)
                val cy = frameBounds.top + frameBounds.height() * (if (dragTarget == 1) annotationCenterY else gifCenterY)
                dragOffsetX = event.x - cx
                dragOffsetY = event.y - cy
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragTarget == 1) {
                    annotationCenterX = ((event.x - dragOffsetX - frameBounds.left) / frameBounds.width()).coerceIn(0f, 1f)
                    annotationCenterY = ((event.y - dragOffsetY - frameBounds.top) / frameBounds.height()).coerceIn(0f, 1f)
                    invalidate()
                } else if (dragTarget == 2) {
                    gifCenterX = ((event.x - dragOffsetX - frameBounds.left) / frameBounds.width()).coerceIn(0f, 1f)
                    gifCenterY = ((event.y - dragOffsetY - frameBounds.top) / frameBounds.height()).coerceIn(0f, 1f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragTarget = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun invalidateDrawable(who: Drawable) = invalidate()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        postDelayed(what, max(0L, `when` - android.os.SystemClock.uptimeMillis()))
    }
    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        removeCallbacks(what)
    }

    override fun onDetachedFromWindow() {
        (gifDrawable as? Animatable)?.stop()
        gifDrawable?.callback = null
        if (bitmap?.isRecycled == false) bitmap?.recycle()
        bitmap = null
        super.onDetachedFromWindow()
    }
}
