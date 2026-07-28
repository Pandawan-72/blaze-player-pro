package fr.retrospare.blazeplayer.ui

import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.chip.Chip
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ajuste les textes de boutons aux dimensions réellement disponibles.
 *
 * Ordre de repli :
 * 1. AutoSize entre la taille normale et 7 sp sur deux lignes ;
 * 2. réduction des paddings et troisième ligne autorisée ;
 * 3. hauteur fixe libérée pour devenir WRAP_CONTENT ;
 * 4. légère condensation horizontale en dernier recours.
 *
 * L'ellipsize reste toujours désactivée : aucun libellé ne peut finir par « … ».
 */
object ButtonTextFitter {

    private const val TAG = "ButtonTextFitter"
    private const val GLOBAL_MIN_SP = 7
    private const val EMERGENCY_MIN_SP = 6
    private const val LAST_RESORT_MIN_SP = 5
    private const val FIXED_TEXT_TAG = "skip_button_text_fitter"
    private const val NORMAL_MAX_LINES = 2
    private const val EMERGENCY_MAX_LINES = 3

    private data class FitState(
        var maximumTextSp: Int,
        val originalHeight: Int,
        var stage: Int = 0,
        var repairPosted: Boolean = false
    )

    private val states = WeakHashMap<TextView, FitState>()

    fun fit(view: TextView, minSp: Int = GLOBAL_MIN_SP, maxSp: Int = 16) {
        if (shouldSkip(view)) return
        if (view is EditText || view.text.isNullOrBlank()) return

        val density = view.resources.displayMetrics.scaledDensity.takeIf { it > 0f } ?: 1f
        val currentSp = ceil(view.textSize / density).toInt().coerceAtLeast(GLOBAL_MIN_SP)
        val effectiveMaximum = max(maxSp, currentSp).coerceAtMost(24)
        val effectiveMinimum = min(GLOBAL_MIN_SP, minSp).coerceAtMost(effectiveMaximum)

        val existing = states[view]
        if (existing != null) {
            existing.maximumTextSp = max(existing.maximumTextSp, effectiveMaximum)
            if (!configureBaseSafely(view, effectiveMinimum, existing.maximumTextSp)) return
            scheduleRepair(view, existing)
            return
        }

        val state = FitState(
            maximumTextSp = effectiveMaximum,
            originalHeight = view.layoutParams?.height ?: 0
        )
        states[view] = state

        if (!configureBaseSafely(view, effectiveMinimum, effectiveMaximum)) {
            states.remove(view)
            return
        }

        view.addOnLayoutChangeListener {
                changed,
                _,
                _,
                _,
                _,
                _,
                _,
                _,
                _ ->
            val textView = changed as? TextView ?: return@addOnLayoutChangeListener
            states[textView]?.let { scheduleRepair(textView, it) }
        }

        view.post { scheduleRepair(view, state) }
    }

