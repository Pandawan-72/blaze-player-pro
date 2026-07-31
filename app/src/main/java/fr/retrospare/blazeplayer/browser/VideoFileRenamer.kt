package fr.retrospare.blazeplayer.browser

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.player.SmbDataSource
import fr.retrospare.blazeplayer.player.SmbSessionPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.EnumSet

/** Renomme une vidéo sans jamais exposer ni modifier son extension dans le champ de saisie. */
object VideoFileRenamer {

    sealed interface Result {
        object Success : Result
        object AlreadyExists : Result
        object ReadOnlyNetwork : Result
        data class PermissionRequired(val intentSender: IntentSender) : Result
        data class Failure(val error: Throwable? = null) : Result
    }

    private val forbiddenCharacters = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    fun originalExtension(item: MediaItem): String {
        // Priorité au suffixe réellement écrit dans le nom afin de conserver aussi sa casse
        // (.MP4 reste .MP4). L'extension n'est jamais proposée dans le champ de saisie.
        val fromName = item.name.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        val fromItem = item.extension.trim().removePrefix(".")
        val fromPath = item.path.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        return fromName.ifBlank { fromItem }.ifBlank { fromPath }
    }

    fun originalBaseName(item: MediaItem): String {
        val extension = originalExtension(item)
        val name = item.name.trim()
        return if (extension.isNotBlank() && name.endsWith(".$extension", ignoreCase = true)) {
            name.dropLast(extension.length + 1)
        } else {
            name.substringBeforeLast('.', name)
        }.ifBlank { name }
    }

    fun normalizeBaseName(raw: String, extension: String): String {
        var value = raw.trim()
        if (extension.isNotBlank() && value.endsWith(".$extension", ignoreCase = true)) {
            value = value.dropLast(extension.length + 1).trimEnd()
        }
        return value
    }

    fun isValidBaseName(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." && !forbiddenCharacters.containsMatchIn(value)

    suspend fun rename(context: Context, item: MediaItem, requestedBaseName: String): Result =
        withContext(Dispatchers.IO) {
            val extension = originalExtension(item)
            val baseName = normalizeBaseName(requestedBaseName, extension)
            if (!isValidBaseName(baseName)) return@withContext Result.Failure(IllegalArgumentException("invalid name"))
            val newDisplayName = if (extension.isBlank()) baseName else "$baseName.$extension"

            when {
                item.path.startsWith("smb://", ignoreCase = true) -> renameSmb(item, newDisplayName)
                item.isNetwork || item.path.startsWith("http://", true) || item.path.startsWith("https://", true) -> Result.ReadOnlyNetwork
                else -> renameLocal(context.applicationContext, item, newDisplayName)
            }
        }

    private fun renameLocal(context: Context, item: MediaItem, newDisplayName: String): Result {
        val resolver = context.contentResolver
        val mediaUri = when {
            item.path.startsWith("content://", ignoreCase = true) -> runCatching { Uri.parse(item.path) }.getOrNull()
            item.id.toLongOrNull() != null -> ContentUris.withAppendedId(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                item.id.toLong()
            )
            else -> null
        }

        if (mediaUri != null) {
            return try {
                val updated = resolver.update(
                    mediaUri,
                    ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName) },
                    null,
                    null
                )
                if (updated > 0) Result.Success else Result.Failure()
            } catch (error: RecoverableSecurityException) {
                Result.PermissionRequired(error.userAction.actionIntent.intentSender)
            } catch (error: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching {
                        MediaStore.createWriteRequest(resolver, listOf(mediaUri)).intentSender
                    }.fold(
                        onSuccess = { Result.PermissionRequired(it) },
                        onFailure = { Result.Failure(error) }
                    )
                } else {
                    Result.Failure(error)
                }
            } catch (error: Throwable) {
                Result.Failure(error)
            }
        }

        val sourcePath = if (item.path.startsWith("file://", true)) Uri.parse(item.path).path else item.path
        val source = sourcePath?.let(::File) ?: return Result.Failure()
        val parent = source.parentFile ?: return Result.Failure()
        val target = File(parent, newDisplayName)
        if (target.exists()) return Result.AlreadyExists
        return try {
            if (!source.renameTo(target)) return Result.Failure()
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(item.mimeType), null)
            Result.Success
        } catch (error: Throwable) {
            Result.Failure(error)
        }
    }

    private fun renameSmb(item: MediaItem, newDisplayName: String): Result {
        var share: com.hierynomus.smbj.share.DiskShare? = null
        var file: com.hierynomus.smbj.share.File? = null
        return try {
            val parsed = SmbDataSource.parseSmbUri(Uri.parse(item.path))
            val oldPath = parsed.filePath.replace('/', '\\')
            val parent = oldPath.substringBeforeLast('\\', "")
            val newPath = if (parent.isBlank()) newDisplayName else "$parent\\$newDisplayName"
            share = SmbSessionPool.getShare(
                parsed.host,
                parsed.port,
                parsed.username,
                parsed.password,
                parsed.shareName
            )
            file = share.openFile(
                oldPath,
                EnumSet.of(AccessMask.DELETE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java)
            )
            file.rename(newPath, false)
            Result.Success
        } catch (error: Throwable) {
            // UPnP est filtré avant cette méthode. Une erreur SMB correspond donc à un refus du
            // serveur, un fichier déjà existant ou un droit insuffisant : l'UI affiche un échec
            // explicite sans altérer l'ancien chemin.
            Result.Failure(error)
        } finally {
            try { file?.close() } catch (_: Throwable) {}
            try { share?.close() } catch (_: Throwable) {}
        }
    }
}
