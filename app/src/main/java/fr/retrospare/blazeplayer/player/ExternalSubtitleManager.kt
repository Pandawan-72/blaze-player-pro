package fr.retrospare.blazeplayer.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Gère les sous-titres externes choisis par l'utilisateur.
 *
 * - La lecture locale reçoit directement le SRT/ASS/SSA via Media3.
 * - Chromecast reçoit toujours une copie WebVTT placée dans le cache et publiée par le serveur
 *   HTTP de Blaze. Le Chromecast ne sait pas ouvrir une URI content:// du téléphone.
 */
object ExternalSubtitleManager {

    data class Selection(
        val uri: String,
        val mimeType: String,
        val label: String
    ) {
        fun toMedia3Configuration(): MediaItem.SubtitleConfiguration =
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
                .setMimeType(mimeType)
                .setLabel(label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
    }

    private const val PREFS = "blaze_external_video_subtitles"
    private const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024
    private val preparedVttByUri = ConcurrentHashMap<String, String>()

    fun createSelection(context: Context, uri: Uri): Selection? {
        val label = displayName(context, uri).ifBlank { "Sous-titres" }
        val mime = subtitleMimeType(context, uri, label) ?: return null
        return Selection(uri.toString(), mime, label)
    }

    fun save(context: Context, videoPath: String, selection: Selection) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(videoPath, "uri"), selection.uri)
            .putString(key(videoPath, "mime"), selection.mimeType)
            .putString(key(videoPath, "label"), selection.label)
            .apply()
    }

    fun restore(context: Context, videoPath: String): Selection? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = prefs.getString(key(videoPath, "uri"), null)?.takeIf { it.isNotBlank() } ?: return null
        val mime = prefs.getString(key(videoPath, "mime"), null)?.takeIf { it.isNotBlank() }
            ?: subtitleMimeType(context, Uri.parse(uri), uri) ?: return null
        val label = prefs.getString(key(videoPath, "label"), null).orEmpty().ifBlank {
            displayName(context, Uri.parse(uri)).ifBlank { "Sous-titres" }
        }
        return Selection(uri, mime, label)
    }

    fun clear(context: Context, videoPath: String) {
        val selection = restore(context, videoPath)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(key(videoPath, "uri"))
            .remove(key(videoPath, "mime"))
            .remove(key(videoPath, "label"))
            .apply()
        selection?.let { preparedVttByUri.remove(it.uri)?.let(::deleteQuietly) }
    }

    fun preparedWebVttPath(selection: Selection): String? =
        preparedVttByUri[selection.uri]?.takeIf { File(it).isFile && File(it).length() > 0L }

    /** Convertit SRT/ASS/SSA en WebVTT. Le travail est très léger et doit être appelé sur Dispatchers.IO. */
    fun prepareWebVtt(context: Context, selection: Selection): String? {
        preparedWebVttPath(selection)?.let { return it }
        val text = readSubtitleText(context, Uri.parse(selection.uri)) ?: return null
        val webVtt = when (selection.mimeType) {
            MimeTypes.TEXT_VTT -> normalizeExistingWebVtt(text)
            MimeTypes.APPLICATION_SUBRIP -> srtToWebVtt(text)
            MimeTypes.TEXT_SSA -> assToWebVtt(text)
            else -> when (extension(selection.label)) {
                "vtt" -> normalizeExistingWebVtt(text)
                "srt" -> srtToWebVtt(text)
                "ass", "ssa" -> assToWebVtt(text)
                else -> null
            }
        } ?: return null
        if (!webVtt.contains("-->")) return null

        val dir = File(context.cacheDir, "cast_subtitles").apply { mkdirs() }
        cleanupOldFiles(dir)
        val out = File(dir, sha256(selection.uri) + ".vtt")
        out.writeText(webVtt, Charsets.UTF_8)
        preparedVttByUri[selection.uri] = out.absolutePath
        return out.absolutePath
    }

    private fun normalizeExistingWebVtt(raw: String): String {
        val clean = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        return if (clean.startsWith("WEBVTT", ignoreCase = true)) "$clean\n" else "WEBVTT\n\n$clean\n"
    }

    private fun srtToWebVtt(raw: String): String {
        val lines = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').lines()
        val out = StringBuilder("WEBVTT\n\n")
        var i = 0
        while (i < lines.size) {
            while (i < lines.size && lines[i].isBlank()) i++
            if (i >= lines.size) break
            if (lines[i].trim().matches(Regex("\\d+"))) i++
            if (i >= lines.size) break
            val timing = lines[i].trim()
            if (!timing.contains("-->")) {
                i++
                continue
            }
            out.append(timing.replace(',', '.')).append('\n')
            i++
            while (i < lines.size && lines[i].isNotBlank()) {
                out.append(lines[i]).append('\n')
                i++
            }
            out.append('\n')
        }
        return out.toString()
    }

    private fun assToWebVtt(raw: String): String? {
        val lines = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').lines()
        var inEvents = false
        var fields = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
        val out = StringBuilder("WEBVTT\n\n")
        var cueCount = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inEvents = trimmed.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (trimmed.startsWith("Format:", ignoreCase = true)) {
                fields = trimmed.substringAfter(':').split(',').map { it.trim() }
                continue
            }
            if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) continue
            val body = trimmed.substringAfter(':').trimStart()
            val values = body.split(',', limit = fields.size.coerceAtLeast(1))
            if (values.size < fields.size) continue
            val startIndex = fields.indexOfFirst { it.equals("Start", true) }
            val endIndex = fields.indexOfFirst { it.equals("End", true) }
            val textIndex = fields.indexOfFirst { it.equals("Text", true) }
            if (startIndex < 0 || endIndex < 0 || textIndex < 0) continue
            val start = assTimeToVtt(values[startIndex].trim()) ?: continue
            val end = assTimeToVtt(values[endIndex].trim()) ?: continue
            val text = cleanAssText(values[textIndex])
            if (text.isBlank()) continue
            out.append(start).append(" --> ").append(end).append('\n')
                .append(text).append("\n\n")
            cueCount++
        }
        return out.toString().takeIf { cueCount > 0 }
    }

    private fun assTimeToVtt(value: String): String? {
        val match = Regex("(\\d+):(\\d{1,2}):(\\d{1,2})[.](\\d{1,3})").matchEntire(value) ?: return null
        val h = match.groupValues[1].toIntOrNull() ?: return null
        val m = match.groupValues[2].toIntOrNull() ?: return null
        val s = match.groupValues[3].toIntOrNull() ?: return null
        val fraction = match.groupValues[4].padEnd(3, '0').take(3)
        return "%02d:%02d:%02d.%s".format(Locale.US, h, m, s, fraction)
    }

    private fun cleanAssText(value: String): String {
        var text = value
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
        text = text
            .replace(Regex("\\{\\\\i1[^}]*}"), "<i>")
            .replace(Regex("\\{\\\\i0[^}]*}"), "</i>")
            .replace(Regex("\\{\\\\b1[^}]*}"), "<b>")
            .replace(Regex("\\{\\\\b0[^}]*}"), "</b>")
            .replace(Regex("\\{[^}]*}"), "")
        return text.trim()
    }

    private fun readSubtitleText(context: Context, uri: Uri): String? {
        val bytes = try {
            val input = when (uri.scheme?.lowercase()) {
                "content" -> context.contentResolver.openInputStream(uri)
                "file" -> uri.path?.let { File(it).inputStream() }
                "http", "https" -> java.net.URL(uri.toString()).openStream()
                null, "" -> File(uri.toString()).inputStream()
                else -> context.contentResolver.openInputStream(uri)
            } ?: return null
            input.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_SUBTITLE_BYTES) return null
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        } catch (_: Exception) {
            return null
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16BE)
        }
        val withoutBom = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else bytes
        val utf8 = String(withoutBom, Charsets.UTF_8)
        return if (utf8.count { it == '\uFFFD' } <= 2) {
            utf8
        } else {
            String(withoutBom, java.nio.charset.Charset.forName("windows-1252"))
        }
    }

    private fun subtitleMimeType(context: Context, uri: Uri, fallbackName: String): String? {
        val ext = extension(fallbackName.ifBlank { uri.lastPathSegment.orEmpty() })
        return when (ext) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            else -> when (context.contentResolver.getType(uri)?.lowercase()) {
                "application/x-subrip", "application/srt", "text/srt" -> MimeTypes.APPLICATION_SUBRIP
                "text/x-ssa", "text/x-ass", "application/x-ass", "application/ass" -> MimeTypes.TEXT_SSA
                "text/vtt" -> MimeTypes.TEXT_VTT
                else -> null
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) return cursor.getString(0).orEmpty()
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    private fun extension(name: String): String = name.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
    private fun key(videoPath: String, suffix: String): String = "${sha256(videoPath)}_$suffix"
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun cleanupOldFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach(::deleteQuietly)
    }

    private fun deleteQuietly(path: String) = deleteQuietly(File(path))
    private fun deleteQuietly(file: File) { runCatching { file.delete() } }
}
