package fr.retrospare.blazeplayer.cast

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import android.media.MediaMetadataRetriever
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Dedicated lightweight HTTP relay for AUDIO cast/local playback.
 *  It is independent from the video relay so a video cast cannot steal the audio source. */
object AudioStreamServerManager {
    @Volatile private var server: AudioStreamServer? = null

    @Synchronized
    fun registerAndGetUrl(context: Context, sourcePath: String): String {
        var s = server
        if (s == null) {
            s = AudioStreamServer(context.applicationContext)
            try { s.start(60_000, false) } catch (e: Exception) {
                android.util.Log.e("AudioStreamServer", "Failed to start audio relay", e)
            }
            server = s
        }
        return s!!.register(sourcePath)
    }


    @Synchronized
    fun registerCoverAndGetUrl(context: Context, sourcePath: String): String {
        var s = server
        if (s == null) {
            s = AudioStreamServer(context.applicationContext)
            try { s.start(60_000, false) } catch (e: Exception) {
                android.util.Log.e("AudioStreamServer", "Failed to start audio relay", e)
            }
            server = s
        }
        return s!!.registerCover(sourcePath)
    }

    @Synchronized fun stopServer() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
    }
}

class AudioStreamServer(private val context: Context, port: Int = 8928) : NanoHTTPD(port) {
    private val sources = ConcurrentHashMap<String, String>()
    private val sizes = ConcurrentHashMap<String, Long>()
    private val coverCache = ConcurrentHashMap<String, ByteArray>()

    fun register(path: String): String {
        val id = sourceId(path)
        sources[id] = path
        val ip = localIpAddress() ?: "127.0.0.1"
        return "http://$ip:$listeningPort/audio/$id/${URLEncoder.encode(fileName(path), "UTF-8")}" 
    }

    fun registerCover(path: String): String {
        val id = sourceId(path)
        sources[id] = path
        val ip = localIpAddress() ?: "127.0.0.1"
        return "http://$ip:$listeningPort/cover/$id/cover.jpg"
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        val parts = session.uri.trim('/').split('/')
        val kind = parts.getOrNull(0)
        val id = parts.getOrNull(1)
        val path = id?.let { sources[it] } ?: return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No audio source"))
        if (kind == "cover") return serveCover(path)
        return try {
            val total = sizeOf(path)
            val mime = mimeOf(path)
            if (session.method == Method.HEAD) {
                val r = newFixedLengthResponse(Response.Status.OK, mime, "")
                if (total > 0) r.addHeader("Content-Length", total.toString())
                r.addHeader("Accept-Ranges", "bytes")
                r.addHeader("Cache-Control", "no-store, no-transform")
                return cors(r)
            }
            val range = session.headers["range"]
            if (range != null && total > 0) serveRange(path, range, total, mime) else serveFull(path, total, mime)
        } catch (e: Exception) {
            android.util.Log.e("AudioStreamServer", "Serve failed for $path", e)
            cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Audio relay error"))
        }
    }

    private fun serveRange(path: String, range: String, total: Long, mime: String): Response {
        val parsed = parseRange(range, total) ?: return cors(newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Bad range"))
        val (start, end) = parsed
        val len = end - start + 1
        val r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, openAt(path, start), len)
        r.addHeader("Content-Range", "bytes $start-$end/$total")
        r.addHeader("Content-Length", len.toString())
        r.addHeader("Accept-Ranges", "bytes")
        r.addHeader("Cache-Control", "no-store, no-transform")
        return cors(r)
    }

    private fun serveFull(path: String, total: Long, mime: String): Response {
        val r = if (total > 0) newFixedLengthResponse(Response.Status.OK, mime, openAt(path, 0), total)
                else newChunkedResponse(Response.Status.OK, mime, openAt(path, 0))
        if (total > 0) r.addHeader("Content-Length", total.toString())
        r.addHeader("Accept-Ranges", "bytes")
        r.addHeader("Cache-Control", "no-store, no-transform")
        return cors(r)
    }

    private fun openAt(path: String, start: Long): InputStream = when {
        path.startsWith("smb://") -> AudioSmbInputStream(fr.retrospare.blazeplayer.player.SmbMediaDataSource(path), start)
        path.startsWith("content://") -> (context.contentResolver.openInputStream(Uri.parse(path)) ?: error("content open failed")).also { if (start > 0) it.skip(start) }
        path.startsWith("http://") || path.startsWith("https://") -> java.net.URL(path).openStream().also { if (start > 0) it.skip(start) }
        else -> java.io.File(path).inputStream().also { if (start > 0) it.skip(start) }
    }

