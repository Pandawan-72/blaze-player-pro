package fr.retrospare.blazeplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Snapshot graphique persistant des pochettes de la grille Albums.
 *
 * Les JPEG 512 px de chaque piste restent la source de qualité pour le lecteur et les fiches.
 * Pour la grille, les relire et les décoder un par un après chaque redémarrage coûtait encore près
 * de deux secondes. Cet atlas regroupe 64 miniatures carrées par page : au lancement du processus,
 * Blaze ne décode qu'un ou deux fichiers contigus puis sert chaque tuile directement depuis la RAM.
 */
object AudioAlbumArtworkAtlas {
    private const val VERSION = 2
    private const val DIRECTORY_NAME = "audio_album_artwork_atlas_v1"
    private const val MANIFEST_NAME = "manifest.json"
    private const val PAGE_PREFIX = "page_"
    private const val PAGE_SUFFIX = ".jpg"
    private const val TILE_SIZE = 192
    private const val COLUMNS = 8
    private const val ROWS = 8
    private const val PAGE_CAPACITY = COLUMNS * ROWS
    private const val MAX_ALBUMS = 2_048
    private const val MAX_BOOT_PAGES = 4
    private const val JPEG_QUALITY = 91

    private data class Entry(val page: Int, val slot: Int, val source: String)

    @Volatile private var entries: Map<String, Entry> = emptyMap()
    private val pages = ConcurrentHashMap<Int, Bitmap>()
    private val loaded = AtomicBoolean(false)
    private val sliceCache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    /**
     * Lecture synchrone volontairement très légère depuis Application.onCreate(). Un atlas de
     * 44 albums correspond à un seul JPEG 1536 x 1536, nettement plus rapide que 44 décodages.
     */
    fun loadBlocking(context: Context) {
        if (!loaded.compareAndSet(false, true)) return
        loadFromDisk(context.applicationContext)
    }

    /** Lecture RAM pure utilisée dans onBindViewHolder. */
    fun peek(albumKey: String, expectedArtworkPath: String = ""): Bitmap? {
        if (albumKey.isBlank()) return null
        val entry = entries[albumKey] ?: return null
        if (expectedArtworkPath.isNotBlank() && canonicalSource(entry.source) != canonicalSource(expectedArtworkPath)) {
            return null
        }
        sliceCache.get(albumKey)?.let { return it }
        val page = pages[entry.page] ?: return null
        val x = (entry.slot % COLUMNS) * TILE_SIZE
        val y = (entry.slot / COLUMNS) * TILE_SIZE
        if (x + TILE_SIZE > page.width || y + TILE_SIZE > page.height) return null
        return runCatching {
            Bitmap.createBitmap(page, x, y, TILE_SIZE, TILE_SIZE)
        }.getOrNull()?.also { sliceCache.put(albumKey, it) }
    }

