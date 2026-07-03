package fr.retrospare.blazeplayer.ui

import android.app.AlertDialog
import android.content.Context
import android.text.format.Formatter
import android.widget.TextView
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
        fullExtract: Boolean = false
    ) {
        val view = TextView(context).apply {
            text = buildBaseMessage(context, mediaPath, extension, itemSizeBytes, itemDurationSeconds)
            setTextIsSelectable(true)
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(context.getString(R.string.action_ok), null)
            .show()
    }

    private fun buildBaseMessage(
        context: Context,
        mediaPath: String,
        extension: String,
        itemSizeBytes: Long,
        itemDurationSeconds: Long
    ): String {
        val sizeText = if (itemSizeBytes > 0) Formatter.formatShortFileSize(context, itemSizeBytes) else context.getString(R.string.unknown_size)
        val durationText = if (itemDurationSeconds > 0) "%d:%02d".format(itemDurationSeconds / 60, itemDurationSeconds % 60) else "N/A"
        val ext = extension.ifBlank { mediaPath.substringBefore('?').substringAfterLast('.', "").uppercase() }
        return context.getString(R.string.dialog_video_info_message, mediaPath, ext, "N/A", durationText, sizeText)
    }
}