    fun fitRecursively(
        root: View,
        minSp: Int = GLOBAL_MIN_SP,
        maxSp: Int = 16,
        excludedViewIds: Set<Int> = emptySet()
    ) {
        if (root.tag == FIXED_TEXT_TAG) return
        // Material Chip impose strictement une seule ligne et lève une
        // UnsupportedOperationException si un fitter tente de le passer en multiligne.
        if (root is Chip) return
        if (root.id in excludedViewIds) return

        if (root is TextView && isButtonText(root)) {
            fit(root, minSp, maxSp)
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                fitRecursively(root.getChildAt(index), minSp, maxSp, excludedViewIds)
            }
        }
    }

    fun isButtonText(textView: TextView): Boolean {
        if (shouldSkip(textView)) return false
        if (textView is EditText || textView.text.isNullOrBlank()) return false

        val text = textView.text.toString().trim()
        if (text.length == 1 && !text[0].isLetterOrDigit()) return false

        if (
            textView is Button ||
            textView is CompoundButton ||
            textView.isClickable ||
            textView.hasOnClickListeners() ||
            hasButtonRoleId(textView)
        ) {
            return true
        }

        // Les boutons Blaze sont souvent un conteneur cliquable avec un TextView enfant.
        var parent = textView.parent
        var depth = 0
        while (parent is View && depth < 4) {
            if (
                parent.isClickable ||
                parent.hasOnClickListeners() ||
                hasButtonRoleId(parent)
            ) {
                return true
            }
            parent = parent.parent
            depth++
        }
        return false
    }

    private fun shouldSkip(view: TextView): Boolean =
        view.tag == FIXED_TEXT_TAG ||
            view is Chip

    /**
     * Le fitter est appliqué globalement à des vues ajoutées dynamiquement.
     * Une vue spécialisée ne doit jamais pouvoir faire tomber toute l'application.
     */
    private fun configureBaseSafely(view: TextView, minSp: Int, maxSp: Int): Boolean =
        try {
            configureBase(view, minSp, maxSp)
            true
        } catch (error: UnsupportedOperationException) {
            states.remove(view)
            Log.w(
                TAG,
                "Text fitting ignored for unsupported ${view.javaClass.name}",
                error
            )
            false
        }

    private fun configureBase(view: TextView, minSp: Int, maxSp: Int) {
        view.ellipsize = null
        view.setHorizontallyScrolling(false)
        view.isSingleLine = false
        view.minLines = 1
        view.maxLines = NORMAL_MAX_LINES
        view.includeFontPadding = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            view.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NORMAL
        }

        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            view,
            minSp,
            max(minSp, maxSp),
            1,
            TypedValue.COMPLEX_UNIT_SP
        )
    }

    private fun scheduleRepair(view: TextView, state: FitState) {
        if (state.repairPosted || !view.isAttachedToWindow) return
        state.repairPosted = true
        view.post {
            state.repairPosted = false
            repairIfNeeded(view, state)
        }
    }

    private fun repairIfNeeded(view: TextView, state: FitState) {
        if (!isClipped(view)) return

        when (state.stage) {
            0 -> {
                state.stage = 1
                val compactPadding = dp(view, 4)
                view.setPaddingRelative(
                    min(view.paddingStart, compactPadding),
                    view.paddingTop,
                    min(view.paddingEnd, compactPadding),
                    view.paddingBottom
                )
                view.maxLines = EMERGENCY_MAX_LINES
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    view.letterSpacing = 0f
                }
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    view,
                    min(EMERGENCY_MIN_SP, state.maximumTextSp),
                    state.maximumTextSp,
                    1,
                    TypedValue.COMPLEX_UNIT_SP
                )
                view.requestLayout()
                scheduleRepair(view, state)
            }

            1 -> {
                state.stage = 2
                val params = view.layoutParams
                if (
                    params != null &&
                    params.height > 0 &&
                    params.height != ViewGroup.LayoutParams.MATCH_PARENT
                ) {
                    view.minimumHeight = max(
                        view.minimumHeight,
                        max(state.originalHeight, view.measuredHeight)
                    )
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    view.layoutParams = params
                }
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    view,
                    min(LAST_RESORT_MIN_SP, state.maximumTextSp),
                    state.maximumTextSp,
                    1,
                    TypedValue.COMPLEX_UNIT_SP
                )
                view.requestLayout()
                scheduleRepair(view, state)
            }

            else -> {
                state.stage = 3
                view.textScaleX = min(view.textScaleX, 0.88f)
                view.maxLines = EMERGENCY_MAX_LINES
                view.ellipsize = null
                view.requestLayout()
            }
        }
    }

    private fun isClipped(view: TextView): Boolean {
        val rawText = view.text?.toString().orEmpty()
        if (rawText.isEmpty() || view.width <= 0 || view.height <= 0) return false

        val layout = view.layout ?: return false
        if (layout.lineCount <= 0) return false

        val lastVisibleLine = min(layout.lineCount, view.maxLines) - 1
        if (lastVisibleLine < 0) return false

        val visibleEnd = layout.getLineEnd(lastVisibleLine)
        val textLength = rawText.trimEnd().length
        val incompleteText = visibleEnd < textLength
        val excessiveLines = layout.lineCount > view.maxLines

        val availableHeight = (
            view.height - view.paddingTop - view.paddingBottom
        ).coerceAtLeast(0)
        val verticalOverflow = layout.height > availableHeight + dp(view, 1)

        return incompleteText || excessiveLines || verticalOverflow
    }

    private fun hasButtonRoleId(view: View): Boolean {
        if (view.id == View.NO_ID) return false
        val name = runCatching {
            view.resources.getResourceEntryName(view.id)
        }.getOrNull()?.lowercase() ?: return false

        return name.startsWith("btn") ||
            name.contains("button") ||
            name.startsWith("action") ||
            name.startsWith("tab") ||
            name.contains("chip") ||
            name.contains("toggle") ||
            name.contains("filter") ||
            name.contains("sort")
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).roundToInt()
}
