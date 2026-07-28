package fr.retrospare.blazeplayer.player

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Résolution robuste des photographies d'artistes sans clé privée.
 *
 * Ordre de recherche :
 *  1. choix manuel ou fichier local artist/background/banner ;
 *  2. cache disque Blaze ;
 *  3. recherche directe Wikidata P18 puis image principale Wikipédia ;
 *  4. MusicBrainz -> Wikidata lorsque les recherches directes échouent ;
 *  5. recherche Wikimedia Commons filtrée en dernier recours.
 *
 * Toutes les images téléchargées sont validées par BitmapFactory avant d'être mises en cache. Les
 * requêtes simultanées pour un même artiste sont fusionnées et MusicBrainz reste limité à moins
 * d'une requête par seconde.
 */
object ArtistImageRepository {
    data class ArtistImage(
        val localPath: String,
        val sourcePageUrl: String = "",
        val author: String = "",
        val licenseName: String = "",
        val licenseUrl: String = "",
        val description: String = "",
        val isLocalFile: Boolean = false,
        val isManualSelection: Boolean = false
    )

    private data class CachedMetadata(
        val localPath: String,
        val sourcePageUrl: String,
        val author: String,
        val licenseName: String,
        val licenseUrl: String,
        val description: String,
        val savedAt: Long
    )

    private data class CommonsImage(
        val imageUrl: String,
        val sourcePageUrl: String,
        val author: String,
        val licenseName: String,
        val licenseUrl: String,
        val description: String
    )

    private data class ScoredId(val id: String, val score: Int)
    private data class ScoredCommons(val image: CommonsImage, val score: Int)

    private const val USER_AGENT = "BlazePlayer/1.0 (Android; https://dev.retro-spare.fr)"
    private const val FAILURE_PREFS = "blaze_artist_image_failures_wikimedia_v7"
    private const val FAILURE_TTL_MS = 2L * 60L * 1000L
    private const val MAX_IMAGE_BYTES = 20L * 1024L * 1024L
    private const val MIN_IMAGE_SIDE_PX = 280
    private const val MAX_MANUAL_IMAGE_BYTES = 30L * 1024L * 1024L
    private const val MUSICBRAINZ_MIN_INTERVAL_MS = 1_100L
    private const val HTTP_MAX_ATTEMPTS = 3
    private const val PREFETCH_CONCURRENCY = 4

    private val gson = Gson()
    private val musicBrainzMutex = Mutex()
    private val resolutionMutexes = ConcurrentHashMap<String, Mutex>()
    private val memoryCache = ConcurrentHashMap<String, ArtistImage>()
    private val cacheMigrationDone = AtomicBoolean(false)
    private val _imageUpdates = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val imageUpdates: SharedFlow<String> = _imageUpdates.asSharedFlow()
    private var lastMusicBrainzRequestAt = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Retour immédiat, sans accès réseau ni décodage disque. Utile pour éviter de remettre
     * brièvement une pochette d'album lorsqu'une vraie photo est déjà connue dans le processus. */
    fun peekMemory(artistName: String): ArtistImage? {
        val cleanName = canonicalArtistName(artistName)
        if (cleanName.isBlank() || isUnknownArtist(cleanName)) return null
        return memoryCache[cacheKey(cleanName)]
    }

    suspend fun resolve(
        context: Context,
        artistName: String,
        tracks: List<LibraryTrack>
    ): ArtistImage? = withContext(AudioLibraryBackgroundDispatchers.io) {
        val cleanName = canonicalArtistName(artistName)
        if (cleanName.isBlank() || isUnknownArtist(cleanName)) return@withContext null

        val cacheKey = cacheKey(cleanName)
        readManualImage(context, cacheKey)?.let { manual ->
            val previous = memoryCache.put(cacheKey, manual)
            if (previous?.localPath != manual.localPath) _imageUpdates.tryEmit(cleanName)
            return@withContext manual
        }

        findLocalArtistImage(tracks)?.let { local ->
            val localImage = ArtistImage(localPath = local.absolutePath, isLocalFile = true)
            val previous = memoryCache.put(cacheKey, localImage)
            if (previous?.localPath != localImage.localPath) _imageUpdates.tryEmit(cleanName)
            return@withContext localImage
        }

        memoryCache[cacheKey]?.let { return@withContext it }

        val resolutionMutex = resolutionMutexes.getOrPut(cacheKey) { Mutex() }
        resolutionMutex.withLock {
            memoryCache[cacheKey]?.let { return@withLock it }
            readCached(context, cacheKey)?.let { cached ->
                val previous = memoryCache.put(cacheKey, cached)
                if (previous?.localPath != cached.localPath) _imageUpdates.tryEmit(cleanName)
                return@withLock cached
            }
            if (recentFailure(context, cacheKey)) return@withLock null

            val resolved = try {
                resolveRemote(cleanName, tracks, context, cacheKey)
            } catch (error: Throwable) {
                fr.retrospare.blazeplayer.debug.CrashReporter.log(
                    context,
                    "Artist image resolution failed: $cleanName",
                    error
                )
                null
            }

            if (resolved == null) {
                markFailure(context, cacheKey)
            } else {
                clearFailure(context, cacheKey)
                val previous = memoryCache.put(cacheKey, resolved)
                if (previous?.localPath != resolved.localPath) _imageUpdates.tryEmit(cleanName)
            }
            resolved
        }
    }

