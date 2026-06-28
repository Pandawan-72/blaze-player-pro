package fr.retrospare.blazeplayer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache

object AudioArtworkHelper {

    // Cache limité à 8MB
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int = 128): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSize && h <= maxSize) return bitmap
        val scale = maxSize.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
            .also { if (it != bitmap) bitmap.recycle() }
    }

    fun getArtwork(context: Context, path: String): Bitmap? {
        cache.get(path)?.let { return it }

        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }

        val bitmap = try {
            if (path.startsWith("content://")) {
                val id = path.substringAfterLast("/").toLongOrNull()
                if (id != null) {
                    val albumUri = Uri.parse("content://media/external/audio/media/$id/albumart")
                    context.contentResolver.openInputStream(albumUri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                } else null
            } else {
                android.media.MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(path)
                    retriever.embeddedPicture?.let {
                        BitmapFactory.decodeByteArray(it, 0, it.size, opts)
                    }
                }
            }
        } catch (e: Exception) { null }

        val scaled = bitmap?.let { scaleBitmap(it) }
        if (scaled != null) cache.put(path, scaled)
        return scaled
    }

    fun clearCache() = cache.evictAll()
}
