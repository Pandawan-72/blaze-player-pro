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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
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
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val networkThumbnailSemaphore = Semaphore(2)

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

    /** Coeur de l'extraction de miniature : mémoire → disque → extraction réelle, cache écrit
     *  au passage. Retourne le bitmap brut plutôt que de l'appliquer à une vue — utilisé à la
     *  fois par [loadThumbnail] (pour l'UI) et par tout appelant ayant besoin du bitmap lui-même
     *  (ex : artwork de la notification de lecture). */
    suspend fun getThumbnailBitmap(
        context: Context,
        path: String,
        timeUs: Long = if (path.startsWith("smb://")) 10_000_000L else 5_000_000L
    ): Bitmap? = coroutineScope {
        // Cache mémoire immédiat avant de passer sur IO.
        cache.get(path)?.let { return@coroutineScope it }

        // Déduplique les demandes simultanées causées par RecyclerView + notification.
        inFlight[path]?.let { return@coroutineScope it.await() }

        val deferred = async(Dispatchers.IO) {
            withTimeoutOrNull(if (path.startsWith("smb://")) 3_500L else 6_000L) {
                if (path.startsWith("smb://")) {
                    networkThumbnailSemaphore.withPermit {
                        extractThumbnailInternal(context.applicationContext, path, timeUs)
                    }
                } else {
                    extractThumbnailInternal(context.applicationContext, path, timeUs)
                }
            } ?: run {
                android.util.Log.w("ThumbnailUtils", "Thumbnail timeout for $path")
                null
            }
        }
        inFlight[path] = deferred
        try {
            deferred.await()
        } finally {
            inFlight.remove(path, deferred)
        }
    }

    private fun extractThumbnailInternal(context: Context, path: String, timeUs: Long): Bitmap? {
        return try {
            cache.get(path)?.let { return it }

            readFromDisk(context, path)?.let { fromDisk ->
                cache.put(path, fromDisk)
                return fromDisk
            }

            val ext = path.substringAfterLast('.', "").lowercase()
            val isAudio = ext in audioExtensions

            if (isAudio) {
                val bitmap = try {
                    when {
                        path.startsWith("content://") -> {
                            val id = path.substringAfterLast("/").toLongOrNull()
                            if (id != null) {
                                val albumUri = Uri.parse("content://media/external/audio/media/$id/albumart")
                                context.contentResolver.openInputStream(albumUri)?.use {
                                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                                    BitmapFactory.decodeStream(it, null, opts)
                                }
                            } else null
                        }
                        path.startsWith("smb://") -> {
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
                        }
                        else -> MediaMetadataRetriever().use { r ->
                            r.setDataSource(path)
                            r.embeddedPicture?.let {
                                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                                BitmapFactory.decodeByteArray(it, 0, it.size, opts)
                            }
                        }
                    }
                } catch (_: Exception) { null }

                bitmap?.let {
                    val scaled = scaleBitmap(it, 128)
                    cache.put(path, scaled)
                    writeToDisk(context, path, scaled)
                    scaled
                }
            } else {
                // Réseau SMB : frame à 10s + sync frame. Une miniature à 30s force
                // souvent des seeks profonds et lents dans les longs MKV/MP4 réseau.
                val retriever = MediaMetadataRetriever()
                var smbDataSource: fr.retrospare.blazeplayer.player.SmbMediaDataSource? = null
                try {
                    if (path.startsWith("smb://")) {
                        smbDataSource = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
                        retriever.setDataSource(smbDataSource)
                    } else {
                        retriever.setDataSource(context, Uri.parse(path))
                    }
                    val option = if (path.startsWith("smb://")) MediaMetadataRetriever.OPTION_CLOSEST_SYNC else MediaMetadataRetriever.OPTION_CLOSEST
                    var bitmap = retriever.getFrameAtTime(timeUs, option)
                    if (bitmap == null && !path.startsWith("smb://")) bitmap = retriever.frameAtTime
                    bitmap?.let {
                        val scaled = scaleBitmap(it, if (path.startsWith("smb://")) 144 else 160)
                        cache.put(path, scaled)
                        writeToDisk(context, path, scaled)
                        scaled
                    }
                } finally {
                    retriever.release()
                    try { smbDataSource?.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ThumbnailUtils", "Failed to get thumbnail bitmap for $path", e)
            null
        }
    }

    suspend fun loadThumbnail(
        context: Context,
        path: String,
        imageView: ImageView,
        timeUs: Long = if (path.startsWith("smb://")) 10_000_000L else 5_000_000L
    ) {
        imageView.setTag(R.id.ivThumbnail, path)
        val bitmap = getThumbnailBitmap(context, path, timeUs)
        val ext = path.substringAfterLast('.', "").lowercase()
        val isAudio = ext in audioExtensions
        withContext(Dispatchers.Main) {
            if (imageView.getTag(R.id.ivThumbnail) != path) return@withContext
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                imageView.setBackgroundColor(0x00000000)
            } else if (isAudio) {
                imageView.setImageResource(R.drawable.ic_music_note_large)
                imageView.scaleType = ImageView.ScaleType.CENTER
                imageView.setBackgroundColor(0xFF1A1D2E.toInt())
            }
            // Vidéo sans miniature : laisse le placeholder XML existant, comme avant.
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
