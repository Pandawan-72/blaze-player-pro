package fr.retrospare.blazeplayer.network

import fr.retrospare.blazeplayer.player.AudioLibraryBackgroundDispatchers
import android.net.Uri
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.data.model.NetworkShare
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

@Singleton
class UpnpBrowser @Inject constructor() {

    data class DeviceDescription(
        val location: String,
        val friendlyName: String,
        val contentDirectoryControlUrl: String,
        val manufacturer: String = "",
        val modelName: String = ""
    )

    private data class BrowsePage(
        val items: List<MediaItem>,
        val numberReturned: Int,
        val totalMatches: Int
    )

    private data class LibraryBrowseNode(
        val objectId: String,
        val logicalPath: String,
        val inheritedArtwork: String = "",
        val depth: Int = 0
    )

    private data class UpnpDirectoryListing(
        val items: List<MediaItem>,
        val complete: Boolean,
        val error: Throwable? = null
    )

    private val controlUrlCache = ConcurrentHashMap<String, String>()

    private val audioExtensions = setOf(
        "mp3", "flac", "aac", "ogg", "opus", "wav", "m4a",
        "wma", "ape", "dts", "ac3", "mka", "aiff", "alac", "wv"
    )
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
    private val coverNames = listOf(
        "cover", "folder", "front", "poster", "default", "jacket",
        "album", "albumart", "album art", "artwork", "jaquette", "pochette"
    )