    private fun sizeOf(path: String): Long = sizes[path]?.takeIf { it > 0 } ?: run {
        val v = when {
            path.startsWith("smb://") -> fr.retrospare.blazeplayer.player.SmbMediaDataSource(path).let { try { it.size } finally { try { it.close() } catch (_: Exception) {} } }
            path.startsWith("content://") -> context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { it.statSize } ?: -1L
            path.startsWith("http://") || path.startsWith("https://") -> -1L
            else -> java.io.File(path).length()
        }
        if (v > 0) sizes[path] = v
        v
    }

    private fun parseRange(range: String, total: Long): Pair<Long, Long>? {
        if (!range.startsWith("bytes=")) return null
        val parts = range.removePrefix("bytes=").substringBefore(',').split('-', limit = 2)
        val start = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val end = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: total - 1
        if (start < 0 || start >= total || end < start) return null
        return start to end.coerceAtMost(total - 1)
    }

    private fun serveCover(path: String): Response {
        return try {
            val art = coverCache[path] ?: embeddedCover(path)?.also { coverCache[path] = it }
                ?: return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No cover"))
            val mime = imageMime(art)
            val r = newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(art), art.size.toLong())
            r.addHeader("Content-Length", art.size.toString())
            r.addHeader("Cache-Control", "public, max-age=86400")
            cors(r)
        } catch (e: Exception) {
            android.util.Log.e("AudioStreamServer", "Cover serve failed for $path", e)
            cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No cover"))
        }
    }

    private fun embeddedCover(path: String): ByteArray? {
        val r = MediaMetadataRetriever()
        var smb: fr.retrospare.blazeplayer.player.SmbMediaDataSource? = null
        return try {
            when {
                path.startsWith("smb://") -> {
                    smb = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
                    r.setDataSource(smb)
                }
                path.startsWith("content://") -> r.setDataSource(context, Uri.parse(path))
                path.startsWith("http://") || path.startsWith("https://") -> r.setDataSource(path, emptyMap())
                else -> r.setDataSource(path)
            }
            r.embeddedPicture
        } finally {
            try { r.release() } catch (_: Exception) {}
            try { smb?.close() } catch (_: Exception) {}
        }
    }

    private fun imageMime(data: ByteArray): String = when {
        data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> "image/png"
        data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> "image/jpeg"
        data.size >= 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() && data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() -> "image/webp"
        else -> "image/jpeg"
    }

    private fun sourceId(path: String): String = java.security.MessageDigest.getInstance("SHA-1")
        .digest(path.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun cors(r: Response): Response { r.addHeader("Access-Control-Allow-Origin", "*"); r.addHeader("Access-Control-Allow-Headers", "Range, Content-Type"); r.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS"); r.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges"); return r }
    private fun fileName(path: String) = path.substringAfterLast('/').ifBlank { "audio" }
    private fun mimeOf(path: String) = when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) { "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"; else -> "audio/mpeg" }
    private fun localIpAddress(): String? = try { java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>().firstOrNull { !it.isLoopbackAddress }?.hostAddress } catch (_: Exception) { null }
}

private class AudioSmbInputStream(private var source: fr.retrospare.blazeplayer.player.SmbMediaDataSource, start: Long) : InputStream() {
    private val path = source.originalUri
    private var pos = start
    private val buf = ByteArray(512 * 1024)
    private var bp = 0
    private var bl = 0
    private var retries = 3
    override fun read(): Int { val b = ByteArray(1); val r = read(b,0,1); return if (r <= 0) -1 else b[0].toInt() and 0xff }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (bp >= bl) {
            bl = source.readAt(pos, buf, 0, buf.size); bp = 0
            while (bl <= 0 && retries-- > 0) { try { source.close() } catch (_: Exception) {}; source = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path); bl = source.readAt(pos, buf, 0, buf.size) }
            if (bl <= 0) return -1
        }
        val n = minOf(len, bl - bp); System.arraycopy(buf, bp, b, off, n); bp += n; pos += n; return n
    }
    override fun close() { try { source.close() } catch (_: Exception) {} }
}
