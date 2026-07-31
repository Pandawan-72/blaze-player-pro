package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.os.Build
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Base64
import android.widget.ImageView
import fr.retrospare.blazeplayer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.random.Random

object ThumbnailUtils {

    private val audioExtensions = setOf(
        "mp3","flac","aac","ogg","oga","opus","wav","m4a","m4b","wma","ape",
        "dts","ac3","mka","wv","aiff","alac"
    )

    private val folderCoverImageExtensions = setOf("jpg", "jpeg", "png", "webp")
    private val preferredFolderCoverExtensions = listOf("jpg", "png", "jpeg", "webp")
    private val preferredFolderCoverBaseNames = listOf(
        "cover", "folder", "front", "poster", "default", "jacket",
        "album", "albumart", "album art", "artwork", "jaquette", "pochette"
    )
    private val fastExactCoverFileNames = preferredFolderCoverBaseNames.flatMap { base ->
        preferredFolderCoverExtensions.flatMap { ext ->
            val capitalized = base.replaceFirstChar { it.uppercaseChar() }
            listOf(
                "$base.$ext",
                "$capitalized.$ext",
                "${base.uppercase()}.$ext",
                "$base.${ext.uppercase()}",
                "$capitalized.${ext.uppercase()}",
                "${base.uppercase()}.${ext.uppercase()}"
            )
        }
    }.distinct()
    private const val MAX_FOLDER_COVER_IMAGE_BYTES = 32L * 1024L * 1024L
    private const val AUDIO_ARTWORK_MAX_PX = 512

    private fun safePathForLog(path: String): String =
        fr.retrospare.blazeplayer.player.SmbDataSource.redactForLog(path)

    private fun extensionOf(path: String): String =
        path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()

    private fun isAudioPath(path: String): Boolean = extensionOf(path) in audioExtensions

    private fun isFolderCoverImagePath(path: String): Boolean = extensionOf(path) in folderCoverImageExtensions

    private fun isAllowedAudioFolderCoverName(path: String): Boolean {
        val fileName = path.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .lowercase(Locale.ROOT)
        val extension = fileName.substringAfterLast('.', "")
        if (extension !in folderCoverImageExtensions) return false

        val base = fileName.substringBeforeLast('.', fileName)
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
        return preferredFolderCoverBaseNames.any {
            base == it || base.startsWith("$it ")
        }
    }

    private fun isNetworkVideoPath(path: String): Boolean =
        path.startsWith("smb://", true) || path.startsWith("http://", true) || path.startsWith("https://", true)

    private fun defaultVideoFrameTimeUs(path: String): Long = 10_000_000L

