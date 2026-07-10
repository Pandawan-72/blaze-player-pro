package fr.retrospare.blazeplayer.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.core.content.ContextCompat
import fr.retrospare.blazeplayer.R

/** UI helpers shared by Blaze Audio premium screens. */
object AudioPremiumUi {

    fun resolveAccentColor(context: Context): Int {
        val blazeGreen = ContextCompat.getColor(context, R.color.green_accent)
        val prefs = AudioProSettings.prefs(context)
        if (!prefs.getBoolean(AudioProSettings.KEY_DYNAMIC_THEME, false)) return blazeGreen
        return runCatching {
            val state = AudioRepository.loadState(context)
            val item = state.items.getOrNull(state.index) ?: return@runCatching blazeGreen
            val bytes = fr.retrospare.blazeplayer.ui.ThumbnailUtils.getCachedAudioArtworkJpegBytes(context, item.path)
                ?: return@runCatching blazeGreen
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@runCatching blazeGreen
            AudioDynamicColor.accentFromBitmap(bitmap)
        }.getOrDefault(blazeGreen)
    }

    fun applyDynamicHero(view: View?, accent: Int) {
        view ?: return
        val top = AudioDynamicColor.mix(Color.rgb(12, 22, 34), accent, 0.48f)
        val middle = AudioDynamicColor.mix(Color.rgb(12, 18, 32), accent, 0.22f)
        val bottom = Color.rgb(5, 8, 18)
        val stroke = withAlpha(accent, 0x9A)

        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, middle, bottom)).apply {
            cornerRadius = view.resources.displayMetrics.density * 26f
            setStroke((view.resources.displayMetrics.density * 0.9f).coerceAtLeast(1f).toInt(), stroke)
        }
        val gloss = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x22FFFFFF, withAlpha(accent, 0x10), 0x00000000)).apply {
            cornerRadius = view.resources.displayMetrics.density * 25f
        }
        val layers = LayerDrawable(arrayOf(base, gloss))
        layers.setLayerInset(1, 1, 1, 1, 1)
        view.background = layers
    }

    fun applyDynamicSolidHero(view: View?, accent: Int) {
        view ?: return
        val solidBackground = AudioDynamicColor.backgroundFromAccent(accent)
        val stroke = withAlpha(accent, 0x9A)

        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = view.resources.displayMetrics.density * 26f
            setColor(solidBackground)
            setStroke((view.resources.displayMetrics.density * 0.9f).coerceAtLeast(1f).toInt(), stroke)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
