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
        resolution: String? = null,
        videoCodec: String? = null,
        audioCodec: String? = null,
        fullExtract: Boolean = false
    ) {
        val view = TextView(context).apply {
            text = buildBaseMessage(context, mediaPath, extension, itemSizeBytes, itemDurationSeconds, resolution, videoCodec, audioCodec)
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
        // Codec vidéo/audio : lignes optionnelles, ajoutées seulement quand l'info est disponible
        // (ex. navigateur réseau avant extraction complète) plutôt que d'afficher une ligne vide.
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
}
