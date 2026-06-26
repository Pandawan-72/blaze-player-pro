package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.ImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ThumbnailUtils {

    suspend fun loadThumbnail(
        context: Context,
        path: String,
        imageView: ImageView,
        timeUs: Long = 1_000_000L
    ) = withContext(Dispatchers.IO) {
        try {
            val retriever = MediaMetadataRetriever()
            if (path.startsWith("smb://") || path.startsWith("ftp://")) {
                // Réseau — on ne peut pas extraire facilement, on laisse le placeholder
                return@withContext
            }
            retriever.setDataSource(context, Uri.parse(path))
            val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            bitmap?.let {
                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(it)
                    imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                }
            }
        } catch (e: Exception) {
            // Garde le placeholder en cas d'erreur
        }
    }
}
