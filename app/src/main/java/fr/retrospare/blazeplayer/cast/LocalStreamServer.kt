package fr.retrospare.blazeplayer.cast

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

/**
 * Petit serveur HTTP local embarque qui relaie un fichier local (content://, /storage/...)
 * ou reseau SMB (smb://) en HTTP, pour le rendre accessible au Chromecast (qui ne sait lire que HTTP/HTTPS).
 * Supporte les requetes Range (HTTP partial content), indispensable pour que le Chromecast puisse
 * faire du buffering progressif et du seek sans devoir telecharger tout le fichier d'un coup.
 */
class LocalStreamServer(
    private val context: Context,
    port: Int = 8927
) : NanoHTTPD(port) {

    private var currentSourcePath: String? = null
    private var currentFileSize: Long = -1L

    fun setSource(path: String) {
        currentSourcePath = path
        currentFileSize = -1L
    }

    /** URL a donner au Chromecast pour lire ce fichier. */
    fun getStreamUrl(): String {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        return "http://$ip:${this.listeningPort}/stream"
    }

    override fun serve(session: IHTTPSession): Response {
        val path = currentSourcePath ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No source set")
        val mimeType = guessMimeType(path)

        return try {
            val totalLength = getOrComputeFileSize(path)
            val rangeHeader = session.headers["range"]

            if (rangeHeader != null && totalLength > 0) {
                serveRange(path, rangeHeader, totalLength, mimeType)
            } else {
                serveFull(path, totalLength, mimeType)
            }
        } catch (e: Exception) {
            android.util.Log.e("LocalStreamServer", "Failed to serve $path", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun getOrComputeFileSize(path: String): Long {
        if (currentFileSize > 0) return currentFileSize
        currentFileSize = when {
            path.startsWith("smb://") -> {
                val smbSource = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
                val size = smbSource.size
                try { smbSource.close() } catch (_: Exception) {}
                size
            }
            path.startsWith("content://") -> {
                context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { it.statSize } ?: -1L
            }
            else -> {
                val file = java.io.File(path)
                if (file.exists()) file.length() else -1L
            }
        }
        return currentFileSize
    }

    private fun openInputStreamAt(path: String, startPosition: Long): InputStream {
        return when {
            path.startsWith("smb://") -> {
                val smbSource = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
                SmbMediaDataSourceInputStream(smbSource, startPosition)
            }
            path.startsWith("content://") -> {
                val stream = context.contentResolver.openInputStream(Uri.parse(path))
                    ?: throw java.io.IOException("Cannot open content://")
                if (startPosition > 0) stream.skip(startPosition)
                stream
            }
            else -> {
                val file = java.io.File(path)
                if (!file.exists()) throw java.io.IOException("File not found")
                val stream = file.inputStream()
                if (startPosition > 0) stream.skip(startPosition)
                stream
            }
        }
    }

    /** Repond a une requete Range (ex: "bytes=1000-") avec le statut 206 Partial Content. */
    private fun serveRange(path: String, rangeHeader: String, totalLength: Long, mimeType: String): Response {
        val range = rangeHeader.removePrefix("bytes=")
        val parts = range.split("-")
        val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (totalLength - 1)
        val contentLength = (end - start + 1).coerceAtLeast(0)

        val inputStream = openInputStreamAt(path, start)
        val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, inputStream, contentLength)
        response.addHeader("Content-Range", "bytes $start-$end/$totalLength")
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    private fun serveFull(path: String, totalLength: Long, mimeType: String): Response {
        val inputStream = openInputStreamAt(path, 0)
        val response = if (totalLength > 0) {
            newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, totalLength)
        } else {
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        }
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    private fun guessMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").substringBefore('?').lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> "video/*"
        }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) { null }
    }
}

/**
 * Adapte SmbMediaDataSource (lecture positionnelle) en InputStream sequentiel pour NanoHTTPD,
 * en demarrant a une position donnee (pour le support des requetes Range).
 */
private class SmbMediaDataSourceInputStream(
    private val source: fr.retrospare.blazeplayer.player.SmbMediaDataSource,
    startPosition: Long = 0
) : InputStream() {
    private var position: Long = startPosition
    private val buffer = ByteArray(8 * 1024 * 1024)
    private var bufferPos = 0
    private var bufferLen = 0

    override fun read(): Int {
        val b = ByteArray(1)
        val read = read(b, 0, 1)
        return if (read <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (bufferPos >= bufferLen) {
            bufferLen = source.readAt(position, buffer, 0, buffer.size)
            bufferPos = 0
            if (bufferLen <= 0) return -1
        }
        val toCopy = minOf(len, bufferLen - bufferPos)
        System.arraycopy(buffer, bufferPos, b, off, toCopy)
        bufferPos += toCopy
        position += toCopy
        return toCopy
    }

    override fun close() {
        try { source.close() } catch (_: Exception) {}
    }
}
