package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import fr.retrospare.blazeplayer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ThumbnailUtils {

    private val audioExtensions = setOf(
        "mp3","flac","aac","ogg","opus","wav","m4a","wma","ape","dts","ac3","mka"
    )

    // Cache LRU limité à 20MB
    private val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int = 256): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSize && h <= maxSize) return bitmap
        val scale = maxSize.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
            .also { if (it != bitmap) bitmap.recycle() }
    }

    suspend fun loadThumbnail(
        context: Context,
        path: String,
        imageView: ImageView,
        timeUs: Long = 1_000_000L
    ) = withContext(Dispatchers.IO) {
        try {
            if (path.startsWith("smb://") || path.startsWith("ftp://")) return@withContext

            // Vérifie le cache d'abord
            cache.get(path)?.let { cached ->
                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(cached)
                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                }
                return@withContext
            }

            val ext = path.substringAfterLast('.', "").lowercase()
            val isAudio = ext in audioExtensions

            if (isAudio) {
                val bitmap = try {
                    if (path.startsWith("content://")) {
                        val id = path.substringAfterLast("/").toLongOrNull()
                        if (id != null) {
                            val albumUri = Uri.parse("content://media/external/audio/media/$id/albumart")
                            context.contentResolver.openInputStream(albumUri)?.use {
                                val opts = BitmapFactory.Options().apply {
                                    inSampleSize = 2 // réduit de moitié à la lecture
                                }
                                BitmapFactory.decodeStream(it, null, opts)
                            }
                        } else null
                    } else {
                        MediaMetadataRetriever().use { r ->
                            r.setDataSource(path)
                            r.embeddedPicture?.let {
                                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                                BitmapFactory.decodeByteArray(it, 0, it.size, opts)
                            }
                        }
                    }
                } catch (e: Exception) { null }

                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        val scaled = scaleBitmap(bitmap)
                        cache.put(path, scaled)
                        imageView.setImageBitmap(scaled)
                        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        imageView.setBackgroundColor(0x00000000)
                    } else {
                        imageView.setImageResource(R.drawable.ic_music_note_large)
                        imageView.scaleType = ImageView.ScaleType.CENTER
                        imageView.setBackgroundColor(0xFF1A1D2E.toInt())
                    }
                }
            } else {
                // Vidéo - réduit la résolution d'extraction
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(path))
                    val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    bitmap?.let {
                        val scaled = scaleBitmap(it, 320)
                        cache.put(path, scaled)
                        withContext(Dispatchers.Main) {
                            imageView.setImageBitmap(scaled)
                            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                    }
                } finally {
                    retriever.release()
                }
            }
        } catch (e: Exception) {
            // Garde le placeholder
        }
    }

    fun clearCache() = cache.evictAll()
}
