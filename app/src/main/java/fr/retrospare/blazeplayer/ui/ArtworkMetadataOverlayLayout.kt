package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Overlay dégradé en bas de la cover audio (titre / artiste).
 *
 * La vue couvre toute la cover mais ne dessine un dégradé sombre que sur sa
 * partie basse. Seuls les coins BAS sont arrondis, avec le même radius que la
 * cover, pour prolonger exactement son bord bas. Les coins HAUT restent droits.
 *
 * Ne force pas le rendu logiciel : certains artworks chargés par les image loaders
 * sont des hardware bitmaps, incompatibles avec un Canvas logiciel.
 */
class ArtworkMetadataOverlayLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Radius des coins bas, en dp. Doit correspondre au radius de la cover. */
    var radiusDp: Float = 0f
        set(value) {
            field = value
            rebuild(width, height)
            invalidate()
        }

    /** Fraction de la hauteur à partir de laquelle le dégradé démarre (0f à 1f). */
    var gradientStartFraction: Float = 0.58f
        set(value) {
            field = value
            rebuild(width, height)
            invalidate()
        }

    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val rect = RectF()
    private val gradientRect = RectF()
    private var shader: LinearGradient? = null

    init {
        setWillNotDraw(false)
        background = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild(w, h)
    }

    private fun rebuild(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val r = radiusDp * resources.displayMetrics.density

        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        // Ordre attendu par Path.addRoundRect(rect, radii, dir) : haut-gauche,
        // haut-droit, bas-droit, bas-gauche (paires rx,ry). Coins hauts à 0
        // (angle droit, comme le haut de la cover), coins bas au radius de la cover.
        val radii = floatArrayOf(
            0f, 0f,
            0f, 0f,
            r, r,
            r, r
        )
        clipPath.addRoundRect(rect, radii, Path.Direction.CW)
        clipPath.close()

        val top = h * gradientStartFraction
        gradientRect.set(0f, top, w.toFloat(), h.toFloat())
        shader = LinearGradient(
            0f, top, 0f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.argb(178, 0, 0, 0), Color.argb(252, 0, 0, 0)),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(clipPath)
        gradientPaint.shader = shader
        canvas.drawRect(gradientRect, gradientPaint)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(clipPath)
        super.draw(canvas)
        canvas.restoreToCount(save)
    }
}
