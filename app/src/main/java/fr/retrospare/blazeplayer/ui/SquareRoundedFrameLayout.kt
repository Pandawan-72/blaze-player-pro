package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Conteneur strictement carré sans aucun arrondi ni masque d'outline.
 *
 * Le nom historique est conservé pour ne pas casser les layouts/ViewBinding existants, mais
 * cette vue n'hérite volontairement plus de [RoundedFrameLayout]. Le lecteur audio doit afficher
 * la pochette bord à bord avec quatre angles droits, y compris après une restauration de vue ou
 * lorsqu'un ancien outline a été appliqué par le thème.
 */
class SquareRoundedFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Compatibilité avec les anciens appels Kotlin. Toute valeur est volontairement ignorée. */
    var radiusDp: Float = 0f
        set(@Suppress("UNUSED_PARAMETER") value) {
            field = 0f
            removeAnyOutlineClipping()
        }

    init {
        removeAnyOutlineClipping()
    }

    private fun removeAnyOutlineClipping() {
        clipToOutline = false
        outlineProvider = null
        elevation = 0f
        translationZ = 0f
        invalidateOutline()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Certains thèmes/rendus restaurent un outline après l'inflation : on le neutralise aussi
        // au rattachement afin que les coins ne puissent jamais redevenir arrondis.
        removeAnyOutlineClipping()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        val size = measuredWidth
        setMeasuredDimension(size, size)
    }
}