    /**
     * Reconstruit l'atlas uniquement lorsque la liste d'albums ou la source d'une pochette change.
     * Cette opération se déroule après le réchauffement du cache normal et ne bloque jamais l'UI.
     */
    suspend fun rebuildIfNeeded(context: Context, snapshot: AudioLibrarySnapshot) = withContext(AudioLibraryBackgroundDispatchers.io) {
        val appContext = context.applicationContext
        if (snapshot.albumKeysSorted.isEmpty()) return@withContext

        val albums = snapshot.albumKeysSorted.asSequence()
            .take(MAX_ALBUMS)
            .mapNotNull { key -> snapshot.albumsByKey[key]?.let { key to it } }
            .toList()
        if (albums.isEmpty()) return@withContext

        val fingerprint = fingerprint(albums)
        val dir = rootDir(appContext)
        val currentManifest = readManifest(dir)
        val manifestEntries = currentManifest?.optJSONArray("entries")
        val highestManifestPage = if (manifestEntries != null) {
            var highest = -1
            for (index in 0 until manifestEntries.length()) {
                highest = maxOf(highest, manifestEntries.optJSONObject(index)?.optInt("page", -1) ?: -1)
            }
            highest
        } else -1
        val cacheComplete = currentManifest != null &&
            currentManifest.optInt("version") == VERSION &&
            currentManifest.optString("fingerprint") == fingerprint &&
            highestManifestPage >= 0 &&
            (0..highestManifestPage).all { pageFile(dir, it).isFile }
        if (cacheComplete) {
            if (entries.isEmpty() || pages.isEmpty()) reload(appContext)
            return@withContext
        }

        val resolved = ArrayList<Triple<String, String, Bitmap>>(albums.size)
        for ((key, album) in albums) {
            val representative = album.tracks.firstOrNull() ?: continue
            val preferred = album.artworkPath.takeIf(AudioLibraryHeuristics::isArtworkReference)
            val bitmap = AudioArtworkResolver.memoryCachedBitmap(representative.path, preferred)
                ?: AudioArtworkResolver.cachedBitmap(appContext, representative.path, preferred)
                ?: continue
            resolved += Triple(key, preferred.orEmpty(), bitmap)
        }
        if (resolved.isEmpty()) return@withContext

        val tmpDir = File(dir.parentFile, "${dir.name}.tmp.${System.nanoTime()}")
        if (!tmpDir.mkdirs()) return@withContext
        try {
            val manifestEntries = JSONArray()
            resolved.chunked(PAGE_CAPACITY).forEachIndexed { pageIndex, pageItems ->
                val pageBitmap = Bitmap.createBitmap(
                    COLUMNS * TILE_SIZE,
                    ROWS * TILE_SIZE,
                    Bitmap.Config.RGB_565
                )
                val canvas = Canvas(pageBitmap)
                canvas.drawColor(Color.rgb(10, 12, 16))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
                pageItems.forEachIndexed { slot, (albumKey, artworkSource, source) ->
                    val destination = Rect(
                        (slot % COLUMNS) * TILE_SIZE,
                        (slot / COLUMNS) * TILE_SIZE,
                        (slot % COLUMNS + 1) * TILE_SIZE,
                        (slot / COLUMNS + 1) * TILE_SIZE
                    )
                    canvas.drawBitmap(source, centerCropSource(source), destination, paint)
                    manifestEntries.put(
                        JSONObject()
                            .put("key", albumKey)
                            .put("page", pageIndex)
                            .put("slot", slot)
                            .put("source", artworkSource)
                    )
                }
                FileOutputStream(pageFile(tmpDir, pageIndex)).use { output ->
                    if (!pageBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        throw IllegalStateException("Unable to encode album artwork atlas page")
                    }
                    output.fd.sync()
                }
                pageBitmap.recycle()
            }

            val manifest = JSONObject()
                .put("version", VERSION)
                .put("fingerprint", fingerprint)
                .put("tileSize", TILE_SIZE)
                .put("entries", manifestEntries)
            File(tmpDir, MANIFEST_NAME).writeText(manifest.toString(), Charsets.UTF_8)

            val oldDir = File(dir.parentFile, "${dir.name}.old")
            runCatching { oldDir.deleteRecursively() }
            if (dir.exists() && !dir.renameTo(oldDir)) dir.deleteRecursively()
            if (!tmpDir.renameTo(dir)) {
                dir.mkdirs()
                tmpDir.copyRecursively(dir, overwrite = true)
                tmpDir.deleteRecursively()
            }
            oldDir.deleteRecursively()
            reload(appContext)
        } catch (_: Exception) {
            tmpDir.deleteRecursively()
        }
    }

    private fun reload(context: Context) {
        // Ne recycle pas explicitement les anciennes pages : certaines ImageView peuvent encore
        // afficher une sous-image créée depuis elles pendant la reconstruction en arrière-plan.
        pages.clear()
        sliceCache.evictAll()
        loadFromDisk(context)
    }

    private fun loadFromDisk(context: Context) {
        val dir = rootDir(context)
        val manifest = readManifest(dir) ?: return
        if (manifest.optInt("version") != VERSION || manifest.optInt("tileSize") != TILE_SIZE) return
        val jsonEntries = manifest.optJSONArray("entries") ?: return
        val parsed = LinkedHashMap<String, Entry>(jsonEntries.length())
        var highestPage = -1
        for (index in 0 until jsonEntries.length()) {
            val item = jsonEntries.optJSONObject(index) ?: continue
            val key = item.optString("key")
            val page = item.optInt("page", -1)
            val slot = item.optInt("slot", -1)
            val source = item.optString("source")
            if (key.isBlank() || page < 0 || slot !in 0 until PAGE_CAPACITY) continue
            parsed[key] = Entry(page, slot, source)
            highestPage = maxOf(highestPage, page)
        }
        if (parsed.isEmpty()) return
        entries = parsed
        for (page in 0..minOf(highestPage, MAX_BOOT_PAGES - 1)) {
            decodePage(pageFile(dir, page))?.let { pages[page] = it }
        }
    }

    private fun decodePage(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 4L) return null
        return runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inDither = true
                }
            )
        }.getOrNull()
    }

    private fun centerCropSource(bitmap: Bitmap): Rect {
        val side = minOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val left = ((bitmap.width - side) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - side) / 2).coerceAtLeast(0)
        return Rect(left, top, left + side, top + side)
    }

    private fun fingerprint(albums: List<Pair<String, LibraryAlbum>>): String {
        val source = buildString {
            albums.forEach { (key, album) ->
                append(key).append('|').append(album.artworkPath)
                album.artworkPath.takeIf { it.isNotBlank() }?.let { path ->
                    val file = File(path)
                    if (file.isFile) append(':').append(file.lastModified()).append(':').append(file.length())
                }
                append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun canonicalSource(path: String): String = path
        .substringBefore('#')
        .trim()
        .replace('\\', '/')

    private fun readManifest(dir: File): JSONObject? = runCatching {
        val file = File(dir, MANIFEST_NAME)
        if (!file.isFile) return@runCatching null
        JSONObject(file.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun rootDir(context: Context): File = File(context.filesDir, DIRECTORY_NAME)
    private fun pageFile(dir: File, page: Int): File = File(dir, "$PAGE_PREFIX$page$PAGE_SUFFIX")
}
