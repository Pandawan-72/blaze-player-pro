package fr.retrospare.blazeplayer.player

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.EnumSet

/** Écriture destructive contrôlée des tags audio.
 *  FFmpeg remuxe le fichier en copie de flux : audio et pochette existante restent inchangés,
 *  seules les métadonnées changent. Les fichiers locaux et SMB/NAS sont modifiés directement ;
 *  les sources non-écrivable (HTTP/UPnP/content providers sans sortie) renvoient une erreur claire. */
object AudioTagWriter {

    data class EditableTags(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val genre: String = "",
        val year: String = "",
        val track: String = "",
        val disc: String = ""
    )

    suspend fun write(context: Context, path: String, tags: EditableTags): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (path.startsWith("http://", true) || path.startsWith("https://", true)) {
                error(context.getString(fr.retrospare.blazeplayer.R.string.audio_tag_network_not_supported))
            }

            val isSmb = path.startsWith("smb://", true)
            val smbParsed = if (isSmb) SmbDataSource.parseSmbUri(Uri.parse(path)) else null
            val tempInputs = mutableListOf<File>()
            val inputFile: File
            val originalUri: Uri? = if (path.startsWith("content://", true)) Uri.parse(path) else null
            if (smbParsed != null) {
                inputFile = copySmbToCache(context, path, smbParsed)
                tempInputs += inputFile
            } else if (originalUri != null) {
                inputFile = File(context.cacheDir, "tag_input_${System.currentTimeMillis()}.${guessExtension(path, "m4a")}")
                context.contentResolver.openInputStream(originalUri)?.use { input ->
                    inputFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error(context.getString(fr.retrospare.blazeplayer.R.string.audio_tag_write_failed))
                tempInputs += inputFile
            } else {
                val cleanPath = if (path.startsWith("file://", true)) Uri.parse(path).path.orEmpty() else path
                inputFile = File(cleanPath)
                if (!inputFile.exists() || !inputFile.isFile) error(context.getString(fr.retrospare.blazeplayer.R.string.audio_tag_write_failed))
            }

            val ext = inputFile.extension.ifBlank { guessExtension(path, "m4a") }
            val outputFile = File(context.cacheDir, "tag_output_${System.currentTimeMillis()}.$ext")
            val args = mutableListOf(
                "-y", "-hide_banner", "-loglevel", "warning",
                "-i", inputFile.absolutePath,
                "-map", "0",
                "-c", "copy",
                "-map_metadata", "0",
                "-id3v2_version", "3"
            )
            fun metadata(key: String, value: String) {
                val clean = value.trim()
                if (clean.isNotBlank()) {
                    args += "-metadata"
                    args += "$key=$clean"
                }
            }
            metadata("title", tags.title)
            metadata("artist", tags.artist)
            metadata("album", tags.album)
            metadata("genre", tags.genre)
            metadata("date", tags.year)
            metadata("track", tags.track)
            metadata("disc", tags.disc)
            args += outputFile.absolutePath

            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            if (!ReturnCode.isSuccess(session.returnCode) || !outputFile.exists() || outputFile.length() <= 0L) {
                throw IllegalStateException(session.allLogsAsString.takeLast(900).ifBlank { context.getString(fr.retrospare.blazeplayer.R.string.audio_tag_write_failed) })
            }

            if (smbParsed != null) {
                writeCacheBackToSmb(context, smbParsed, outputFile)
            } else if (originalUri != null) {
                context.contentResolver.openOutputStream(originalUri, "w")?.use { output ->
                    outputFile.inputStream().use { input -> input.copyTo(output) }
                } ?: error(context.getString(fr.retrospare.blazeplayer.R.string.audio_tag_write_failed))
            } else {
                inputFile.outputStream().use { output ->
                    outputFile.inputStream().use { input -> input.copyTo(output) }
                }
                MediaScannerConnection.scanFile(context, arrayOf(inputFile.absolutePath), null, null)
            }
            outputFile.delete()
            tempInputs.forEach { it.delete() }

            AudioMetadataExtractor.putCached(
                context,
                path,
                AudioTechnicalInfo(
                    title = tags.title,
                    artist = tags.artist,
                    album = tags.album,
                    trackNumber = tags.track.substringBefore('/').toIntOrNull() ?: 0,
                    extension = ext.uppercase()
                )
            )
            AudioLocalEnhancements.saveOverride(context, path, AudioLocalEnhancements.MetadataOverride())
        }
    }

    private fun copySmbToCache(context: Context, path: String, parsed: SmbDataSource.ParsedSmbUri): File {
        val temp = File(context.cacheDir, "tag_smb_input_${System.currentTimeMillis()}.${guessExtension(path, "m4a")}")
        var share: com.hierynomus.smbj.share.DiskShare? = null
        var file: com.hierynomus.smbj.share.File? = null
        try {
            share = SmbSessionPool.getShare(parsed.host, parsed.port, parsed.username, parsed.password, parsed.shareName)
            file = share.openFile(
                parsed.filePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java)
            )
            val size = file.getFileInformation(FileStandardInformation::class.java).endOfFile
            val buffer = ByteArray(512 * 1024)
            var offset = 0L
            temp.outputStream().use { output ->
                while (offset < size) {
                    val wanted = minOf(buffer.size.toLong(), size - offset).toInt()
                    val read = file.read(buffer, offset, 0, wanted)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    offset += read.toLong()
                }
            }
            if (size > 0L && temp.length() <= 0L) error(context.getString(fr.retrospare.blazeplayer.R.string.audio_tag_write_failed))
            return temp
        } catch (e: Exception) {
            temp.delete()
            throw e
        } finally {
            try { file?.close() } catch (_: Exception) {}
            try { share?.close() } catch (_: Exception) {}
        }
    }

    private fun writeCacheBackToSmb(context: Context, parsed: SmbDataSource.ParsedSmbUri, source: File) {
        var share: com.hierynomus.smbj.share.DiskShare? = null
        var file: com.hierynomus.smbj.share.File? = null
        try {
            share = SmbSessionPool.getShare(parsed.host, parsed.port, parsed.username, parsed.password, parsed.shareName)
            file = share.openFile(
                parsed.filePath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE,
                EnumSet.noneOf(SMB2CreateOptions::class.java)
            )
            val buffer = ByteArray(512 * 1024)
            var offset = 0L
            source.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    file.write(buffer, offset, 0, read)
                    offset += read.toLong()
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException(context.getString(fr.retrospare.blazeplayer.R.string.audio_tag_write_failed), e)
        } finally {
            try { file?.close() } catch (_: Exception) {}
            try { share?.close() } catch (_: Exception) {}
        }
    }

    private fun guessExtension(path: String, fallback: String): String = path.substringBefore('?').substringAfterLast('.', fallback).takeIf { it.length in 2..5 } ?: fallback
}