    /** Copie une image choisie par l'utilisateur dans le stockage interne de Blaze. */
    suspend fun setManualImage(context: Context, artistName: String, uri: Uri): ArtistImage? =
        withContext(AudioLibraryBackgroundDispatchers.io) {
            val cleanName = canonicalArtistName(artistName)
            if (cleanName.isBlank() || isUnknownArtist(cleanName)) return@withContext null
            val key = cacheKey(cleanName)
            val bytes = readUriBytesBounded(context, uri, MAX_MANUAL_IMAGE_BYTES) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth < MIN_IMAGE_SIDE_PX || bounds.outHeight < MIN_IMAGE_SIDE_PX) return@withContext null
            val extension = when {
                bounds.outMimeType?.contains("png", true) == true -> "png"
                bounds.outMimeType?.contains("webp", true) == true -> "webp"
                else -> "jpg"
            }
            val dir = manualDir(context)
            dir.listFiles()?.filter { it.name.startsWith("$key.") }?.forEach(File::delete)
            val target = File(dir, "$key.$extension")
            val temp = File(dir, "$key.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.writeBytes(bytes)
                temp.delete()
            }
            if (!isValidImageFile(target)) {
                target.delete()
                return@withContext null
            }
            clearFailure(context, key)
            memoryCache.remove(key)
            ArtistImage(
                localPath = target.absolutePath,
                isLocalFile = true,
                isManualSelection = true
            ).also {
                memoryCache[key] = it
                _imageUpdates.tryEmit(cleanName)
            }
        }

    fun hasManualImage(context: Context, artistName: String): Boolean =
        readManualImage(context, cacheKey(canonicalArtistName(artistName))) != null

    fun clearManualImage(context: Context, artistName: String) {
        val key = cacheKey(canonicalArtistName(artistName))
        manualDir(context).listFiles()?.filter { it.name.startsWith("$key.") }?.forEach(File::delete)
        memoryCache.remove(key)
        clearFailure(context, key)
        _imageUpdates.tryEmit(canonicalArtistName(artistName))
    }

    /**
     * Précharge les artistes dès qu'ils apparaissent dans le snapshot de bibliothèque. Les chemins
     * Wikimedia rapides sont parallélisés par petits lots ; MusicBrainz reste sérialisé globalement.
     */
    suspend fun prefetch(context: Context, artists: List<LibraryArtist>) = withContext(AudioLibraryBackgroundDispatchers.io) {
        val candidates = artists.asSequence()
            .filter { it.name.isNotBlank() && !isUnknownArtist(it.name) }
            .distinctBy { normalize(canonicalArtistName(it.name)) }
            .sortedWith(compareByDescending<LibraryArtist> { it.albums }.thenBy { normalize(it.name) })
            .filter { artist ->
                val cleanName = canonicalArtistName(artist.name)
                val key = cacheKey(cleanName)
                memoryCache[key] == null && !recentFailure(context, key)
            }
            .toList()

        // Les recherches Wikidata/Wikipédia sont parallélisées par petits lots. Si elles échouent,
        // le verrou global MusicBrainz conserve malgré tout sa limite stricte d'une requête/seconde.
        coroutineScope {
            val semaphore = Semaphore(PREFETCH_CONCURRENCY)
            candidates.map { artist ->
                async {
                    semaphore.withPermit {
                        runCatching { resolve(context, artist.name, artist.tracks) }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun resolveRemote(
        artistName: String,
        tracks: List<LibraryTrack>,
        context: Context,
        cacheKey: String
    ): ArtistImage? {
        val searchNames = artistSearchNames(artistName)
        val languages = wikimediaLanguages()

        // 1) Chemin rapide sans quota strict : Wikidata direct. Pour la majorité des artistes
        // connus, le libellé exact mène immédiatement à P18 et évite deux appels MusicBrainz.
        val wikidataIds = linkedSetOf<String>()
        searchNames.forEach { name ->
            languages.forEach { language ->
                searchWikidataEntities(name, language).forEach(wikidataIds::add)
            }
        }
        wikidataIds.take(8).forEach { id ->
            resolveWikidataImage(id)?.let { source ->
                downloadAndCache(context, cacheKey, source)?.let { return it }
            }
        }

        // 2) PageImages Wikipédia : très rapide et souvent plus tolérant aux alias, accents et
        // noms de scène que la recherche MusicBrainz.
        searchNames.forEach { name ->
            languages.forEach { language ->
                wikipediaImageFiles(name, language).forEach { fileName ->
                    lookupCommonsImage(fileName)?.let { source ->
                        downloadAndCache(context, cacheKey, source)?.let { return it }
                    }
                }
            }
        }

        // 3) Chemin d'identification précis : MusicBrainz -> Wikidata. Plus lent à cause de la
        // limite officielle, il n'est utilisé que lorsque les chemins Wikimedia directs échouent.
        val mbids = linkedSetOf<String>()
        searchNames.forEach { name ->
            searchMusicBrainzArtists(name).forEach(mbids::add)
        }
        if (mbids.isEmpty()) {
            searchMusicBrainzViaRelease(artistName, tracks)?.let(mbids::add)
        }
        mbids.take(5).forEach { mbid ->
            val wikidataId = lookupWikidataId(mbid) ?: return@forEach
            resolveWikidataImage(wikidataId)?.let { source ->
                downloadAndCache(context, cacheKey, source)?.let { return it }
            }
        }

        // 4) Dernier recours Commons. Les résultats restent filtrés contre les logos/pochettes,
        // mais plusieurs formulations sont tentées pour couvrir les artistes moins documentés.
        searchNames.forEach { name ->
            searchCommonsImages(name).forEach { source ->
                downloadAndCache(context, cacheKey, source)?.let { return it }
            }
        }
        return null
    }

    private fun findLocalArtistImage(tracks: List<LibraryTrack>): File? {
        val preferredNames = setOf(
            "artist.jpg", "artist.jpeg", "artist.png", "artist.webp",
            "background.jpg", "background.jpeg", "background.png", "background.webp",
            "banner.jpg", "banner.jpeg", "banner.png", "banner.webp"
        )
        tracks.asSequence()
            .mapNotNull { track ->
                val raw = track.path.removePrefix("file://")
                if (raw.startsWith("content://", true) || raw.startsWith("smb://", true) || raw.startsWith("http", true)) null
                else File(raw).parentFile
            }
            .flatMap { albumFolder -> sequenceOf(albumFolder.parentFile, albumFolder) }
            .filterNotNull()
            .distinctBy { it.absolutePath }
            .forEach { folder ->
                folder.listFiles()?.firstOrNull { file ->
                    file.isFile && file.length() > 0L && file.name.lowercase(Locale.ROOT) in preferredNames
                }?.let { return it }
            }
        return null
    }

    private fun readCached(context: Context, key: String): ArtistImage? {
        val dir = cacheDir(context)
        val metadataFile = File(dir, "$key.json")
        if (!metadataFile.isFile) return null
        val metadata = runCatching {
            gson.fromJson(metadataFile.readText(), CachedMetadata::class.java)
        }.getOrNull() ?: return null
        val image = File(metadata.localPath)
        if (!isValidImageFile(image)) {
            metadataFile.delete()
            image.delete()
            return null
        }
        metadataFile.setLastModified(System.currentTimeMillis())
        image.setLastModified(System.currentTimeMillis())
        return ArtistImage(
            localPath = image.absolutePath,
            sourcePageUrl = metadata.sourcePageUrl,
            author = metadata.author,
            licenseName = metadata.licenseName,
            licenseUrl = metadata.licenseUrl,
            description = metadata.description
        )
    }

    private fun recentFailure(context: Context, key: String): Boolean {
        val at = context.getSharedPreferences(FAILURE_PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
        return at > 0L && System.currentTimeMillis() - at < FAILURE_TTL_MS
    }

    private fun markFailure(context: Context, key: String) {
        context.getSharedPreferences(FAILURE_PREFS, Context.MODE_PRIVATE)
            .edit().putLong(key, System.currentTimeMillis()).apply()
    }

    private fun clearFailure(context: Context, key: String) {
        context.getSharedPreferences(FAILURE_PREFS, Context.MODE_PRIVATE).edit().remove(key).apply()
    }

    private suspend fun searchMusicBrainzArtists(artistName: String): List<String> {
        val query = "artist:\"${escapeLucene(artistName)}\""
        val url = Uri.parse("https://musicbrainz.org/ws/2/artist/").buildUpon()
            .appendQueryParameter("query", query)
            .appendQueryParameter("fmt", "json")
            .appendQueryParameter("limit", "8")
            .build().toString()
        val root = musicBrainzJson(url) ?: return emptyList()
        val normalizedRequested = normalize(artistName)
        return root.getAsJsonArray("artists").orEmptyElements().mapNotNull { element ->
            val item = element.asJsonObject
            val id = item.string("id") ?: return@mapNotNull null
            val name = item.string("name").orEmpty()
            val sortName = item.string("sort-name").orEmpty()
            val aliases = item.getAsJsonArray("aliases").orEmptyElements()
                .mapNotNull { it.asJsonObject.string("name") }
            val apiScore = item.get("score")?.asInt ?: 0
            val exactBonus = when {
                normalize(name) == normalizedRequested -> 180
                normalize(sortName) == normalizedRequested -> 160
                aliases.any { normalize(it) == normalizedRequested } -> 150
                normalize(name).contains(normalizedRequested) -> 35
                else -> 0
            }
            val typeBonus = when (item.string("type")?.lowercase(Locale.ROOT)) {
                "group", "orchestra", "choir" -> 12
                "person" -> 8
                else -> 0
            }
            ScoredId(id, apiScore + exactBonus + typeBonus)
        }.filter { it.score >= 110 }
            .sortedByDescending { it.score }
            .map { it.id }
            .distinct()
            .take(4)
    }

    private suspend fun searchMusicBrainzViaRelease(
        artistName: String,
        tracks: List<LibraryTrack>
    ): String? {
        val album = tracks.asSequence().map { it.album.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val query = "artist:\"${escapeLucene(artistName)}\" AND release:\"${escapeLucene(album)}\""
        val url = Uri.parse("https://musicbrainz.org/ws/2/release/").buildUpon()
            .appendQueryParameter("query", query)
            .appendQueryParameter("fmt", "json")
            .appendQueryParameter("limit", "4")
            .build().toString()
        val root = musicBrainzJson(url) ?: return null
        val requested = normalize(artistName)
        return root.getAsJsonArray("releases").orEmptyElements().asSequence()
            .sortedByDescending { it.asJsonObject.get("score")?.asInt ?: 0 }
            .mapNotNull { release ->
                release.asJsonObject.getAsJsonArray("artist-credit").orEmptyElements()
                    .mapNotNull { credit -> credit.asJsonObject.getAsJsonObject("artist") }
                    .firstOrNull { artist -> normalize(artist.string("name").orEmpty()) == requested }
                    ?.string("id")
            }
            .firstOrNull()
    }

    private suspend fun lookupWikidataId(mbid: String): String? {
        val url = Uri.parse("https://musicbrainz.org/ws/2/artist/$mbid").buildUpon()
            .appendQueryParameter("inc", "url-rels")
            .appendQueryParameter("fmt", "json")
            .build().toString()
        val root = musicBrainzJson(url) ?: return null
        root.getAsJsonArray("relations").orEmptyElements().forEach { element ->
            val relation = element.asJsonObject
            val type = relation.string("type").orEmpty()
            val resource = relation.getAsJsonObject("url")?.string("resource").orEmpty()
            if (type.equals("wikidata", true) || resource.contains("wikidata.org/wiki/Q", true)) {
                Regex("Q\\d+", RegexOption.IGNORE_CASE).find(resource)?.value
                    ?.uppercase(Locale.ROOT)?.let { return it }
            }
        }
        return null
    }

    private suspend fun searchWikidataEntities(artistName: String, language: String): List<String> {
        val url = Uri.parse("https://www.wikidata.org/w/api.php").buildUpon()
            .appendQueryParameter("action", "wbsearchentities")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("type", "item")
            .appendQueryParameter("language", language)
            .appendQueryParameter("uselang", language)
            .appendQueryParameter("limit", "10")
            .appendQueryParameter("search", artistName)
            .build().toString()
        val root = getJson(url) ?: return emptyList()
        val requested = normalize(artistName)
        val positiveWords = listOf(
            "music", "musician", "singer", "rapper", "band", "group", "duo", "composer", "dj",
            "musique", "musicien", "chanteur", "chanteuse", "rappeur", "groupe", "orchestre"
        )
        val negativeWords = listOf("politician", "footballer", "actor", "actress", "writer", "company", "album", "song", "film")
        return root.getAsJsonArray("search").orEmptyElements().mapNotNull { element ->
            val item = element.asJsonObject
            val id = item.string("id") ?: return@mapNotNull null
            val label = item.string("label").orEmpty()
            val description = item.string("description").orEmpty().lowercase(Locale.ROOT)
            val labelScore = when {
                normalize(label) == requested -> 170
                normalize(label).contains(requested) -> 65
                requested.contains(normalize(label)) -> 35
                else -> 0
            }
            val musicScore = positiveWords.count { description.contains(it) } * 28
            val negative = negativeWords.count { description.contains(it) } * 35
            ScoredId(id, labelScore + musicScore - negative)
        }.filter { it.score >= 125 }
            .sortedByDescending { it.score }
            .map { it.id }
            .distinct()
            .take(6)
    }

    private suspend fun resolveWikidataImage(wikidataId: String): CommonsImage? {
        val file = lookupWikidataImageFile(wikidataId) ?: return null
        return lookupCommonsImage(file)
    }

    private suspend fun lookupWikidataImageFile(wikidataId: String): String? {
        val url = "https://www.wikidata.org/wiki/Special:EntityData/$wikidataId.json"
        val root = getJson(url) ?: return null
        val entity = root.getAsJsonObject("entities")?.getAsJsonObject(wikidataId) ?: return null
        val claims = entity.getAsJsonObject("claims") ?: return null
        return claims.getAsJsonArray("P18").orEmptyElements().asSequence()
            .mapNotNull { statement ->
                statement.asJsonObject.getAsJsonObject("mainsnak")
                    ?.getAsJsonObject("datavalue")
                    ?.get("value")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
            }
            .firstOrNull { it.isNotBlank() }
    }

    private suspend fun wikipediaImageFiles(artistName: String, language: String): List<String> {
        val host = "https://$language.wikipedia.org/w/api.php"
        val exactUrl = Uri.parse(host).buildUpon()
            .appendQueryParameter("action", "query")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("redirects", "1")
            .appendQueryParameter("prop", "pageimages")
            .appendQueryParameter("piprop", "name")
            .appendQueryParameter("titles", artistName)
            .build().toString()
        val exact = pageImageFiles(getJson(exactUrl), artistName)
        if (exact.isNotEmpty()) return exact

        val searchUrl = Uri.parse(host).buildUpon()
            .appendQueryParameter("action", "query")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("generator", "search")
            .appendQueryParameter("gsrnamespace", "0")
            .appendQueryParameter("gsrlimit", "6")
            .appendQueryParameter("gsrsearch", "intitle:\"$artistName\"")
            .appendQueryParameter("prop", "pageimages")
            .appendQueryParameter("piprop", "name")
            .build().toString()
        val titled = pageImageFiles(getJson(searchUrl), artistName)
        if (titled.isNotEmpty()) return titled

        val broadUrl = Uri.parse(host).buildUpon()
            .appendQueryParameter("action", "query")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("generator", "search")
            .appendQueryParameter("gsrnamespace", "0")
            .appendQueryParameter("gsrlimit", "8")
            .appendQueryParameter("gsrsearch", "$artistName music OR band OR singer")
            .appendQueryParameter("prop", "pageimages")
            .appendQueryParameter("piprop", "name")
            .build().toString()
        return pageImageFiles(getJson(broadUrl), artistName)
    }

    private fun pageImageFiles(root: JsonObject?, artistName: String): List<String> {
        val pages = root?.getAsJsonObject("query")?.getAsJsonObject("pages") ?: return emptyList()
        val requested = normalize(artistName)
        return pages.entrySet().mapNotNull { (_, value) ->
            val page = value.asJsonObject
            val image = page.string("pageimage") ?: return@mapNotNull null
            val title = page.string("title").orEmpty()
            val score = when {
                normalize(title) == requested -> 200
                normalize(title).startsWith(requested) -> 140
                normalize(title).contains(requested) -> 100
                else -> 0
            }
            image to score
        }.filter { it.second >= 100 }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(4)
    }

    private suspend fun searchCommonsImages(artistName: String): List<CommonsImage> {
        val url = Uri.parse("https://commons.wikimedia.org/w/api.php").buildUpon()
            .appendQueryParameter("action", "query")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("generator", "search")
            .appendQueryParameter("gsrnamespace", "6")
            .appendQueryParameter("gsrlimit", "12")
            .appendQueryParameter("gsrsearch", "intitle:\"$artistName\"")
            .appendQueryParameter("prop", "imageinfo")
            .appendQueryParameter("iiprop", "url|extmetadata|size")
            .appendQueryParameter("iiurlwidth", "1600")
            .appendQueryParameter("iiextmetadatalanguage", "fr")
            .build().toString()
        val root = getJson(url) ?: return emptyList()
        val pages = root.getAsJsonObject("query")?.getAsJsonObject("pages") ?: return emptyList()
        val requested = normalize(artistName)
        val blocked = listOf("logo", "signature", "album", "cover", "poster", "affiche", "icon", "icône", "disc", "vinyl")
        val musicWords = listOf("concert", "live", "singer", "musician", "band", "group", "groupe", "chanteur", "musicien")
        return pages.entrySet().mapNotNull { (_, value) ->
            val page = value.asJsonObject
            val title = page.string("title").orEmpty()
            val info = page.getAsJsonArray("imageinfo").orEmptyElements().firstOrNull()?.asJsonObject
                ?: return@mapNotNull null
            val ext = info.getAsJsonObject("extmetadata")
            val description = ext.metadataValue("ImageDescription").cleanHtml()
            val haystack = "$title $description".lowercase(Locale.ROOT)
            if (blocked.any(haystack::contains)) return@mapNotNull null
            val width = info.get("width")?.asInt ?: 0
            val height = info.get("height")?.asInt ?: 0
            if (width < MIN_IMAGE_SIDE_PX || height < MIN_IMAGE_SIDE_PX) return@mapNotNull null
            val titleScore = when {
                normalize(title).contains(requested) -> 150
                else -> 0
            }
            val musicScore = musicWords.count(haystack::contains) * 18
            val landscapeBonus = if (width >= height) 18 else 4
            val imageUrl = info.string("thumburl") ?: info.string("url") ?: return@mapNotNull null
            ScoredCommons(
                CommonsImage(
                    imageUrl = imageUrl,
                    sourcePageUrl = info.string("descriptionurl").orEmpty(),
                    author = ext.metadataValue("Artist").cleanHtml(),
                    licenseName = ext.metadataValue("LicenseShortName").cleanHtml(),
                    licenseUrl = ext.metadataValue("LicenseUrl"),
                    description = description
                ),
                titleScore + musicScore + landscapeBonus
            )
        }.filter { it.score >= 155 }
            .sortedByDescending { it.score }
            .map { it.image }
            .take(4)
    }

    private suspend fun lookupCommonsImage(fileName: String): CommonsImage? {
        val title = if (fileName.startsWith("File:", true)) fileName else "File:$fileName"
        val url = Uri.parse("https://commons.wikimedia.org/w/api.php").buildUpon()
            .appendQueryParameter("action", "query")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("prop", "imageinfo")
            .appendQueryParameter("iiprop", "url|extmetadata|size")
            .appendQueryParameter("iiurlwidth", "1600")
            .appendQueryParameter("iiextmetadatalanguage", "fr")
            .appendQueryParameter("titles", title)
            .build().toString()
        val root = getJson(url) ?: return null
        val pages = root.getAsJsonObject("query")?.getAsJsonObject("pages") ?: return null
        val page = pages.entrySet().firstOrNull()?.value?.asJsonObject ?: return null
        val info = page.getAsJsonArray("imageinfo").orEmptyElements().firstOrNull()?.asJsonObject ?: return null
        val width = info.get("width")?.asInt ?: 0
        val height = info.get("height")?.asInt ?: 0
        if (width < MIN_IMAGE_SIDE_PX || height < MIN_IMAGE_SIDE_PX) return null
        val ext = info.getAsJsonObject("extmetadata")
        val imageUrl = info.string("thumburl") ?: info.string("url") ?: return null
        return CommonsImage(
            imageUrl = imageUrl,
            sourcePageUrl = info.string("descriptionurl").orEmpty(),
            author = ext.metadataValue("Artist").cleanHtml(),
            licenseName = ext.metadataValue("LicenseShortName").cleanHtml(),
            licenseUrl = ext.metadataValue("LicenseUrl"),
            description = ext.metadataValue("ImageDescription").cleanHtml()
        )
    }

    private suspend fun downloadAndCache(context: Context, key: String, source: CommonsImage): ArtistImage? {
        val request = Request.Builder()
            .url(normalizeRemoteUrl(source.imageUrl))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/jpeg,image/png,image/webp,image/*;q=0.9,*/*;q=0.5")
            .build()
        val response = executeWithRetry(request) ?: return null
        response.use {
            if (!it.isSuccessful) return null
            val body = it.body ?: return null
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_IMAGE_BYTES) return null
            val bytes = body.bytes()
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth < MIN_IMAGE_SIDE_PX || bounds.outHeight < MIN_IMAGE_SIDE_PX) return null

            val extension = when {
                bounds.outMimeType?.contains("png", true) == true -> "png"
                bounds.outMimeType?.contains("webp", true) == true -> "webp"
                else -> "jpg"
            }
            val dir = cacheDir(context)
            dir.listFiles()?.filter { file -> file.name.startsWith("$key.") && file.extension != "json" }
                ?.forEach(File::delete)
            val imageFile = File(dir, "$key.$extension")
            val temp = File(dir, "$key.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(imageFile)) {
                imageFile.writeBytes(bytes)
                temp.delete()
            }
            if (!isValidImageFile(imageFile)) {
                imageFile.delete()
                return null
            }
            val metadata = CachedMetadata(
                localPath = imageFile.absolutePath,
                sourcePageUrl = source.sourcePageUrl,
                author = source.author,
                licenseName = source.licenseName,
                licenseUrl = source.licenseUrl,
                description = source.description,
                savedAt = System.currentTimeMillis()
            )
            File(dir, "$key.json").writeText(gson.toJson(metadata))
            trimCache(dir, key)
            return ArtistImage(
                localPath = imageFile.absolutePath,
                sourcePageUrl = source.sourcePageUrl,
                author = source.author,
                licenseName = source.licenseName,
                licenseUrl = source.licenseUrl,
                description = source.description
            )
        }
    }

    private suspend fun musicBrainzJson(url: String): JsonObject? = musicBrainzMutex.withLock {
        val elapsed = SystemClock.elapsedRealtime() - lastMusicBrainzRequestAt
        if (elapsed < MUSICBRAINZ_MIN_INTERVAL_MS) delay(MUSICBRAINZ_MIN_INTERVAL_MS - elapsed)
        val result = getJson(url)
        lastMusicBrainzRequestAt = SystemClock.elapsedRealtime()
        result
    }

    private suspend fun getJson(url: String): JsonObject? {
        val request = Request.Builder()
            .url(normalizeRemoteUrl(url))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val response = executeWithRetry(request) ?: return null
        return response.use {
            if (!it.isSuccessful) return null
            val body = it.body?.string().orEmpty()
            if (body.isBlank()) null else runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        }
    }

    private suspend fun executeWithRetry(request: Request): Response? {
        repeat(HTTP_MAX_ATTEMPTS) { attempt ->
            val response = runCatching { client.newCall(request).execute() }.getOrNull()
            if (response != null) {
                val retryable = response.code == 408 || response.code == 425 || response.code == 429 || response.code >= 500
                if (!retryable || attempt == HTTP_MAX_ATTEMPTS - 1) return response
                val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1L, 8L)
                response.close()
                delay((retryAfterSeconds?.times(1000L) ?: (450L * (attempt + 1) * (attempt + 1))))
            } else if (attempt < HTTP_MAX_ATTEMPTS - 1) {
                delay(450L * (attempt + 1))
            }
        }
        return null
    }

    private fun isValidImageFile(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_MANUAL_IMAGE_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth >= MIN_IMAGE_SIDE_PX && bounds.outHeight >= 160
    }

    private fun trimCache(dir: File, protectedKey: String) {
        val metadataFiles = dir.listFiles()?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        var retainedBytes = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        metadataFiles.drop(160).forEach { metadata ->
            if (metadata.nameWithoutExtension == protectedKey) return@forEach
            val key = metadata.nameWithoutExtension
            dir.listFiles()?.filter { it.name.startsWith("$key.") }?.forEach { file ->
                retainedBytes -= file.length()
                file.delete()
            }
            memoryCache.remove(key)
        }
        if (retainedBytes <= 120L * 1024L * 1024L) return
        metadataFiles.asReversed().forEach { metadata ->
            if (retainedBytes <= 120L * 1024L * 1024L) return
            if (metadata.nameWithoutExtension == protectedKey) return@forEach
            val key = metadata.nameWithoutExtension
            dir.listFiles()?.filter { it.name.startsWith("$key.") }?.forEach { file ->
                retainedBytes -= file.length()
                file.delete()
            }
            memoryCache.remove(key)
        }
    }

    private fun artistSearchNames(value: String): List<String> = buildList {
        val canonical = canonicalArtistName(value)
        add(canonical)
        canonical.replace(Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE), "")
            .trim().takeIf { it.isNotBlank() && !it.equals(canonical, true) }?.let(::add)
        canonical.replace(Regex("\\s*[\\[(].*?[\\])]\\s*$"), "")
            .trim().takeIf { it.isNotBlank() && !it.equals(canonical, true) }?.let(::add)
    }.distinctBy(::normalize)

    private fun canonicalArtistName(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isUnknownArtist(value: String): Boolean {
        val normalized = normalize(value)
        return normalized.isBlank() || normalized in setOf(
            "unknown artist", "artiste inconnu", "unbekannter interpret", "artista desconocido",
            "artista sconosciuto", "onbekende artiest", "artista desconhecido", "неизвестный исполнитель"
        )
    }

    private fun cacheKey(artistName: String): String = sha256(normalize(artistName))

    private fun readManualImage(context: Context, key: String): ArtistImage? {
        val file = manualDir(context).listFiles()?.firstOrNull { it.name.startsWith("$key.") } ?: return null
        if (!isValidImageFile(file)) {
            file.delete()
            return null
        }
        return ArtistImage(
            localPath = file.absolutePath,
            isLocalFile = true,
            isManualSelection = true
        )
    }

    private fun readUriBytesBounded(context: Context, uri: Uri, maxBytes: Long): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return@runCatching null
                output.write(buffer, 0, read)
            }
            output.toByteArray().takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun cacheDir(context: Context): File {
        // Migration effectuée une seule fois par processus. Les images v6 valides sont conservées,
        // tandis que les vieux essais antérieurs ne sont plus parcourus à chaque résolution.
        if (cacheMigrationDone.compareAndSet(false, true)) {
            listOf(
                "artist_images",
                "artist_images_banner_v3",
                "artist_images_wikimedia_v4",
                "artist_images_wikimedia_v5"
            ).forEach { name ->
                File(context.cacheDir, name).takeIf { it.exists() }?.deleteRecursively()
            }
        }
        return File(context.cacheDir, "artist_images_wikimedia_v6").apply { mkdirs() }
    }

    private fun manualDir(context: Context): File =
        File(context.filesDir, "artist_images_manual").apply { mkdirs() }

    private fun wikimediaLanguages(): List<String> = buildList {
        Locale.getDefault().language.takeIf { it.matches(Regex("[a-z]{2,3}")) }?.let(::add)
        add("en")
        add("fr")
    }.distinct()

    private fun normalizeRemoteUrl(value: String): String = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://${value.removePrefix("http://")}" 
        else -> value
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('&', ' ')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun escapeLucene(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun com.google.gson.JsonArray?.orEmptyElements(): List<com.google.gson.JsonElement> =
        this?.toList().orEmpty()

    private fun JsonObject?.metadataValue(name: String): String {
        val item = this?.getAsJsonObject(name) ?: return ""
        return item.get("value")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    }

    private fun String.cleanHtml(): String = replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()
}
