package fr.retrospare.blazeplayer.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * Paroles compactes affichées au-dessus de la pochette du lecteur portrait.
 *
 * La vue se rafraîchit à la cadence de l'écran pendant la lecture. Les Enhanced LRC peuvent donc
 * avancer mot par mot ou syllabe par syllabe sans dépendre du polling de progression à 500 ms du
 * Fragment. La ligne suivante reste visible sous la ligne active, comme dans l'ancien overlay.
 */
class StandardLyricsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var lines: List<AudioLocalEnhancements.LyricLine> = emptyList()
    private var accentColor: Int = Color.rgb(63, 215, 143)
    private var basePositionMs: Long = 0L
    private var baseRealtimeMs: Long = SystemClock.elapsedRealtime()
    private var playbackSpeed: Float = 1f
    private var playing: Boolean = false
    private var renderedPositionMs: Float = Float.NaN
    private var lastFrameRealtimeNs: Long = 0L
    private var karaoKastPerformanceMode: Boolean = false

    private var cachedActiveIndex: Int = Int.MIN_VALUE
    private var cachedSegmentIndex: Int = Int.MIN_VALUE
    private var cachedWidth: Int = -1
    private var currentLayout: StaticLayout? = null
    private var nextLayout: StaticLayout? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val sidePadding = 2f * density
    private val lineGap = 7f * density

    fun setLyrics(value: List<AudioLocalEnhancements.LyricLine>) {
        lines = value.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }
        renderedPositionMs = Float.NaN
        lastFrameRealtimeNs = 0L
        clearLayouts()
        invalidate()
    }

    fun setAccentColor(color: Int) {
        if (accentColor == color) return
        accentColor = color
        clearLayouts()
        invalidate()
    }

    fun setKaraoKastPerformanceMode(enabled: Boolean) {
        if (karaoKastPerformanceMode == enabled) return
        karaoKastPerformanceMode = enabled
        clearLayouts()
        setLayerType(LAYER_TYPE_HARDWARE, null)
        invalidate()
    }

    fun updatePlaybackPosition(
        positionMs: Long,
        isPlaying: Boolean,
        speed: Float = 1f
    ) {
        val now = SystemClock.elapsedRealtime()
        val safePosition = positionMs.coerceAtLeast(0L)
        val stateChanged = playing != isPlaying
        val largeCorrection = !renderedPositionMs.isNaN() &&
            abs(safePosition - renderedPositionMs) >= SEEK_SNAP_THRESHOLD_MS

        basePositionMs = safePosition
        baseRealtimeMs = now
        playbackSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        playing = isPlaying

        if (!isPlaying || stateChanged || largeCorrection || renderedPositionMs.isNaN()) {
            renderedPositionMs = safePosition.toFloat()
            lastFrameRealtimeNs = SystemClock.elapsedRealtimeNanos()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty() || width <= 0 || height <= 0) return

        val rawPositionMs = currentPositionMs()
        val smoothedPositionMs = smoothRenderPosition(rawPositionMs)

        // Les échantillons du MediaController peuvent légèrement osciller lors de leur
        // resynchronisation périodique. L'extrapolation brute provoquait alors des sauts de
        // plusieurs centaines de millisecondes. On conserve donc l'horloge lissée comme source
        // principale, mais une ligne LRC standard est autorisée à rattraper une petite avance
        // fiable de l'horloge du player. Le mode Enhanced reste strictement inchangé.
        val smoothedActiveIndex = activeLineIndex(smoothedPositionMs)
        val preciseStandardPositionMs = preciseStandardLinePosition(smoothedPositionMs, rawPositionMs)
        val preciseCandidateIndex = activeLineIndex(preciseStandardPositionMs)
        val touchesEnhancedLine =
            lines.getOrNull(smoothedActiveIndex)?.segments?.isNotEmpty() == true ||
                lines.getOrNull(preciseCandidateIndex)?.segments?.isNotEmpty() == true
        val activeIndex = if (touchesEnhancedLine) smoothedActiveIndex else preciseCandidateIndex
        val segmentIndex = activeEnhancedSegmentIndex(activeIndex, smoothedPositionMs)
        if (activeIndex != cachedActiveIndex || segmentIndex != cachedSegmentIndex || width != cachedWidth) {
            cachedActiveIndex = activeIndex
            cachedSegmentIndex = segmentIndex
            cachedWidth = width
            rebuildLayouts(activeIndex, segmentIndex)
        }

        val current = currentLayout ?: return
        val next = nextLayout
        // Le conteneur XML possède déjà le padding supérieur qui place les paroles dans
        // la partie la plus sombre du dégradé. Ne pas recentrer verticalement ici : avec
        // deux lignes compactes, cela les décalait vers la zone transparente de la cover.
        var top = 0f

        drawLayout(canvas, current, top, 255)
        top += current.height + lineGap
        if (next != null && top < height) drawLayout(canvas, next, top, 218)

        contentDescription = lines.getOrNull(activeIndex)?.text.orEmpty()
        if (playing && isShown) {
            if (karaoKastPerformanceMode) postInvalidateDelayed(KARAO_KAST_FRAME_DELAY_MS)
            else postInvalidateOnAnimation()
        }
    }

    private fun rebuildLayouts(activeIndex: Int, segmentIndex: Int) {
        currentLayout = lines.getOrNull(activeIndex)?.let { line ->
            buildLayout(
                text = enhancedText(line, segmentIndex),
                active = true,
                textSizeSp = 15f,
                maxLines = 2
            )
        }
        val nextLine = lines.drop(activeIndex + 1).firstOrNull { it.text.isNotBlank() }
        nextLayout = nextLine?.let { line ->
            buildLayout(
                text = line.text,
                active = false,
                textSizeSp = 13f,
                maxLines = 2
            )
        }
    }

    private fun enhancedText(
        line: AudioLocalEnhancements.LyricLine,
        activeSegmentIndex: Int
    ): CharSequence {
        if (line.segments.isEmpty()) return line.text
        return SpannableString(line.text).apply {
            if (activeSegmentIndex < 0) return@apply
            val completedColor = Color.argb(
                205,
                Color.red(accentColor),
                Color.green(accentColor),
                Color.blue(accentColor)
            )
            line.segments.forEachIndexed { index, segment ->
                if (index > activeSegmentIndex) return@forEachIndexed
                val start = segment.start.coerceIn(0, length)
                val end = segment.endExclusive.coerceIn(start, length)
                if (end <= start) return@forEachIndexed
                setSpan(
                    ForegroundColorSpan(if (index == activeSegmentIndex) accentColor else completedColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (index == activeSegmentIndex) {
                    setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
    }

    private fun buildLayout(
        text: CharSequence,
        active: Boolean,
        textSizeSp: Float,
        maxLines: Int
    ): StaticLayout {
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active && text !is SpannableString) accentColor else Color.WHITE
            textSize = textSizeSp * scaledDensity
            typeface = Typeface.create("sans-serif-condensed", if (active) Typeface.BOLD else Typeface.NORMAL)
            if (!karaoKastPerformanceMode) {
                setShadowLayer(4f * density, 0f, density, Color.argb(210, 0, 0, 0))
            }
        }
        val availableWidth = (width - sidePadding * 2f).toInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(density, 1f)
            .setMaxLines(maxLines)
            .build()
    }

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, top: Float, alpha: Int) {
        val saveCount = canvas.save()
        val previousAlpha = layout.paint.alpha
        layout.paint.alpha = alpha
        canvas.translate((width - layout.width) / 2f, top)
        layout.draw(canvas)
        layout.paint.alpha = previousAlpha
        canvas.restoreToCount(saveCount)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clearLayouts()
    }

    private fun clearLayouts() {
        cachedActiveIndex = Int.MIN_VALUE
        cachedSegmentIndex = Int.MIN_VALUE
        cachedWidth = -1
        currentLayout = null
        nextLayout = null
    }

    private fun currentPositionMs(): Long {
        if (!playing) return basePositionMs
        val elapsed = (SystemClock.elapsedRealtime() - baseRealtimeMs).coerceAtLeast(0L)
        return basePositionMs + (elapsed * playbackSpeed).roundToLong()
    }

    private fun smoothRenderPosition(rawPositionMs: Long): Long {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (renderedPositionMs.isNaN() || lastFrameRealtimeNs == 0L || !playing) {
            renderedPositionMs = rawPositionMs.toFloat()
            lastFrameRealtimeNs = nowNs
            return rawPositionMs
        }
        val frameDeltaMs = ((nowNs - lastFrameRealtimeNs) / 1_000_000f).coerceIn(0f, 50f)
        lastFrameRealtimeNs = nowNs
        val predicted = renderedPositionMs + frameDeltaMs * playbackSpeed
        val error = rawPositionMs - predicted
        renderedPositionMs = if (abs(error) >= SEEK_SNAP_THRESHOLD_MS) {
            rawPositionMs.toFloat()
        } else {
            val correction = 1f - exp(-frameDeltaMs / POSITION_CORRECTION_TAU_MS)
            predicted + error * correction
        }
        return renderedPositionMs.roundToLong()
    }

    private fun preciseStandardLinePosition(smoothedPositionMs: Long, rawPositionMs: Long): Long {
        val rawLeadMs = rawPositionMs - smoothedPositionMs
        if (rawLeadMs <= MAX_STANDARD_LINE_LAG_MS) return smoothedPositionMs

        // Une différence trop importante correspond généralement à un échantillon irrégulier,
        // à un seek ou à une transition d'état : dans ce cas l'horloge stable reste prioritaire.
        if (rawLeadMs > MAX_TRUSTED_CONTROLLER_LEAD_MS) return smoothedPositionMs

        // La coloration d'une ligne standard ne peut ainsi jamais rester plus de quelques
        // dizaines de millisecondes derrière une avance crédible du lecteur.
        return rawPositionMs - MAX_STANDARD_LINE_LAG_MS
    }

    private fun activeLineIndex(positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var result = 0
        val adjusted = positionMs + LYRICS_LOOKAHEAD_MS
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= adjusted) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun activeEnhancedSegmentIndex(lineIndex: Int, positionMs: Long): Int {
        val segments = lines.getOrNull(lineIndex)?.segments.orEmpty()
        if (segments.isEmpty()) return -1
        val adjusted = positionMs + LYRICS_LOOKAHEAD_MS
        var low = 0
        var high = segments.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (segments[mid].timeMs <= adjusted) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private companion object {
        const val LYRICS_LOOKAHEAD_MS = 180L
        const val SEEK_SNAP_THRESHOLD_MS = 700f
        const val POSITION_CORRECTION_TAU_MS = 115f
        const val MAX_STANDARD_LINE_LAG_MS = 55L
        const val MAX_TRUSTED_CONTROLLER_LEAD_MS = 240L
        const val KARAO_KAST_FRAME_DELAY_MS = 33L
    }
}
