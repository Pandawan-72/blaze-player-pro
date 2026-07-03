package fr.retrospare.blazeplayer.network

import android.net.Uri
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.data.model.NetworkShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
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

    suspend fun describe(location: String): DeviceDescription? = withContext(Dispatchers.IO) {
        try {
            val xml = httpGet(location, 5_000) ?: return@withContext null
            parseDeviceDescription(location, xml)
        } catch (_: Exception) { null }
    }

    suspend fun listFiles(share: NetworkShare, objectId: String): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        try {
            val controlUrl = resolveControlUrl(share)
            if (controlUrl.isBlank()) return@withContext Result.failure(IllegalStateException("UPnP ContentDirectory introuvable"))
            val id = if (objectId.isBlank()) "0" else objectId
            val soap = buildBrowseSoap(id)
            val response = httpPost(controlUrl, soap, 12_000)
                ?: return@withContext Result.failure(IllegalStateException("Réponse UPnP vide"))
            Result.success(parseBrowseResponse(id, response))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun resolveControlUrl(share: NetworkShare): String {
        val stored = share.shareName.trim()
        if (stored.startsWith("http://", true) || stored.startsWith("https://", true)) return stored
        val desc = describe(share.host) ?: return stored
        return desc.contentDirectoryControlUrl
    }

    companion object {
        fun parseDeviceDescription(location: String, xml: String): DeviceDescription? {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
                .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val friendly = doc.getElementsByTagName("friendlyName").item(0)?.textContent?.trim().orEmpty()
            val manufacturer = doc.getElementsByTagName("manufacturer").item(0)?.textContent?.trim().orEmpty()
            val model = doc.getElementsByTagName("modelName").item(0)?.textContent?.trim().orEmpty()
            val services = doc.getElementsByTagName("service")
            var control = ""
            for (i in 0 until services.length) {
                val e = services.item(i) as? Element ?: continue
                val type = e.getElementsByTagName("serviceType").item(0)?.textContent.orEmpty()
                if (type.contains("ContentDirectory", true)) {
                    control = e.getElementsByTagName("controlURL").item(0)?.textContent?.trim().orEmpty()
                    break
                }
            }
            if (control.isBlank()) return null
            val absolute = resolveUrl(location, control)
            return DeviceDescription(location, friendly.ifBlank { Uri.parse(location).host ?: "UPnP" }, absolute, manufacturer, model)
        }

        fun resolveUrl(base: String, maybeRelative: String): String {
            return try { URL(URL(base), maybeRelative).toString() } catch (_: Exception) { maybeRelative }
        }
    }

    private fun buildBrowseSoap(objectId: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
              <ObjectID>${xmlEscape(objectId)}</ObjectID>
              <BrowseFlag>BrowseDirectChildren</BrowseFlag>
              <Filter>*</Filter>
              <StartingIndex>0</StartingIndex>
              <RequestedCount>250</RequestedCount>
              <SortCriteria></SortCriteria>
            </u:Browse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun parseBrowseResponse(parentId: String, response: String): List<MediaItem> {
        val outer = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            .newDocumentBuilder().parse(ByteArrayInputStream(response.toByteArray(Charsets.UTF_8)))
        val result = outer.getElementsByTagName("Result").item(0)?.textContent.orEmpty()
        if (result.isBlank()) return emptyList()
        val inner = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            .newDocumentBuilder().parse(ByteArrayInputStream(result.toByteArray(Charsets.UTF_8)))
        val out = mutableListOf<MediaItem>()
        val containers = inner.getElementsByTagName("container")
        for (i in 0 until containers.length) {
            val e = containers.item(i) as? Element ?: continue
            val id = e.getAttribute("id").ifBlank { "$parentId/$i" }
            val title = textOf(e, "dc:title").ifBlank { textOf(e, "title") }.ifBlank { "Dossier UPnP" }
            out += MediaItem(id = id, name = title, path = id, mimeType = "folder", isNetwork = true)
        }
        val items = inner.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val e = items.item(i) as? Element ?: continue
            val title = textOf(e, "dc:title").ifBlank { textOf(e, "title") }.ifBlank { "Média UPnP" }
            val res = firstResource(e) ?: continue
            val uri = res.textContent?.trim().orEmpty()
            if (uri.isBlank()) continue
            val protocol = res.getAttribute("protocolInfo")
            val mime = protocol.split(':').getOrNull(2).orEmpty().ifBlank { guessMime(uri) }
            val duration = parseDurationSeconds(res.getAttribute("duration"))
            val size = res.getAttribute("size").toLongOrNull() ?: 0L
            val ext = guessExtension(uri, title, mime)
            val videoCodec = protocolToVideoCodec(protocol)
            val audioCodec = protocolToAudioCodec(protocol)
            out += MediaItem(
                id = uri,
                name = title,
                path = uri,
                mimeType = mime,
                size = size,
                duration = duration,
                extension = ext,
                isNetwork = true,
                videoCodec = videoCodec,
                audioCodec = audioCodec
            )
        }
        return out
    }

    private fun firstResource(e: Element): Element? {
        val nodes = e.getElementsByTagName("res")
        for (i in 0 until nodes.length) {
            val r = nodes.item(i) as? Element ?: continue
            val protocol = r.getAttribute("protocolInfo")
            val url = r.textContent?.trim().orEmpty()
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                if (protocol.contains("video", true) || protocol.contains("audio", true) || protocol.contains("image", true) || protocol.isBlank()) return r
            }
        }
        return null
    }

    private fun textOf(e: Element, tag: String): String = e.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()

    private fun parseDurationSeconds(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            val parts = value.substringBefore('.').split(':').map { it.toLongOrNull() ?: 0L }
            when (parts.size) {
                3 -> (parts[0] * 3600) + (parts[1] * 60) + parts[2]
                2 -> (parts[0] * 60) + parts[1]
                1 -> parts[0]
                else -> 0L
            }
        } catch (_: Exception) { 0L }
    }

    private fun guessExtension(uri: String, title: String, mime: String): String {
        fun from(value: String): String {
            val ext = value.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
            return ext.takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } } ?: ""
        }
        from(title).ifBlank { from(uri) }.ifBlank {
            when {
                mime.contains("matroska", true) || mime.contains("mkv", true) -> "mkv"
                mime.contains("mp4", true) -> "mp4"
                mime.contains("avi", true) -> "avi"
                mime.contains("webm", true) -> "webm"
                mime.contains("mpeg", true) -> "mpg"
                else -> ""
            }
        }.let { return it }
    }

    private fun guessMime(uri: String): String = when (uri.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    private fun protocolToVideoCodec(protocol: String): String? {
        val p = protocol.lowercase()
        return when {
            !p.contains("video") -> null
            p.contains("hevc") || p.contains("h265") -> "H.265"
            p.contains("avc") || p.contains("h264") -> "H.264"
            p.contains("vp9") -> "VP9"
            p.contains("vp8") -> "VP8"
            p.contains("mpeg2") -> "MPEG-2"
            p.contains("mpeg4") -> "MPEG-4"
            else -> null
        }
    }

    private fun protocolToAudioCodec(protocol: String): String? {
        val p = protocol.lowercase()
        return when {
            p.contains("aac") || p.contains("mp4a") -> "AAC"
            p.contains("ac3") -> "AC3"
            p.contains("eac3") -> "EAC3"
            p.contains("dts") -> "DTS"
            p.contains("flac") -> "FLAC"
            p.contains("opus") -> "Opus"
            p.contains("vorbis") -> "Vorbis"
            p.contains("mp3") || p.contains("mpeg") -> "MP3"
            else -> null
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun httpPost(url: String, body: String, timeoutMs: Int): String? {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
            setRequestProperty("Content-Length", bytes.size.toString())
        }
        c.outputStream.use { it.write(bytes) }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
