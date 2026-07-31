package fr.retrospare.blazeplayer.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.text.format.Formatter
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import fr.retrospare.blazeplayer.R
import kotlinx.coroutines.CoroutineScope

/** Modal d'informations local uniquement : aucune recherche réseau ici. */
object VideoInfoDialog {
    fun show(
        context: Context,
        scope: CoroutineScope,
        title: String,
        mediaPath: String,
        displayName: String = title,
        extension: String = "",
        itemSizeBytes: Long = 0L,
        itemDurationSeconds: Long = 0L,
        resolution: String? = null,
        videoCodec: String? = null,
        audioCodec: String? = null,
        fullExtract: Boolean = false
    ) {
        val view = TextView(context).apply {
            text = buildBaseMessage(context, mediaPath, extension, itemSizeBytes, itemDurationSeconds, resolution, videoCodec, audioCodec)
            setTextIsSelectable(true)
            val pad = context.dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(context.getString(R.string.action_ok), null)
            .showPremium()
    }

    /**
     * Version volontairement concise pour le menu des miniatures de l'accueil : uniquement le
     * titre puis les quatre badges demandés. Aucun chemin, taille ou résolution ne surcharge la
     * fenêtre. La rangée de badges reste accessible sur les petits écrans grâce au scroll interne.
     */
    fun showHistorySummary(
        context: Context,
        displayName: String,
        extension: String,
        itemDurationSeconds: Long,
        videoCodec: String?,
        audioCodec: String?
    ) {
        val horizontalPadding = context.dp(20)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, context.dp(18), horizontalPadding, context.dp(4))
        }

        root.addView(TextView(context).apply {
            text = displayName
            maxLines = 2
            minLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(48)
        ))

        val badgeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(10), 0, context.dp(6))
        }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(badgeRow, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val cleanExtension = extension.trim().removePrefix(".").uppercase()
        badgeRow.addView(makeBadge(context, cleanExtension.ifBlank { "—" }, container = cleanExtension.isNotBlank()))
        badgeRow.addView(makeBadge(context, videoCodec?.trim().orEmpty().ifBlank { "—" }))
        badgeRow.addView(makeBadge(context, audioCodec?.trim().orEmpty().ifBlank { "—" }))
        badgeRow.addView(makeBadge(context, formatDuration(itemDurationSeconds)))

        AlertDialog.Builder(context)
            .setView(root)
            .setPositiveButton(context.getString(R.string.action_ok), null)
            .showPremium()
    }

    private fun makeBadge(context: Context, value: String, container: Boolean = false): TextView {
        return TextView(context).apply {
            text = value
            includeFontPadding = false
            textSize = 11f
            setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
            setTypeface(typeface, Typeface.BOLD)
            if (container) BadgeStyle.applyContainerBadge(this, value)
            else BadgeStyle.applyTechnicalBadge(this)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, context.dp(6), 0) }
        }
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0L) return "—"
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val remainingSeconds = seconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
        } else {
            "%d:%02d".format(minutes, remainingSeconds)
        }
    }

    private fun buildBaseMessage(
        context: Context,
        mediaPath: String,
        extension: String,
        itemSizeBytes: Long,
        itemDurationSeconds: Long,
        resolution: String?,
        videoCodec: String?,
        audioCodec: String?
    ): String {
        val sizeText = if (itemSizeBytes > 0) Formatter.formatShortFileSize(context, itemSizeBytes) else context.getString(R.string.unknown_size)
        val durationText = if (itemDurationSeconds > 0) "%d:%02d".format(itemDurationSeconds / 60, itemDurationSeconds % 60) else "N/A"
        val ext = extension.ifBlank { mediaPath.substringBefore('?').substringAfterLast('.', "").uppercase() }
        val resolutionText = resolution?.takeIf { it.isNotBlank() } ?: context.getString(R.string.unknown_generic)
        val base = context.getString(R.string.dialog_video_info_message, mediaPath, ext, resolutionText, durationText, sizeText)
        val codecLines = buildString {
            videoCodec?.takeIf { it.isNotBlank() }?.let {
                append('\n').append(context.getString(R.string.dialog_video_info_video_codec, it))
            }
            audioCodec?.takeIf { it.isNotBlank() }?.let {
                append('\n').append(context.getString(R.string.dialog_video_info_audio_codec, it))
            }
        }
        return base + codecLines
    }

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