    suspend fun describe(location: String): DeviceDescription? = withContext(AudioLibraryBackgroundDispatchers.network) {
        try {
            val xml = httpGet(location, 5_000) ?: return@withContext null
            parseDeviceDescription(location, xml)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Liste complète des enfants avec pagination. L'ancienne implémentation demandait 250 éléments
     * puis ignorait TotalMatches : certains serveurs exigeaient plusieurs requêtes et les derniers
     * albums n'étaient jamais vus.
     */
    suspend fun listFiles(
        share: NetworkShare,
        objectId: String
    ): Result<List<MediaItem>> {
        val listing = listFilesForScan(share, objectId)
        return when {
            listing.complete -> Result.success(listing.items)
            listing.items.isNotEmpty() -> Result.success(listing.items)
            else -> Result.failure(
                listing.error ?: IllegalStateException("Listing UPnP incomplet")
            )
        }
    }

    /**
     * Pagination conforme à ContentDirectory : StartingIndex avance selon NumberReturned et
     * TotalMatches n'est utilisé que lorsqu'il est réellement fourni par le serveur.
     */
    private suspend fun listFilesForScan(
        share: NetworkShare,
        objectId: String
    ): UpnpDirectoryListing = withContext(AudioLibraryBackgroundDispatchers.network) {
        val controlUrl = runCatching { resolveControlUrl(share) }
            .getOrDefault("")
        if (controlUrl.isBlank()) {
            return@withContext UpnpDirectoryListing(
                emptyList(),
                complete = false,
                error = IllegalStateException("UPnP ContentDirectory introuvable")
            )
        }

        val id = objectId.ifBlank { "0" }
        val merged = mutableListOf<MediaItem>()
        val seenPaths = HashSet<String>()
        val pageSignatures = HashSet<String>()
        var startingIndex = 0
        var totalMatches = 0
        var pageCount = 0
        var complete = true
        var lastError: Throwable? = null

        while (
            pageCount < MAX_BROWSE_PAGES &&
            currentCoroutineContext().isActive
        ) {
            val pageResult = browsePageWithRetry(
                controlUrl = controlUrl,
                objectId = id,
                startingIndex = startingIndex
            )
            if (pageResult.isFailure) {
                complete = false
                lastError = pageResult.exceptionOrNull()
                break
            }

            val (page, _) = pageResult.getOrThrow()
            val signature = page.items.take(8)
                .joinToString("|") { it.path }
            if (
                startingIndex > 0 &&
                signature.isNotBlank() &&
                !pageSignatures.add(signature)
            ) {
                complete = false
                lastError = IllegalStateException(
                    "Le serveur UPnP répète la même page"
                )
                break
            }
            if (signature.isNotBlank()) pageSignatures += signature

            page.items.forEach { item ->
                if (seenPaths.add(item.path)) merged += item
            }

            val returned = page.numberReturned
                .takeIf { it > 0 }
                ?: page.items.size

            if (returned <= 0) break

            startingIndex += returned
            if (page.totalMatches > 0) {
                totalMatches = page.totalMatches
                if (startingIndex >= totalMatches) break
            }
            // TotalMatches peut légalement valoir 0 alors que le serveur renvoie encore des
            // éléments. Dans ce cas on continue jusqu'à une page vide ou une page répétée.

            pageCount++
        }

        if (!currentCoroutineContext().isActive) complete = false
        if (pageCount >= MAX_BROWSE_PAGES) complete = false

        UpnpDirectoryListing(
            items = merged.distinctBy { it.path },
            complete = complete,
            error = lastError
        )
    }

    private suspend fun browsePageWithRetry(
        controlUrl: String,
        objectId: String,
        startingIndex: Int
    ): Result<Pair<BrowsePage, Int>> {
        var lastError: Throwable? = null
        repeat(UPNP_PAGE_MAX_ATTEMPTS) { attempt ->
            val requestedCount = UPNP_PAGE_SIZES[
                attempt.coerceAtMost(UPNP_PAGE_SIZES.lastIndex)
            ]
            val timeoutMs = UPNP_PAGE_TIMEOUTS_MS[
                attempt.coerceAtMost(UPNP_PAGE_TIMEOUTS_MS.lastIndex)
            ]
            try {
                val response = httpPost(
                    controlUrl,
                    buildBrowseSoap(
                        objectId,
                        startingIndex,
                        requestedCount
                    ),
                    timeoutMs
                )
                if (!response.isNullOrBlank()) {
                    return Result.success(
                        parseBrowsePage(
                            objectId,
                            response,
                            controlUrl
                        ) to requestedCount
                    )
                }
                lastError = IllegalStateException("Réponse UPnP vide")
            } catch (error: Throwable) {
                lastError = error
            }

            if (attempt + 1 < UPNP_PAGE_MAX_ATTEMPTS) {
                delay(UPNP_RETRY_DELAYS_MS[attempt])
            }
        }
        return Result.failure(
            lastError ?: IllegalStateException("Page UPnP inaccessible")
        )
    }

    /**
     * Découverte UPnP exhaustive avec file de containers, retries par page et rapport de complétude.
     * Les titres découverts restent valides même lorsqu'un container isolé est temporairement
     * indisponible ; le dossier n'est simplement pas marqué comme entièrement confirmé.
     */
    suspend fun scanAudioLibrary(
        share: NetworkShare,
        startObjectId: String = "0",
        startFolderName: String = "",
        maxTracks: Int = 500_000,
        maxDepth: Int = 64,
        concurrency: Int = 4,
        beforeDirectory: suspend () -> Unit = {},
        onBatch: suspend (List<MediaItem>) -> Unit = {}
    ): Result<NetworkLibraryScanReport> = coroutineScope {
        try {
            var foundCount = 0
            var visitedDirectoryCount = 0
            var limitReached = false
            val failedDirectories = linkedSetOf<String>()
            val seenPaths = HashSet<String>(16_384)
            val seenContainers = HashSet<String>()
            val queue = ArrayDeque<LibraryBrowseNode>()

            val rootNode = LibraryBrowseNode(
                objectId = startObjectId.ifBlank { "0" },
                logicalPath = cleanLogicalSegment(startFolderName)
            )
            queue.add(rootNode)
            seenContainers += rootNode.objectId

            while (
                queue.isNotEmpty() &&
                foundCount < maxTracks &&
                currentCoroutineContext().isActive
            ) {
                beforeDirectory()
                val nodes = buildList {
                    repeat(concurrency.coerceAtLeast(1)) {
                        if (queue.isNotEmpty()) add(queue.removeFirst())
                    }
                }

                val responses = nodes.map { node ->
                    async(AudioLibraryBackgroundDispatchers.network) {
                        node to listFilesForScan(share, node.objectId)
                    }
                }.awaitAll()

                val audioBatch = mutableListOf<MediaItem>()
                responses.forEach { (node, listing) ->
                    if (listing.items.isNotEmpty() || listing.complete) {
                        visitedDirectoryCount++
                    }
                    if (!listing.complete) {
                        failedDirectories += node.logicalPath
                            .ifBlank { node.objectId }
                    }

                    val items = listing.items
                    val imageItems = items.filter(::isImageItem)
                    val namedCover = imageItems.minByOrNull {
                        coverCandidatePriority(it.name)
                    }?.takeIf {
                        coverCandidatePriority(it.name) < 100
                    }
                    val fallbackImage = imageItems
                        .maxByOrNull { it.size }
                    val folderArtwork = namedCover?.path
                        .orEmpty()
                        .ifBlank {
                            fallbackImage?.path.orEmpty()
                        }
                        .ifBlank { node.inheritedArtwork }

                    items.forEach itemLoop@ { item ->
                        if (foundCount + audioBatch.size >= maxTracks) {
                            limitReached = true
                            return@itemLoop
                        }

                        if (
                            item.mimeType == "folder" ||
                            item.mimeType == "share"
                        ) {
                            if (node.depth >= maxDepth) {
                                failedDirectories += joinLogicalPath(
                                    node.logicalPath,
                                    item.name
                                )
                            } else if (seenContainers.add(item.path)) {
                                val childArtwork = item.previewUris
                                    .firstOrNull()
                                    .orEmpty()
                                    .ifBlank {
                                        folderArtwork.takeIf {
                                            isDiscFolderName(item.name)
                                        }.orEmpty()
                                    }
                                queue.add(
                                    LibraryBrowseNode(
                                        objectId = item.path,
                                        logicalPath = joinLogicalPath(
                                            node.logicalPath,
                                            item.name
                                        ),
                                        inheritedArtwork = childArtwork,
                                        depth = node.depth + 1
                                    )
                                )
                            }
                        } else if (isAudioItem(item)) {
                            if (seenPaths.add(item.path)) {
                                val titleSegment = item.name.ifBlank {
                                    Uri.parse(item.path)
                                        .lastPathSegment
                                        .orEmpty()
                                }
                                val artwork = item.previewUris
                                    .firstOrNull()
                                    .orEmpty()
                                    .ifBlank { folderArtwork }

                                audioBatch += item.copy(
                                    previewUris = artwork
                                        .takeIf { it.isNotBlank() }
                                        ?.let(::listOf)
                                        ?: emptyList(),
                                    libraryPath = joinLogicalPath(
                                        node.logicalPath,
                                        titleSegment
                                    )
                                )
                            }
                        }
                    }
                }

                if (audioBatch.isNotEmpty()) {
                    foundCount += audioBatch.size
                    onBatch(audioBatch)
                }
            }

            if (foundCount >= maxTracks && queue.isNotEmpty()) {
                limitReached = true
            }
            val cancelled = !currentCoroutineContext().isActive
            val complete =
                !cancelled &&
                    !limitReached &&
                    queue.isEmpty() &&
                    failedDirectories.isEmpty()

            if (
                visitedDirectoryCount == 0 &&
                failedDirectories.isNotEmpty()
            ) {
                Result.failure(
                    IllegalStateException(
                        "Aucun dossier UPnP n'a pu être parcouru"
                    )
                )
            } else {
                Result.success(
                    NetworkLibraryScanReport(
                        foundCount = foundCount,
                        visitedDirectoryCount = visitedDirectoryCount,
                        failedDirectories = failedDirectories.toList(),
                        limitReached = limitReached,
                        cancelled = cancelled,
                        complete = complete
                    )
                )
            }
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun cleanLogicalSegment(value: String): String =
        value.replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .trim('/', '\\')

    private fun joinLogicalPath(parent: String, child: String): String {
        val cleanParent = parent.trim().trim('/', '\\')
        val cleanChild = cleanLogicalSegment(child)
        return when {
            cleanParent.isBlank() -> cleanChild
            cleanChild.isBlank() -> cleanParent
            else -> "$cleanParent/$cleanChild"
        }
    }

    private fun isImageItem(item: MediaItem): Boolean =
        item.mimeType.startsWith("image/", ignoreCase = true) ||
            item.extension.lowercase() in imageExtensions

    private fun coverCandidatePriority(name: String): Int {
        val base = name.substringBeforeLast('.', name)
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .lowercase()
        val index = coverNames.indexOfFirst {
            base == it || base.startsWith("$it ")
        }
        return if (index >= 0) index else 100
    }

    private fun isDiscFolderName(value: String): Boolean =
        Regex(
            "^(?:cd|disc|disk|disque|vol(?:ume)?)\\s*\\d+",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(value.trim())

    private fun isAudioItem(item: MediaItem): Boolean =
        item.mimeType.startsWith("audio/", ignoreCase = true) ||
            item.extension.lowercase() in audioExtensions

    private suspend fun resolveControlUrl(share: NetworkShare): String {
        val key = share.id.ifBlank { "${share.host}|${share.shareName}" }
        controlUrlCache[key]?.takeIf { it.isNotBlank() }?.let { return it }

        val stored = share.shareName.trim()
        val resolved = if (
            stored.startsWith("http://", true) ||
            stored.startsWith("https://", true)
        ) {
            stored
        } else {
            describe(share.host)?.contentDirectoryControlUrl.orEmpty()
                .ifBlank { stored }
        }

        if (resolved.isNotBlank()) controlUrlCache[key] = resolved
        return resolved
    }

    companion object {
        private const val MAX_BROWSE_PAGES = 2_000
        private const val UPNP_PAGE_MAX_ATTEMPTS = 4
        private val UPNP_PAGE_SIZES = intArrayOf(500, 250, 100, 50)
        private val UPNP_PAGE_TIMEOUTS_MS = intArrayOf(
            12_000,
            16_000,
            22_000,
            30_000
        )
        private val UPNP_RETRY_DELAYS_MS = longArrayOf(
            250L,
            750L,
            1_800L
        )

        fun parseDeviceDescription(location: String, xml: String): DeviceDescription? {
            val doc = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
            }.newDocumentBuilder().parse(
                ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
            )
            val friendly = doc.getElementsByTagName("friendlyName")
                .item(0)?.textContent?.trim().orEmpty()
            val manufacturer = doc.getElementsByTagName("manufacturer")
                .item(0)?.textContent?.trim().orEmpty()
            val model = doc.getElementsByTagName("modelName")
                .item(0)?.textContent?.trim().orEmpty()
            val services = doc.getElementsByTagName("service")
            var control = ""
            for (index in 0 until services.length) {
                val element = services.item(index) as? Element ?: continue
                val type = element.getElementsByTagName("serviceType")
                    .item(0)?.textContent.orEmpty()
                if (type.contains("ContentDirectory", true)) {
                    control = element.getElementsByTagName("controlURL")
                        .item(0)?.textContent?.trim().orEmpty()
                    break
                }
            }
            if (control.isBlank()) return null
            val absolute = resolveUrl(location, control)
            return DeviceDescription(
                location,
                friendly.ifBlank { Uri.parse(location).host ?: "UPnP" },
                absolute,
                manufacturer,
                model
            )
        }

        fun resolveUrl(base: String, maybeRelative: String): String = try {
            URL(URL(base), maybeRelative).toString()
        } catch (_: Exception) {
            maybeRelative
        }
    }

    private fun buildBrowseSoap(
        objectId: String,
        startingIndex: Int,
        requestedCount: Int
    ): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
              <ObjectID>${xmlEscape(objectId)}</ObjectID>
              <BrowseFlag>BrowseDirectChildren</BrowseFlag>
              <Filter>*</Filter>
              <StartingIndex>$startingIndex</StartingIndex>
              <RequestedCount>$requestedCount</RequestedCount>
              <SortCriteria></SortCriteria>
            </u:Browse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun parseBrowsePage(
        parentId: String,
        response: String,
        controlUrl: String
    ): BrowsePage {
        val outer = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }.newDocumentBuilder().parse(
            ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
        )

        val result = outer.getElementsByTagName("Result")
            .item(0)?.textContent.orEmpty()
        val numberReturned = outer.getElementsByTagName("NumberReturned")
            .item(0)?.textContent?.trim()?.toIntOrNull() ?: 0
        val totalMatches = outer.getElementsByTagName("TotalMatches")
            .item(0)?.textContent?.trim()?.toIntOrNull() ?: 0

        if (result.isBlank()) {
            return BrowsePage(emptyList(), numberReturned, totalMatches)
        }

        val inner = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }.newDocumentBuilder().parse(
            ByteArrayInputStream(result.toByteArray(Charsets.UTF_8))
        )
        val out = mutableListOf<MediaItem>()

        val containers = inner.getElementsByTagName("container")
        for (index in 0 until containers.length) {
            val element = containers.item(index) as? Element ?: continue
            val id = element.getAttribute("id").ifBlank { "$parentId/$index" }
            val title = textOf(element, "dc:title")
                .ifBlank { textOf(element, "title") }
                .ifBlank { "Dossier UPnP" }
            val containerArt = textOf(element, "upnp:albumArtURI")
                .ifBlank { textOf(element, "albumArtURI") }
                .takeIf { it.isNotBlank() }
                ?.let { resolveUrl(controlUrl, it) }
                .orEmpty()
            out += MediaItem(
                id = id,
                name = title,
                path = id,
                mimeType = "folder",
                isNetwork = true,
                previewUris = containerArt
                    .takeIf { it.isNotBlank() }
                    ?.let(::listOf)
                    ?: emptyList()
            )
        }

        val items = inner.getElementsByTagName("item")
        for (index in 0 until items.length) {
            val element = items.item(index) as? Element ?: continue
            val title = textOf(element, "dc:title")
                .ifBlank { textOf(element, "title") }
                .ifBlank { "Média UPnP" }
            val resource = firstItemResource(element) ?: continue
            val uri = resource.textContent?.trim().orEmpty()
            if (uri.isBlank()) continue

            val protocol = resource.getAttribute("protocolInfo")
            val mime = protocol.split(':').getOrNull(2).orEmpty()
                .ifBlank { guessMime(uri) }
            val duration = parseDurationSeconds(resource.getAttribute("duration"))
            val size = resource.getAttribute("size").toLongOrNull() ?: 0L
            val extension = guessExtension(uri, title, mime)
            val artist = textOf(element, "upnp:artist")
                .ifBlank { textOf(element, "dc:creator") }
                .ifBlank { textOf(element, "artist") }
            val album = textOf(element, "upnp:album")
                .ifBlank { textOf(element, "album") }
            val trackNumber = textOf(element, "upnp:originalTrackNumber")
                .ifBlank { textOf(element, "originalTrackNumber") }
                .toIntOrNull() ?: 0
            val albumArt = textOf(element, "upnp:albumArtURI")
                .ifBlank { textOf(element, "albumArtURI") }
                .takeIf { it.isNotBlank() }
                ?.let { resolveUrl(controlUrl, it) }
                .orEmpty()

            out += MediaItem(
                id = uri,
                name = title,
                path = uri,
                mimeType = mime,
                size = size,
                duration = duration,
                extension = extension,
                isNetwork = true,
                videoCodec = protocolToVideoCodec(protocol),
                audioCodec = protocolToAudioCodec(protocol),
                previewUris = albumArt.takeIf { it.isNotBlank() }
                    ?.let(::listOf)
                    ?: emptyList(),
                artist = artist,
                album = album,
                trackNumber = trackNumber
            )
        }

        return BrowsePage(out, numberReturned, totalMatches)
    }

    private fun firstItemResource(element: Element): Element? {
        val nodes = element.getElementsByTagName("res")
        var imageFallback: Element? = null
        var genericFallback: Element? = null

        for (index in 0 until nodes.length) {
            val resource = nodes.item(index) as? Element ?: continue
            val protocol = resource.getAttribute("protocolInfo")
            val url = resource.textContent?.trim().orEmpty()
            if (
                !url.startsWith("http://", true) &&
                !url.startsWith("https://", true)
            ) {
                continue
            }

            when {
                protocol.contains("audio", true) ||
                    protocol.contains("video", true) -> return resource
                protocol.contains("image", true) && imageFallback == null ->
                    imageFallback = resource
                genericFallback == null ->
                    genericFallback = resource
            }
        }
        return imageFallback ?: genericFallback
    }

    private fun textOf(element: Element, tag: String): String =
        element.getElementsByTagName(tag).item(0)
            ?.textContent?.trim().orEmpty()

    private fun parseDurationSeconds(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            val parts = value.substringBefore('.')
                .split(':')
                .map { it.toLongOrNull() ?: 0L }
            when (parts.size) {
                3 -> (parts[0] * 3600) + (parts[1] * 60) + parts[2]
                2 -> (parts[0] * 60) + parts[1]
                1 -> parts[0]
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun guessExtension(uri: String, title: String, mime: String): String {
        fun from(value: String): String {
            val extension = value.substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('.', "")
                .lowercase()
            return extension.takeIf {
                it.length in 2..5 && it.all(Char::isLetterOrDigit)
            }.orEmpty()
        }

        return from(title).ifBlank { from(uri) }.ifBlank {
            when {
                mime.contains("flac", true) -> "flac"
                mime.contains("aac", true) -> "aac"
                mime.contains("ogg", true) -> "ogg"
                mime.contains("wav", true) -> "wav"
                mime.contains("mp4", true) -> "m4a"
                mime.contains("mpeg", true) -> "mp3"
                mime.contains("matroska", true) -> "mka"
                else -> ""
            }
        }
    }

    private fun guessMime(uri: String): String = when (
        uri.substringBefore('?').substringAfterLast('.', "").lowercase()
    ) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "mka" -> "audio/x-matroska"
        "mp4", "m4v", "mov" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }

    private fun protocolToVideoCodec(protocol: String): String? {
        val normalized = protocol.lowercase()
        return when {
            !normalized.contains("video") -> null
            normalized.contains("hevc") || normalized.contains("h265") -> "H.265"
            normalized.contains("avc") || normalized.contains("h264") -> "H.264"
            normalized.contains("vp9") -> "VP9"
            normalized.contains("vp8") -> "VP8"
            normalized.contains("mpeg2") -> "MPEG-2"
            normalized.contains("mpeg4") -> "MPEG-4"
            else -> null
        }
    }

    private fun protocolToAudioCodec(protocol: String): String? {
        val normalized = protocol.lowercase()
        return when {
            normalized.contains("aac") || normalized.contains("mp4a") -> "AAC"
            normalized.contains("ac3") -> "AC3"
            normalized.contains("eac3") -> "EAC3"
            normalized.contains("dts") -> "DTS"
            normalized.contains("flac") -> "FLAC"
            normalized.contains("opus") -> "Opus"
            normalized.contains("vorbis") -> "Vorbis"
            normalized.contains("mp3") || normalized.contains("mpeg") -> "MP3"
            else -> null
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpPost(url: String, body: String, timeoutMs: Int): String? {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "text/xml; charset=\"utf-8\""
            )
            connection.setRequestProperty(
                "SOAPACTION",
                "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""
            )
            connection.setRequestProperty("Content-Length", bytes.size.toString())
            connection.setRequestProperty("Connection", "keep-alive")
            connection.outputStream.use { it.write(bytes) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
