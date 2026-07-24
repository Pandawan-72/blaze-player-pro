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
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Paroles LRC du mode karaoké paysage.
 *
 * Le texte défile continuellement du bas vers le haut, à la manière d'un générique de fin. La
 * position cible reste calculée depuis les time codes LRC, mais la position visuelle suit cette
 * cible avec une vitesse et une accélération plafonnées. Les intervalles très courts ne peuvent
 * donc plus provoquer de déplacement brutal d'une ligne entière.
 *
 * Les Enhanced LRC colorent la ligne active mot par mot ou syllabe par syllabe.
 */
class KaraokeLyricsView @JvmOverloads constructor(
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
    private var karaoKastPerformanceMode: Boolean = false

    private var renderedPositionMs: Float = Float.NaN
    private var lastPositionFrameRealtimeNs: Long = 0L

    /** Géométrie fixe des lignes : elle ne change jamais lors du passage à la ligne suivante. */
    private var lineCenters = FloatArray(0)
    private var lineHeights = IntArray(0)
    private var geometryWidth = -1

    /** Position verticale réellement affichée dans le contenu complet des paroles. */
    private var renderedContentY = Float.NaN
    private var previousTargetContentY = Float.NaN
    private var lastScrollFrameRealtimeNs: Long = 0L
    private var forceScrollSnap = true

    private var cachedActiveIndex: Int = -1
    private var cachedEnhancedSegmentIndex: Int = Int.MIN_VALUE
    private val layoutCache = mutableMapOf<Int, StaticLayout>()

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val horizontalPadding = 18f * density
    private val lineGap = 22f * density

    fun setLyrics(value: List<AudioLocalEnhancements.LyricLine>) {
        lines = value.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }
        cachedActiveIndex = -1
        cachedEnhancedSegmentIndex = Int.MIN_VALUE
        renderedPositionMs = Float.NaN
        lastPositionFrameRealtimeNs = 0L
        clearGeometryAndScrollState()
        contentDescription = lines.firstOrNull()?.text.orEmpty()
        invalidate()
    }

    fun setAccentColor(color: Int) {
        accentColor = color
        layoutCache.clear()
        invalidate()
    }

    fun setKaraoKastPerformanceMode(enabled: Boolean) {
        if (karaoKastPerformanceMode == enabled) return
        karaoKastPerformanceMode = enabled
        layoutCache.clear()
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
        val safeSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        val stateChanged = playing != isPlaying

        // Le MediaController est rééchantillonné régulièrement par le Fragment. Sur certains
        // appareils, deux échantillons consécutifs peuvent légèrement reculer pendant une
        // transition de buffer ou une resynchronisation de session. On compare donc la nouvelle
        // mesure à notre horloge monotone avant de déplacer l'ancre : les petites oscillations
        // sont absorbées, tandis qu'un vrai seek ou une grosse dérive recale immédiatement.
        val predictedBeforeAnchor = if (playing) {
            val elapsed = (now - baseRealtimeMs).coerceAtLeast(0L)
            basePositionMs + (elapsed * playbackSpeed).roundToLong()
        } else {
            basePositionMs
        }
        val anchorErrorMs = safePosition - predictedBeforeAnchor
        val largeCorrection = stateChanged ||
            renderedPositionMs.isNaN() ||
            abs(anchorErrorMs) >= SEEK_SNAP_THRESHOLD_MS

        val anchoredPosition = when {
            largeCorrection || !isPlaying -> safePosition
            anchorErrorMs in -BACKWARD_JITTER_TOLERANCE_MS until 0L -> predictedBeforeAnchor
            anchorErrorMs < -BACKWARD_JITTER_TOLERANCE_MS ->
                predictedBeforeAnchor + anchorErrorMs.coerceAtLeast(-MAX_BACKWARD_ANCHOR_CORRECTION_MS)
            else -> safePosition
        }.coerceAtLeast(0L)

        basePositionMs = anchoredPosition
        baseRealtimeMs = now
        playbackSpeed = safeSpeed
        playing = isPlaying

        if (!isPlaying || stateChanged || largeCorrection || renderedPositionMs.isNaN()) {
            renderedPositionMs = anchoredPosition.toFloat()
            lastPositionFrameRealtimeNs = SystemClock.elapsedRealtimeNanos()
        }
        if (largeCorrection || renderedContentY.isNaN()) {
            forceScrollSnap = true
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty() || width <= 0 || height <= 0) return

        ensureGeometry()
        if (lineCenters.isEmpty()) return

        val positionMs = smoothRenderPosition(currentPositionMs())
        val activeIndex = activeLineIndex(positionMs)
        val enhancedSegmentIndex = activeEnhancedSegmentIndex(activeIndex, positionMs)
        if (cachedActiveIndex != activeIndex) {
            cachedActiveIndex = activeIndex
            cachedEnhancedSegmentIndex = enhancedSegmentIndex
            layoutCache.clear()
        } else if (cachedEnhancedSegmentIndex != enhancedSegmentIndex) {
            cachedEnhancedSegmentIndex = enhancedSegmentIndex
            layoutCache.remove(activeIndex)
        }

        val targetContentY = targetContentPosition(activeIndex, positionMs)
        val visibleContentY = smoothContentPosition(targetContentY)
        val visualCenterIndex = nearestLineIndex(visibleContentY)
        val start = minOf(visualCenterIndex, activeIndex).minus(9).coerceAtLeast(0)
        val end = maxOf(visualCenterIndex, activeIndex).plus(11).coerceAtMost(lines.lastIndex)

        val layouts = linkedMapOf<Int, StaticLayout>()
        for (index in start..end) {
            layouts[index] = layoutCache.getOrPut(index) {
                buildLineLayout(index, activeIndex, enhancedSegmentIndex)
            }
        }

        val anchorY = height * ACTIVE_LINE_ANCHOR
        val translationY = anchorY - visibleContentY
        val fadeZone = (height * EDGE_FADE_PORTION).coerceAtLeast(1f)

        for (index in start..end) {
            val layout = layouts[index] ?: continue
            val centerY = lineCenters[index] + translationY
            val top = centerY - layout.height / 2f
            val bottom = centerY + layout.height / 2f
            if (bottom < 0f || top > height) continue

            val topFactor = bottom.div(fadeZone).coerceIn(0f, 1f)
            val bottomFactor = (height - top).div(fadeZone).coerceIn(0f, 1f)
            val edgeAlpha = min(topFactor, bottomFactor)
            val baseAlpha = if (index == activeIndex) 1f else 0.76f
            val alpha = (255f * edgeAlpha * baseAlpha).toInt().coerceIn(0, 255)
            if (alpha > 3) drawScrollingLine(canvas, layout, centerY, alpha)
        }

        contentDescription = lines.getOrNull(activeIndex)?.text.orEmpty()
        if (playing && isShown) {
            if (karaoKastPerformanceMode) postInvalidateDelayed(KARAO_KAST_FRAME_DELAY_MS)
            else postInvalidateOnAnimation()
        }
    }

    private fun drawScrollingLine(
        canvas: Canvas,
        layout: StaticLayout,
        centerY: Float,
        alpha: Int
    ) {
        val saveCount = canvas.save()
        val previousAlpha = layout.paint.alpha
        layout.paint.alpha = alpha
        canvas.translate((width - layout.width) / 2f, centerY - layout.height / 2f)
        layout.draw(canvas)
        layout.paint.alpha = previousAlpha
        canvas.restoreToCount(saveCount)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clearGeometryAndScrollState()
    }

    private fun currentPositionMs(): Long {
        if (!playing) return basePositionMs
        val elapsed = (SystemClock.elapsedRealtime() - baseRealtimeMs).coerceAtLeast(0L)
        return basePositionMs + (elapsed * playbackSpeed).roundToLong()
    }

    private fun smoothRenderPosition(rawPositionMs: Long): Long {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (renderedPositionMs.isNaN() || lastPositionFrameRealtimeNs == 0L || !playing) {
            renderedPositionMs = rawPositionMs.toFloat()
            lastPositionFrameRealtimeNs = nowNs
            return rawPositionMs
        }

        val frameDeltaMs = ((nowNs - lastPositionFrameRealtimeNs) / 1_000_000f).coerceIn(0f, 50f)
        lastPositionFrameRealtimeNs = nowNs
        val predicted = renderedPositionMs + frameDeltaMs * playbackSpeed
        val error = rawPositionMs - predicted
        renderedPositionMs = if (abs(error) >= SEEK_SNAP_THRESHOLD_MS) {
            forceScrollSnap = true
            rawPositionMs.toFloat()
        } else {
            val correction = 1f - exp(-frameDeltaMs / POSITION_CORRECTION_TAU_MS)
            predicted + error * correction
        }
        return renderedPositionMs.roundToLong()
    }

    /**
     * Suit la position LRC avec un mouvement amorti. La vitesse cible tient compte de la vitesse
     * réelle des time codes, mais elle est plafonnée pour éviter qu'un intervalle très court ne
     * fasse traverser une ligne entière en une seule image.
     */
    private fun smoothContentPosition(targetContentY: Float): Float {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (
            forceScrollSnap ||
            renderedContentY.isNaN() ||
            previousTargetContentY.isNaN() ||
            lastScrollFrameRealtimeNs == 0L ||
            !playing
        ) {
            renderedContentY = targetContentY
            previousTargetContentY = targetContentY
            lastScrollFrameRealtimeNs = nowNs
            forceScrollSnap = false
            return renderedContentY
        }

        val rawFrameDeltaSeconds = (nowNs - lastScrollFrameRealtimeNs) / 1_000_000_000f
        val frameDeltaSeconds = rawFrameDeltaSeconds.coerceIn(1f / 240f, 0.12f)
        lastScrollFrameRealtimeNs = nowNs
        previousTargetContentY = targetContentY

        val positionError = targetContentY - renderedContentY
        val emergencyDistance = maxOf(
            EMERGENCY_CATCHUP_DISTANCE_DP * density,
            lineHeights.getOrNull(cachedActiveIndex.coerceAtLeast(0))?.toFloat()?.times(1.35f) ?: 0f
        )

        // La cible est déjà une interpolation temporelle douce entre deux timecodes LRC. Ajouter
        // par-dessus une limite de vitesse créait du retard dès que deux lignes étaient proches.
        // On suit donc directement cette cible avec un amortissement court et dépendant de l'erreur.
        // Après une frame réellement longue (GC, rotation, surcharge UI), on se recale sans tenter
        // de rejouer visuellement le temps perdu.
        if (rawFrameDeltaSeconds >= LONG_FRAME_SNAP_SECONDS || abs(positionError) >= emergencyDistance) {
            renderedContentY = targetContentY
            return renderedContentY
        }

        val normalizedError = (abs(positionError) / (CATCHUP_REFERENCE_DISTANCE_DP * density))
            .coerceIn(0f, 1f)
        val tauSeconds = SCROLL_FOLLOW_TAU_SECONDS -
            (SCROLL_FOLLOW_TAU_SECONDS - SCROLL_CATCHUP_TAU_SECONDS) * normalizedError
        val follow = 1f - exp(-frameDeltaSeconds / tauSeconds)
        renderedContentY += positionError * follow

        if (abs(targetContentY - renderedContentY) <= SNAP_TO_TARGET_DISTANCE_DP * density) {
            renderedContentY = targetContentY
        }
        return renderedContentY
    }

    private fun targetContentPosition(activeIndex: Int, positionMs: Long): Float {
        val currentCenter = lineCenters[activeIndex]
        val nextIndex = (activeIndex + 1).coerceAtMost(lines.lastIndex)
        if (nextIndex == activeIndex) return currentCenter

        val currentTime = lines[activeIndex].timeMs
        val nextTime = lines[nextIndex].timeMs
        if (nextTime <= currentTime) return currentCenter

        val rawProgress = ((positionMs - currentTime).toFloat() / (nextTime - currentTime).toFloat())
            .coerceIn(0f, 1f)

        // Courbe douce avec pente nulle aux raccords. Le filtre de vitesse ci-dessus assure ensuite
        // le mouvement continu même lorsque deux lignes possèdent des time codes très rapprochés.
        val easedProgress = rawProgress * rawProgress * (3f - 2f * rawProgress)
        return currentCenter + (lineCenters[nextIndex] - currentCenter) * easedProgress
    }

    private fun ensureGeometry() {
        val availableWidth = (width - horizontalPadding * 2f).toInt().coerceAtLeast(1)
        if (
            geometryWidth == availableWidth &&
            lineCenters.size == lines.size &&
            lineHeights.size == lines.size
        ) return

        geometryWidth = availableWidth
        lineCenters = FloatArray(lines.size)
        lineHeights = IntArray(lines.size)

        for (index in lines.indices) {
            lineHeights[index] = buildGeometryLayout(lines[index].text, availableWidth).height
        }
        if (lines.isNotEmpty()) {
            lineCenters[0] = lineHeights[0] / 2f
            for (index in 1 until lines.size) {
                lineCenters[index] = lineCenters[index - 1] +
                    (lineHeights[index - 1] + lineHeights[index]) / 2f + lineGap
            }
        }

        layoutCache.clear()
        forceScrollSnap = true
    }

    private fun clearGeometryAndScrollState() {
        layoutCache.clear()
        lineCenters = FloatArray(0)
        lineHeights = IntArray(0)
        geometryWidth = -1
        renderedContentY = Float.NaN
        previousTargetContentY = Float.NaN
        lastScrollFrameRealtimeNs = 0L
        forceScrollSnap = true
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

    private fun nearestLineIndex(contentY: Float): Int {
        var low = 0
        var high = lineCenters.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lineCenters[mid] < contentY) low = mid + 1 else high = mid - 1
        }
        val upper = low.coerceIn(0, lineCenters.lastIndex)
        val lower = (upper - 1).coerceAtLeast(0)
        return if (
            abs(lineCenters[upper] - contentY) < abs(lineCenters[lower] - contentY)
        ) upper else lower
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

    private fun buildLineLayout(
        index: Int,
        activeIndex: Int,
        enhancedSegmentIndex: Int
    ): StaticLayout {
        val active = index == activeIndex
        val line = lines[index]
        val paint = createStableTextPaint().apply {
            color = if (active && line.segments.isEmpty()) accentColor else Color.WHITE
        }

        val displayText: CharSequence = if (active && line.segments.isNotEmpty()) {
            SpannableString(line.text).apply {
                if (enhancedSegmentIndex >= 0) {
                    val completedColor = Color.argb(
                        205,
                        Color.red(accentColor),
                        Color.green(accentColor),
                        Color.blue(accentColor)
                    )
                    line.segments.forEachIndexed { segmentIndex, segment ->
                        if (segmentIndex > enhancedSegmentIndex) return@forEachIndexed
                        val start = segment.start.coerceIn(0, length)
                        val end = segment.endExclusive.coerceIn(start, length)
                        if (end <= start) return@forEachIndexed
                        setSpan(
                            ForegroundColorSpan(
                                if (segmentIndex == enhancedSegmentIndex) accentColor else completedColor
                            ),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }
        } else {
            line.text
        }

        val availableWidth = (width - horizontalPadding * 2f).toInt().coerceAtLeast(1)
        return buildStaticLayout(displayText, paint, availableWidth)
    }

    private fun buildGeometryLayout(text: CharSequence, availableWidth: Int): StaticLayout =
        buildStaticLayout(text, createStableTextPaint(), availableWidth)

    private fun createStableTextPaint(): TextPaint =
        TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 255
            textSize = KARAOKE_TEXT_SIZE_SP * scaledDensity
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            if (!karaoKastPerformanceMode) {
                setShadowLayer(6f * density, 0f, 2f * density, Color.argb(215, 0, 0, 0))
            }
        }

    private fun buildStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        availableWidth: Int
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(3f * density, 1f)
            .setMaxLines(3)
            .build()

    private companion object {
        const val ACTIVE_LINE_ANCHOR = 0.58f
        const val EDGE_FADE_PORTION = 0.20f
        const val KARAOKE_TEXT_SIZE_SP = 27f
        const val LYRICS_LOOKAHEAD_MS = 180L
        const val SEEK_SNAP_THRESHOLD_MS = 520f
        const val POSITION_CORRECTION_TAU_MS = 70f
        const val BACKWARD_JITTER_TOLERANCE_MS = 45L
        const val MAX_BACKWARD_ANCHOR_CORRECTION_MS = 90L

        /** Suivi court : la position visuelle reste liée au timecode au lieu d'accumuler du retard. */
        const val SCROLL_FOLLOW_TAU_SECONDS = 0.052f
        const val SCROLL_CATCHUP_TAU_SECONDS = 0.022f
        const val CATCHUP_REFERENCE_DISTANCE_DP = 72f
        const val EMERGENCY_CATCHUP_DISTANCE_DP = 120f
        const val LONG_FRAME_SNAP_SECONDS = 0.095f
        const val SNAP_TO_TARGET_DISTANCE_DP = 0.25f
        const val KARAO_KAST_FRAME_DELAY_MS = 33L
    }
}
