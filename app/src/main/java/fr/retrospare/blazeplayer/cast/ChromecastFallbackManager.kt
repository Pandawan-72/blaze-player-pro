package fr.retrospare.blazeplayer.cast

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import fr.retrospare.blazeplayer.player.VideoMetadataExtractor
import fr.retrospare.blazeplayer.player.VideoTechnicalInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Fallback Cast à coût maîtrisé.
 *
 * Blaze essaie toujours la source originale en premier. Cette classe n'est appelée qu'après un
 * échec confirmé du receiver. Elle ne réencode JAMAIS la vidéo :
 * - flux vidéo : copie bit à bit (`-c:v copy`),
 * - audio : copie lorsqu'il est compatible MP4/Cast,
 * - sinon uniquement l'audio est converti en AAC stéréo 48 kHz.
 */
object ChromecastFallbackManager {

    data class PreparedSource(
        val originalPath: String,
        val outputPath: String,
        val audioTranscoded: Boolean,
        val technicalInfo: VideoTechnicalInfo
    )

    sealed class Result {
        data class Ready(val source: PreparedSource) : Result()
        data class NotPossible(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    private val prepared = ConcurrentHashMap<String, PreparedSource>()
    private val attempted = ConcurrentHashMap.newKeySet<String>()

    fun preparedPath(originalPath: String): String? = prepared[originalPath]?.outputPath
        ?.takeIf { File(it).isFile && File(it).length() > 0L }

    fun preparedSource(originalPath: String): PreparedSource? = prepared[originalPath]
        ?.takeIf { File(it.outputPath).isFile && File(it.outputPath).length() > 0L }

    fun hasAttempted(originalPath: String, forceAudioTranscode: Boolean = false): Boolean =
        attempted.contains(attemptKey(originalPath, forceAudioTranscode))

    suspend fun prepare(
        context: Context,
        originalPath: String,
        castModelName: String?,
        forceAudioTranscode: Boolean = false
    ): Result = withContext(Dispatchers.IO) {
        val existingPrepared = preparedSource(originalPath)
        if (existingPrepared != null && (!forceAudioTranscode || existingPrepared.audioTranscoded)) {
            return@withContext Result.Ready(existingPrepared)
        }
        if (!attempted.add(attemptKey(originalPath, forceAudioTranscode))) {
            return@withContext Result.Failed("fallback already attempted")
        }

        val appContext = context.applicationContext
        val info = runCatching { VideoMetadataExtractor.extractFull(appContext, originalPath) }
            .getOrElse { VideoTechnicalInfo() }

        ChromecastCompatibility.incompatibilityReason(info, castModelName)?.let { reason ->
            // Un remux ne change ni le codec vidéo ni la résolution. Si ceux-ci sont incompatibles,
            // poursuivre créerait un gros fichier temporaire sans aucune chance de lecture.
            return@withContext Result.NotPossible(reason)
        }

        if (!videoCanBeCopiedToMp4(info.videoCodec)) {
            return@withContext Result.NotPossible("video codec ${info.videoCodec.ifBlank { "unknown" }} cannot be remuxed safely")
        }

        val baseDir = File(appContext.externalCacheDir ?: appContext.cacheDir, "cast_fallback").apply { mkdirs() }
        cleanupOldFiles(baseDir)
        val estimatedSize = info.sizeBytes.takeIf { it > 0L } ?: sourceLength(appContext, originalPath)
        val reclaimableBytes = existingPrepared?.outputPath?.let { File(it).takeIf(File::isFile)?.length() } ?: 0L
        if (estimatedSize > 0L && baseDir.usableSpace + reclaimableBytes < estimatedSize + MIN_FREE_MARGIN_BYTES) {
            return@withContext Result.NotPossible("not enough cache space for remux")
        }

        val input = when {
            originalPath.startsWith("http://", true) || originalPath.startsWith("https://", true) -> originalPath
            else -> VideoStreamServerManager.getLoopbackStreamUrlFor(appContext, originalPath)
                ?: return@withContext Result.Failed("local relay unavailable")
        }
        val output = File(baseDir, sha256(originalPath) + ".mp4")
        val partial = File(baseDir, output.name + ".partial")
        partial.delete()
        output.delete()

        val audioTranscodeRequired = forceAudioTranscode || audioNeedsAac(info.audioCodec)
        var transcoded = audioTranscodeRequired
        var session = executeRemux(input, partial, transcodeAudio = audioTranscodeRequired)

        // Si le codec audio était inconnu et que MP4 refuse sa copie, une seconde tentative AAC
        // est le seul fallback autorisé. La vidéo reste toujours en copie de flux.
        if (!ReturnCode.isSuccess(session.returnCode) && !audioTranscodeRequired) {
            partial.delete()
            session = executeRemux(input, partial, transcodeAudio = true)
            transcoded = true
        }

        if (!ReturnCode.isSuccess(session.returnCode) || !partial.isFile || partial.length() <= 0L) {
            val logs = runCatching { session.allLogsAsString }.getOrNull().orEmpty().takeLast(1400)
            partial.delete()
            return@withContext Result.Failed(logs.ifBlank { "FFmpeg remux failed" })
        }

        if (!partial.renameTo(output)) {
            runCatching { partial.copyTo(output, overwrite = true) }
            partial.delete()
        }
        if (!output.isFile || output.length() <= 0L) {
            return@withContext Result.Failed("remux output missing")
        }

        val source = PreparedSource(originalPath, output.absolutePath, transcoded, info)
        prepared[originalPath] = source
        Result.Ready(source)
    }

    fun clearPrepared(originalPath: String) {
        prepared.remove(originalPath)?.let { runCatching { File(it.outputPath).delete() } }
        attempted.removeIf { it.startsWith(originalPath + "|") }
    }

    fun cleanupAll(context: Context) {
        prepared.values.forEach { runCatching { File(it.outputPath).delete() } }
        prepared.clear()
        attempted.clear()
        val baseDir = File(context.externalCacheDir ?: context.cacheDir, "cast_fallback")
        cleanupOldFiles(baseDir, deleteAll = true)
    }

    private fun executeRemux(input: String, output: File, transcodeAudio: Boolean) =
        FFmpegKit.executeWithArguments(
            buildList {
                addAll(listOf("-y", "-hide_banner", "-loglevel", "warning", "-i", input))
                addAll(listOf("-map", "0:v:0", "-map", "0:a?"))
                addAll(listOf("-map_metadata", "0", "-map_chapters", "0"))
                addAll(listOf("-c:v", "copy"))
                if (transcodeAudio) {
                    addAll(listOf("-c:a", "aac", "-b:a", "192k", "-ac", "2", "-ar", "48000"))
                } else {
                    addAll(listOf("-c:a", "copy"))
                }
                addAll(listOf("-sn", "-dn"))
                // MP4 fragmenté : aucun second passage "faststart", pas de réencodage et lecture
                // Range fiable une fois le fichier terminé.
                addAll(
                    listOf(
                        "-movflags", "+frag_keyframe+empty_moov+default_base_moof+omit_tfhd_offset",
                        "-avoid_negative_ts", "make_zero",
                        "-max_interleave_delta", "0",
                        "-f", "mp4",
                        output.absolutePath
                    )
                )
            }.toTypedArray()
        )

    private fun videoCanBeCopiedToMp4(codec: String): Boolean {
        if (codec.isBlank()) return true // FFmpeg décidera sans réencoder.
        val value = codec.uppercase(Locale.ROOT)
        return value in setOf("H.264", "H.265", "HEVC", "AV1", "DOLBY VISION")
    }

    private fun audioNeedsAac(codec: String): Boolean {
        if (codec.isBlank()) return false
        val value = codec.uppercase(Locale.ROOT).replace("-", "")
        return value !in setOf("AAC", "MP3", "AC3", "EAC3", "EAC3JOC")
    }

    private fun sourceLength(context: Context, path: String): Long = when {
        path.startsWith("content://", true) -> runCatching {
            context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(path), "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0L } ?: 0L
            } ?: 0L
        }.getOrDefault(0L)
        path.startsWith("file://", true) -> android.net.Uri.parse(path).path?.let { File(it).length() } ?: 0L
        path.startsWith("/", true) -> File(path).length()
        else -> 0L
    }

    private fun cleanupOldFiles(dir: File, deleteAll: Boolean = false) {
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        dir.listFiles()?.filter { deleteAll || it.lastModified() < cutoff || it.name.endsWith(".partial") }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun attemptKey(path: String, forceAudioTranscode: Boolean): String =
        "$path|${if (forceAudioTranscode) "audio" else "remux"}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private const val MIN_FREE_MARGIN_BYTES = 192L * 1024L * 1024L
}
