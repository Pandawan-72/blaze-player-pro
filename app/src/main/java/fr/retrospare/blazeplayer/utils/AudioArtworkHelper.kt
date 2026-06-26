package fr.retrospare.blazeplayer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

object AudioArtworkHelper {

    private val cache = LinkedHashMap<String, Bitmap?>(50, 0.75f, true)

    fun getArtwork(context: Context, path: String): Bitmap? {
        if (cache.containsKey(path)) return cache[path]

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
                android.media.MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(path)
                    retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            }
        } catch (e: Exception) { null }

        cache[path] = bitmap
        return bitmap
    }

    fun clearCache() = cache.clear()
}
