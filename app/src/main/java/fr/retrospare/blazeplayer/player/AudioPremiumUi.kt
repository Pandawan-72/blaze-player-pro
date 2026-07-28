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
        if (!prefs.getBoolean(AudioProSettings.KEY_DYNAMIC_THEME, true)) return blazeGreen

        val persistedAccent = context.applicationContext
            .getSharedPreferences(DYNAMIC_AUDIO_PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DYNAMIC_ACCENT, AudioDynamicColor.DEFAULT_ACCENT)
            .let(AudioDynamicColor::ensureReadableAccent)

        // Un écran premium ouvert pendant la lecture réutilise la couleur déjà persistée. Il ne
        // recompresse jamais une pochette sur le thread UI au risque de perturber les contrôles.
        if (AudioLibraryWorkState.isPlaybackProtected()) return persistedAccent

        return runCatching {
            val state = AudioRepository.loadState(context)
            val item = state.items.getOrNull(state.index) ?: return@runCatching persistedAccent
            // Le résolveur connaît aussi bien le chemin d'une cover externe que le JPEG persistant
            // issu d'une image embarquée. AudioMediaCache reste un repli de compatibilité.
            val bytes = AudioArtworkResolver.cachedJpegBytes(
                context,
                item.path,
                item.artworkPath
            ) ?: AudioMediaCache.getCachedArtworkJpegBytes(context, item.path)
                ?: return@runCatching persistedAccent
            AudioDynamicColor.accentFromArtworkBytes(bytes) ?: persistedAccent
        }.getOrDefault(persistedAccent)
    }

    fun applyDynamicHero(view: View?, accent: Int) {
        view ?: return
        val safeAccent = AudioDynamicColor.ensureReadableAccent(accent)
        val top = AudioDynamicColor.mix(Color.rgb(12, 22, 34), safeAccent, 0.48f)
        val middle = AudioDynamicColor.mix(Color.rgb(12, 18, 32), safeAccent, 0.22f)
        val bottom = Color.rgb(5, 8, 18)
        val stroke = withAlpha(safeAccent, 0x9A)

        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, middle, bottom)).apply {
            cornerRadius = view.resources.displayMetrics.density * 26f
            setStroke((view.resources.displayMetrics.density * 0.9f).coerceAtLeast(1f).toInt(), stroke)
        }
        val gloss = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x22FFFFFF, withAlpha(safeAccent, 0x10), 0x00000000)).apply {
            cornerRadius = view.resources.displayMetrics.density * 25f
        }
        val layers = LayerDrawable(arrayOf(base, gloss))
        layers.setLayerInset(1, 1, 1, 1, 1)
        view.background = layers
    }

    fun applyDynamicSolidHero(view: View?, accent: Int) {
        view ?: return
        val safeAccent = AudioDynamicColor.ensureReadableAccent(accent)
        val solidBackground = AudioDynamicColor.backgroundFromAccent(safeAccent)
        val stroke = withAlpha(safeAccent, 0x9A)

        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = view.resources.displayMetrics.density * 26f
            setColor(solidBackground)
            setStroke((view.resources.displayMetrics.density * 0.9f).coerceAtLeast(1f).toInt(), stroke)
        }
    }

    private const val DYNAMIC_AUDIO_PREFS = "blaze_audio_dynamic_colors"
    private const val KEY_DYNAMIC_ACCENT = "dynamic_accent"

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
