package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.ImageView
import fr.retrospare.blazeplayer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ThumbnailUtils {

    private val audioExtensions = setOf(
        "mp3","flac","aac","ogg","opus","wav","m4a","wma","ape","dts","ac3","mka"
    )

    suspend fun loadThumbnail(
        context: Context,
        path: String,
        imageView: ImageView,
        timeUs: Long = 1_000_000L
    ) = withContext(Dispatchers.IO) {
        try {
            if (path.startsWith("smb://") || path.startsWith("ftp://")) return@withContext

            val ext = path.substringAfterLast('.', "").lowercase()
            val isAudio = ext in audioExtensions

            if (isAudio) {
                // Fichier audio — on charge la cover de l'album
                val bitmap = try {
                    if (path.startsWith("content://")) {
                        val id = path.substringAfterLast("/").toLongOrNull()
                        if (id != null) {
                            val albumUri = Uri.parse("content://media/external/audio/media/$id/albumart")
                            context.contentResolver.openInputStream(albumUri)?.use {
                                BitmapFactory.decodeStream(it)
                            }
                        } else null
                    } else {
                        MediaMetadataRetriever().use { r ->
                            r.setDataSource(path)
                            r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                    }
                } catch (e: Exception) { null }

                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                        imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        imageView.setBackgroundColor(0x00000000)
                    } else {
                        // Pas de cover — note de musique verte
                        imageView.setImageResource(R.drawable.ic_music_note_large)
                        imageView.scaleType = android.widget.ImageView.ScaleType.CENTER
                        imageView.setBackgroundColor(0xFF1A1D2E.toInt())
                    }
                }
            } else {
                // Fichier vidéo — frame vidéo
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(path))
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                bitmap?.let {
                    withContext(Dispatchers.Main) {
                        imageView.setImageBitmap(it)
                        imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    }
                }
            }
        } catch (e: Exception) {
            // Garde le placeholder
        }
    }
}
