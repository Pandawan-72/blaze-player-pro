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

    private fun extensionOf(path: String): String =
        path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()

    private fun isAudioPath(path: String): Boolean = extensionOf(path) in audioExtensions

    private fun isNetworkVideoPath(path: String): Boolean =
        path.startsWith("smb://", true) || path.startsWith("http://", true) || path.startsWith("https://", true)

    private fun defaultVideoFrameTimeUs(path: String): Long = 10_000_000L

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
    private const val DISK_CACHE_JPEG_QUALITY = 95

    private fun diskCacheDir(context: Context): File =
        File(context.cacheDir, DISK_CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun keyFor(path: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(path.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun diskFileFor(context: Context, path: String): File =
        File(diskCacheDir(context), keyFor(path) + ".jpg")

    private fun deleteFromDisk(context: Context, path: String) {
        try { diskFileFor(context, path).delete() } catch (_: Exception) {}
    }

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


    private fun setRetrieverDataSource(context: Context, retriever: MediaMetadataRetriever, path: String): AutoCloseable? {
        return when {
            path.startsWith("smb://", true) -> {
                val smbDataSource = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
                retriever.setDataSource(smbDataSource)
                smbDataSource
            }
            path.startsWith("http://", true) || path.startsWith("https://", true) -> {
                retriever.setDataSource(path, emptyMap())
                null
            }
            path.startsWith("content://", true) -> {
                val uri = Uri.parse(path)
                try {
                    // Le chemin SAF le plus compatible. Pour les fournisseurs SAF distants,
                    // le provider peut streamer derrière ce file descriptor sans exposer de chemin local.
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        retriever.setDataSource(pfd.fileDescriptor)
                        pfd
                    } else {
                        retriever.setDataSource(context, uri)
                        null
                    }
                } catch (_: Exception) {
                    retriever.setDataSource(context, uri)
                    null
                }
            }
            path.startsWith("file://", true) -> {
                retriever.setDataSource(context, Uri.parse(path))
                null
            }
            else -> {
                retriever.setDataSource(path)
                null
            }
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


    // Caches séparés audio/vidéo : évite qu'une miniature vidéo (même chemin logique,
    // ancien cache Vxx ou artworkUri Cast) soit réutilisée comme pochette audio.
    private fun audioKey(path: String): String = "audio-hires-v2:$path"
    private fun videoKey(path: String): String = "video:$path"
    private fun customVideoKey(path: String): String = "custom-video-thumb:$path"
    // Versionne le cache des frames vidéo pour forcer une vraie extraction à 10s
    // après mise à jour, au lieu de réutiliser d'anciennes miniatures à 1s/5s
    // ou des artworks DLNA mis en cache sous la même URL.
    private fun thumbnailKey(path: String): String = if (isAudioPath(path)) path else "video-frame-10s-v2:$path"

    fun getCachedAudioArtworkJpegBytes(context: Context, path: String): ByteArray? {
        val key = audioKey(path)
        val bitmap = cache.get(key) ?: readFromDisk(context.applicationContext, key)?.also { cache.put(key, it) } ?: return null
        return try {
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_JPEG_QUALITY, out)
                out.toByteArray()
            }
        } catch (_: Exception) { null }
    }

    fun cacheAudioArtworkData(context: Context, path: String, artworkData: ByteArray?) {
        if (artworkData == null || artworkData.isEmpty()) return
        try {
            val bitmap = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size) ?: return
            val scaled = scaleBitmap(bitmap, 1024)
            val key = audioKey(path)
            cache.put(key, scaled)
            writeToDisk(context.applicationContext, key, scaled)
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to cache audio artwork for $path", e)
        }
    }

    suspend fun getAudioArtworkJpegBytes(context: Context, path: String): ByteArray? {
        getCachedAudioArtworkJpegBytes(context, path)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            withTimeoutOrNull(if (path.startsWith("smb://")) 4_000L else 6_000L) {
                extractAudioArtworkInternal(context.applicationContext, path)
            }
        } ?: return null
        return withContext(Dispatchers.IO) {
            try {
                java.io.ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_JPEG_QUALITY, out)
                    out.toByteArray()
                }
            } catch (_: Exception) { null }
        }
    }

    private fun extractAudioArtworkInternal(context: Context, path: String): Bitmap? {
        val key = audioKey(path)
        cache.get(key)?.let { return it }
        readFromDisk(context, key)?.let { cache.put(key, it); return it }
        val bitmap = try {
            when {
                path.startsWith("content://") -> MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, Uri.parse(path))
                    r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
                path.startsWith("smb://") -> {
                    var smbDataSourceAudio: fr.retrospare.blazeplayer.player.SmbMediaDataSource? = null
                    try {
                        smbDataSourceAudio = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
                        MediaMetadataRetriever().use { r ->
                            r.setDataSource(smbDataSourceAudio)
                            r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                    } finally {
                        try { smbDataSourceAudio?.close() } catch (_: Exception) {}
                    }
                }
                path.startsWith("http://") || path.startsWith("https://") -> MediaMetadataRetriever().use { r ->
                    r.setDataSource(path, emptyMap())
                    r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
                else -> MediaMetadataRetriever().use { r ->
                    r.setDataSource(path)
                    r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            }
        } catch (_: Exception) { null }
        return bitmap?.let {
            val scaled = scaleBitmap(it, 1024)
            cache.put(key, scaled)
            writeToDisk(context, key, scaled)
            scaled
        }
    }

    /** Retourne immédiatement une image déjà en cache mémoire/disque, sans extraction réseau. */
    fun getCachedThumbnailBitmap(context: Context, path: String): Bitmap? {
        if (!isAudioPath(path)) {
            val customKey = customVideoKey(path)
            cache.get(customKey)?.let { return it }
            readFromDisk(context.applicationContext, customKey)?.let {
                cache.put(customKey, it)
                return it
            }
        }
        val key = thumbnailKey(path)
        cache.get(key)?.let { return it }
        return readFromDisk(context.applicationContext, key)?.also { cache.put(key, it) }
    }

    fun hasCustomVideoThumbnail(context: Context, path: String): Boolean {
        if (isAudioPath(path)) return false
        val key = customVideoKey(path)
        return cache.get(key) != null || diskFileFor(context.applicationContext, key).exists()
    }

    fun setCustomVideoThumbnail(context: Context, videoPath: String, imageUri: Uri): Boolean {
        if (isAudioPath(videoPath)) return false
        return try {
            val bitmap = context.contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return false
            val scaled = scaleBitmap(bitmap, 1024)
            val key = customVideoKey(videoPath)
            cache.put(key, scaled)
            writeToDisk(context.applicationContext, key, scaled)
            true
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to set custom video thumbnail for $videoPath", e)
            false
        }
    }

    fun deleteCustomVideoThumbnail(context: Context, videoPath: String) {
        val key = customVideoKey(videoPath)
        cache.remove(key)
        deleteFromDisk(context.applicationContext, key)
    }

    /** Retourne une pochette déjà cachée en JPEG, prête à être remise dans MediaMetadata. */
    fun getCachedThumbnailJpegBytes(context: Context, path: String): ByteArray? {
        val bitmap = getCachedThumbnailBitmap(context, path) ?: return null
        return try {
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_JPEG_QUALITY, out)
                out.toByteArray()
            }
        } catch (_: Exception) { null }
    }

    /** Met en cache disque/RAM une pochette extraite ailleurs, pour survivre aux fermetures. */
    fun cacheArtworkData(context: Context, path: String, artworkData: ByteArray?) {
        if (artworkData == null || artworkData.isEmpty()) return
        try {
            val bitmap = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size) ?: return
            val scaled = scaleBitmap(bitmap, 1024)
            val key = thumbnailKey(path)
            cache.put(key, scaled)
            writeToDisk(context.applicationContext, key, scaled)
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to cache artwork for $path", e)
        }
    }

    /** Extraction avec retour bytes pour réhydrater le player/mini-player après relance app. */
    suspend fun getThumbnailJpegBytes(
        context: Context,
        path: String,
        timeUs: Long = defaultVideoFrameTimeUs(path)
    ): ByteArray? {
        val bitmap = getThumbnailBitmap(context, path, timeUs) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                java.io.ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_JPEG_QUALITY, out)
                    out.toByteArray()
                }
            } catch (_: Exception) { null }
        }
    }

    /** Coeur de l'extraction de miniature : mémoire → disque → extraction réelle, cache écrit
     *  au passage. Retourne le bitmap brut plutôt que de l'appliquer à une vue — utilisé à la
     *  fois par [loadThumbnail] (pour l'UI) et par tout appelant ayant besoin du bitmap lui-même
     *  (ex : artwork de la notification de lecture). */
    suspend fun getThumbnailBitmap(
        context: Context,
        path: String,
        timeUs: Long = defaultVideoFrameTimeUs(path)
    ): Bitmap? = coroutineScope {
        if (!isAudioPath(path)) {
            val customKey = customVideoKey(path)
            cache.get(customKey)?.let { return@coroutineScope it }
            readFromDisk(context.applicationContext, customKey)?.let { custom ->
                cache.put(customKey, custom)
                return@coroutineScope custom
            }
        }

        val key = thumbnailKey(path)
        // Cache mémoire immédiat avant de passer sur IO.
        cache.get(key)?.let { return@coroutineScope it }

        // Déduplique les demandes simultanées causées par RecyclerView + notification.
        inFlight[key]?.let { return@coroutineScope it.await() }

        val deferred = async(Dispatchers.IO) {
            withTimeoutOrNull(if (isNetworkVideoPath(path)) 6_000L else 6_000L) {
                if (isNetworkVideoPath(path)) {
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
        inFlight[key] = deferred
        try {
            deferred.await()
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    private fun extractThumbnailInternal(context: Context, path: String, timeUs: Long): Bitmap? {
        return try {
            if (!isAudioPath(path)) {
                val customKey = customVideoKey(path)
                cache.get(customKey)?.let { return it }
                readFromDisk(context, customKey)?.let { custom ->
                    cache.put(customKey, custom)
                    return custom
                }
            }

            val key = thumbnailKey(path)
            cache.get(key)?.let { return it }

            readFromDisk(context, key)?.let { fromDisk ->
                cache.put(key, fromDisk)
                return fromDisk
            }

            val ext = extensionOf(path)
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
                        path.startsWith("http://", true) || path.startsWith("https://", true) -> MediaMetadataRetriever().use { r ->
                            r.setDataSource(path, emptyMap())
                            r.embeddedPicture?.let {
                                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                                BitmapFactory.decodeByteArray(it, 0, it.size, opts)
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
                    cache.put(key, scaled)
                    writeToDisk(context, key, scaled)
                    scaled
                }
            } else {
                // Frame vidéo à 10s, y compris pour les Uri SAF content:// provenant de fournisseurs distants.
                // On passe par openFileDescriptor en priorité : c'est plus fiable que setDataSource(context, uri)
                // pour certains conteneurs comme AVI exposés par des fournisseurs de fichiers distants.
                val retriever = MediaMetadataRetriever()
                var closeable: AutoCloseable? = null
                try {
                    closeable = setRetrieverDataSource(context, retriever, path)
                    val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.let { it * 1000L }
                    val targetUs = if (durationUs != null && durationUs in 1 until timeUs) {
                        (durationUs * 0.10f).toLong().coerceAtLeast(500_000L)
                    } else {
                        timeUs
                    }
                    var bitmap = retriever.getFrameAtTime(targetUs, option)
                    if (bitmap == null) bitmap = retriever.getFrameAtTime(10_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap == null) bitmap = retriever.getFrameAtTime(5_000_000L, option)
                    if (bitmap == null) bitmap = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap == null && !isNetworkVideoPath(path)) bitmap = retriever.frameAtTime
                    bitmap?.let {
                        val scaled = scaleBitmap(it, if (isNetworkVideoPath(path)) 144 else 160)
                        cache.put(key, scaled)
                        writeToDisk(context, key, scaled)
                        scaled
                    }
                } finally {
                    retriever.release()
                    try { closeable?.close() } catch (_: Exception) {}
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
        timeUs: Long = defaultVideoFrameTimeUs(path)
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
