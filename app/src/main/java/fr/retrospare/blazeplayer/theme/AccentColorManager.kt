package fr.retrospare.blazeplayer.theme

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import fr.retrospare.blazeplayer.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Source unique de la couleur d'accentuation de Blaze Player.
 *
 * SharedPreferences est volontairement utilisé ici plutôt que DataStore : le thème doit être lu
 * de façon synchrone AVANT super.onCreate(), sinon la première frame serait affichée en vert puis
 * recolorée après coup. Le vert Blaze reste la valeur par défaut pour les installations existantes.
 */
object AccentColorManager {
    private const val PREFS_NAME = "blaze_appearance"
    private const val KEY_ACCENT = "accent_color"

    enum class Option(
        val key: String,
        @StringRes val labelRes: Int,
        @ColorRes val colorRes: Int,
        @ColorRes val strokeRes: Int,
        @StyleRes val themeRes: Int,
        @StyleRes val fullscreenThemeRes: Int
    ) {
        BLAZE_GREEN(
            "green", R.string.accent_color_green, R.color.accent_green,
            R.color.accent_green_stroke, R.style.Theme_BlazePlayer,
            R.style.Theme_BlazePlayer_Fullscreen
        ),
        OCEAN_BLUE(
            "blue", R.string.accent_color_blue, R.color.accent_blue,
            R.color.accent_blue_stroke, R.style.Theme_BlazePlayer_AccentBlue,
            R.style.Theme_BlazePlayer_Fullscreen_AccentBlue
        ),
        VIOLET(
            "purple", R.string.accent_color_purple, R.color.accent_purple,
            R.color.accent_purple_stroke, R.style.Theme_BlazePlayer_AccentPurple,
            R.style.Theme_BlazePlayer_Fullscreen_AccentPurple
        ),
        PINK(
            "pink", R.string.accent_color_pink, R.color.accent_pink,
            R.color.accent_pink_stroke, R.style.Theme_BlazePlayer_AccentPink,
            R.style.Theme_BlazePlayer_Fullscreen_AccentPink
        ),
        ORANGE(
            "orange", R.string.accent_color_orange, R.color.accent_orange,
            R.color.accent_orange_stroke, R.style.Theme_BlazePlayer_AccentOrange,
            R.style.Theme_BlazePlayer_Fullscreen_AccentOrange
        ),
        RED(
            "red", R.string.accent_color_red, R.color.accent_red,
            R.color.accent_red_stroke, R.style.Theme_BlazePlayer_AccentRed,
            R.style.Theme_BlazePlayer_Fullscreen_AccentRed
        ),
        TURQUOISE(
            "turquoise", R.string.accent_color_turquoise, R.color.accent_turquoise,
            R.color.accent_turquoise_stroke, R.style.Theme_BlazePlayer_AccentTurquoise,
            R.style.Theme_BlazePlayer_Fullscreen_AccentTurquoise
        ),
        GOLD(
            "gold", R.string.accent_color_gold, R.color.accent_gold,
            R.color.accent_gold_stroke, R.style.Theme_BlazePlayer_AccentGold,
            R.style.Theme_BlazePlayer_Fullscreen_AccentGold
        )
    }

    fun current(context: Context): Option {
        val key = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACCENT, Option.BLAZE_GREEN.key)
        return Option.values().firstOrNull { it.key == key } ?: Option.BLAZE_GREEN
    }

    /** Écriture synchrone : l'activité peut être recréée juste après sans relire l'ancienne teinte. */
    fun set(context: Context, option: Option): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCENT, option.key)
            .commit()

    @StyleRes
    fun normalTheme(context: Context): Int = current(context).themeRes

    @StyleRes
    fun fullscreenTheme(context: Context): Int = current(context).fullscreenThemeRes

    @ColorInt
    fun accent(context: Context): Int = ContextCompat.getColor(context, current(context).colorRes)

    @ColorInt
    fun accentStroke(context: Context): Int = ContextCompat.getColor(context, current(context).strokeRes)

    /**
     * Rapproche automatiquement la teinte turquoise du logo de la couleur d'accentuation choisie,
     * sans l'aplatir avec un simple tint : les dégradés, le relief, le blanc et les noirs du PNG
     * restent intacts. Le logo original n'est jamais modifié sur disque.
     */
    fun applyLogoHue(imageView: ImageView) {
        val option = current(imageView.context)
        if (option == Option.BLAZE_GREEN) {
            imageView.clearColorFilter()
            return
        }

        val targetColor = ContextCompat.getColor(imageView.context, option.colorRes)
        val targetHsv = FloatArray(3)
        Color.colorToHSV(targetColor, targetHsv)

        // Teinte dominante réelle du logo PNG, légèrement plus turquoise que le vert Blaze UI.
        val logoBaseHue = 168f
        var delta = targetHsv[0] - logoBaseHue
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f

        val radians = Math.toRadians(delta.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val matrix = ColorMatrix(
            floatArrayOf(
                0.213f + c * 0.787f - s * 0.213f,
                0.715f - c * 0.715f - s * 0.715f,
                0.072f - c * 0.072f + s * 0.928f,
                0f, 0f,

                0.213f - c * 0.213f + s * 0.143f,
                0.715f + c * 0.285f + s * 0.140f,
                0.072f - c * 0.072f - s * 0.283f,
                0f, 0f,

                0.213f - c * 0.213f - s * 0.787f,
                0.715f - c * 0.715f + s * 0.715f,
                0.072f + c * 0.928f + s * 0.072f,
                0f, 0f,

                0f, 0f, 0f, 1f, 0f
            )
        )
        imageView.colorFilter = ColorMatrixColorFilter(matrix)
    }
}
