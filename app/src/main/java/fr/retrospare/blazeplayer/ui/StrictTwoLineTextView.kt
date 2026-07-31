package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.text.Layout
import android.text.StaticLayout
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * TextView utilisé pour les noms de fichiers vidéo.
 *
 * `maxLines=2` avec `ellipsize=end` ne suffit pas toujours lorsque la largeur utile change pendant
 * le recyclage d'une carte (bouton de sélection, menu, enrichissement des métadonnées). Cette vue
 * tronque donc elle-même la chaîne avant de l'afficher. Le texte réellement remis au moteur de
 * rendu tient forcément sur deux lignes au maximum : une troisième ligne est impossible, même
 * pendant une nouvelle mesure de RecyclerView.
 */
class StrictTwoLineTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var sourceText: String = ""
    private var displayedText: String = ""
    private var lastAvailableWidth: Int = -1
    private var lastAppliedSourceText: String = ""
    private var applyPosted = false

    init {
        isSingleLine = false
        setHorizontallyScrolling(false)
        maxLines = MAX_TITLE_LINES
        minLines = MAX_TITLE_LINES
        ellipsize = null // La troncature est faite manuellement et de manière déterministe.
        includeFontPadding = false
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    }

    fun setStrictText(value: CharSequence?) {
        val normalized = value
            ?.toString()
            .orEmpty()
            .replace(WHITESPACE_REGEX, " ")
            .trim()

        contentDescription = normalized
        if (sourceText == normalized && width > 0) {
            applyStrictText()
            return
        }

        sourceText = normalized
        applyStrictText()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) applyStrictText()
    }

    private fun applyStrictText() {
        val availableWidth = width - compoundPaddingLeft - compoundPaddingRight
        if (availableWidth <= 0) {
            if (!applyPosted) {
                applyPosted = true
                post {
                    applyPosted = false
                    applyStrictText()
                }
            }
            return
        }

        if (availableWidth == lastAvailableWidth && lastAppliedSourceText == sourceText) return

        val fitted = fitToTwoLines(sourceText, availableWidth)
        lastAvailableWidth = availableWidth
        lastAppliedSourceText = sourceText
        if (displayedText != fitted) {
            displayedText = fitted
            super.setText(fitted, BufferType.NORMAL)
        }
    }

    private fun fitToTwoLines(value: String, availableWidth: Int): String {
        if (value.isEmpty()) return value
        if (lineCount(value, availableWidth) <= MAX_TITLE_LINES) return value

        var low = 0
        var high = value.length
        var best = ELLIPSIS

        while (low <= high) {
            val middle = (low + high) ushr 1
            val prefix = safePrefix(value, middle).trimEnd()
            val candidate = if (prefix.isEmpty()) ELLIPSIS else prefix + ELLIPSIS

            if (lineCount(candidate, availableWidth) <= MAX_TITLE_LINES) {
                best = candidate
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        return best
    }

    private fun safePrefix(value: String, requestedEnd: Int): String {
        var end = requestedEnd.coerceIn(0, value.length)
        if (end in 1 until value.length &&
            Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) {
            end -= 1
        }
        return value.substring(0, end)
    }

    private fun lineCount(value: String, availableWidth: Int): Int =
        StaticLayout.Builder.obtain(value, 0, value.length, paint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
            .lineCount

    companion object {
        private const val MAX_TITLE_LINES = 2
        private const val ELLIPSIS = "..."
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
