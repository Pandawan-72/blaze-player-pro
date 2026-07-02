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
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.random.Random

object ThumbnailUtils {

    private val audioExtensions = setOf(
        "mp3","flac","aac","ogg","opus","wav","m4a","wma","ape","dts","ac3","mka"
    )

    // Cache mémoire (RAM) LRU limité à 15MB — sert les miniatures déjà vues pendant cette session,
    // évite même le passage par le disque tant que l'app tourne.
    private val cache = object : LruCache<String, Bitmap>(15 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Cache DISQUE persistant (survit aux redémarrages de l'app) : c'est lui qui évite de
    // devoir rouvrir une connexion SMB et re-décoder une frame vidéo / une pochette audio à
    // chaque fois qu'on revient sur l'accueil ou qu'on rouvre l'app — particulièrement lent sur
    // fichiers réseau. Plafonné en taille avec une purge des plus anciens fichiers au besoin.
    private const val DISK_CACHE_DIR_NAME = "thumb_cache"
    private const val DISK_CACHE_MAX_BYTES = 300L * 1024 * 1024 // 300 Mo
    private const val DISK_CACHE_JPEG_QUALITY = 85

    private fun diskCacheDir(context: Context): File =
        File(context.cacheDir, DISK_CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun keyFor(path: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(path.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun diskFileFor(context: Context, path: String): File =
        File(diskCacheDir(context), keyFor(path) + ".jpg")

    private fun readFromDisk(context: Context, path: String): Bitmap? {
        val file = diskFileFor(context, path)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)?.also {
                // Touche le fichier pour que la purge LRU (basée sur lastModified) le considère
                // comme récemment utilisé et ne le supprime pas en priorité.
                file.setLastModified(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeToDisk(context: Context, path: String, bitmap: Bitmap) {
        try {
            val file = diskFileFor(context, path)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_JPEG_QUALITY, out)
            }
            // Purge occasionnelle (pas à chaque écriture, pour ne pas lister le dossier en
            // permanence) : ~1 écriture sur 20 déclenche une vérification de la taille totale.
            if (Random.nextInt(20) == 0) pruneDiskCacheIfNeeded(context)
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to write disk thumbnail cache", e)
        }
    }

    private fun pruneDiskCacheIfNeeded(context: Context) {
        try {
            val dir = diskCacheDir(context)
            val files = dir.listFiles() ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= DISK_CACHE_MAX_BYTES) return
            // Supprime les plus anciens (lastModified) en premier jusqu'à repasser sous la limite.
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (totalSize <= DISK_CACHE_MAX_BYTES) return
                totalSize -= f.length()
                f.delete()
            }
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Disk cache prune failed", e)
        }
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
        timeUs: Long = 30_000_000L
    ) = withContext(Dispatchers.IO) {
        try {
            // 1. Cache mémoire (le plus rapide, RAM)
            cache.get(path)?.let { cached ->
                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(cached)
                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                }
                return@withContext
            }

            // 2. Cache disque (rapide, local, pas de reseau/SMB requis)
            readFromDisk(context, path)?.let { fromDisk ->
                cache.put(path, fromDisk)
                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(fromDisk)
                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                    imageView.setBackgroundColor(0x00000000)
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
                    } else if (path.startsWith("smb://")) {
                        // Pochette embarquée d'un fichier audio réseau : nécessite le DataSource SMB
                        // dédié (MediaMetadataRetriever#setDataSource(String) ne comprend pas smb://).
                        var smbDataSourceAudio: fr.retrospare.blazeplayer.player.SmbMediaDataSource? = null
                        try {
                            smbDataSourceAudio = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
                            MediaMetadataRetriever().use { r ->
                                r.setDataSource(smbDataSourceAudio)
                                r.embeddedPicture?.let {
                                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                                    BitmapFactory.decodeByteArray(it, 0, it.size, opts)
                                }
                            }
                        } finally {
                            try { smbDataSourceAudio?.close() } catch (_: Exception) {}
                        }
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

                if (bitmap != null) {
                    val scaled = scaleBitmap(bitmap, 128)
                    cache.put(path, scaled)
                    writeToDisk(context, path, scaled)
                    withContext(Dispatchers.Main) {
                        imageView.setImageBitmap(scaled)
                        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        imageView.setBackgroundColor(0x00000000)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        imageView.setImageResource(R.drawable.ic_music_note_large)
                        imageView.scaleType = ImageView.ScaleType.CENTER
                        imageView.setBackgroundColor(0xFF1A1D2E.toInt())
                    }
                }
            } else {
                // Vidéo - réduit la résolution d'extraction
                val retriever = MediaMetadataRetriever()
                var smbDataSource: fr.retrospare.blazeplayer.player.SmbMediaDataSource? = null
                try {
                    if (path.startsWith("smb://")) {
                        smbDataSource = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
                        retriever.setDataSource(smbDataSource)
                    } else {
                        retriever.setDataSource(context, Uri.parse(path))
                    }
                    var bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap == null) {
                        bitmap = retriever.frameAtTime
                    }
                    bitmap?.let {
                        val scaled = scaleBitmap(it, 160)
                        cache.put(path, scaled)
                        writeToDisk(context, path, scaled)
                        withContext(Dispatchers.Main) {
                            imageView.setImageBitmap(scaled)
                            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                    }
                } finally {
                    retriever.release()
                    try { smbDataSource?.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ThumbnailUtils", "Failed to load thumbnail for $path", e)
        }
    }

    fun clearCache() = cache.evictAll()

    /** Vide aussi le cache disque persistant (ex: bouton "Vider le cache" dans les réglages). */
    fun clearDiskCache(context: Context) {
        try {
            diskCacheDir(context).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to clear disk thumbnail cache", e)
        }
    }
}
