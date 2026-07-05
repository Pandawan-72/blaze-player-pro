package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.util.AttributeSet

/**
 * Variante de RoundedFrameLayout qui force sa hauteur à égaler sa largeur mesurée, directement
 * dans onMeasure — sans passer par un `view.post { ... }` exécuté à chaque bind RecyclerView
 * (ancienne technique utilisée dans Blaze Gallery pour les tuiles de dossiers). Cette ancienne
 * technique forçait une deuxième passe de layout après l'affichage initial de chaque tuile,
 * provoquant un redimensionnement visible ("saut") pendant le scroll et du travail de mise en
 * page superflu à chaque recyclage de vue. Ici la vue est carrée dès la première mesure, sans
 * travail additionnel ni délai.
 */
class SquareRoundedFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RoundedFrameLayout(context, attrs, defStyleAttr) {

    init {
        radiusDp = 0f
        clipToOutline = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
