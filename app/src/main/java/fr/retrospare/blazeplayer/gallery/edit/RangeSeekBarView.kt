package fr.retrospare.blazeplayer.gallery.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Barre de sélection à deux poignées pour choisir un segment [startMs, endMs] dans une vidéo de
 * durée totale [durationMs] — l'équivalent de la barre de découpe de Google Photos. Affiche, une
 * fois fournies via [setFrames], de vraies vignettes extraites de la vidéo en fond de piste
 * (comme les galeries premium) plutôt qu'une simple barre de couleur unie ; la portion hors de la
 * sélection est assombrie pour bien distinguer ce qui sera coupé.
 */
class RangeSeekBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var durationMs: Long = 0L
        set(value) {
            field = value
            startMs = 0L
            endMs = value
            invalidate()
        }
    var startMs: Long = 0L
        private set
    var endMs: Long = 0L
        private set

    /** Durée minimale du segment sélectionné, pour éviter un export vide ou quasi nul. */
    var minRangeMs: Long = 500L

    var onRangeChanged: ((start: Long, end: Long) -> Unit)? = null

    /** Position de lecture courante (curseur fin indépendant des poignées). */
    var playheadMs: Long = 0L
        set(value) { field = value; invalidate() }

    private var frames: List<Bitmap>? = null

    private val density = resources.displayMetrics.density
    private val thumbRadius = 11f * density
    private val touchSlop = 30f * density
    private val trackHeight = 6f * density

    private val trackPaint = Paint().apply { color = Color.argb(90, 255, 255, 255) }
    private val rangePaint = Paint().apply {
        color = try { ContextCompat.getColor(context, fr.retrospare.blazeplayer.R.color.green_accent) } catch (_: Exception) { Color.rgb(63, 215, 143) }
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val thumbPaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val playheadPaint = Paint().apply { color = Color.WHITE; strokeWidth = 2f * density }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val frameSrcRect = Rect()
    private val frameDstRect = RectF()

    private enum class Drag { NONE, START, END }
    private var drag = Drag.NONE

    /** Vignettes extraites de la vidéo (voir [VideoTrimActivity]), dessinées côte à côte pour
     *  remplir toute la piste — remplace la barre de couleur unie une fois chargées. */
    fun setFrames(bitmaps: List<Bitmap>) {
        frames = bitmaps
        invalidate()
    }

    private fun xForMs(ms: Long): Float {
        if (durationMs <= 0L || width <= 0) return thumbRadius
        val usable = width - thumbRadius * 2
        return thumbRadius + usable * (ms.toFloat() / durationMs.toFloat())
    }

    private fun msForX(x: Float): Long {
        if (durationMs <= 0L || width <= 0) return 0L
        val usable = width - thumbRadius * 2
        val ratio = ((x - thumbRadius) / usable).coerceIn(0f, 1f)
        return (ratio * durationMs).toLong()
    }

    fun setRange(start: Long, end: Long) {
        val s = start.coerceIn(0, durationMs)
        val e = end.coerceIn(s, durationMs)
        startMs = s
        endMs = e
        invalidate()
        onRangeChanged?.invoke(startMs, endMs)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dStart = abs(x - xForMs(startMs))
                val dEnd = abs(x - xForMs(endMs))
                drag = when {
                    dStart <= touchSlop && dStart <= dEnd -> Drag.START
                    dEnd <= touchSlop -> Drag.END
                    else -> Drag.NONE
                }
                return drag != Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                when (drag) {
                    Drag.START -> {
                        val newStart = msForX(x).coerceAtMost((endMs - minRangeMs).coerceAtLeast(0))
                        setRange(newStart, endMs)
                    }
                    Drag.END -> {
                        val newEnd = msForX(x).coerceAtLeast((startMs + minRangeMs).coerceAtMost(durationMs))
                        setRange(startMs, newEnd)
                    }
                    Drag.NONE -> return false
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = Drag.NONE; return true }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val trackLeft = thumbRadius
        val trackRight = width - thumbRadius
        val xs = xForMs(startMs); val xe = xForMs(endMs)
        val currentFrames = frames

        if (currentFrames != null && currentFrames.isNotEmpty() && trackRight > trackLeft) {
            val slotWidth = (trackRight - trackLeft) / currentFrames.size
            currentFrames.forEachIndexed { i, bmp ->
                frameSrcRect.set(0, 0, bmp.width, bmp.height)
                frameDstRect.set(trackLeft + i * slotWidth, 0f, trackLeft + (i + 1) * slotWidth, height.toFloat())
                canvas.drawBitmap(bmp, frameSrcRect, frameDstRect, framePaint)
            }
            // Assombrit tout ce qui est hors de la sélection, pour bien voir ce qui sera coupé.
            canvas.drawRect(trackLeft, 0f, xs, height.toFloat(), dimPaint)
            canvas.drawRect(xe, 0f, trackRight, height.toFloat(), dimPaint)
            canvas.drawRect(xs, 1.5f * density, xe, height - 1.5f * density, rangePaint)
        } else {
            val cy = height / 2f
            canvas.drawRoundRect(trackLeft, cy - trackHeight / 2, trackRight, cy + trackHeight / 2, trackHeight / 2, trackHeight / 2, trackPaint)
            canvas.drawRoundRect(xs, cy - trackHeight / 2, xe, cy + trackHeight / 2, trackHeight / 2, trackHeight / 2, Paint().apply { color = rangePaint.color; style = Paint.Style.FILL })
        }

        if (playheadMs in startMs..endMs) {
            val xp = xForMs(playheadMs)
            canvas.drawLine(xp, 0f, xp, height.toFloat(), playheadPaint)
        }
        val cy = height / 2f
        canvas.drawCircle(xs, cy, thumbRadius, thumbPaint)
        canvas.drawCircle(xe, cy, thumbRadius, thumbPaint)
    }
}
