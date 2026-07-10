package fr.retrospare.blazeplayer.ui

import android.content.Context
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.widget.ImageView
import fr.retrospare.blazeplayer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.random.Random

object ThumbnailUtils {

    private val audioExtensions = setOf(
        "mp3","flac","aac","ogg","opus","wav","m4a","wma","ape","dts","ac3","mka",
        "wv","aiff","alac"
    )

    private val folderCoverImageExtensions = setOf("jpg", "jpeg", "png")
    private const val MAX_FOLDER_COVER_IMAGE_BYTES = 16L * 1024L * 1024L

    private fun extensionOf(path: String): String =
        path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()

    private fun isAudioPath(path: String): Boolean = extensionOf(path) in audioExtensions

    private fun isFolderCoverImagePath(path: String): Boolean = extensionOf(path) in folderCoverImageExtensions

    private fun isNetworkVideoPath(path: String): Boolean =
        path.startsWith("smb://", true) || path.startsWith("http://", true) || path.startsWith("https://", true)

    private fun defaultVideoFrameTimeUs(path: String): Long = 10_000_000L

    // Cache mémoire (RAM) LRU limité à 15MB — sert les miniatures déjà vues pendant cette session,
    // évite même le passage par le disque tant que l'app tourne.
    private val cache = object : LruCache<String, Bitmap>(15 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val audioArtworkInFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val networkThumbnailSemaphore = Semaphore(2)
    private val audioArtworkSemaphore = Semaphore(1)
    // Valeur = (dernière modification du dossier au moment du scan, chemin de la cover trouvée
    // ou "" si aucune). Se rebase automatiquement sur lastModified() du dossier : si un fichier
    // est ajouté/renommé/supprimé dedans, l'horodatage change et on relance un simple listFiles()
    // au lieu de continuer à servir indéfiniment l'ancienne pochette (ou "aucune pochette") mise
    // en cache lors d'un scan précédent — sans ça, une cover ajoutée après coup n'apparaissait
    // jamais tant que le processus de l'app restait vivant.
    private val folderCoverPathCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val audioArtworkDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + 6) } catch (_: Exception) {}
            runnable.run()
        }.apply {
            name = "BlazeAudioArtworkBg"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()

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
        // Ne pas recycler automatiquement le bitmap source : il peut venir du cache LRU et être
        // encore référencé par une autre ImageView. Le LruCache se charge de libérer la mémoire.
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }


    // Caches séparés audio/vidéo : évite qu'une miniature vidéo (même chemin logique,
    // ancien cache Vxx ou artworkUri Cast) soit réutilisée comme pochette audio.
    private fun audioKey(path: String): String {
        // v6 : la clé audio tient compte de la jaquette dossier prioritaire, du parser ID3 durci et des chemins locaux décodés. Si un cover.jpg/png
        // apparaît ou change, on évite de réutiliser une ancienne pochette embarquée cachée.
        val preferredCover = if (isAudioPath(path)) preferredFolderCoverPathForAudioPath(path) else null
        val stampedPath = preferredCover ?: path
        val imageStamp = if (isFolderCoverImagePath(stampedPath)) folderCoverStamp(stampedPath) else ""
        return "audio-hires-v6:$path:${preferredCover.orEmpty()}$imageStamp"
    }

    private fun folderCoverStamp(path: String): String = localFileForImagePath(path)
        ?.takeIf { it.exists() }
        ?.let { ":${it.lastModified()}:${it.length()}" }
        .orEmpty()
    private fun videoKey(path: String): String = "video:$path"
    private fun customVideoKey(path: String): String = "custom-video-thumb:$path"
    // Versionne le cache des frames vidéo pour forcer une vraie extraction à 10s
    // après mise à jour, au lieu de réutiliser d'anciennes miniatures à 1s/5s
    // ou des artworks DLNA mis en cache sous la même URL.
    private fun thumbnailKey(path: String): String = if (isAudioPath(path)) "audio-thumb-v3:${audioKey(path)}" else "video-frame-10s-v2:$path"

    /** Retourne une pochette audio déjà en cache RAM/disque sans ouvrir le fichier source.
     *  À utiliser depuis l'UI : évite de lancer MediaMetadataRetriever sur le thread principal. */
    fun getCachedAudioArtworkBitmap(context: Context, path: String): Bitmap? {
        val key = audioKey(path)
        cache.get(key)?.let { return it }
        readFromDisk(context.applicationContext, key)?.let { cached ->
            cache.put(key, cached)
            return cached
        }
        // Pour un fichier audio avec cover dossier existante, ne pas retomber sur une ancienne
        // pochette embarquée cachée : getAudioArtworkBitmap() devra décoder la cover dossier.
        val coverPath = if (isAudioPath(path)) preferredFolderCoverPathForAudioPath(path) else null
        if (coverPath != null) {
            val coverKey = audioKey(coverPath)
            cache.get(coverKey)?.let { return it }
            readFromDisk(context.applicationContext, coverKey)?.let { cached ->
                cache.put(coverKey, cached)
                cache.put(key, cached)
                return cached
            }
            return null
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

    /** Permet aux écrans de savoir s'il faut lancer une extraction même si MediaMetadata contient
     *  déjà une image embarquée : une image jpg/png à la racine du dossier reste prioritaire. */
    fun hasPreferredFolderCoverForAudio(path: String): Boolean = preferredFolderCoverPathForAudioPath(path) != null


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

    fun cacheAudioArtworkData(context: Context, path: String, artworkData: ByteArray?) {
        if (artworkData == null || artworkData.isEmpty()) return
        // Si une cover dossier existe pour ce morceau, elle doit rester prioritaire. On évite donc
        // de remplir la clé prioritaire avec une image embarquée reçue via MediaMetadata.
        if (isAudioPath(path) && preferredFolderCoverPathForAudioPath(path) != null) return
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
     *  Elle force l'extraction réelle quand le cache est vide : cover dossier prioritaire,
     *  puis pochette embarquée via MediaMetadataRetriever/ID3. */
    fun getAudioArtworkJpegBytesBlocking(context: Context, path: String): ByteArray? {
        getCachedAudioArtworkJpegBytes(context, path)?.let { return it }
        val bitmap = extractAudioArtworkInternal(context.applicationContext, path) ?: return null
        return try {
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_JPEG_QUALITY, out)
                out.toByteArray()
            }
        } catch (_: Exception) { null }
    }

    /** Extraction audio dédupliquée et bridée. Sur NAS/FLAC lourds, lancer trop de
     *  retrievers en parallèle provoque des freezes I/O et des ANR indirects. */
    suspend fun getAudioArtworkBitmap(context: Context, path: String): Bitmap? = coroutineScope {
        getCachedAudioArtworkBitmap(context, path)?.let { return@coroutineScope it }
        val key = audioKey(path)
        audioArtworkInFlight[key]?.let { return@coroutineScope it.await() }
        val deferred = async(audioArtworkDispatcher) {
            // Le réseau (SMB) avait moins de budget que le local alors qu'il en faut structurellement
            // PLUS (latence par aller-retour, image de cover parfois volumineuse à transférer) : un
            // MP3 sans cover embarquée qui doit chercher son cover.jpg voisin sur le NAS n'avait
            // souvent pas le temps de terminer avant timeout, alors qu'un fichier local ou une image
            // déjà résolue passait toujours largement.
            val timeoutMs = when {
                isFolderCoverImagePath(path) && path.startsWith("smb://", true) -> 6_000L
                isFolderCoverImagePath(path) -> 3_000L
                path.startsWith("smb://", true) -> 7_000L
                else -> 5_000L
            }
            withTimeoutOrNull(timeoutMs) {
                audioArtworkSemaphore.withPermit {
                    extractAudioArtworkInternal(context.applicationContext, path)
                }
            }
        }
        audioArtworkInFlight[key] = deferred
        try { deferred.await() } finally { audioArtworkInFlight.remove(key, deferred) }
    }

    private fun localFileForImagePath(path: String): File? {
        val raw = path.substringBefore('?').substringBefore('#')
        val clean = runCatching { Uri.decode(raw) }.getOrDefault(raw)
        return try {
            if (clean.startsWith("file://", true)) File(Uri.parse(clean).path.orEmpty()) else File(clean)
        } catch (_: Exception) { null }
    }

    fun preferredFolderCoverPathForAudioPath(path: String): String? {
        if (!isAudioPath(path)) return null
        if (path.startsWith("smb://", true) || path.startsWith("http://", true) ||
            path.startsWith("https://", true) || path.startsWith("upnp://", true)) return null
        return localCoverSearchDirectoriesForAudioPath(path)
            .asSequence()
            .mapNotNull { preferredCoverImageInDirectory(it) }
            .firstOrNull()
            ?.absolutePath
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

    private fun preferredCoverImageInDirectory(directory: File): File? {
        val key = directory.absolutePath
        val dirStamp = directory.lastModified()
        folderCoverPathCache[key]?.let { (stamp, coverPath) ->
            if (stamp == dirStamp) {
                if (coverPath.isBlank()) return null
                File(coverPath).takeIf { it.exists() && it.isFile }?.let { return it }
            }
        }
        val cover = runCatching {
            directory.listFiles { file -> file.isFile && extensionOf(file.name) in folderCoverImageExtensions }
                ?.sortedWith(Comparator { a, b -> naturalFileNameCompare(a.name, b.name) })
                ?.firstOrNull()
        }.getOrNull()
        folderCoverPathCache[key] = dirStamp to cover?.absolutePath.orEmpty()
        return cover
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
                        if (!isFolderCoverImagePath(name)) continue
                        val id = cursor.getLong(idIdx)
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
                        candidates += name to uri
                    }
                }
            }
        }
        return candidates
            .sortedWith(Comparator { a, b -> naturalFileNameCompare(a.first, b.first) })
            .firstOrNull()
            ?.second
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

    private fun decodeFolderCoverBitmap(context: Context, path: String): Bitmap? {
        if (!isFolderCoverImagePath(path) && !path.startsWith("content://", true)) return null
        return try {
            when {
                path.startsWith("content://", true) -> context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
                path.startsWith("file://", true) -> localFileForImagePath(path)?.takeIf { it.exists() }?.absolutePath?.let(BitmapFactory::decodeFile)
                path.startsWith("smb://", true) -> decodeSmbFolderCoverBitmap(path)
                path.startsWith("http://", true) || path.startsWith("https://", true) -> null
                else -> localFileForImagePath(path)?.takeIf { it.exists() }?.absolutePath?.let(BitmapFactory::decodeFile)
            }
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to decode folder cover image for $path", e)
            null
        }
    }

    private fun decodeSmbFolderCoverBitmap(path: String): Bitmap? {
        var source: fr.retrospare.blazeplayer.player.SmbMediaDataSource? = null
        return try {
            source = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
            val size = source.getSize()
            if (size <= 0L || size > MAX_FOLDER_COVER_IMAGE_BYTES) return null
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = source.readAt(offset.toLong(), bytes, offset, bytes.size - offset)
                if (read <= 0) break
                offset += read
            }
            if (offset <= 0) null else BitmapFactory.decodeByteArray(bytes, 0, offset)
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to read SMB folder cover image for $path", e)
            null
        } finally {
            try { source?.close() } catch (_: Exception) {}
        }
    }

    // Cache la cover-dossier trouvée par répertoire SMB, avec une durée de vie courte (contrairement
    // au cache local basé sur lastModified() : on ne peut pas "stat" un dossier réseau aussi
    // simplement, un TTL est le compromis le plus simple pour éviter de relister le dossier à
    // chaque miniature tout en restant à jour si une cover est ajoutée/changée sur le NAS).
    private val smbFolderCoverCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private const val SMB_FOLDER_COVER_CACHE_TTL_MS = 60_000L

    /** Recherche une image jpg/jpeg/png dans le dossier SMB qui contient [path], comme le fait déjà
     *  [preferredFolderCoverPathForAudioPath] pour le stockage local. Sans ça, un MP3 sur un partage
     *  réseau sans tag APIC embarqué n'affichait jamais rien, même avec un cover.jpg juste à côté :
     *  seule la pochette embarquée était tentée pour smb://, la recherche de cover-dossier était
     *  jusqu'ici entièrement absente pour ce protocole. Volontairement lent-tolérant (un seul
     *  listing réseau, mis en cache) plutôt qu'une extraction lourde. */
    private fun preferredSmbFolderCoverPath(path: String): String? {
        if (!path.startsWith("smb://", true)) return null
        return try {
            val parsed = fr.retrospare.blazeplayer.player.SmbDataSource.parseSmbUri(Uri.parse(path))
            val lastSep = parsed.filePath.lastIndexOf('\\')
            if (lastSep < 0) return null // fichier à la racine du partage, pas de dossier parent à lister
            val dirPath = parsed.filePath.substring(0, lastSep)
            val cacheKey = "${parsed.host}:${parsed.port}:${parsed.shareName}:$dirPath"
            val now = System.currentTimeMillis()
            smbFolderCoverCache[cacheKey]?.let { (ts, cover) ->
                if (now - ts < SMB_FOLDER_COVER_CACHE_TTL_MS) return cover.ifBlank { null }
            }
            val share = fr.retrospare.blazeplayer.player.SmbSessionPool.getShare(
                parsed.host, parsed.port, parsed.username, parsed.password, parsed.shareName
            )
            val coverName = try {
                share.list(dirPath)
                    .asSequence()
                    .map { it.fileName }
                    .filter { name -> !name.startsWith(".") && extensionOf(name) in folderCoverImageExtensions }
                    .sortedWith(Comparator { a, b -> naturalFileNameCompare(a, b) })
                    .firstOrNull()
            } finally {
                try { share.close() } catch (_: Exception) {}
            }
            val coverPath = coverName?.let { name ->
                val fullPath = if (dirPath.isBlank()) name else "$dirPath\\$name"
                buildSmbCoverUri(parsed, fullPath.replace("\\", "/"))
            }
            smbFolderCoverCache[cacheKey] = now to coverPath.orEmpty()
            coverPath
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "SMB folder cover search failed for $path", e)
            null
        }
    }

    private fun buildSmbCoverUri(parsed: fr.retrospare.blazeplayer.player.SmbDataSource.ParsedSmbUri, cleanPath: String): String {
        val auth = if (parsed.username.isNullOrEmpty()) "" else {
            val pass = parsed.password?.let { ":${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
            "${java.net.URLEncoder.encode(parsed.username, "UTF-8")}$pass@"
        }
        val portPart = if (parsed.port != 445) ":${parsed.port}" else ""
        return "smb://$auth${parsed.host}$portPart/${parsed.shareName}/$cleanPath"
    }

    private fun extractAudioArtworkInternal(context: Context, path: String): Bitmap? {
        val key = audioKey(path)

        // Priorité 1 : image jpg/jpeg/png dans le dossier qui contient le morceau. Pour les
        // dossiers CD1/Disc 2 sans image locale, on retombe ensuite sur le dossier album parent.
        // On décode avant tout cache embarqué éventuel pour éviter qu'un ancien embedded art masque le fichier cover.
        if (isAudioPath(path)) {
            val coverPath = preferredFolderCoverPathForAudioPath(path)
                ?: preferredMediaStoreFolderCoverPathForAudioPath(context, path)
                ?: preferredSmbFolderCoverPath(path)
            if (coverPath != null) {
                // On tente la cover dossier AVANT le cache disque de la piste : si une image a été
                // ajoutée à côté des morceaux après une première lecture, elle doit remplacer
                // immédiatement l'ancienne pochette embarquée cachée.
                decodeFolderCoverBitmap(context, coverPath)?.let { coverBitmap ->
                    val scaled = scaleBitmap(coverBitmap, 1024)
                    cache.put(key, scaled)
                    cache.put(audioKey(coverPath), scaled)
                    writeToDisk(context, key, scaled)
                    writeToDisk(context, audioKey(coverPath), scaled)
                    return scaled
                }
                cache.get(key)?.let { return it }
                readFromDisk(context, key)?.let { cache.put(key, it); return it }
            }
        }

        cache.get(key)?.let { return it }
        readFromDisk(context, key)?.let { cache.put(key, it); return it }

        if (isFolderCoverImagePath(path)) {
            return decodeFolderCoverBitmap(context, path)?.let {
                val scaled = scaleBitmap(it, 1024)
                cache.put(key, scaled)
                writeToDisk(context, key, scaled)
                scaled
            }
        }

        // Sur réseau, on tente d'abord notre parseur binaire maison : quelques lectures larges et
        // séquentielles (voir RandomAccessSource/readFully) plutôt que le sondage à l'aveugle que
        // fait MediaMetadataRetriever en interne (petites lectures éparpillées pour détecter le
        // conteneur), qui multiplie les allers-retours réseau et pouvait à lui seul épuiser le
        // budget de temps avant même d'atteindre le repli — observé notamment sur MP3/ID3 volumineux.
        val bitmap = if (path.startsWith("smb://", true)) {
            extractEmbeddedArtworkFallback(context, path) ?: tryExtractEmbeddedArtworkWithRetriever(context, path)
        } else {
            tryExtractEmbeddedArtworkWithRetriever(context, path) ?: extractEmbeddedArtworkFallback(context, path)
        }
        return bitmap?.let {
            val scaled = scaleBitmap(it, 1024)
            cache.put(key, scaled)
            writeToDisk(context, key, scaled)
            scaled
        }
    }

    private fun tryExtractEmbeddedArtworkWithRetriever(context: Context, path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        var closeable: AutoCloseable? = null
        return try {
            closeable = setRetrieverDataSource(context, retriever, path)
            retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "MediaMetadataRetriever artwork failed for $path", e)
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
    private fun extractEmbeddedArtworkFallback(context: Context, path: String): Bitmap? {
        val imageBytes = try {
            extractEmbeddedArtworkBytes(context, path, extensionOf(path))
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Embedded artwork fallback failed for $path", e)
            null
        } ?: return null
        return try {
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractEmbeddedArtworkBytes(context: Context, path: String, ext: String): ByteArray? {
        when (ext) {
            "mp3" -> readId3TagBytes(context, path)?.let { findImageBytesInId3Tag(it) }?.let { return it }
            "flac" -> extractFlacPictureBytes(context, path)?.let { return it }
            "m4a", "mp4", "m4b", "aac", "alac" -> extractMp4CoverBytes(context, path)?.let { return it }
        }
        // Tag APEv2 accroché en fin de fichier : le cas normal pour APE/WV, mais aussi rencontré en
        // plus d'ID3 sur certains MP3/WAV/FLAC tagués par d'anciens outils.
        extractApeV2PictureBytes(context, path)?.let { return it }
        // Dernier recours, générique et volontairement simple : recherche d'une signature JPEG/PNG
        // dans les premiers Mo du fichier. Couvre OGG/Opus/WMA/AIFF/MKA/AC3/DTS et tout tag non
        // standard, sans avoir à écrire un parseur dédié pour chaque conteneur exotique.
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

    private fun openRandomAccessSource(context: Context, path: String): RandomAccessSource? = try {
        when {
            path.startsWith("smb://", true) -> SmbRandomAccessSource(fr.retrospare.blazeplayer.player.SmbMediaDataSource(path))
            path.startsWith("content://", true) -> context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                ?.let { ContentRandomAccessSource(it) }
            path.startsWith("http://", true) || path.startsWith("https://", true) -> null
            path.startsWith("file://", true) -> localFileForImagePath(path)?.takeIf { it.exists() && it.isFile }?.let { LocalRandomAccessSource(it) }
            else -> File(path).takeIf { it.exists() && it.isFile }?.let { LocalRandomAccessSource(it) }
        }
    } catch (e: Exception) {
        android.util.Log.w("ThumbnailUtils", "Failed to open random access source for $path", e)
        null
    }

    private const val MAX_EMBEDDED_IMAGE_BYTES = 20 * 1024 * 1024 // 20 Mo, garde-fou anti-OOM
    private const val MAX_METADATA_SCAN_BYTES = 24 * 1024 * 1024  // borne les blocs/tags parcourus
    private const val GENERIC_SIGNATURE_SCAN_BYTES = 4 * 1024 * 1024 // dernier recours seulement

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
            while (!last && offset + 4 <= source.size && guard < 256) {
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
            android.util.Log.w("ThumbnailUtils", "FLAC picture parse failed for $path", e)
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
            android.util.Log.w("ThumbnailUtils", "MP4 cover parse failed for $path", e)
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
            android.util.Log.w("ThumbnailUtils", "APEv2 picture parse failed for $path", e)
            null
        } finally {
            try { source.close() } catch (_: Exception) {}
        }
    }

    // ---- Dernier recours : signature JPEG/PNG brute, pour les conteneurs sans parseur dédié ----

    private fun genericSignatureScanBytes(context: Context, path: String): ByteArray? {
        val bytes = readLeadingBytes(context, path, GENERIC_SIGNATURE_SCAN_BYTES) ?: return null
        return imageBytesFromFramePayload(bytes, false)
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
                path.startsWith("http://", true) || path.startsWith("https://", true) -> null
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
            val bounded = tagSize.coerceAtMost(16 * 1024 * 1024)
            val body = readFully(source, 10L, bounded) ?: return header
            val all = ByteArray(10 + body.size)
            System.arraycopy(header, 0, all, 0, 10)
            System.arraycopy(body, 0, all, 10, body.size)
            all
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailUtils", "Failed to read ID3 tag for $path", e)
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

    private fun findImageBytesInId3Tag(tag: ByteArray): ByteArray? {
        if (tag.size < 10 || tag[0] != 'I'.code.toByte() || tag[1] != 'D'.code.toByte() || tag[2] != '3'.code.toByte()) return null
        val major = tag[3].toInt() and 0xFF
        val flags = tag[5].toInt() and 0xFF
        val unsynchronised = flags and 0x80 != 0
        val tagEnd = (10 + synchsafeInt(tag, 6)).coerceAtMost(tag.size)
        var offset = 10
        if (major >= 3 && flags and 0x40 != 0 && offset + 4 <= tagEnd) {
            val extSize = if (major == 4) synchsafeInt(tag, offset) else normalInt(tag, offset)
            if (extSize > 0) {
                // ID3v2.4 inclut les 4 octets de taille dans extSize ; ID3v2.3 ne les inclut pas.
                // Sans ce décalage correct, on peut commencer à lire au milieu de l'en-tête étendu
                // et rater la frame APIC de certains MP3 parfaitement valides.
                val headerBytes = if (major == 3) 4 else 0
                offset = (offset + extSize + headerBytes).coerceAtMost(tagEnd)
            }
        }

        while (offset + (if (major == 2) 6 else 10) <= tagEnd) {
            if (major == 2) {
                val id = String(tag, offset, 3, Charsets.ISO_8859_1)
                val frameSize = ((tag[offset + 3].toInt() and 0xFF) shl 16) or
                    ((tag[offset + 4].toInt() and 0xFF) shl 8) or
                    (tag[offset + 5].toInt() and 0xFF)
                offset += 6
                if (frameSize <= 0 || offset + frameSize > tagEnd) break
                val payload = tag.copyOfRange(offset, offset + frameSize)
                if (id == "PIC") imageBytesFromFramePayload(payload, unsynchronised)?.let { return it }
                offset += frameSize
            } else {
                val id = String(tag, offset, 4, Charsets.ISO_8859_1)
                val frameSize = if (major == 4) synchsafeInt(tag, offset + 4) else normalInt(tag, offset + 4)
                offset += 10
                if (frameSize <= 0 || offset + frameSize > tagEnd) break
                val payload = tag.copyOfRange(offset, offset + frameSize)
                if (id == "APIC") imageBytesFromFramePayload(payload, unsynchronised)?.let { return it }
                offset += frameSize
            }
        }
        // Dernier recours : certains tags APIC mal formés gardent quand même les bytes JPEG/PNG.
        return imageBytesFromFramePayload(tag.copyOfRange(10, tagEnd), unsynchronised)
    }

    private fun imageBytesFromFramePayload(payload: ByteArray, unsynchronised: Boolean): ByteArray? {
        val clean = if (unsynchronised) removeId3Unsynchronisation(payload) else payload
        val starts = listOf(indexOfBytes(clean, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())), indexOfBytes(clean, byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())))
            .filter { it >= 0 }
        val start = starts.minOrNull() ?: return null
        val end = if (clean[start] == 0x89.toByte()) {
            indexOfBytes(clean, byteArrayOf(0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()), start)
                .takeIf { it >= 0 }?.plus(8) ?: clean.size
        } else {
            indexOfBytes(clean, byteArrayOf(0xFF.toByte(), 0xD9.toByte()), start + 2)
                .takeIf { it >= 0 }?.plus(2) ?: clean.size
        }
        return clean.copyOfRange(start, end.coerceAtMost(clean.size)).takeIf { it.size > 16 }
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
                extractAudioArtworkInternal(context, path)?.let {
                    val scaled = scaleBitmap(it, 160)
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
