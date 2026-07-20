package fr.retrospare.blazeplayer.gallery.slideshow

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.core.content.res.ResourcesCompat
import fr.retrospare.blazeplayer.R

/**
 * Six familles Google Fonts volontairement très différentes pour les annotations Diapo.
 * Les fontes sont demandées au provider Google Fonts de Google Play Services : aucun fichier de
 * police n'est embarqué dans l'APK. Un fallback système est toujours disponible hors ligne.
 */
object SlideshowFontCatalog {
    data class FontOption(
        val nameRes: Int,
        val fontRes: Int,
        val fallbackFamily: String,
        val fallbackStyle: Int = Typeface.NORMAL
    )

    val options = listOf(
        FontOption(R.string.slideshow_font_montserrat, R.font.slideshow_montserrat, "sans-serif", Typeface.BOLD),
        FontOption(R.string.slideshow_font_playfair, R.font.slideshow_playfair, "serif", Typeface.BOLD),
        FontOption(R.string.slideshow_font_pacifico, R.font.slideshow_pacifico, "cursive"),
        FontOption(R.string.slideshow_font_bebas, R.font.slideshow_bebas_neue, "sans-serif-condensed", Typeface.BOLD),
        FontOption(R.string.slideshow_font_caveat, R.font.slideshow_caveat, "cursive"),
        FontOption(R.string.slideshow_font_cinzel, R.font.slideshow_cinzel, "serif", Typeface.BOLD)
    )

    fun fallback(index: Int): Typeface {
        val option = options[index.coerceIn(0, options.lastIndex)]
        return Typeface.create(option.fallbackFamily, option.fallbackStyle)
    }

    fun getBlocking(context: Context, index: Int): Typeface {
        val safe = index.coerceIn(0, options.lastIndex)
        return runCatching { ResourcesCompat.getFont(context, options[safe].fontRes) }.getOrNull()
            ?: fallback(safe)
    }

    fun request(context: Context, index: Int, onReady: (Typeface) -> Unit) {
        val safe = index.coerceIn(0, options.lastIndex)
        val fallback = fallback(safe)
        runCatching {
            ResourcesCompat.getFont(
                context,
                options[safe].fontRes,
                object : ResourcesCompat.FontCallback() {
                    override fun onFontRetrieved(typeface: Typeface) = onReady(typeface)
                    override fun onFontRetrievalFailed(reason: Int) = onReady(fallback)
                },
                Handler(Looper.getMainLooper())
            )
        }.onFailure { onReady(fallback) }
    }
}