    // Cache mémoire (RAM) LRU limité à 15MB — sert les miniatures déjà vues pendant cette session,
    // évite même le passage par le disque tant que l'app tourne.
    private val cache = object : LruCache<String, Bitmap>(15 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Cache RAM chaud réservé aux miniatures vidéo. Le cache générique est partagé avec Blaze
    // Gallery et les pochettes audio ; il pouvait donc évincer les vignettes de l'historique dès
    // qu'on visitait un autre onglet. Ce second LRU conserve au minimum les tuiles visibles et
    // permet leur réaffichage synchrone, sans flash vide au retour sur Blaze Video.
    private val videoThumbnailHotCacheBytes = (Runtime.getRuntime().maxMemory() / 16)
        .coerceIn(12L * 1024L * 1024L, 24L * 1024L * 1024L)
        .toInt()
    private val videoThumbnailHotCache = object : LruCache<String, Bitmap>(videoThumbnailHotCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun promoteVideoHotCache(key: String, bitmap: Bitmap) {
        videoThumbnailHotCache.put(key, bitmap)
    }

    // Cache RAM exclusivement réservé à Blaze Gallery. Il est volontairement séparé des caches
    // vidéo et audio : le défilement de centaines de photos ne doit plus évincer les pochettes
    // d'albums, et inversement. Le budget conserve plusieurs écrans de tuiles 4 colonnes afin que
    // le retour arrière et les flings rapides soient servis de façon strictement synchrone.
    private val galleryImageHotCacheBytes = (Runtime.getRuntime().maxMemory() / 8)
        .coerceIn(32L * 1024L * 1024L, 96L * 1024L * 1024L)
        .toInt()
    private val galleryImageHotCache = object : LruCache<String, Bitmap>(galleryImageHotCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private fun gallerySizeBucket(maxSize: Int): Int = when {
        maxSize <= 280 -> 280
        maxSize <= 512 -> 512
        else -> maxSize
    }
    private fun galleryHotKey(path: String, maxSize: Int): String =
        "gallery-image-hot-v3:${gallerySizeBucket(maxSize)}:$path"
    private fun promoteGalleryHotCache(path: String, maxSize: Int, bitmap: Bitmap) {
        val key = galleryHotKey(path, maxSize)
        galleryImageHotCache.put(key, bitmap)
        cache.put(key, bitmap)
    }

    /** Lecture RAM pure, sûre depuis onBindViewHolder. Aucun accès MediaStore/disque. */
    fun peekMemoryImageThumbnailBitmap(path: String, maxSize: Int = 360): Bitmap? {
        val key = galleryHotKey(path, maxSize)
        galleryImageHotCache.get(key)?.let { return it }
        return cache.get(key)?.also { galleryImageHotCache.put(key, it) }
    }
    // Cache RAM dédié, exclusivement pour les pochettes de la bibliothèque audio, séparé du cache
    // partagé ci-dessus (partagé avec la Galerie photo/vidéo). Une bibliothèque de plusieurs
    // centaines/milliers de titres peut faire défiler beaucoup plus de pochettes distinctes que les
    // 15MB partagés ne peuvent en retenir : chaque éviction prématurée forçait un redécodage visible
    // (flash/saut d'image) au moment où l'utilisateur revenait sur une ligne déjà vue. Budget calé sur
    // le tas de l'app (borné 16–32MB) pour rester généreux sans risquer un OutOfMemoryError sur un
    // appareil d'entrée de gamme.
    private val audioCoverHotCacheBytes = (Runtime.getRuntime().maxMemory() / 12)
        .coerceIn(16L * 1024L * 1024L, 32L * 1024L * 1024L)
        .toInt()
    private val audioCoverHotCache = object : LruCache<String, Bitmap>(audioCoverHotCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private fun promoteAudioHotCache(key: String, bitmap: Bitmap) {
        audioCoverHotCache.put(key, bitmap)
    }
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val audioArtworkInFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val embeddedArtworkInFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val networkThumbnailSemaphore = Semaphore(2)
    private val audioArtworkSemaphore = Semaphore(4)
    private val embeddedArtworkSemaphore = Semaphore(3)
    // Valeur = (dernière modification du dossier au moment du scan, chemin de la cover trouvée
    // ou "" si aucune). Se rebase automatiquement sur lastModified() du dossier : si un fichier
    // est ajouté/renommé/supprimé dedans, l'horodatage change et on relance un simple listFiles()
    // au lieu de continuer à servir indéfiniment l'ancienne pochette (ou "aucune pochette") mise
    // en cache lors d'un scan précédent — sans ça, une cover ajoutée après coup n'apparaissait
    // jamais tant que le processus de l'app restait vivant.
    private val folderCoverPathCache = ConcurrentHashMap<String, Pair<Long, List<String>>>()
    private val audioFolderCoverEpoch = AtomicLong(0L)
    /**
     * Les images de dossier sont des lectures courtes et peuvent être décodées en parallèle.
     * L'ancienne file mono-thread en priorité minimale expliquait l'arrêt apparent après quelques
     * centaines d'albums : les demandes continuaient simplement à s'empiler pendant des heures.
     */
    private val audioArtworkDispatcher = Executors.newFixedThreadPool(4) { runnable ->
        Thread {
            try {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND
                )
            } catch (_: Exception) {
            }
            runnable.run()
        }.apply {
            name = "BlazeAudioArtworkExternal"
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }.asCoroutineDispatcher()

    /**
     * Extraction embarquée séparée. Trois workers au maximum évitent de saturer un NAS tout en
     * permettant à plusieurs albums indépendants d'avancer réellement en parallèle.
     */
    private val embeddedArtworkDispatcher = Executors.newFixedThreadPool(3) { runnable ->
        Thread {
            try {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND
                )
            } catch (_: Exception) {
            }
            runnable.run()
        }.apply {
            name = "BlazeAudioArtworkEmbedded"
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }.asCoroutineDispatcher()

    // Les miniatures photo doivent rester hors du thread principal : une simple lecture disque ou
    // une requête MediaStore dans onBindViewHolder suffit à créer des micro-saccades pendant le
    // scroll de l'accueil Galerie, surtout avec 4 aperçus par dossier. Deux workers bas-priorité
    // gardent les vignettes visibles réactives sans saturer l'I/O ni le GPU.
    private val imageThumbnailDispatcher = Executors.newFixedThreadPool(4) { runnable ->
        Thread {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + 5) } catch (_: Exception) {}
            runnable.run()
        }.apply {
            name = "BlazeImageThumbBg"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()

    private val galleryWarmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val galleryWarmupStarted = AtomicBoolean(false)

    // Cache DISQUE persistant (survit aux redémarrages de l'app) : c'est lui qui évite de
    // devoir rouvrir une connexion SMB et re-décoder une frame vidéo / une pochette audio à
    // chaque fois qu'on revient sur l'accueil ou qu'on rouvre l'app — particulièrement lent sur
    // fichiers réseau. Plafonné en taille avec une purge des plus anciens fichiers au besoin.
    private const val DISK_CACHE_DIR_NAME = "thumb_cache"
    private const val DISK_CACHE_MAX_BYTES = 512L * 1024 * 1024 // 512 Mo
    private const val DISK_CACHE_JPEG_QUALITY = 95

    private fun diskCacheDir(context: Context): File =
        File(context.cacheDir, DISK_CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun keyFor(path: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(path.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun diskFileFor(context: Context, path: String): File =
        File(diskCacheDir(context), keyFor(path) + ".jpg")

    private fun imageThumbnailKey(context: Context, path: String, maxSize: Int): String =
        "image-thumb-v1:$maxSize:$path:${imageContentStamp(context, path)}"

    private fun imageContentStamp(context: Context, path: String): String {
        return try {
            when {
                path.startsWith("content://", true) -> {
                    val uri = Uri.parse(path)
                    val projection = arrayOf(
                        MediaStore.MediaColumns.DATE_MODIFIED,
                        MediaStore.MediaColumns.SIZE
                    )
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                            val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                            val date = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                            val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                            ":$date:$size"
                        } else ""
                    }.orEmpty()
                }
                else -> localFileForImagePath(path)
                    ?.takeIf { it.exists() && it.isFile }
                    ?.let { ":${it.lastModified()}:${it.length()}" }
                    .orEmpty()
            }
        } catch (_: Exception) { "" }
    }

    private fun deleteFromDisk(context: Context, path: String) {
        try { diskFileFor(context, path).delete() } catch (_: Exception) {}
    }

    private fun readFromDisk(context: Context, path: String): Bitmap? {
        val file = diskFileFor(context, path)
        if (!file.isFile || file.length() <= 4L) return null
        return try {
            // Les fichiers du cache sont toujours nos propres JPEG complets. Une enveloppe
            // incomplète indique une ancienne écriture interrompue : on la supprime au lieu de
            // laisser BitmapFactory afficher uniquement les premières lignes de l'image.
            if (!hasCompleteJpegEnvelope(file)) {
                file.delete()
                null
            } else {
                BitmapFactory.decodeFile(file.absolutePath)?.also {
                    file.setLastModified(System.currentTimeMillis())
                } ?: run {
                    file.delete()
                    null
                }
            }
        } catch (_: Exception) {
            runCatching { file.delete() }
            null
        }
    }

    private fun writeToDisk(context: Context, path: String, bitmap: Bitmap) {
        val file = diskFileFor(context, path)
        val tmp = File(file.parentFile, ".${file.name}.${Thread.currentThread().id}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_JPEG_QUALITY, out)) {
                    throw IllegalStateException("JPEG thumbnail compression failed")
                }
                out.fd.sync()
            }
            replaceFileAtomically(tmp, file)
            if (Random.nextInt(20) == 0) pruneDiskCacheIfNeeded(context)
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            android.util.Log.w("ThumbnailUtils", "Failed to write disk thumbnail cache", e)
        }
    }

    private fun replaceFileAtomically(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun hasCompleteJpegEnvelope(file: File): Boolean = runCatching {
        if (file.length() < 4L) return@runCatching false
        RandomAccessFile(file, "r").use { raf ->
            val first = raf.readUnsignedByte()
            val second = raf.readUnsignedByte()
            raf.seek(file.length() - 2L)
            val beforeLast = raf.readUnsignedByte()
            val last = raf.readUnsignedByte()
            first == 0xFF && second == 0xD8 && beforeLast == 0xFF && last == 0xD9
        }
    }.getOrDefault(false)

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
        // Ne pas recycler automatiquement le bitmap source : il peut venir du cache LRU et être
        // encore référencé par une autre ImageView. Le LruCache se charge de libérer la mémoire.
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }


    // Caches séparés audio/vidéo : évite qu'une miniature vidéo (même chemin logique,
    // ancien cache Vxx ou artworkUri Cast) soit réutilisée comme pochette audio.
    private fun audioKey(path: String): String {
        // v11 : priorité stricte à cover.jpg puis cover.png. La pochette embarquée n'est utilisée
        // qu'en repli, et le stamp du fichier de dossier reste dans la clé pour rafraîchir le cache
        // dès qu'une cover est ajoutée ou remplacée.
        val fallbackCover = if (isAudioPath(path)) preferredFolderCoverPathForAudioPath(path) else null
        val imageStamp = fallbackCover?.let { folderCoverStamp(it) }.orEmpty()
        val remoteEpoch = if (path.startsWith("smb://", true)) ":${audioFolderCoverEpoch.get()}" else ""
        return "audio-hires-v11:$path:${fallbackCover.orEmpty()}$imageStamp$remoteEpoch"
    }

    private fun audioNoProbeKey(path: String): String {
        val remoteEpoch = if (path.startsWith("smb://", true)) ":${audioFolderCoverEpoch.get()}" else ""
        return "audio-hires-v11:$path:$remoteEpoch"
    }

    private fun putAudioArtworkAliases(context: Context, path: String, key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
        promoteAudioHotCache(key, bitmap)
        writeToDisk(context.applicationContext, key, bitmap)
        // Alias cache-first destiné aux écrans de bibliothèque : il permet de retrouver une cover
        // déjà générée sans relancer preferredFolderCoverPathForAudioPath(), donc sans listFiles()
        // sur chaque dossier au moment de l'ouverture.
        if (isAudioPath(path)) {
            val noProbeKey = audioNoProbeKey(path)
            cache.put(noProbeKey, bitmap)
            promoteAudioHotCache(noProbeKey, bitmap)
            writeToDisk(context.applicationContext, noProbeKey, bitmap)
        }
    }

    private fun folderCoverStamp(path: String): String = localFileForImagePath(path)
        ?.takeIf { it.exists() }
        ?.let { ":${it.lastModified()}:${it.length()}" }
        .orEmpty()
    private fun customVideoKey(path: String): String = "custom-video-thumb:$path"
    // Les miniatures vidéo automatiques viennent d'une extraction légère et cachée : miniature
    // personnalisée en priorité, puis thumbnail système MediaStore quand Android l'a déjà indexée,
    // puis frame vidéo bornée par timeout. Clé séparée pour ne pas réutiliser les anciens snapshots
    // capturés pendant la lecture.
    private fun thumbnailKey(path: String): String = if (isAudioPath(path)) "audio-thumb-v3:${audioKey(path)}" else "video-frame-lite-v2:$path"
    // Cache séparé du navigateur vidéo : il garantit une frame réellement extraite dans
    // les dix premières secondes, sans réutiliser une miniature système MediaStore potentiellement
    // choisie ailleurs dans la vidéo ni une miniature personnalisée de l'accueil.
    private fun browserEarlyFrameKey(path: String): String = "browser-early-frame-v1:$path"

    /** Retourne une pochette audio déjà en cache RAM/disque sans ouvrir le fichier source.
     *  À utiliser depuis l'UI : évite de lancer MediaMetadataRetriever sur le thread principal. */
    fun getCachedAudioArtworkBitmap(context: Context, path: String): Bitmap? {
        val key = audioKey(path)
        cache.get(key)?.let { return it }
        readFromDisk(context.applicationContext, key)?.let { cached ->
            cache.put(key, cached)
            promoteAudioHotCache(key, cached)
            return cached
        }
        if (isFolderCoverImagePath(path)) {
            val imageKey = audioKey(path)
            cache.get(imageKey)?.let { return it }
            readFromDisk(context.applicationContext, imageKey)?.let { cached ->
                cache.put(imageKey, cached)
                promoteAudioHotCache(imageKey, cached)
                return cached
            }
        }
        return null
    }
    /** Retourne RAM + disque sans résoudre cover.jpg voisin.
     *  C'est le chemin d'ouverture rapide de la bibliothèque : lire un jpg déjà en cache est OK,
     *  lister le dossier de chaque titre/album ne l'est pas. */
    fun getCachedAudioArtworkBitmapNoFolderProbe(context: Context, path: String): Bitmap? {
        val simpleKey = audioNoProbeKey(path)
        cache.get(simpleKey)?.let { return it }
        readFromDisk(context.applicationContext, simpleKey)?.let { cached ->
            cache.put(simpleKey, cached)
            promoteAudioHotCache(simpleKey, cached)
            return cached
        }
        val thumbKey = "audio-thumb-v3:$simpleKey"
        cache.get(thumbKey)?.let { return it }
        readFromDisk(context.applicationContext, thumbKey)?.let { cached ->
            cache.put(thumbKey, cached)
            promoteAudioHotCache(thumbKey, cached)
            return cached
        }
        // Si artworkPath est déjà une image explicite indexée (cover.jpg/png), audioKey() ne
        // déclenche aucun scan de dossier car ce n'est pas un chemin audio. On peut donc réutiliser
        // le cache normal dans ce cas.
        if (isFolderCoverImagePath(path)) {
            val imageKey = audioKey(path)
            cache.get(imageKey)?.let { return it }
            readFromDisk(context.applicationContext, imageKey)?.let { cached ->
                cache.put(imageKey, cached)
                promoteAudioHotCache(imageKey, cached)
                return cached
            }
        }
        return null
    }

    /** Retourne uniquement le cache RAM : aucune lecture disque ni extraction.
     *  Utilisé pendant l'inflation des grandes bibliothèques pour éviter les micro-freezes UI. */
    fun getMemoryCachedAudioArtworkBitmap(path: String): Bitmap? {
        cache.get(audioKey(path))?.let { return it }
        val coverPath = if (isAudioPath(path)) preferredFolderCoverPathForAudioPath(path) else null
        return coverPath?.let { cache.get(audioKey(it)) }
    }

    /** Variante strictement no-I/O : ne résout pas cover.jpg et ne touche pas au disque.
     *  À utiliser pendant l'ouverture de la bibliothèque ou quand un audio joue. */
    fun getMemoryCachedAudioArtworkBitmapNoIo(path: String): Bitmap? {
        // Cache dédié en premier : budget plus généreux et non partagé avec la Galerie, donc plus
        // de chances qu'une pochette déjà vue soit encore résidente, pour un rebind instantané.
        val noProbeKey = audioNoProbeKey(path)
        audioCoverHotCache.get(noProbeKey)?.let { return it }
        audioCoverHotCache.get("audio-thumb-v3:$noProbeKey")?.let { return it }
        cache.get(noProbeKey)?.let { return it }
        cache.get("audio-thumb-v3:$noProbeKey")?.let { return it }
        // Pour artworkPath déjà résolu vers cover.jpg/png (local ou smb), audioKey() ne déclenche
        // aucun probe de dossier et permet de réutiliser l'image RAM immédiatement au retour écran.
        if (isFolderCoverImagePath(path) || path.startsWith("content://", true)) {
            audioCoverHotCache.get(audioKey(path))?.let { return it }
            cache.get(audioKey(path))?.let { return it }
        }
        return null
    }

    /** Indique si le dossier contient une cover exacte cover.jpg ou cover.png. Cette image de
     *  dossier est prioritaire sur la pochette embarquée lors de l'extraction. */
    fun hasPreferredFolderCoverForAudio(path: String): Boolean = preferredFolderCoverPathForAudioPath(path) != null

    /** Appelé au début d'un rafraîchissement de bibliothèque. Les caches de lookup, notamment les
     *  résultats négatifs SMB, ne doivent pas masquer un cover.jpg ajouté depuis le dernier scan. */
    fun invalidateAudioFolderCoverLookups() {
        folderCoverPathCache.clear()
        smbFolderCoverCache.clear()
        audioFolderCoverEpoch.incrementAndGet()
    }


    fun hasCachedAudioArtwork(context: Context, path: String): Boolean = getCachedAudioArtworkBitmap(context, path) != null

    fun getCachedAudioArtworkJpegBytes(context: Context, path: String): ByteArray? {
        val bitmap = getCachedAudioArtworkBitmap(context, path) ?: return null
        return try {
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_JPEG_QUALITY, out)
                out.toByteArray()
            }
        } catch (_: Exception) { null }
    }

    /** Cache RAM/disque strict, sans chercher cover.jpg/png voisin. Chemin prioritaire pour
     *  player/mini-player/bibliothèque au premier affichage : aucun listing dossier/NAS n'est
     *  déclenché pour simplement relire une cover déjà extraite. */
    fun getCachedAudioArtworkJpegBytesNoFolderProbe(context: Context, path: String): ByteArray? {
        val bitmap = getCachedAudioArtworkBitmapNoFolderProbe(context, path) ?: return null
        return try {
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_JPEG_QUALITY, out)
                out.toByteArray()
            }
        } catch (_: Exception) { null }
    }

    /** Associe un bitmap déjà résolu (par exemple un cover.jpg indexé dans Room) au chemin de
     *  la piste audio. Tous les écrans qui ne connaissent que le chemin audio récupèrent ensuite
     *  exactement la même pochette depuis les alias RAM/disque. */
    fun cacheResolvedAudioArtworkBitmap(context: Context, audioPath: String, bitmap: Bitmap) {
        if (audioPath.isBlank()) return
        try {
            val scaled = scaleBitmap(bitmap, AUDIO_ARTWORK_MAX_PX)
            putAudioArtworkAliases(context.applicationContext, audioPath, audioKey(audioPath), scaled)
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to alias resolved audio artwork for ${safePathForLog(audioPath)}", e)
        }
    }

    fun cacheAudioArtworkData(context: Context, path: String, artworkData: ByteArray?) {
        if (artworkData == null || artworkData.isEmpty()) return
        try {
            val bitmap = decodeByteArraySampledStrict(artworkData, artworkData.size, AUDIO_ARTWORK_MAX_PX) ?: return
            val scaled = scaleBitmap(bitmap, AUDIO_ARTWORK_MAX_PX)
            val key = audioKey(path)
            putAudioArtworkAliases(context, path, key, scaled)
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to cache audio artwork for ${safePathForLog(path)}", e)
        }
    }

    suspend fun getAudioArtworkJpegBytes(context: Context, path: String): ByteArray? {
        if (!isAudioPath(path)) getCachedAudioArtworkJpegBytes(context, path)?.let { return it }
        val bitmap = getAudioArtworkBitmap(context, path) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                java.io.ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_JPEG_QUALITY, out)
                    out.toByteArray()
                }
            } catch (_: Exception) { null }
        }
    }

    /** Version synchrone à utiliser uniquement depuis un thread de fond/service.
     *  Elle force l'extraction réelle quand le cache est vide : cover.jpg, puis cover.png,
     *  puis seulement la pochette embarquée si aucun fichier de dossier n'existe. */
    fun getAudioArtworkJpegBytesBlocking(context: Context, path: String): ByteArray? {
        if (!isAudioPath(path)) getCachedAudioArtworkJpegBytes(context, path)?.let { return it }
        val bitmap = extractAudioArtworkInternal(context.applicationContext, path) ?: return null
        return try {
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_JPEG_QUALITY, out)
                out.toByteArray()
            }
        } catch (_: Exception) { null }
    }

    /**
     * Décode explicitement une référence d'image. Contrairement à getAudioArtworkBitmap(), une URL
     * HTTP sans extension est ici volontairement considérée comme une albumArtURI.
     */
    suspend fun getExplicitArtworkBitmap(
        context: Context,
        path: String
    ): Bitmap? = coroutineScope {
        if (path.isBlank()) return@coroutineScope null
        val key = "explicit:${audioKey(path)}"
        audioArtworkInFlight[key]?.let {
            return@coroutineScope it.await()
        }

        val deferred = async(audioArtworkDispatcher) {
            val timeoutMs = when {
                path.startsWith("smb://", true) -> 35_000L
                path.startsWith("http://", true) ||
                    path.startsWith("https://", true) -> 30_000L
                else -> 12_000L
            }
            withTimeoutOrNull(timeoutMs) {
                audioArtworkSemaphore.withPermit {
                    getCachedAudioArtworkBitmapNoFolderProbe(
                        context.applicationContext,
                        path
                    ) ?: decodeFolderCoverBitmap(
                        context.applicationContext,
                        path
                    )?.let {
                        val scaled =
                            scaleBitmap(it, AUDIO_ARTWORK_MAX_PX)
                        cacheResolvedAudioArtworkBitmap(
                            context.applicationContext,
                            path,
                            scaled
                        )
                        scaled
                    }
                }
            }
        }
        audioArtworkInFlight[key] = deferred
        try {
            deferred.await()
        } finally {
            audioArtworkInFlight.remove(key, deferred)
        }
    }

    /**
     * Force l'extraction embarquée, même pour une URL UPnP sans extension. L'extension déclarée
     * provient du conteneur ou du chemin logique Artiste/Album/Titre.
     */
    suspend fun getEmbeddedAudioArtworkBitmap(
        context: Context,
        path: String,
        declaredExtension: String = ""
    ): Bitmap? = coroutineScope {
        if (path.isBlank()) return@coroutineScope null
        getCachedAudioArtworkBitmapNoFolderProbe(
            context.applicationContext,
            path
        )?.let { return@coroutineScope it }

        val normalizedExtension = declaredExtension
            .trim()
            .removePrefix(".")
            .lowercase(Locale.ROOT)
            .ifBlank { extensionOf(path) }
        val key =
            "embedded:${audioKey(path)}:$normalizedExtension"
        embeddedArtworkInFlight[key]?.let {
            return@coroutineScope it.await()
        }

        val deferred = async(embeddedArtworkDispatcher) {
            if (
                (
                    path.startsWith("smb://", true) ||
                        path.startsWith("http://", true) ||
                        path.startsWith("https://", true)
                    ) &&
                fr.retrospare.blazeplayer.player.BlazePlayerService
                    .isAudioPlaybackActive
            ) {
                return@async null
            }

            val timeoutMs = when {
                path.startsWith("smb://", true) -> 90_000L
                path.startsWith("http://", true) ||
                    path.startsWith("https://", true) -> 90_000L
                path.startsWith("content://", true) -> 45_000L
                else -> 35_000L
            }

            withTimeoutOrNull(timeoutMs) {
                embeddedArtworkSemaphore.withPermit {
                    val bitmap = extractEmbeddedArtworkInternal(
                        context.applicationContext,
                        path,
                        normalizedExtension
                    ) ?: return@withPermit null
                    val scaled =
                        scaleBitmap(bitmap, AUDIO_ARTWORK_MAX_PX)
                    putAudioArtworkAliases(
                        context.applicationContext,
                        path,
                        audioKey(path),
                        scaled
                    )
                    scaled
                }
            }
        }
        embeddedArtworkInFlight[key] = deferred
        try {
            deferred.await()
        } finally {
            embeddedArtworkInFlight.remove(key, deferred)
        }
    }

    /** Résolution historique complète, désormais fondée sur les deux voies explicites ci-dessus. */
    suspend fun getAudioArtworkBitmap(
        context: Context,
        path: String
    ): Bitmap? {
        if (path.isBlank()) return null
        if (isFolderCoverImagePath(path)) {
            return getExplicitArtworkBitmap(context, path)
        }
        return getCachedAudioArtworkBitmapNoFolderProbe(
            context.applicationContext,
            path
        ) ?: getEmbeddedAudioArtworkBitmap(
            context,
            path,
            extensionOf(path)
        )
    }

    private fun extractEmbeddedArtworkInternal(
        context: Context,
        path: String,
        declaredExtension: String
    ): Bitmap? {
        val extension = declaredExtension
            .trim()
            .removePrefix(".")
            .lowercase(Locale.ROOT)
            .ifBlank { extensionOf(path) }

        // Pour MP3, le parseur ID3 est prioritaire même en local. MediaMetadataRetriever varie
        // beaucoup selon le constructeur et ignore régulièrement APIC, notamment avec plusieurs
        // images, une désynchronisation de frame ID3v2.4 ou un en-tête étendu.
        val preferBoundedParser =
            extension == "mp3" ||
                path.startsWith("smb://", true) ||
                path.startsWith("http://", true) ||
                path.startsWith("https://", true)
        return if (preferBoundedParser) {
            extractEmbeddedArtworkFallback(
                context,
                path,
                extension
            ) ?: tryExtractEmbeddedArtworkWithRetriever(
                context,
                path
            )
        } else {
            tryExtractEmbeddedArtworkWithRetriever(
                context,
                path
            ) ?: extractEmbeddedArtworkFallback(
                context,
                path,
                extension
            )
        }
    }

    private fun localFileForImagePath(path: String): File? {
        val raw = path.substringBefore('?').substringBefore('#')
        val clean = runCatching { Uri.decode(raw) }.getOrDefault(raw)
        return try {
            if (clean.startsWith("file://", true)) File(Uri.parse(clean).path.orEmpty()) else File(clean)
        } catch (_: Exception) { null }
    }

    /**
     * Lookup ultra-court pour les vues visibles : uniquement des noms exacts type cover.jpg/png,
     * pas de listFiles() massif, pas de MediaStore, pas de réseau. Ne jamais appeler cette méthode
     * pendant la construction globale du modèle de bibliothèque.
     */
    fun fastPreferredFolderCoverPathForAudioPath(path: String): String? {
        if (!isAudioPath(path)) return null
        if (path.startsWith("smb://", true) || path.startsWith("http://", true) ||
            path.startsWith("https://", true) || path.startsWith("content://", true) ||
            path.startsWith("upnp://", true)) return null
        return localCoverSearchDirectoriesForAudioPath(path)
            .asSequence()
            .mapNotNull { directory ->
                val key = directory.absolutePath
                val stamp = directory.lastModified()
                folderCoverPathCache[key]?.let { (cachedStamp, cachedPaths) ->
                    if (cachedStamp == stamp) {
                        cachedPaths.firstOrNull { cached -> File(cached).exists() && File(cached).isFile }
                            ?.let { return@mapNotNull it }
                    }
                }
                fastExactCoverFileNames
                    .asSequence()
                    .map { File(directory, it) }
                    .firstOrNull { it.exists() && it.isFile }
                    ?.absolutePath
                    ?.also { folderCoverPathCache[key] = stamp to listOf(it) }
            }
            .firstOrNull()
    }

    fun preferredFolderCoverPathForAudioPath(path: String): String? =
        preferredLocalFolderCoverCandidatesForAudioPath(path).firstOrNull()

    /**
     * Retourne tous les candidats externes du dossier album, dans l'ordre de préférence.
     * Une image quelconque du dossier est conservée en dernier recours : de nombreuses collections
     * utilisent le nom de l'album plutôt que cover.jpg.
     */
    suspend fun folderArtworkCandidatesForAudioPath(
        context: Context,
        path: String
    ): List<String> = withContext(Dispatchers.IO) {
        when {
            path.startsWith("smb://", true) ->
                (
                    smbFolderArtworkCandidates(path) +
                        smbFolderCoverCandidatePaths(path)
                ).distinct()

            path.startsWith("http://", true) ||
                path.startsWith("https://", true) ||
                path.startsWith("upnp://", true) ->
                emptyList()

            else ->
                (
                    preferredLocalFolderCoverCandidatesForAudioPath(
                        path
                    ) +
                        listOfNotNull(
                            preferredMediaStoreFolderCoverPathForAudioPath(
                                context.applicationContext,
                                path
                            )
                        )
                ).distinct()
        }
    }

    /** Liste ordonnée et validée des pochettes de dossier locales. On garde plusieurs candidats
     *  au lieu d'un seul : un cover.jpg présent mais illisible ne doit pas empêcher le repli vers
     *  cover.png. Le lookup est insensible à la casse pour les volumes dont le nom réel est
     *  Cover.JPG/COVER.PNG. */
    private fun preferredLocalFolderCoverCandidatesForAudioPath(path: String): List<String> {
        if (!isAudioPath(path)) return emptyList()
        if (path.startsWith("smb://", true) || path.startsWith("http://", true) ||
            path.startsWith("https://", true) || path.startsWith("content://", true) ||
            path.startsWith("upnp://", true)) return emptyList()
        return localCoverSearchDirectoriesForAudioPath(path)
            .flatMap { preferredCoverImagesInDirectory(it) }
            .distinctBy { it.absolutePath.lowercase(Locale.ROOT) }
            .map { it.absolutePath }
    }

    private fun localCoverSearchDirectoriesForAudioPath(path: String): List<File> {
        val raw = path.substringBefore('?').substringBefore('#')
        val clean = runCatching { Uri.decode(raw) }.getOrDefault(raw)
        val file = try {
            if (clean.startsWith("file://", true)) File(Uri.parse(clean).path.orEmpty()) else File(clean)
        } catch (_: Exception) { return emptyList() }
        val parent = file.parentFile ?: return emptyList()
        val albumDir = if (isDiscFolderName(parent.name) && parent.parentFile != null) parent.parentFile else parent
        return listOfNotNull(parent, albumDir)
            .filter { it.exists() && it.isDirectory }
            .distinctBy { it.absolutePath }
    }

    private fun preferredCoverImagesInDirectory(directory: File): List<File> {
        val key = directory.absolutePath
        val dirStamp = directory.lastModified()
        folderCoverPathCache[key]?.let { (stamp, coverPaths) ->
            if (stamp == dirStamp) {
                val cached = coverPaths
                    .map(::File)
                    .filter { it.exists() && it.isFile }
                if (cached.isNotEmpty()) return cached
            }
        }

        val covers = runCatching {
            directory.listFiles()
                ?.asSequence()
                ?.filter { file ->
                    file.isFile &&
                        !file.name.startsWith(".") &&
                        extensionOf(file.name) in folderCoverImageExtensions &&
                        file.length() in 1L..MAX_FOLDER_COVER_IMAGE_BYTES
                }
                ?.sortedWith(
                    compareBy<File> { folderCoverPriority(it.name) }
                        .thenByDescending { it.length() }
                        .thenBy { it.name.lowercase(Locale.ROOT) }
                )
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())

        // Une image nommée d'après l'album est un candidat valide même si elle ne s'appelle pas
        // cover.jpg. Le tri conserve néanmoins cover/front/folder en priorité.
        if (covers.isNotEmpty()) {
            folderCoverPathCache[key] =
                dirStamp to covers.map { it.absolutePath }
        } else {
            folderCoverPathCache.remove(key)
        }
        return covers
    }

    private fun preferredMediaStoreFolderCoverPathForAudioPath(context: Context, path: String): String? {
        if (path.startsWith("smb://", true) || path.startsWith("http://", true) ||
            path.startsWith("https://", true) || path.startsWith("content://", true) ||
            path.startsWith("upnp://", true)) return null
        val directories = localCoverSearchDirectoriesForAudioPath(path)
        if (directories.isEmpty()) return null
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA
        )
        val candidates = mutableListOf<Pair<String, String>>()
        directories.forEach { directory ->
            runCatching {
                val dirPath = directory.absolutePath.trimEnd('/')
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Images.Media.DATA} LIKE ?",
                    arrayOf("$dirPath/%"),
                    null
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    while (cursor.moveToNext()) {
                        val imagePath = cursor.getString(dataIdx).orEmpty()
                        val parent = runCatching { File(imagePath).parentFile?.absolutePath }.getOrNull()
                        if (parent != dirPath) continue
                        val name = cursor.getString(nameIdx).orEmpty().ifBlank { imagePath.substringAfterLast('/') }
                        if (!isAllowedAudioFolderCoverName(name)) continue
                        val id = cursor.getLong(idIdx)
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
                        candidates += name to uri
                    }
                }
            }
        }
        return candidates
            .sortedWith(Comparator { a, b -> preferredCoverFileCompare(a.first, b.first) })
            .firstOrNull()
            ?.second
    }

    private fun preferredCoverFileCompare(left: String, right: String): Int {
        val lp = folderCoverPriority(left)
        val rp = folderCoverPriority(right)
        if (lp != rp) return lp.compareTo(rp)
        return naturalFileNameCompare(left, right)
    }

    private fun folderCoverPriority(name: String): Int {
        val base = name.substringBeforeLast('.', name)
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .lowercase()
        val extPenalty = when (extensionOf(name)) {
            "jpg" -> 0
            "png" -> 1
            "jpeg" -> 2
            else -> 9
        }
        val exactIndex = preferredFolderCoverBaseNames.indexOf(base)
        if (exactIndex >= 0) return exactIndex * 10 + extPenalty
        val prefixIndex = preferredFolderCoverBaseNames.indexOfFirst { base.startsWith(it) }
        if (prefixIndex >= 0) return 100 + prefixIndex * 10 + extPenalty
        return 1_000 + extPenalty
    }

    private fun naturalFileNameCompare(left: String, right: String): Int {
        val a = Regex("\\d+|\\D+").findAll(left.lowercase()).map { it.value }.toList()
        val b = Regex("\\d+|\\D+").findAll(right.lowercase()).map { it.value }.toList()
        val maxParts = maxOf(a.size, b.size)
        for (i in 0 until maxParts) {
            val av = a.getOrNull(i) ?: return -1
            val bv = b.getOrNull(i) ?: return 1
            val ad = av.all { it.isDigit() }
            val bd = bv.all { it.isDigit() }
            val cmp = if (ad && bd) {
                val an = av.trimStart('0').ifBlank { "0" }
                val bn = bv.trimStart('0').ifBlank { "0" }
                when {
                    an.length != bn.length -> an.length.compareTo(bn.length)
                    an != bn -> an.compareTo(bn)
                    else -> av.length.compareTo(bv.length)
                }
            } else av.compareTo(bv)
            if (cmp != 0) return cmp
        }
        return left.compareTo(right, ignoreCase = true)
    }

    private fun isDiscFolderName(value: String): Boolean = Regex("^(?:cd|disc|disk|disque|vol(?:ume)?)\\s*\\d+", RegexOption.IGNORE_CASE).containsMatchIn(value.trim())

    private fun decodeFolderCoverBitmap(
        context: Context,
        path: String
    ): Bitmap? {
        val explicitRemoteImage =
            path.startsWith("http://", true) ||
                path.startsWith("https://", true)
        if (
            !isFolderCoverImagePath(path) &&
            !path.startsWith("content://", true) &&
            !explicitRemoteImage
        ) {
            return null
        }
        return try {
            when {
                path.startsWith("smb://", true) -> decodeSmbFolderCoverBitmap(path)
                path.startsWith("http://", true) ||
                    path.startsWith("https://", true) -> {
                    val bytes = readImageBytesBounded(
                        context,
                        path,
                        MAX_FOLDER_COVER_IMAGE_BYTES.toInt()
                    )
                    bytes?.let {
                        decodeByteArraySampledStrict(
                            it,
                            it.size,
                            AUDIO_ARTWORK_MAX_PX
                        )
                    }
                }
                else -> {
                    // Pour une cover de dossier, on lit le fichier entier avant de décoder. Une
                    // image tronquée ne doit jamais être acceptée puis persistée comme une cover
                    // dont seule la bande supérieure est visible.
                    val bytes = readImageBytesBounded(context, path, MAX_FOLDER_COVER_IMAGE_BYTES.toInt())
                    bytes?.let { decodeByteArraySampledStrict(it, it.size, AUDIO_ARTWORK_MAX_PX) }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to decode folder cover image for ${safePathForLog(path)}", e)
            null
        }
    }

    private fun decodeSmbFolderCoverBitmap(path: String): Bitmap? {
        if (fr.retrospare.blazeplayer.player.BlazePlayerService.isAudioPlaybackActive) return null
        // Le NAS doit avoir livré exactement la taille annoncée avant tout décodage. Auparavant,
        // un readAt() transitoirement court était interprété comme une fin de fichier et le codec
        // Android pouvait restituer uniquement le haut du JPEG, ensuite mis en cache durablement.
        repeat(3) { attempt ->
            if (fr.retrospare.blazeplayer.player.BlazePlayerService.isAudioPlaybackActive) return null
            var source: fr.retrospare.blazeplayer.player.SmbMediaDataSource? = null
            try {
                val smbSource = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
                source = smbSource
                val size = smbSource.getSize()
                if (size <= 0L || size > MAX_FOLDER_COVER_IMAGE_BYTES || size > Int.MAX_VALUE) return null
                val expected = size.toInt()
                val bytes = ByteArray(expected)
                var offset = 0
                var emptyReads = 0
                while (offset < expected) {
                    if (fr.retrospare.blazeplayer.player.BlazePlayerService.isAudioPlaybackActive) return null
                    val read = smbSource.readAt(offset.toLong(), bytes, offset, expected - offset)
                    when {
                        read > 0 -> {
                            offset += read
                            emptyReads = 0
                        }
                        emptyReads < 2 -> {
                            emptyReads++
                            Thread.sleep(35L * emptyReads)
                        }
                        else -> break
                    }
                }
                if (offset == expected) {
                    decodeByteArraySampledStrict(bytes, expected, AUDIO_ARTWORK_MAX_PX)?.let { return it }
                }
                android.util.Log.w(
                    "ThumbnailUtils",
                    "Incomplete SMB cover read ${safePathForLog(path)}: $offset/$expected (attempt ${attempt + 1})"
                )
            } catch (e: Exception) {
                if (!fr.retrospare.blazeplayer.player.SmbDataSource.isMissingPathError(e)) {
                    android.util.Log.w("ThumbnailUtils", "Failed SMB cover attempt ${attempt + 1} for ${safePathForLog(path)}", e)
                }
            } finally {
                try { source?.close() } catch (_: Exception) {}
            }
            if (attempt < 2 && !fr.retrospare.blazeplayer.player.BlazePlayerService.isAudioPlaybackActive) {
                Thread.sleep(90L * (attempt + 1))
            }
        }
        return null
    }

    // Cache la cover-dossier trouvée par répertoire SMB, avec une durée de vie courte (contrairement
    // au cache local basé sur lastModified() : on ne peut pas "stat" un dossier réseau aussi
    // simplement, un TTL est le compromis le plus simple pour éviter de relister le dossier à
    // chaque miniature tout en restant à jour si une cover est ajoutée/changée sur le NAS).
    private val smbFolderCoverCache =
        ConcurrentHashMap<String, Pair<Long, List<String>>>()
    private const val SMB_FOLDER_COVER_CACHE_TTL_MS = 15_000L

    /** Construit les chemins directs cover.jpg/cover.png dans le dossier du titre, puis dans le
     *  dossier album si la piste se trouve sous CD1/Disc 1. Cela évite de dépendre uniquement d'un
     *  listing SMB, qui peut être incomplet ou expirer sur certains NAS. */
    private fun smbFolderCoverCandidatePaths(path: String): List<String> {
        if (!path.startsWith("smb://", true)) return emptyList()
        return runCatching {
            val parsed = fr.retrospare.blazeplayer.player.SmbDataSource.parseSmbUri(Uri.parse(path))
            val normalized = parsed.filePath.replace('\\', '/')
            val fileDir = normalized.substringBeforeLast('/', "")
            val dirs = mutableListOf(fileDir)
            val lastDirName = fileDir.substringAfterLast('/', fileDir)
            if (isDiscFolderName(lastDirName)) {
                val albumDir = fileDir.substringBeforeLast('/', "")
                if (albumDir.isNotBlank()) dirs += albumDir
            }
            dirs.distinct().flatMap { dir ->
                fastExactCoverFileNames.map { name ->
                    buildSmbCoverUri(parsed, if (dir.isBlank()) name else "$dir/$name")
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Recherche uniquement cover.jpg puis cover.png dans le dossier SMB qui contient [path], comme le fait déjà
     *  [preferredFolderCoverPathForAudioPath] pour le stockage local. Sans ça, un MP3 sur un partage
     *  réseau sans tag APIC embarqué n'affichait jamais rien, même avec un cover.jpg juste à côté :
     *  seule la pochette embarquée était tentée pour smb://, la recherche de cover-dossier était
     *  jusqu'ici entièrement absente pour ce protocole. Volontairement lent-tolérant (un seul
     *  listing réseau, mis en cache) plutôt qu'une extraction lourde. */
    private fun smbFolderArtworkCandidates(path: String): List<String> {
        if (!path.startsWith("smb://", true)) return emptyList()
        if (
            fr.retrospare.blazeplayer.player.BlazePlayerService
                .isAudioPlaybackActive
        ) {
            return emptyList()
        }

        return try {
            val parsed =
                fr.retrospare.blazeplayer.player.SmbDataSource
                    .parseSmbUri(Uri.parse(path))
            val normalized = parsed.filePath.replace('\\', '/')
            val fileDir = normalized.substringBeforeLast('/', "")
            val searchDirs = buildList {
                add(fileDir)
                val lastDirName =
                    fileDir.substringAfterLast('/', fileDir)
                if (isDiscFolderName(lastDirName)) {
                    fileDir.substringBeforeLast('/', "")
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }.distinct()
            val now = System.currentTimeMillis()
            val result = ArrayList<String>()

            for (dir in searchDirs) {
                if (
                    fr.retrospare.blazeplayer.player.BlazePlayerService
                        .isAudioPlaybackActive
                ) {
                    break
                }

                val dirPath = dir.replace('/', '\\')
                val cacheKey =
                    "${parsed.host}:${parsed.port}:${parsed.shareName}:$dirPath"
                val cached = smbFolderCoverCache[cacheKey]
                val candidates = if (
                    cached != null &&
                    now - cached.first <
                    SMB_FOLDER_COVER_CACHE_TTL_MS
                ) {
                    cached.second
                } else {
                    val share =
                        fr.retrospare.blazeplayer.player.SmbSessionPool
                            .getShare(
                                parsed.host,
                                parsed.port,
                                parsed.username,
                                parsed.password,
                                parsed.shareName
                            )
                    val found = try {
                        share.list(dirPath)
                            .asSequence()
                            .filter { info ->
                                val name = info.fileName
                                val extension = extensionOf(name)
                                !name.startsWith(".") &&
                                    extension in
                                    folderCoverImageExtensions &&
                                    info.endOfFile in
                                    1L..MAX_FOLDER_COVER_IMAGE_BYTES
                            }
                            .sortedWith(
                                compareBy<com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation> {
                                    folderCoverPriority(it.fileName)
                                }.thenByDescending {
                                    it.endOfFile
                                }.thenBy {
                                    it.fileName.lowercase(Locale.ROOT)
                                }
                            )
                            .map { info ->
                                buildSmbCoverUri(
                                    parsed,
                                    if (dir.isBlank()) {
                                        info.fileName
                                    } else {
                                        "$dir/${info.fileName}"
                                    }
                                )
                            }
                            .toList()
                    } finally {
                        runCatching { share.close() }
                    }
                    smbFolderCoverCache[cacheKey] = now to found
                    found
                }
                result += candidates
            }
            result.distinct()
        } catch (e: Exception) {
            android.util.Log.w(
                "ThumbnailUtils",
                "SMB folder cover search failed for " +
                    safePathForLog(path),
                e
            )
            emptyList()
        }
    }

    private fun preferredSmbFolderCoverPath(path: String): String? =
        smbFolderArtworkCandidates(path).firstOrNull()

    private fun buildSmbCoverUri(parsed: fr.retrospare.blazeplayer.player.SmbDataSource.ParsedSmbUri, cleanPath: String): String {
        return fr.retrospare.blazeplayer.player.SmbDataSource.buildSmbUri(
            host = parsed.host,
            port = parsed.port,
            shareName = parsed.shareName,
            filePath = cleanPath,
            username = parsed.username,
            password = parsed.password
        )
    }

    private fun extractAudioArtworkInternal(context: Context, path: String): Bitmap? {
        val key = audioKey(path)

        // UPnP albumArtURI n'a pas toujours une extension .jpg/.png. Une URL explicitement
        // fournie comme artwork doit être décodée comme image directement, sans passer par
        // MediaMetadataRetriever comme s'il s'agissait d'un morceau audio.
        if (
            (path.startsWith("http://", true) || path.startsWith("https://", true)) &&
            !isAudioPath(path)
        ) {
            cache.get(key)?.let { return it }
            readFromDisk(context, key)?.let {
                cache.put(key, it)
                return it
            }
            val bytes = readImageBytesBounded(
                context,
                path,
                MAX_FOLDER_COVER_IMAGE_BYTES.toInt()
            )
            return bytes?.let {
                decodeByteArraySampledStrict(
                    it,
                    it.size,
                    AUDIO_ARTWORK_MAX_PX
                )
            }?.let {
                val scaled = scaleBitmap(it, AUDIO_ARTWORK_MAX_PX)
                cache.put(key, scaled)
                promoteAudioHotCache(key, scaled)
                writeToDisk(context, key, scaled)
                scaled
            }
        }

        if (isFolderCoverImagePath(path)) {
            cache.get(key)?.let { return it }
            readFromDisk(context, key)?.let { cache.put(key, it); return it }
            if (path.startsWith("smb://", true) && fr.retrospare.blazeplayer.player.BlazePlayerService.isAudioPlaybackActive) return null
            return decodeFolderCoverBitmap(context, path)?.let {
                val scaled = scaleBitmap(it, AUDIO_ARTWORK_MAX_PX)
                putAudioArtworkAliases(context, path, key, scaled)
                scaled
            }
        }

        // Toujours valider les fichiers de dossier avant de réutiliser une éventuelle pochette
        // embarquée mise en cache. Un timeout réseau ponctuel ne doit pas figer l'embedded comme
        // choix définitif alors qu'un cover.jpg existe réellement à côté du titre.
        if (isAudioPath(path)) {
            if (path.startsWith("smb://", true) && fr.retrospare.blazeplayer.player.BlazePlayerService.isAudioPlaybackActive) {
                cache.get(key)?.let { return it }
                readFromDisk(context, key)?.let { cache.put(key, it); return it }
                return null
            }
            val explicitCandidates = buildList {
                addAll(preferredLocalFolderCoverCandidatesForAudioPath(path))
                preferredMediaStoreFolderCoverPathForAudioPath(context, path)?.let { add(it) }
                // Le listing SMB fournit le nom réel, avec sa casse exacte (Cover.JPG, COVER.PNG…).
                // Les chemins directs restent ensuite en secours si le NAS refuse le listing.
                preferredSmbFolderCoverPath(path)?.let { add(it) }
                addAll(smbFolderCoverCandidatePaths(path))
            }.distinct()
            for (coverPath in explicitCandidates) {
                if (path.startsWith("smb://", true) && fr.retrospare.blazeplayer.player.BlazePlayerService.isAudioPlaybackActive) return null
                val coverKey = audioKey(coverPath)
                val coverBitmap = cache.get(coverKey)
                    ?: readFromDisk(context, coverKey)?.also { cache.put(coverKey, it) }
                    ?: decodeFolderCoverBitmap(context, coverPath)
                if (coverBitmap != null) {
                    val scaled = scaleBitmap(coverBitmap, AUDIO_ARTWORK_MAX_PX)
                    putAudioArtworkAliases(context, path, key, scaled)
                    cache.put(coverKey, scaled)
                    promoteAudioHotCache(coverKey, scaled)
                    writeToDisk(context, coverKey, scaled)
                    return scaled
                }
            }
        }

        // Aucun cover.jpg/png exploitable : seulement maintenant, réutiliser l'embedded déjà en
        // cache ou lancer son extraction.
        cache.get(key)?.let { return it }
        readFromDisk(context, key)?.let { cache.put(key, it); return it }

        // Repli automatique : extraction de la pochette embarquée uniquement quand aucun
        // cover.jpg/cover.png exploitable n'a été trouvé.
        if (path.startsWith("smb://", true) && fr.retrospare.blazeplayer.player.BlazePlayerService.isAudioPlaybackActive) return null
        val embedded = if (path.startsWith("smb://", true)) {
            extractEmbeddedArtworkFallback(context, path) ?: tryExtractEmbeddedArtworkWithRetriever(context, path)
        } else {
            tryExtractEmbeddedArtworkWithRetriever(context, path) ?: extractEmbeddedArtworkFallback(context, path)
        }
        if (embedded != null) {
            val scaled = scaleBitmap(embedded, AUDIO_ARTWORK_MAX_PX)
            putAudioArtworkAliases(context, path, key, scaled)
            return scaled
        }
        return null
    }

    private fun tryExtractEmbeddedArtworkWithRetriever(context: Context, path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        var closeable: AutoCloseable? = null
        return try {
            closeable = setRetrieverDataSource(context, retriever, path)
            retriever.embeddedPicture?.let { decodeByteArraySampledStrict(it, it.size, AUDIO_ARTWORK_MAX_PX) }
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "MediaMetadataRetriever artwork failed for ${safePathForLog(path)}", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { closeable?.close() } catch (_: Exception) {}
        }
    }

    /** Filet de sécurité quand MediaMetadataRetriever ne renvoie pas d'embeddedPicture — ce qui
     *  arrive très souvent pour FLAC/MP4/APE/OGG selon l'appareil et la version d'Android, alors
     *  que l'image est bien présente dans le fichier. Un parseur binaire minimaliste et borné par
     *  format (pas de dépendance externe) plutôt qu'un simple retour null. */
    private fun extractEmbeddedArtworkFallback(
        context: Context,
        path: String,
        declaredExtension: String = extensionOf(path)
    ): Bitmap? {
        val imageBytes = try {
            extractEmbeddedArtworkBytes(
                context,
                path,
                declaredExtension
                    .trim()
                    .removePrefix(".")
                    .lowercase(Locale.ROOT)
            )
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Embedded artwork fallback failed for ${safePathForLog(path)}", e)
            null
        } ?: return null
        return try {
            decodeByteArraySampledStrict(imageBytes, imageBytes.size, AUDIO_ARTWORK_MAX_PX)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractEmbeddedArtworkBytes(
        context: Context,
        path: String,
        ext: String
    ): ByteArray? {
        // ID3 peut exister dans MP3, AAC, WAV, AIFF et même dans certains FLAC. On le vérifie donc
        // pour tous les formats au lieu de le réserver à l'extension .mp3.
        readId3TagBytes(context, path)
            ?.let(::findImageBytesInId3Tag)
            ?.let { return it }

        when (ext) {
            "flac" ->
                extractFlacPictureBytes(context, path)?.let { return it }

            "m4a", "mp4", "m4b", "aac", "alac" ->
                extractMp4CoverBytes(context, path)?.let { return it }

            "ogg", "opus", "oga" ->
                extractVorbisCommentPictureBytes(context, path)
                    ?.let { return it }
        }

        // APEv2 couvre APE/WV et de nombreux fichiers historiques MP3/WAV/FLAC.
        extractApeV2PictureBytes(context, path)?.let { return it }

        // Dernier recours : signatures brutes au début ET à la fin du conteneur. Cela couvre les
        // pièces jointes WMA/ASF, Matroska/MKA, WAV/AIFF et les tags non standards.
        return genericSignatureScanBytes(context, path)
    }

    // ---- Accès aléatoire minimal, indépendant du type de chemin (local/SAF/SMB) ----
    // Nécessaire pour lire un atome MP4 'moov' situé en fin de fichier (fichiers sans "faststart",
    // très courants) ou un tag APEv2 accroché aux 32 derniers octets, sans charger le fichier
    // entier en mémoire.
    private interface RandomAccessSource : AutoCloseable {
        val size: Long
        fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    }

    private class LocalRandomAccessSource(file: File) : RandomAccessSource {
        private val raf = RandomAccessFile(file, "r")
        override val size: Long = raf.length()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position < 0 || position >= size) return -1
            raf.seek(position)
            return raf.read(buffer, offset, length)
        }
        override fun close() { try { raf.close() } catch (_: Exception) {} }
    }

    private class ContentRandomAccessSource(private val pfd: android.os.ParcelFileDescriptor) : RandomAccessSource {
        private val stream = java.io.FileInputStream(pfd.fileDescriptor)
        private val channel = stream.channel
        override val size: Long = try { channel.size() } catch (_: Exception) { -1L }
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position < 0 || (size >= 0 && position >= size)) return -1
            return try {
                channel.position(position)
                stream.read(buffer, offset, length)
            } catch (_: Exception) { -1 }
        }
        override fun close() {
            try { stream.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    private class SmbRandomAccessSource(private val source: fr.retrospare.blazeplayer.player.SmbMediaDataSource) : RandomAccessSource {
        override val size: Long = source.getSize()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            source.readAt(position, buffer, offset, length)
        override fun close() { try { source.close() } catch (_: Exception) {} }
    }

    /**
     * Accès aléatoire HTTP par requêtes Range, utilisé pour les URL UPnP/DLNA. Un petit cache LRU
     * de blocs évite un aller-retour par lecture de champ lors de l'analyse MP4/FLAC/APEv2.
     */
    private class HttpRandomAccessSource(
        private val url: String
    ) : RandomAccessSource {
        private val blockCache = object :
            LinkedHashMap<Long, ByteArray>(HTTP_RANGE_CACHE_BLOCKS, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, ByteArray>?
            ): Boolean = size > HTTP_RANGE_CACHE_BLOCKS
        }

        override val size: Long = resolveSize()

        private fun openConnection(start: Long, end: Long): HttpURLConnection {
            return (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=$start-$end")
            }
        }

        private fun resolveSize(): Long {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection)
                try {
                    connection.requestMethod = "HEAD"
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 8_000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.connect()
                    val length = connection.contentLengthLong
                    if (length > 0L) return length
                } finally {
                    connection.disconnect()
                }
            }

            return runCatching {
                val connection = openConnection(0L, 0L)
                try {
                    connection.connect()
                    val contentRange = connection.getHeaderField("Content-Range")
                        .orEmpty()
                    contentRange.substringAfterLast('/', "")
                        .toLongOrNull()
                        ?.takeIf { it > 0L }
                        ?: connection.contentLengthLong
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(-1L)
        }

        @Synchronized
        private fun block(blockStart: Long): ByteArray? {
            blockCache[blockStart]?.let { return it }
            val blockEnd = if (size > 0L) {
                minOf(
                    size - 1L,
                    blockStart + HTTP_RANGE_BLOCK_BYTES - 1L
                )
            } else {
                blockStart + HTTP_RANGE_BLOCK_BYTES - 1L
            }
            if (blockEnd < blockStart) return null

            val bytes = runCatching {
                val connection = openConnection(blockStart, blockEnd)
                try {
                    connection.connect()
                    val code = connection.responseCode
                    if (
                        code != HttpURLConnection.HTTP_PARTIAL &&
                        code != HttpURLConnection.HTTP_OK
                    ) {
                        return@runCatching null
                    }
                    connection.inputStream.use { input ->
                        if (
                            code == HttpURLConnection.HTTP_OK &&
                            blockStart > 0L
                        ) {
                            var toSkip = blockStart
                            while (toSkip > 0L) {
                                val skipped = input.skip(toSkip)
                                if (skipped > 0L) {
                                    toSkip -= skipped
                                } else if (input.read() >= 0) {
                                    toSkip--
                                } else {
                                    return@use ByteArray(0)
                                }
                            }
                        }

                        val maximum = (
                            blockEnd - blockStart + 1L
                        ).coerceAtMost(
                            HTTP_RANGE_BLOCK_BYTES.toLong()
                        ).toInt()
                        val output = java.io.ByteArrayOutputStream(maximum)
                        val buffer = ByteArray(32 * 1024)
                        var total = 0
                        while (total < maximum) {
                            val read = input.read(
                                buffer,
                                0,
                                minOf(buffer.size, maximum - total)
                            )
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            total += read
                        }
                        output.toByteArray()
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull() ?: return null

            if (bytes.isNotEmpty()) blockCache[blockStart] = bytes
            return bytes.takeIf { it.isNotEmpty() }
        }

        override fun readAt(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int {
            if (
                position < 0L ||
                length <= 0 ||
                (size > 0L && position >= size)
            ) {
                return -1
            }

            var sourcePosition = position
            var destinationOffset = offset
            var remaining = length
            var total = 0

            while (remaining > 0 && (size <= 0L || sourcePosition < size)) {
                val blockStart =
                    (sourcePosition / HTTP_RANGE_BLOCK_BYTES) *
                        HTTP_RANGE_BLOCK_BYTES
                val bytes = block(blockStart) ?: break
                val inBlock = (sourcePosition - blockStart).toInt()
                if (inBlock >= bytes.size) break
                val count = minOf(remaining, bytes.size - inBlock)
                System.arraycopy(
                    bytes,
                    inBlock,
                    buffer,
                    destinationOffset,
                    count
                )
                total += count
                remaining -= count
                destinationOffset += count
                sourcePosition += count
            }
            return if (total > 0) total else -1
        }

        override fun close() {
            synchronized(this) { blockCache.clear() }
        }
    }

    private fun openRandomAccessSource(context: Context, path: String): RandomAccessSource? = try {
        when {
            path.startsWith("smb://", true) -> SmbRandomAccessSource(fr.retrospare.blazeplayer.player.SmbMediaDataSource(path))
            path.startsWith("content://", true) -> context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                ?.let { ContentRandomAccessSource(it) }
            path.startsWith("http://", true) ||
                path.startsWith("https://", true) ->
                HttpRandomAccessSource(path)
            path.startsWith("file://", true) -> localFileForImagePath(path)?.takeIf { it.exists() && it.isFile }?.let { LocalRandomAccessSource(it) }
            else -> File(path).takeIf { it.exists() && it.isFile }?.let { LocalRandomAccessSource(it) }
        }
    } catch (e: Exception) {
        android.util.Log.w("ThumbnailUtils", "Failed to open random access source for ${safePathForLog(path)}", e)
        null
    }

    private const val MAX_EMBEDDED_IMAGE_BYTES =
        32 * 1024 * 1024
    private const val MAX_METADATA_SCAN_BYTES =
        40 * 1024 * 1024
    private const val GENERIC_SIGNATURE_SCAN_BYTES =
        12 * 1024 * 1024
    private const val VORBIS_COMMENT_SCAN_BYTES =
        16 * 1024 * 1024
    private const val HTTP_RANGE_BLOCK_BYTES = 512 * 1024L
    private const val HTTP_RANGE_CACHE_BLOCKS = 8

    private fun readFully(source: RandomAccessSource, position: Long, length: Int): ByteArray? {
        if (length <= 0 || length > MAX_EMBEDDED_IMAGE_BYTES || position < 0) return null
        val buffer = ByteArray(length)
        var total = 0
        while (total < length) {
            val read = source.readAt(position + total, buffer, total, length - total)
            if (read <= 0) break
            total += read
        }
        return if (total > 0) buffer.copyOf(total) else null
    }

    private fun beUInt32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 3 >= bytes.size) return 0L
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun beUInt64(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }

    private fun leUInt32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 3 >= bytes.size) return 0L
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    // ---- FLAC : bloc METADATA_BLOCK_PICTURE (type 6) ----

    private fun extractFlacPictureBytes(context: Context, path: String): ByteArray? {
        val source = openRandomAccessSource(context, path) ?: return null
        return try {
            val magic = readFully(source, 0, 4) ?: return null
            if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
                magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()) return null
            var offset = 4L
            var last = false
            var guard = 0
            while (
                !last &&
                (
                    source.size <= 0L ||
                        offset + 4 <= source.size
                    ) &&
                guard < 256
            ) {
                guard++
                val header = readFully(source, offset, 4) ?: return null
                last = (header[0].toInt() and 0x80) != 0
                val blockType = header[0].toInt() and 0x7F
                val blockSize = ((header[1].toLong() and 0xFF) shl 16) or
                    ((header[2].toLong() and 0xFF) shl 8) or (header[3].toLong() and 0xFF)
                offset += 4
                if (blockSize <= 0L || blockSize > MAX_METADATA_SCAN_BYTES) {
                    if (blockType == 6) return null
                    offset += blockSize
                    continue
                }
                if (blockType == 6) {
                    val block = readFully(source, offset, blockSize.toInt()) ?: return null
                    return parseFlacPictureBlock(block)
                }
                offset += blockSize
            }
            null
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "FLAC picture parse failed for ${safePathForLog(path)}", e)
            null
        } finally {
            try { source.close() } catch (_: Exception) {}
        }
    }

    private fun parseFlacPictureBlock(block: ByteArray): ByteArray? {
        return try {
            var p = 4 // type d'image (4 octets), ignoré : on prend la première image du bloc
            val mimeLen = beUInt32(block, p).toInt(); p += 4
            if (mimeLen !in 0..(block.size - p)) return imageBytesFromFramePayload(block, false)
            p += mimeLen
            val descLen = beUInt32(block, p).toInt(); p += 4
            if (descLen !in 0..(block.size - p)) return imageBytesFromFramePayload(block, false)
            p += descLen
            p += 16 // largeur, hauteur, profondeur, nb couleurs (4 x 4 octets)
            val dataLen = beUInt32(block, p).toInt(); p += 4
            if (dataLen <= 0 || dataLen > block.size - p) return imageBytesFromFramePayload(block, false)
            block.copyOfRange(p, p + dataLen)
        } catch (e: Exception) {
            imageBytesFromFramePayload(block, false)
        }
    }

    // ---- MP4 / M4A : atome 'covr' sous moov/udta/meta/ilst/data ----

    private val MP4_CONTAINER_BOXES = setOf("moov", "udta", "ilst")

    private fun extractMp4CoverBytes(context: Context, path: String): ByteArray? {
        val source = openRandomAccessSource(context, path) ?: return null
        return try {
            if (source.size < 16) return null
            findMp4Covr(source, 0L, source.size, depth = 0)
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "MP4 cover parse failed for ${safePathForLog(path)}", e)
            null
        } finally {
            try { source.close() } catch (_: Exception) {}
        }
    }

    private fun mp4BoxHeader(source: RandomAccessSource, offset: Long, end: Long): Triple<String, Long, Long>? {
        if (offset + 8 > end) return null
        val header = readFully(source, offset, 8) ?: return null
        var boxSize = beUInt32(header, 0)
        val type = String(header, 4, 4, Charsets.US_ASCII)
        var headerLen = 8L
        if (boxSize == 1L) {
            val ext = readFully(source, offset + 8, 8) ?: return null
            boxSize = beUInt64(ext, 0)
            headerLen = 16L
        } else if (boxSize == 0L) {
            boxSize = end - offset
        }
        if (boxSize < headerLen || offset + boxSize > end) return null
        return Triple(type, offset + headerLen, offset + boxSize)
    }

    private fun findMp4Covr(source: RandomAccessSource, start: Long, end: Long, depth: Int): ByteArray? {
        if (depth > 8) return null
        var offset = start
        while (offset + 8 <= end) {
            val (type, payloadStart, boxEnd) = mp4BoxHeader(source, offset, end) ?: return null
            when {
                type == "covr" -> findMp4DataPayload(source, payloadStart, boxEnd)?.let { return it }
                type == "meta" -> {
                    // 'meta' est une "full box" (4 octets version/flags) sous moov/udta — sauf chez
                    // certains encodeurs fautifs qui enchaînent directement les sous-boîtes.
                    val peek = readFully(source, payloadStart, 8)
                    val childStart = if (peek != null && looksLikeBoxTypeAt(peek, 4)) payloadStart else payloadStart + 4
                    findMp4Covr(source, childStart, boxEnd, depth + 1)?.let { return it }
                }
                type in MP4_CONTAINER_BOXES -> findMp4Covr(source, payloadStart, boxEnd, depth + 1)?.let { return it }
            }
            offset = boxEnd
        }
        return null
    }

    private fun looksLikeBoxTypeAt(bytes: ByteArray, offset: Int): Boolean {
        if (offset + 4 > bytes.size) return false
        return (offset until offset + 4).all { i -> bytes[i].toInt().toChar().isLetterOrDigit() }
    }

    private fun findMp4DataPayload(source: RandomAccessSource, start: Long, end: Long): ByteArray? {
        var offset = start
        while (offset + 8 <= end) {
            val (type, payloadStart, boxEnd) = mp4BoxHeader(source, offset, end) ?: return null
            if (type == "data") {
                // Boîte 'data' iTunes : 4 octets type indicateur + 4 octets locale, puis l'image brute.
                val dataStart = payloadStart + 8
                val dataLen = (boxEnd - dataStart).coerceAtMost(MAX_EMBEDDED_IMAGE_BYTES.toLong())
                if (dataLen > 0) return readFully(source, dataStart, dataLen.toInt())
            }
            offset = boxEnd
        }
        return null
    }

    // ---- OGG / Opus : METADATA_BLOCK_PICTURE ou COVERART encodé en Base64 ----

    private fun extractVorbisCommentPictureBytes(
        context: Context,
        path: String
    ): ByteArray? {
        val source = openRandomAccessSource(context, path) ?: return null
        return try {
            val length = when {
                source.size > 0L ->
                    minOf(source.size, VORBIS_COMMENT_SCAN_BYTES.toLong()).toInt()
                else -> VORBIS_COMMENT_SCAN_BYTES
            }
            val data = readFully(source, 0L, length) ?: return null

            extractBase64CommentValue(data, "METADATA_BLOCK_PICTURE=")
                ?.let { encoded ->
                    runCatching {
                        Base64.decode(encoded, Base64.DEFAULT)
                    }.getOrNull()
                }
                ?.let(::parseFlacPictureBlock)
                ?.let { return it }

            extractBase64CommentValue(data, "COVERART=")
                ?.let { encoded ->
                    runCatching {
                        Base64.decode(encoded, Base64.DEFAULT)
                    }.getOrNull()
                }
                ?.let { imageBytesFromFramePayload(it, false) ?: it }
        } catch (error: Throwable) {
            android.util.Log.w(
                "ThumbnailUtils",
                "Vorbis picture parse failed for ${safePathForLog(path)}",
                error
            )
            null
        } finally {
            runCatching { source.close() }
        }
    }

    private fun extractBase64CommentValue(
        bytes: ByteArray,
        key: String
    ): ByteArray? {
        val keyBytes = key.toByteArray(Charsets.US_ASCII)
        var start = -1
        for (index in 0..(bytes.size - keyBytes.size).coerceAtLeast(-1)) {
            var match = true
            for (keyIndex in keyBytes.indices) {
                val value = bytes[index + keyIndex]
                if (
                    value.toInt().toChar().uppercaseChar() !=
                    keyBytes[keyIndex].toInt().toChar().uppercaseChar()
                ) {
                    match = false
                    break
                }
            }
            if (match) {
                start = index + keyBytes.size
                break
            }
        }
        if (start < 0 || start >= bytes.size) return null

        var end = start
        while (end < bytes.size) {
            val c = bytes[end].toInt().toChar()
            if (
                c.isLetterOrDigit() ||
                c == '+' ||
                c == '/' ||
                c == '=' ||
                c == '\r' ||
                c == '\n'
            ) {
                end++
            } else {
                break
            }
        }
        if (end <= start) return null
        return bytes.copyOfRange(start, end)
    }

    // ---- APEv2 : tag accroché en fin de fichier (APE, WavPack, et parfois MP3/WAV/FLAC) ----

    private const val APE_FOOTER_SIZE = 32

    private fun extractApeV2PictureBytes(context: Context, path: String): ByteArray? {
        val source = openRandomAccessSource(context, path) ?: return null
        return try {
            if (source.size < APE_FOOTER_SIZE) return null
            val footer = readFully(source, source.size - APE_FOOTER_SIZE, APE_FOOTER_SIZE) ?: return null
            if (String(footer, 0, 8, Charsets.US_ASCII) != "APETAGEX") return null
            // "Tag Size" (spec APEv2) : taille des items + footer, EXCLUT l'en-tête optionnel de 32
            // octets qui peut précéder les items.
            val tagSize = leUInt32(footer, 12)
            val itemCount = leUInt32(footer, 16)
            if (tagSize <= APE_FOOTER_SIZE || tagSize > MAX_METADATA_SCAN_BYTES) return null
            if (itemCount <= 0 || itemCount > 4096) return null
            val itemsSize = (tagSize - APE_FOOTER_SIZE).toInt()
            val itemsStart = source.size - tagSize
            val body = readFully(source, itemsStart, itemsSize) ?: return null
            var offset = 0
            repeat(itemCount.toInt()) {
                if (offset + 8 > body.size) return null
                val valueSize = leUInt32(body, offset).toInt(); offset += 4
                val flags = leUInt32(body, offset).toInt(); offset += 4
                val keyEnd = (offset until body.size).firstOrNull { body[it] == 0.toByte() } ?: return null
                val key = String(body, offset, keyEnd - offset, Charsets.US_ASCII)
                offset = keyEnd + 1
                if (valueSize < 0 || offset + valueSize > body.size) return null
                val isBinary = ((flags shr 1) and 0x3) == 1
                if (isBinary && (key.equals("Cover Art (Front)", true) || key.equals("Cover Art (Back)", true))) {
                    val value = body.copyOfRange(offset, offset + valueSize)
                    // La valeur est "nom-de-fichier <octets image>" : on cherche la signature
                    // JPEG/PNG plutôt que de se fier à un unique octet NUL (le nom peut être vide).
                    imageBytesFromFramePayload(value, false)?.let { return it }
                }
                offset += valueSize
            }
            null
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "APEv2 picture parse failed for ${safePathForLog(path)}", e)
            null
        } finally {
            try { source.close() } catch (_: Exception) {}
        }
    }

    // ---- Dernier recours : signature JPEG/PNG brute, pour les conteneurs sans parseur dédié ----

    private fun genericSignatureScanBytes(
        context: Context,
        path: String
    ): ByteArray? {
        readLeadingBytes(context, path, GENERIC_SIGNATURE_SCAN_BYTES)
            ?.let { imageBytesFromFramePayload(it, false) }
            ?.let { return it }

        val source = openRandomAccessSource(context, path) ?: return null
        return try {
            if (source.size <= 0L) return null
            val length = minOf(
                GENERIC_SIGNATURE_SCAN_BYTES.toLong(),
                source.size
            ).toInt()
            val start = (source.size - length).coerceAtLeast(0L)
            readFully(source, start, length)
                ?.let { imageBytesFromFramePayload(it, false) }
        } finally {
            runCatching { source.close() }
        }
    }

    private fun readLeadingBytes(context: Context, path: String, maxBytes: Int): ByteArray? {
        fun fromStream(input: java.io.InputStream): ByteArray {
            val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (total < maxBytes) {
                val r = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (r <= 0) break
                out.write(buffer, 0, r)
                total += r
            }
            return out.toByteArray()
        }
        return try {
            when {
                path.startsWith("content://", true) -> context.contentResolver.openInputStream(Uri.parse(path))?.use(::fromStream)
                path.startsWith("file://", true) -> localFileForImagePath(path)?.takeIf { it.exists() }?.inputStream()?.use(::fromStream)
                path.startsWith("smb://", true) -> {
                    val source = openRandomAccessSource(context, path) ?: return null
                    try {
                        val len = minOf(maxBytes.toLong(), source.size).toInt()
                        readFully(source, 0, len)
                    } finally {
                        try { source.close() } catch (_: Exception) {}
                    }
                }
                path.startsWith("http://", true) ||
                    path.startsWith("https://", true) -> {
                    val source = openRandomAccessSource(context, path)
                        ?: return null
                    try {
                        val len = if (source.size > 0L) {
                            minOf(maxBytes.toLong(), source.size).toInt()
                        } else {
                            maxBytes
                        }
                        readFully(source, 0L, len)
                    } finally {
                        runCatching { source.close() }
                    }
                }
                else -> File(path).takeIf { it.exists() && it.isFile }?.inputStream()?.use(::fromStream)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Lit l'en-tête ID3v2 + son contenu via [RandomAccessSource] : local/SAF/SMB partagent le même
     *  chemin de lecture borné, au lieu de l'ancien lecteur SMB dédié qui découpait la lecture en
     *  petits blocs de 64 Ko (un aller-retour réseau par bloc — jusqu'à plusieurs dizaines pour une
     *  grosse pochette embarquée). [readFully] délègue à [fr.retrospare.blazeplayer.player.SmbMediaDataSource],
     *  qui négocie déjà des blocs bien plus larges (1 Mo) en interne : nettement moins d'allers-retours
     *  pour le même tag sur un partage réseau. */
    private fun readId3TagBytes(context: Context, path: String): ByteArray? {
        val source = openRandomAccessSource(context, path) ?: return null
        return try {
            val header = readFully(source, 0L, 10) ?: return null
            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
            val tagSize = synchsafeInt(header, 6).takeIf { it > 0 } ?: return null
            val bounded = tagSize.coerceAtMost(MAX_EMBEDDED_IMAGE_BYTES)
            val body = readFully(source, 10L, bounded) ?: return header
            val all = ByteArray(10 + body.size)
            System.arraycopy(header, 0, all, 0, 10)
            System.arraycopy(body, 0, all, 10, body.size)
            all
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to read ID3 tag for ${safePathForLog(path)}", e)
            null
        } finally {
            try { source.close() } catch (_: Exception) {}
        }
    }

    private fun synchsafeInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun normalInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private data class Id3ImageCandidate(
        val bytes: ByteArray,
        val pictureType: Int,
        val order: Int
    )

    private fun findImageBytesInId3Tag(tag: ByteArray): ByteArray? {
        if (
            tag.size < 10 ||
            tag[0] != 'I'.code.toByte() ||
            tag[1] != 'D'.code.toByte() ||
            tag[2] != '3'.code.toByte()
        ) {
            return null
        }
        val major = tag[3].toInt() and 0xFF
        if (major !in 2..4) {
            return imageBytesFromFramePayload(tag.copyOfRange(10, tag.size), false)
        }
        val flags = tag[5].toInt() and 0xFF
        val tagUnsynchronised = flags and 0x80 != 0
        val tagEnd = (10 + synchsafeInt(tag, 6)).coerceAtMost(tag.size)
        var offset = 10
        if (major >= 3 && flags and 0x40 != 0 && offset + 4 <= tagEnd) {
            val extSize = if (major == 4) synchsafeInt(tag, offset) else normalInt(tag, offset)
            if (extSize > 0) {
                val headerBytes = if (major == 3) 4 else 0
                offset = (offset + extSize + headerBytes).coerceAtMost(tagEnd)
            }
        }

        val candidates = mutableListOf<Id3ImageCandidate>()
        var order = 0
        val frameHeaderSize = if (major == 2) 6 else 10
        while (offset + frameHeaderSize <= tagEnd) {
            val frameStart = offset
            val idLength = if (major == 2) 3 else 4
            val id = String(tag, offset, idLength, Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' }) break
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) {
                offset++
                continue
            }

            val frameSize = if (major == 2) {
                ((tag[offset + 3].toInt() and 0xFF) shl 16) or
                    ((tag[offset + 4].toInt() and 0xFF) shl 8) or
                    (tag[offset + 5].toInt() and 0xFF)
            } else if (major == 4) {
                synchsafeInt(tag, offset + 4)
            } else {
                normalInt(tag, offset + 4)
            }
            val formatFlags = if (major >= 3) tag[offset + 9].toInt() and 0xFF else 0
            offset += frameHeaderSize
            if (frameSize <= 0 || offset + frameSize > tagEnd) {
                // Un frame corrompu ne doit pas condamner les APIC suivants. On reprend juste après
                // son en-tête et le scan global final récupérera aussi les tags très mal formés.
                offset = (frameStart + 1).coerceAtMost(tagEnd)
                continue
            }

            if (id == "APIC" || id == "PIC") {
                val rawPayload = tag.copyOfRange(offset, offset + frameSize)
                normaliseId3ArtworkPayload(
                    rawPayload,
                    major,
                    formatFlags,
                    tagUnsynchronised
                )?.let { payload ->
                    val pictureType = id3PictureType(payload, major)
                    val imageSearchStart = id3ImageSearchStart(payload, major)
                    imageByteCandidates(payload, imageSearchStart).forEach { image ->
                        candidates += Id3ImageCandidate(image, pictureType, order++)
                    }
                }
            }
            offset += frameSize
        }

        // Les tailles de frame peuvent devenir fausses après désynchronisation sur certains tags
        // historiques. Un scan borné du corps complet sert de filet de sécurité et récupère aussi
        // une seconde APIC lorsque la première est une icône ou une image invalide.
        val body = tag.copyOfRange(10, tagEnd)
        val cleanBody = if (tagUnsynchronised) removeId3Unsynchronisation(body) else body
        imageByteCandidates(cleanBody, 0).forEach { image ->
            candidates += Id3ImageCandidate(image, 0, order++)
        }

        return candidates
            .distinctBy { candidate ->
                val bytes = candidate.bytes
                buildString {
                    append(bytes.size)
                    append(':')
                    repeat(minOf(16, bytes.size)) { index ->
                        append((bytes[index].toInt() and 0xFF).toString(16))
                    }
                }
            }
            .maxWithOrNull(
                compareBy<Id3ImageCandidate> { id3ImageCandidateScore(it) }
                    .thenByDescending { it.order }
            )
            ?.bytes
    }

    private fun normaliseId3ArtworkPayload(
        payload: ByteArray,
        major: Int,
        formatFlags: Int,
        tagUnsynchronised: Boolean
    ): ByteArray? {
        var start = 0
        var frameUnsynchronised = false
        when (major) {
            3 -> {
                val compressed = formatFlags and 0x80 != 0
                val encrypted = formatFlags and 0x40 != 0
                if (compressed || encrypted) return null
                if (formatFlags and 0x20 != 0) start += 1 // group identifier
            }
            4 -> {
                val grouped = formatFlags and 0x40 != 0
                val compressed = formatFlags and 0x08 != 0
                val encrypted = formatFlags and 0x04 != 0
                frameUnsynchronised = formatFlags and 0x02 != 0
                val hasDataLengthIndicator = formatFlags and 0x01 != 0
                if (compressed || encrypted) return null
                if (grouped) start += 1
                if (hasDataLengthIndicator) start += 4
            }
        }
        if (start !in 0 until payload.size) return null
        val trimmed = payload.copyOfRange(start, payload.size)
        return if (tagUnsynchronised || frameUnsynchronised) {
            removeId3Unsynchronisation(trimmed)
        } else {
            trimmed
        }
    }

    private fun id3PictureType(payload: ByteArray, major: Int): Int {
        if (payload.isEmpty()) return 0
        return if (major == 2) {
            payload.getOrNull(4)?.toInt()?.and(0xFF) ?: 0
        } else {
            var mimeEnd = 1
            while (mimeEnd < payload.size && payload[mimeEnd] != 0.toByte()) mimeEnd++
            payload.getOrNull(mimeEnd + 1)?.toInt()?.and(0xFF) ?: 0
        }
    }

    private fun id3ImageSearchStart(payload: ByteArray, major: Int): Int {
        if (payload.isEmpty()) return 0
        return if (major == 2) {
            5.coerceAtMost(payload.size)
        } else {
            var mimeEnd = 1
            while (mimeEnd < payload.size && payload[mimeEnd] != 0.toByte()) mimeEnd++
            (mimeEnd + 2).coerceAtMost(payload.size)
        }
    }

    private fun id3ImageCandidateScore(candidate: Id3ImageCandidate): Long {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            BitmapFactory.decodeByteArray(candidate.bytes, 0, candidate.bytes.size, options)
        }
        val valid = options.outWidth > 0 && options.outHeight > 0
        if (!valid) return Long.MIN_VALUE + candidate.bytes.size
        val typePriority = when (candidate.pictureType) {
            3 -> 5L // front cover
            1, 2 -> 1L // icône de fichier : jamais prioritaire face à une vraie pochette
            4 -> 2L // back cover
            0 -> 3L // autre / type inconnu
            else -> 3L
        }
        val area = options.outWidth.toLong() * options.outHeight.toLong()
        return typePriority * 1_000_000_000_000L +
            area.coerceAtMost(999_999_999L) * 1_000L +
            candidate.bytes.size.coerceAtMost(999).toLong()
    }

    private fun imageByteCandidates(payload: ByteArray, fromIndex: Int = 0): List<ByteArray> {
        if (payload.size < 16) return emptyList()
        val result = mutableListOf<ByteArray>()
        var cursor = fromIndex.coerceIn(0, payload.size)
        while (cursor < payload.size - 3) {
            val jpeg = indexOfBytes(
                payload,
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
                cursor
            )
            val png = indexOfBytes(
                payload,
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                cursor
            )
            val webp = findWebpStart(payload, cursor)
            val start = listOf(jpeg, png, webp).filter { it >= 0 }.minOrNull() ?: break
            val bytes = extractImageBytesAt(payload, start, png, webp)
            if (bytes != null) result += bytes
            cursor = (start + maxOf(bytes?.size ?: 0, 4)).coerceAtMost(payload.size)
        }
        return result
    }

    private fun extractImageBytesAt(
        bytes: ByteArray,
        start: Int,
        pngStart: Int,
        webpStart: Int
    ): ByteArray? {
        val end = when (start) {
            pngStart -> indexOfBytes(
                bytes,
                byteArrayOf(0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()),
                start
            ).takeIf { it >= 0 }?.plus(8) ?: bytes.size

            webpStart -> {
                val riffSize = leUInt32(bytes, start + 4)
                (start.toLong() + riffSize + 8L)
                    .coerceAtMost(bytes.size.toLong())
                    .toInt()
            }

            else -> indexOfBytes(
                bytes,
                byteArrayOf(0xFF.toByte(), 0xD9.toByte()),
                start + 2
            ).takeIf { it >= 0 }?.plus(2) ?: bytes.size
        }
        return bytes.copyOfRange(start, end.coerceIn(start, bytes.size))
            .takeIf { it.size > 16 }
    }

    private fun imageBytesFromFramePayload(
        payload: ByteArray,
        unsynchronised: Boolean
    ): ByteArray? {
        val clean = if (unsynchronised) {
            removeId3Unsynchronisation(payload)
        } else {
            payload
        }

        val jpegStart = indexOfBytes(
            clean,
            byteArrayOf(
                0xFF.toByte(),
                0xD8.toByte(),
                0xFF.toByte()
            )
        )
        val pngStart = indexOfBytes(
            clean,
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47
            )
        )
        val webpStart = findWebpStart(clean)
        val start = listOf(jpegStart, pngStart, webpStart)
            .filter { it >= 0 }
            .minOrNull()
            ?: return null

        val end = when (start) {
            pngStart -> indexOfBytes(
                clean,
                byteArrayOf(
                    0x49,
                    0x45,
                    0x4E,
                    0x44,
                    0xAE.toByte(),
                    0x42,
                    0x60,
                    0x82.toByte()
                ),
                start
            ).takeIf { it >= 0 }?.plus(8) ?: clean.size

            webpStart -> {
                val riffSize = leUInt32(clean, start + 4)
                (start.toLong() + riffSize + 8L)
                    .coerceAtMost(clean.size.toLong())
                    .toInt()
            }

            else -> indexOfBytes(
                clean,
                byteArrayOf(
                    0xFF.toByte(),
                    0xD9.toByte()
                ),
                start + 2
            ).takeIf { it >= 0 }?.plus(2) ?: clean.size
        }

        return clean.copyOfRange(
            start,
            end.coerceIn(start, clean.size)
        ).takeIf { it.size > 16 }
    }

    private fun findWebpStart(bytes: ByteArray, fromIndex: Int = 0): Int {
        var index = fromIndex.coerceAtLeast(0)
        while (index + 12 <= bytes.size) {
            val riff = bytes[index] == 'R'.code.toByte() &&
                bytes[index + 1] == 'I'.code.toByte() &&
                bytes[index + 2] == 'F'.code.toByte() &&
                bytes[index + 3] == 'F'.code.toByte()
            val webp = bytes[index + 8] == 'W'.code.toByte() &&
                bytes[index + 9] == 'E'.code.toByte() &&
                bytes[index + 10] == 'B'.code.toByte() &&
                bytes[index + 11] == 'P'.code.toByte()
            if (riff && webp) return index
            index++
        }
        return -1
    }

    private fun removeId3Unsynchronisation(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            out.write(b.toInt())
            if (b == 0xFF.toByte() && i + 1 < bytes.size && bytes[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, fromIndex: Int = 0): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        var i = fromIndex.coerceAtLeast(0)
        while (i <= haystack.size - needle.size) {
            var ok = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) { ok = false; break }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    /** Lecture RAM pure de la frame précoce réservée au navigateur vidéo. */
    fun peekMemoryEarlyVideoFrameBitmap(path: String): Bitmap? {
        if (isAudioPath(path)) return null
        val key = browserEarlyFrameKey(path)
        videoThumbnailHotCache.get(key)?.let { return it }
        return cache.get(key)?.also { promoteVideoHotCache(key, it) }
    }

    /** Extrait une vraie frame vidéo avant 10 secondes et la conserve dans un cache distinct. */
    suspend fun getEarlyVideoFrameBitmap(
        context: Context,
        path: String,
        timeUs: Long = 5_000_000L
    ): Bitmap? = coroutineScope {
        if (isAudioPath(path)) return@coroutineScope null
        peekMemoryEarlyVideoFrameBitmap(path)?.let { return@coroutineScope it }

        val key = browserEarlyFrameKey(path)
        inFlight[key]?.let { return@coroutineScope it.await() }
        val deferred = async(Dispatchers.IO) {
            videoThumbnailHotCache.get(key)?.let { return@async it }
            cache.get(key)?.let {
                promoteVideoHotCache(key, it)
                return@async it
            }
            readFromDisk(context.applicationContext, key)?.let { cached ->
                cache.put(key, cached)
                promoteVideoHotCache(key, cached)
                return@async cached
            }

            val safeTimeUs = timeUs.coerceIn(250_000L, 9_500_000L)
            val timeoutMs = if (isNetworkVideoPath(path)) 4_500L else 2_500L
            val extracted = withTimeoutOrNull(timeoutMs) {
                if (isNetworkVideoPath(path)) {
                    networkThumbnailSemaphore.withPermit {
                        extractEarlyVideoFrameInternal(context.applicationContext, path, safeTimeUs, key)
                    }
                } else {
                    extractEarlyVideoFrameInternal(context.applicationContext, path, safeTimeUs, key)
                }
            }
            if (extracted == null) {
                android.util.Log.w("ThumbnailUtils", "Early browser frame timeout/failure for ${safePathForLog(path)}")
            }
            extracted
        }
        inFlight[key] = deferred
        try {
            deferred.await()
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    /** Retourne uniquement une miniature déjà présente en RAM. Cette méthode est sûre dans
     *  onBindViewHolder : aucune lecture disque, MediaStore ou réseau n'est effectuée. */
    fun peekMemoryThumbnailBitmap(path: String): Bitmap? {
        if (!isAudioPath(path)) {
            val customKey = customVideoKey(path)
            videoThumbnailHotCache.get(customKey)?.let { return it }
            cache.get(customKey)?.let {
                promoteVideoHotCache(customKey, it)
                return it
            }
        }
        val key = thumbnailKey(path)
        if (!isAudioPath(path)) {
            videoThumbnailHotCache.get(key)?.let { return it }
        }
        return cache.get(key)?.also {
            if (!isAudioPath(path)) promoteVideoHotCache(key, it)
        }
    }

    /** Retourne une image déjà en cache mémoire/disque, sans extraction réseau. À appeler hors du
     *  thread principal si une lecture disque est possible. */
    fun getCachedThumbnailBitmap(context: Context, path: String): Bitmap? {
        peekMemoryThumbnailBitmap(path)?.let { return it }
        if (!isAudioPath(path)) {
            val customKey = customVideoKey(path)
            readFromDisk(context.applicationContext, customKey)?.let {
                cache.put(customKey, it)
                promoteVideoHotCache(customKey, it)
                return it
            }
        }
        val key = thumbnailKey(path)
        return readFromDisk(context.applicationContext, key)?.also {
            cache.put(key, it)
            if (!isAudioPath(path)) promoteVideoHotCache(key, it)
        }
    }

    /** Charge uniquement les JPEG déjà présents dans le cache disque vers le cache RAM vidéo.
     *  Aucune vidéo n'est ouverte et aucune connexion NAS n'est créée. */
    suspend fun prewarmCachedVideoThumbnails(
        context: Context,
        paths: List<String>,
        limit: Int = 24
    ) = coroutineScope {
        val appContext = context.applicationContext
        val semaphore = Semaphore(3)
        paths.asSequence()
            .filter { it.isNotBlank() && !isAudioPath(it) }
            .distinct()
            .take(limit.coerceAtLeast(0))
            .map { path ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (peekMemoryThumbnailBitmap(path) != null) return@withPermit
                        val customKey = customVideoKey(path)
                        val custom = readFromDisk(appContext, customKey)
                        if (custom != null) {
                            cache.put(customKey, custom)
                            promoteVideoHotCache(customKey, custom)
                            return@withPermit
                        }
                        val key = thumbnailKey(path)
                        readFromDisk(appContext, key)?.let { bitmap ->
                            cache.put(key, bitmap)
                            promoteVideoHotCache(key, bitmap)
                        }
                    }
                }
            }
            .toList()
            .forEach { it.await() }
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
            promoteVideoHotCache(key, scaled)
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
        videoThumbnailHotCache.remove(key)
        deleteFromDisk(context.applicationContext, key)
    }

    fun hasCachedVideoPlaybackSnapshot(context: Context, videoPath: String): Boolean {
        // Conservé pour compatibilité binaire avec les anciens appelants, mais les miniatures
        // vidéo ne dépendent plus d'un snapshot capturé pendant la lecture.
        return false
    }

    /**
     * Compatibilité avec l'ancien système de snapshot pendant lecture. On ne l'utilise plus pour
     * alimenter les miniatures : la galerie et le navigateur passent par l'extraction légère
     * cachée, qui remplit les vidéos jamais lues et évite les cases noires.
     */
    fun cacheVideoPlaybackSnapshot(context: Context, videoPath: String, bitmap: Bitmap?): Boolean = false

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
            android.util.Log.w("ThumbnailUtils", "Failed to cache artwork for ${safePathForLog(path)}", e)
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
        // Le bind RecyclerView peut récupérer une vignette chaude sans changer de dispatcher.
        peekMemoryThumbnailBitmap(path)?.let { return@coroutineScope it }

        val key = thumbnailKey(path)
        // Déduplique les demandes simultanées causées par RecyclerView + notification.
        inFlight[key]?.let { return@coroutineScope it.await() }

        // Toute lecture disque et toute extraction sont maintenant faites sur Dispatchers.IO.
        // Auparavant readFromDisk() s'exécutait avant le changement de dispatcher lorsque
        // loadThumbnail() était appelé depuis lifecycleScope(Main), ce qui ajoutait une micro-
        // saccade visible et retardait le premier rendu de la grille.
        val deferred = async(Dispatchers.IO) {
            if (!isAudioPath(path)) {
                val customKey = customVideoKey(path)
                videoThumbnailHotCache.get(customKey)?.let { return@async it }
                cache.get(customKey)?.let {
                    promoteVideoHotCache(customKey, it)
                    return@async it
                }
                readFromDisk(context.applicationContext, customKey)?.let { custom ->
                    cache.put(customKey, custom)
                    promoteVideoHotCache(customKey, custom)
                    return@async custom
                }
            }

            if (!isAudioPath(path)) {
                videoThumbnailHotCache.get(key)?.let { return@async it }
            }
            cache.get(key)?.let {
                if (!isAudioPath(path)) promoteVideoHotCache(key, it)
                return@async it
            }
            readFromDisk(context.applicationContext, key)?.let { fromDisk ->
                cache.put(key, fromDisk)
                if (!isAudioPath(path)) promoteVideoHotCache(key, fromDisk)
                return@async fromDisk
            }

            val timeoutMs = when {
                isAudioPath(path) -> 6_000L
                isNetworkVideoPath(path) -> 4_500L
                else -> 2_500L
            }
            val extracted = withTimeoutOrNull(timeoutMs) {
                if (!isAudioPath(path) && isNetworkVideoPath(path)) {
                    networkThumbnailSemaphore.withPermit {
                        extractThumbnailInternal(context.applicationContext, path, timeUs)
                    }
                } else {
                    extractThumbnailInternal(context.applicationContext, path, timeUs)
                }
            } ?: run {
                android.util.Log.w("ThumbnailUtils", "Thumbnail timeout for ${safePathForLog(path)}")
                null
            }
            if (extracted != null && !isAudioPath(path)) {
                promoteVideoHotCache(key, extracted)
            }
            extracted
        }
        inFlight[key] = deferred
        try {
            deferred.await()
        } finally {
            inFlight.remove(key, deferred)
        }
    }


    private fun tryLoadSystemVideoThumbnail(context: Context, path: String): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return null
        if (!path.startsWith("content://", ignoreCase = true)) return null
        return try {
            context.contentResolver.loadThumbnail(Uri.parse(path), android.util.Size(512, 512), null)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractEarlyVideoFrameInternal(
        context: Context,
        path: String,
        requestedTimeUs: Long,
        cacheKey: String
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        var closeable: AutoCloseable? = null
        return try {
            closeable = setRetrieverDataSource(context, retriever, path)
            val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.times(1000L)
            val targetUs = when {
                durationUs == null || durationUs <= 0L -> requestedTimeUs
                durationUs <= 750_000L -> (durationUs / 2L).coerceAtLeast(100_000L)
                durationUs <= requestedTimeUs -> (durationUs / 2L).coerceAtLeast(250_000L)
                else -> requestedTimeUs
            }.coerceAtMost(9_500_000L)

            // OPTION_CLOSEST donne une frame réellement proche de 5 s pour les fichiers locaux.
            // Pour SMB/HTTP, OPTION_CLOSEST_SYNC limite fortement les lectures aléatoires tout en
            // restant dans la zone de début grâce à une cible de 5 s maximum.
            val primaryOption = if (isNetworkVideoPath(path)) {
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            } else {
                MediaMetadataRetriever.OPTION_CLOSEST
            }
            var bitmap = retriever.getFrameAtTime(targetUs, primaryOption)
            if (bitmap == null) {
                bitmap = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            bitmap?.let {
                val scaled = scaleBitmap(it, if (isNetworkVideoPath(path)) 180 else 240)
                cache.put(cacheKey, scaled)
                promoteVideoHotCache(cacheKey, scaled)
                writeToDisk(context, cacheKey, scaled)
                scaled
            }
        } catch (error: Throwable) {
            android.util.Log.w("ThumbnailUtils", "Unable to extract early browser frame for ${safePathForLog(path)}", error)
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
            try { closeable?.close() } catch (_: Throwable) {}
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
                extractAudioArtworkInternal(context, path)?.let {
                    val scaled = scaleBitmap(it, 160)
                    cache.put(key, scaled)
                    writeToDisk(context, key, scaled)
                    val simpleThumbKey = "audio-thumb-v3:${audioNoProbeKey(path)}"
                    cache.put(simpleThumbKey, scaled)
                    writeToDisk(context, simpleThumbKey, scaled)
                    scaled
                }
            } else {
                // 1) D'abord demander la miniature système pour les vidéos locales content:// :
                // Android la sert souvent depuis son propre cache MediaStore, donc c'est beaucoup
                // plus léger que d'ouvrir le conteneur vidéo nous-mêmes.
                tryLoadSystemVideoThumbnail(context, path)?.let { systemThumb ->
                    val scaled = scaleBitmap(systemThumb, 360)
                    cache.put(key, scaled)
                    writeToDisk(context, key, scaled)
                    return scaled
                }

                // 2) Fallback borné : une seule frame proche de 10s/20% selon la durée. Le tout
                // est déjà sous timeout dans getThumbnailBitmap(), donc un MP4 bizarre ou un NAS
                // lent ne bloque pas le scroll.
                val retriever = MediaMetadataRetriever()
                var closeable: AutoCloseable? = null
                try {
                    closeable = setRetrieverDataSource(context, retriever, path)
                    val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.let { it * 1000L }
                    val targetUs = when {
                        durationUs != null && durationUs < 5_000_000L -> (durationUs / 2L).coerceAtLeast(250_000L)
                        durationUs != null && durationUs < timeUs -> (durationUs * 0.20f).toLong().coerceAtLeast(500_000L)
                        else -> timeUs
                    }
                    var bitmap = retriever.getFrameAtTime(targetUs, option)
                    if (bitmap == null && !isNetworkVideoPath(path)) bitmap = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap == null) bitmap = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap == null && !isNetworkVideoPath(path)) bitmap = retriever.frameAtTime
                    bitmap?.let {
                        val scaled = scaleBitmap(it, if (isNetworkVideoPath(path)) 180 else 360)
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
            android.util.Log.e("ThumbnailUtils", "Failed to get thumbnail bitmap for ${safePathForLog(path)}", e)
            null
        }
    }

    suspend fun getImageThumbnailBitmap(
        context: Context,
        path: String,
        maxSize: Int = 360
    ): Bitmap? {
        peekMemoryImageThumbnailBitmap(path, maxSize)?.let { return it }
        return withContext(imageThumbnailDispatcher) {
            val appContext = context.applicationContext
            peekMemoryImageThumbnailBitmap(path, maxSize)?.let { return@withContext it }
            val key = imageThumbnailKey(appContext, path, maxSize)

            cache.get(key)?.let {
                promoteGalleryHotCache(path, maxSize, it)
                return@withContext it
            }
            readFromDisk(appContext, key)?.let { fromDisk ->
                cache.put(key, fromDisk)
                promoteGalleryHotCache(path, maxSize, fromDisk)
                return@withContext fromDisk
            }

            val inFlightKey = galleryHotKey(path, maxSize)
            val existing = inFlight[inFlightKey]
            if (existing != null) return@withContext existing.await()

            val deferred = kotlinx.coroutines.CompletableDeferred<Bitmap?>()
            val winner = inFlight.putIfAbsent(inFlightKey, deferred)
            if (winner != null) return@withContext winner.await()

            try {
                val result = extractImageThumbnailInternal(appContext, path, key, maxSize)
                deferred.complete(result)
                result
            } catch (throwable: Throwable) {
                deferred.completeExceptionally(throwable)
                throw throwable
            } finally {
                inFlight.remove(inFlightKey, deferred)
            }
        }
    }

    /** Réchauffe plusieurs miniatures en parallèle. Les premières tuiles sont appelées avant la
     * pose de l'adapter ; le reste continue silencieusement dans le cache système/RAM/disque. */
    suspend fun warmImageThumbnails(
        context: Context,
        paths: List<String>,
        maxSize: Int = 360,
        limit: Int = paths.size,
        concurrency: Int = 6
    ) = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, 8))
        paths.asSequence().filter { it.isNotBlank() }.distinct().take(limit.coerceAtLeast(0))
            .map { path ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { getImageThumbnailBitmap(context.applicationContext, path, maxSize) }
                }
            }.toList().forEach { it.await() }
    }

    suspend fun warmVideoThumbnails(
        context: Context,
        paths: List<String>,
        limit: Int = paths.size,
        concurrency: Int = 4
    ) = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, 6))
        paths.asSequence().filter { it.isNotBlank() }.distinct().take(limit.coerceAtLeast(0))
            .map { path ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { getThumbnailBitmap(context.applicationContext, path) }
                }
            }.toList().forEach { it.await() }
    }

    /** Prépare automatiquement les médias récents dès le lancement du processus, comme une galerie
     * système : MediaStore fournit ses miniatures déjà indexées avant même l'ouverture de l'onglet. */
    fun scheduleGalleryMediaStoreWarmup(context: Context, initialDelayMs: Long = 900L) {
        if (!galleryWarmupStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        galleryWarmupScope.launch {
            if (initialDelayMs > 0L) delay(initialDelayMs)
            val recentImages = queryRecentMediaUris(appContext, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 160)
            val recentVideos = queryRecentMediaUris(appContext, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 72)
            // Au tout premier lancement, les permissions peuvent ne pas encore être accordées.
            // Autorise alors un nouvel appel depuis l'écran Galerie au lieu de considérer le warmup
            // comme définitivement effectué avec une liste vide.
            if (recentImages.isEmpty() && recentVideos.isEmpty()) {
                galleryWarmupStarted.set(false)
                return@launch
            }
            warmImageThumbnails(appContext, recentImages, maxSize = 280, concurrency = 6)
            recentVideos.chunked(4).forEach { batch ->
                batch.map { path -> async(Dispatchers.IO) { getThumbnailBitmap(appContext, path) } }
                    .forEach { it.await() }
                delay(20L)
            }
        }
    }

    private fun queryRecentMediaUris(context: Context, collection: Uri, limit: Int): List<String> {
        return try {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val out = ArrayList<String>(limit)
            val queryArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.os.Bundle().apply {
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
                    putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
                }
            } else null
            val cursor = if (queryArgs != null) {
                context.contentResolver.query(collection, projection, queryArgs, null)
            } else {
                context.contentResolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
            }
            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (it.moveToNext() && out.size < limit) {
                    out += ContentUris.withAppendedId(collection, it.getLong(idCol)).toString()
                }
            }
            out
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractImageThumbnailInternal(context: Context, path: String, key: String, maxSize: Int): Bitmap? {
        return try {
            peekMemoryImageThumbnailBitmap(path, maxSize)?.let { return it }
            cache.get(key)?.let {
                promoteGalleryHotCache(path, maxSize, it)
                return it
            }
            readFromDisk(context, key)?.let { fromDisk ->
                cache.put(key, fromDisk)
                promoteGalleryHotCache(path, maxSize, fromDisk)
                return fromDisk
            }

            // Android 10+ : ContentResolver.loadThumbnail() interroge directement le cache de
            // miniatures MediaStore utilisé par les galeries système. C'est beaucoup plus rapide
            // que rouvrir et redécoder le JPEG original à chaque bind.
            val systemThumbnail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && path.startsWith("content://", true)) {
                runCatching {
                    context.contentResolver.loadThumbnail(Uri.parse(path), android.util.Size(maxSize, maxSize), null)
                }.getOrNull()
            } else null

            val decoded = systemThumbnail ?: decodeSampledImageBitmap(context, path, maxSize) ?: return null
            val scaled = scaleBitmap(decoded, maxSize)
            cache.put(key, scaled)
            promoteGalleryHotCache(path, maxSize, scaled)
            writeToDisk(context, key, scaled)
            scaled
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to cache gallery image thumbnail for ${safePathForLog(path)}", e)
            null
        }
    }

    private fun openImageInputStream(
        context: Context,
        path: String
    ): java.io.InputStream? = when {
        path.startsWith("content://", true) ->
            context.contentResolver.openInputStream(Uri.parse(path))

        path.startsWith("http://", true) || path.startsWith("https://", true) -> {
            val connection = java.net.URL(path).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            val input = connection.inputStream
            object : java.io.FilterInputStream(input) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        }

        path.startsWith("file://", true) ->
            localFileForImagePath(path)?.takeIf { it.exists() && it.isFile }?.inputStream()

        else ->
            localFileForImagePath(path)?.takeIf { it.exists() && it.isFile }?.inputStream()
    }

    private fun decodeSampledImageBitmap(context: Context, path: String, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openImageInputStream(context, path)?.use { input -> BitmapFactory.decodeStream(input, null, bounds) }

        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sample = 1
            while ((bounds.outWidth / sample) > maxSize * 2 || (bounds.outHeight / sample) > maxSize * 2) {
                sample *= 2
            }
            val decoded = openImageInputStream(context, path)?.use { input ->
                BitmapFactory.decodeStream(
                    input,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample.coerceAtLeast(1)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inDither = true
                    }
                )
            }
            if (decoded != null) return decoded
        }

        // Un fichier peut rester exploitable malgré des bounds invalides (JPEG tronqué, octets
        // parasites avant SOI, EOI manquant). On charge au plus 32 Mo et on tente les décodeurs
        // tolérants, dont ImageDecoder avec acceptation des images partielles sur Android 9+.
        val bytes = readImageBytesBounded(context, path, MAX_FOLDER_COVER_IMAGE_BYTES.toInt()) ?: return null
        return decodeByteArraySampled(bytes, bytes.size, maxSize)
    }

    private fun readImageBytesBounded(context: Context, path: String, maxBytes: Int): ByteArray? = try {
        openImageInputStream(context, path)?.use { input ->
            val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 128 * 1024))
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > maxBytes) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    } catch (_: Exception) {
        null
    }

    /** Décode uniquement une image dont le conteneur a été reçu en entier. Cette variante est
     * utilisée pour les pochettes audio persistées : elle interdit les bitmaps partielles que
     * BitmapFactory/ImageDecoder peuvent produire avec un JPEG réseau interrompu. */
    private fun decodeByteArraySampledStrict(data: ByteArray, length: Int, maxSize: Int): Bitmap? {
        val payload = completeImagePayload(data, length) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload, 0, payload.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while ((bounds.outWidth / sample) > maxSize * 2 || (bounds.outHeight / sample) > maxSize * 2) {
            sample *= 2
        }
        BitmapFactory.decodeByteArray(
            payload,
            0,
            payload.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inDither = true
            }
        )?.let { return it }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(payload))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                val largest = maxOf(info.size.width, info.size.height)
                if (info.size.width > 0 && info.size.height > 0 && largest > maxSize * 2) {
                    val scale = (maxSize * 2f) / largest.toFloat()
                    decoder.setTargetSize(
                        maxOf(1, (info.size.width * scale).toInt()),
                        maxOf(1, (info.size.height * scale).toInt())
                    )
                }
                // Aucun OnPartialImageListener ici : une image incomplète doit échouer et laisser
                // l'application essayer la cover suivante ou la pochette embarquée.
            }
        }.getOrNull()
    }

    private fun completeImagePayload(data: ByteArray, length: Int): ByteArray? {
        val safeLength = length.coerceAtMost(data.size)
        if (safeLength < 12) return null

        val jpegStart = findSignature(data, safeLength, byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        if (jpegStart >= 0) {
            val jpegEnd = findLastSignature(
                data,
                jpegStart + 2,
                safeLength,
                byteArrayOf(0xFF.toByte(), 0xD9.toByte())
            )
            if (jpegEnd < 0) return null
            return data.copyOfRange(jpegStart, jpegEnd + 2)
        }

        val webpStart = findWebpStart(
            data.copyOfRange(0, safeLength)
        )
        if (webpStart >= 0 && webpStart + 12 <= safeLength) {
            val riffSize = leUInt32(data, webpStart + 4)
            val webpEnd = (
                webpStart.toLong() + riffSize + 8L
            ).coerceAtMost(safeLength.toLong()).toInt()
            if (webpEnd > webpStart + 12) {
                return data.copyOfRange(webpStart, webpEnd)
            }
        }

        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val pngStart = findSignature(data, safeLength, pngSignature)
        if (pngStart >= 0) {
            val iendType = findLastSignature(
                data,
                pngStart + pngSignature.size,
                safeLength,
                byteArrayOf(0x49, 0x45, 0x4E, 0x44)
            )
            // Après le type IEND viennent les 4 octets CRC. La longueur du chunk se trouve juste
            // avant le type et fait déjà partie de la tranche depuis pngStart.
            if (iendType < 0 || iendType + 8 > safeLength) return null
            return data.copyOfRange(pngStart, iendType + 8)
        }
        return null
    }

    private fun decodeByteArraySampled(data: ByteArray, length: Int, maxSize: Int): Bitmap? {
        if (length <= 0) return null
        val safeLength = length.coerceAtMost(data.size)
        decodeByteArrayRegion(data, 0, safeLength, maxSize)?.let { return it }

        // Recherche d'une vraie signature si le fichier contient un préambule parasite. Pour les
        // JPEG incomplets, on ajoute également le marqueur EOI : BitmapFactory et ImageDecoder
        // peuvent alors restituer la partie valide déjà présente au lieu de retourner null.
        val repaired = repairImagePayload(data, safeLength) ?: return null
        return decodeByteArrayRegion(repaired, 0, repaired.size, maxSize)
    }

    private fun decodeByteArrayRegion(
        data: ByteArray,
        offset: Int,
        length: Int,
        maxSize: Int
    ): Bitmap? {
        if (length <= 0 || offset < 0 || offset + length > data.size) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, offset, length, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sample = 1
            while ((bounds.outWidth / sample) > maxSize * 2 || (bounds.outHeight / sample) > maxSize * 2) {
                sample *= 2
            }
            BitmapFactory.decodeByteArray(
                data,
                offset,
                length,
                BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceAtLeast(1)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inDither = true
                }
            )?.let { return it }
        }

        // Deuxième essai sans phase de bounds : quelques JPEG mal formés sont refusés par le scan
        // d'en-tête mais décodés directement par les codecs de certains appareils.
        BitmapFactory.decodeByteArray(
            data,
            offset,
            length,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inDither = true
            }
        )?.let { return it }

        return decodePartialWithImageDecoder(data, offset, length, maxSize)
    }

    private fun decodePartialWithImageDecoder(
        data: ByteArray,
        offset: Int,
        length: Int,
        maxSize: Int
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(data, offset, length).slice())
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.setOnPartialImageListener { true }
                val width = info.size.width
                val height = info.size.height
                val largest = maxOf(width, height)
                if (width > 0 && height > 0 && largest > maxSize * 2) {
                    val scale = (maxSize * 2f) / largest.toFloat()
                    decoder.setTargetSize(
                        maxOf(1, (width * scale).toInt()),
                        maxOf(1, (height * scale).toInt())
                    )
                }
            }
        }.getOrNull()
    }

    private fun repairImagePayload(data: ByteArray, length: Int): ByteArray? {
        val safeLength = length.coerceAtMost(data.size)
        val jpegStart = findSignature(data, safeLength, byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        if (jpegStart >= 0) {
            val jpegEndMarker = findLastSignature(data, jpegStart + 2, safeLength, byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
            val sourceEnd = if (jpegEndMarker >= 0) jpegEndMarker + 2 else safeLength
            val appendEoi = jpegEndMarker < 0
            if (jpegStart == 0 && sourceEnd == safeLength && !appendEoi) return null
            return ByteArray(sourceEnd - jpegStart + if (appendEoi) 2 else 0).also { repaired ->
                data.copyInto(repaired, 0, jpegStart, sourceEnd)
                if (appendEoi) {
                    repaired[repaired.lastIndex - 1] = 0xFF.toByte()
                    repaired[repaired.lastIndex] = 0xD9.toByte()
                }
            }
        }

        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val pngStart = findSignature(data, safeLength, pngSignature)
        if (pngStart > 0) return data.copyOfRange(pngStart, safeLength)
        return null
    }

    private fun findSignature(data: ByteArray, length: Int, signature: ByteArray): Int {
        if (signature.isEmpty() || length < signature.size) return -1
        for (i in 0..(length - signature.size)) {
            var matches = true
            for (j in signature.indices) {
                if (data[i + j] != signature[j]) {
                    matches = false
                    break
                }
            }
            if (matches) return i
        }
        return -1
    }

    private fun findLastSignature(data: ByteArray, start: Int, length: Int, signature: ByteArray): Int {
        if (signature.isEmpty() || length < signature.size) return -1
        for (i in (length - signature.size) downTo start.coerceAtLeast(0)) {
            var matches = true
            for (j in signature.indices) {
                if (data[i + j] != signature[j]) {
                    matches = false
                    break
                }
            }
            if (matches) return i
        }
        return -1
    }

    suspend fun loadImageThumbnail(
        context: Context,
        path: String,
        imageView: ImageView,
        maxSize: Int = 360
    ): Boolean {
        val bitmap = peekMemoryImageThumbnailBitmap(path, maxSize)
            ?: getImageThumbnailBitmap(context, path, maxSize)
        return withContext(Dispatchers.Main) {
            if (imageView.getTag(R.id.ivThumbnail) != path) return@withContext false
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                imageView.setBackgroundColor(0x00000000)
                true
            } else {
                false
            }
        }
    }

    suspend fun loadEarlyVideoFrame(
        context: Context,
        path: String,
        imageView: ImageView,
        timeUs: Long = 5_000_000L
    ): Boolean {
        imageView.setTag(R.id.ivThumbnail, path)
        val bitmap = peekMemoryEarlyVideoFrameBitmap(path)
            ?: getEarlyVideoFrameBitmap(context, path, timeUs)
        return withContext(Dispatchers.Main) {
            if (imageView.getTag(R.id.ivThumbnail) != path) return@withContext false
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                imageView.setBackgroundColor(0x00000000)
                true
            } else {
                false
            }
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


    /** Invalidation ciblée quand un fichier audio est remplacé/modifié.
     *  Les pochettes audio sont cachées par path ; si le NAS remplace le fichier au même chemin,
     *  on doit supprimer les alias no-probe pour éviter d'afficher l'ancienne cover. */
    fun invalidateAudioArtwork(context: Context, path: String) {
        if (path.isBlank()) return
        val keys = linkedSetOf<String>()
        runCatching { keys += audioNoProbeKey(path) }
        runCatching { keys += audioKey(path) }
        val expanded = keys.toList().flatMap { listOf(it, "audio-thumb-v3:$it") }
        expanded.forEach { key ->
            runCatching { cache.remove(key) }
            runCatching { deleteFromDisk(context.applicationContext, key) }
        }
    }

    fun clearCache() {
        cache.evictAll()
        videoThumbnailHotCache.evictAll()
        audioCoverHotCache.evictAll()
    }

    /** Vide aussi le cache disque persistant (ex: bouton "Vider le cache" dans les réglages). */
    fun clearDiskCache(context: Context) {
        try {
            diskCacheDir(context).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to clear disk thumbnail cache", e)
        }
    }
}
