package fr.retrospare.blazeplayer.player

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ExternalMediaIntentUtils {
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts", "m2ts", "mpeg", "mpg"
    )
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "aac", "ogg", "opus", "wav", "m4a", "wma", "ape", "dts", "ac3", "mka", "wv", "aiff", "alac"
    )

    data class ExternalMedia(
        val uri: Uri,
        val path: String,
        val name: String,
        val mimeType: String?,
        val kind: Kind
    ) {
        enum class Kind { VIDEO, AUDIO, UNKNOWN }
    }

    fun fromViewIntent(context: Context, intent: Intent?): ExternalMedia? = fromExternalIntent(context, intent)

    fun fromExternalIntent(context: Context, intent: Intent?): ExternalMedia? {
        val safeIntent = intent ?: return null
        val uri = when (safeIntent.action) {
            Intent.ACTION_VIEW -> safeIntent.data
            Intent.ACTION_SEND -> safeIntent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_SEND_MULTIPLE -> safeIntent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
            else -> null
        } ?: return null
        // Important ANR: certains gestionnaires de fichiers bloquent longtemps dans
        // ContentResolver.getType()/query(DISPLAY_NAME) quand on les appelle pendant le
        // lancement de l'app. Pour l'ouverture externe on reste volontairement non bloquant :
        // le nom est inféré depuis l'Uri et les métadonnées complètes seront enrichies plus
        // tard dans AudioPlayerFragment, sur Dispatchers.IO.
        val mime = safeIntent.type
        val rawName = resolveDisplayNameFast(uri)
        val ext = extensionFrom(rawName)
            .ifBlank { extensionFrom(uri.lastPathSegment.orEmpty()) }
            .ifBlank { extensionFromMime(mime) }
        val name = ensureNameHasExtension(rawName, ext)
        val kind = when {
            mime?.startsWith("video/", ignoreCase = true) == true -> ExternalMedia.Kind.VIDEO
            mime?.startsWith("audio/", ignoreCase = true) == true -> ExternalMedia.Kind.AUDIO
            ext in VIDEO_EXTENSIONS -> ExternalMedia.Kind.VIDEO
            ext in AUDIO_EXTENSIONS -> ExternalMedia.Kind.AUDIO
            else -> ExternalMedia.Kind.UNKNOWN
        }
        return ExternalMedia(uri, uri.toString(), name, mime, kind)
    }

    fun isAudioFileName(name: String): Boolean = extensionFrom(name) in AUDIO_EXTENSIONS
    fun isVideoFileName(name: String): Boolean = extensionFrom(name) in VIDEO_EXTENSIONS

    private fun resolveDisplayNameFast(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return File(uri.path.orEmpty()).name.ifBlank { "media" }
        }
        val raw = uri.lastPathSegment.orEmpty().substringAfterLast('/').substringAfterLast(':')
        return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }
            .getOrDefault(raw)
            .ifBlank { "media" }
    }

    private fun ensureNameHasExtension(name: String, ext: String): String {
        if (ext.isBlank()) return name
        return if (extensionFrom(name).isNotBlank()) name else "$name.$ext"
    }

    private fun extensionFromMime(mime: String?): String = when (mime?.lowercase()?.substringBefore(';')) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
        "audio/aac", "audio/aacp" -> "aac"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/x-ms-wma" -> "wma"
        "audio/x-ape", "audio/ape" -> "ape"
        "audio/ac3", "audio/vnd.dolby.dd-raw" -> "ac3"
        "audio/eac3" -> "eac3"
        "audio/vnd.dts" -> "dts"
        "audio/x-matroska" -> "mka"
        else -> ""
    }

    private fun extensionFrom(value: String): String =
        value.substringAfterLast('.', missingDelimiterValue = "").lowercase()
}
