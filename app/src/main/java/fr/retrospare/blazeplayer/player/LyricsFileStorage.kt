package fr.retrospare.blazeplayer.player

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import java.io.File
import java.util.EnumSet

/** Écrit un fichier LRC à côté de la piste, avec exactement le même nom de base. */
object LyricsFileStorage {
    data class SaveResult(val fileName: String, val location: String)

    fun saveBesideTrack(context: Context, audioPath: String, lyrics: String): SaveResult {
        require(audioPath.isNotBlank()) { "Chemin audio manquant" }
        require(lyrics.isNotBlank()) { "Paroles vides" }

        val normalized = lyrics.trimEnd() + "\n"
        return when {
            audioPath.startsWith("smb://", ignoreCase = true) -> saveToSmb(audioPath, normalized)
            audioPath.startsWith("http://", ignoreCase = true) || audioPath.startsWith("https://", ignoreCase = true) ->
                throw IllegalStateException("La source distante est en lecture seule")
            audioPath.startsWith("content://", ignoreCase = true) -> saveToContentUri(context, Uri.parse(audioPath), normalized)
            else -> saveToLocalFile(audioPath, normalized)
        }
    }

    private fun saveToLocalFile(audioPath: String, lyrics: String): SaveResult {
        val decoded = if (audioPath.startsWith("file://", ignoreCase = true)) {
            Uri.parse(audioPath).path.orEmpty()
        } else {
            Uri.decode(audioPath.substringBefore('?'))
        }
        val audioFile = File(decoded)
        val parent = audioFile.parentFile ?: throw IllegalStateException("Dossier audio introuvable")
        if (!parent.exists() || !parent.isDirectory) throw IllegalStateException("Dossier audio introuvable")
        val lrcName = lrcNameFor(audioFile.name)
        val target = File(parent, lrcName)
        target.outputStream().buffered().use { it.write(lyrics.toByteArray(Charsets.UTF_8)) }
        return SaveResult(lrcName, target.absolutePath)
    }

    private fun saveToSmb(audioPath: String, lyrics: String): SaveResult {
        val parsed = SmbDataSource.parseSmbUri(Uri.parse(audioPath))
        if (parsed.host.isBlank() || parsed.shareName.isBlank() || parsed.filePath.isBlank()) {
            throw IllegalStateException("Chemin SMB invalide")
        }
        val audioName = parsed.filePath.substringAfterLast('\\')
        val lrcName = lrcNameFor(audioName)
        val folder = parsed.filePath.substringBeforeLast('\\', missingDelimiterValue = "")
        val destination = if (folder.isBlank()) lrcName else "$folder\\$lrcName"

        var share: com.hierynomus.smbj.share.DiskShare? = null
        var file: com.hierynomus.smbj.share.File? = null
        try {
            share = SmbSessionPool.getShare(parsed.host, parsed.port, parsed.username, parsed.password, parsed.shareName)
            file = share.openFile(
                destination,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            )
            val bytes = lyrics.toByteArray(Charsets.UTF_8)
            file.write(bytes, 0L, 0, bytes.size)
            return SaveResult(lrcName, destination)
        } finally {
            try { file?.close() } catch (_: Exception) {}
            try { share?.close() } catch (_: Exception) {}
        }
    }

    private fun saveToContentUri(context: Context, audioUri: Uri, lyrics: String): SaveResult {
        val resolver = context.applicationContext.contentResolver
        val displayName = queryString(resolver, audioUri, OpenableColumns.DISPLAY_NAME)
            ?: audioUri.lastPathSegment?.substringAfterLast('/')
            ?: throw IllegalStateException("Nom du morceau introuvable")
        val lrcName = lrcNameFor(displayName)

        // Certains fournisseurs exposent encore un chemin fichier directement accessible.
        val directPath = queryString(resolver, audioUri, MediaStore.MediaColumns.DATA)
        if (!directPath.isNullOrBlank()) {
            val directFile = File(directPath)
            val parent = directFile.parentFile
            if (parent != null && parent.exists() && parent.canWrite()) {
                val target = File(parent, lrcName)
                target.outputStream().buffered().use { it.write(lyrics.toByteArray(Charsets.UTF_8)) }
                return SaveResult(lrcName, target.absolutePath)
            }
        }

        saveThroughDocumentProvider(context, audioUri, lrcName, lyrics)?.let { return it }
        saveThroughMediaStore(context, audioUri, lrcName, lyrics)?.let { return it }
        throw IllegalStateException("Android n’autorise pas l’écriture dans le dossier de ce morceau")
    }

    private fun saveThroughDocumentProvider(
        context: Context,
        audioUri: Uri,
        lrcName: String,
        lyrics: String
    ): SaveResult? = runCatching {
        if (!DocumentsContract.isDocumentUri(context, audioUri)) return@runCatching null
        val authority = audioUri.authority ?: return@runCatching null
        val documentId = DocumentsContract.getDocumentId(audioUri)
        val parentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentId.isBlank()) return@runCatching null
        val isTree = audioUri.pathSegments.contains("tree")
        val parentUri = if (isTree) {
            DocumentsContract.buildDocumentUriUsingTree(audioUri, parentId)
        } else {
            DocumentsContract.buildDocumentUri(authority, parentId)
        }
        val childrenUri = if (isTree) {
            DocumentsContract.buildChildDocumentsUriUsingTree(audioUri, parentId)
        } else {
            DocumentsContract.buildChildDocumentsUri(authority, parentId)
        }
        val resolver = context.contentResolver
        var targetUri: Uri? = null
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex).equals(lrcName, ignoreCase = true)) {
                    val childId = cursor.getString(idIndex)
                    targetUri = if (isTree) DocumentsContract.buildDocumentUriUsingTree(audioUri, childId)
                    else DocumentsContract.buildDocumentUri(authority, childId)
                    break
                }
            }
        }
        if (targetUri == null) {
            targetUri = DocumentsContract.createDocument(resolver, parentUri, "text/plain", lrcName)
        }
        val finalUri = targetUri ?: return@runCatching null
        resolver.openOutputStream(finalUri, "wt")?.use { it.write(lyrics.toByteArray(Charsets.UTF_8)) }
            ?: return@runCatching null
        SaveResult(lrcName, finalUri.toString())
    }.getOrNull()

    private fun saveThroughMediaStore(
        context: Context,
        audioUri: Uri,
        lrcName: String,
        lyrics: String
    ): SaveResult? = runCatching {
        val resolver = context.contentResolver
        val relativePath = queryString(resolver, audioUri, MediaStore.MediaColumns.RELATIVE_PATH)
            ?: return@runCatching null
        val collection = MediaStore.Files.getContentUri("external")
        var targetUri: Uri? = null
        resolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath, lrcName),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                targetUri = ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        }
        if (targetUri == null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, lrcName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            targetUri = resolver.insert(collection, values)
        }
        val finalUri = targetUri ?: return@runCatching null
        resolver.openOutputStream(finalUri, "wt")?.use { it.write(lyrics.toByteArray(Charsets.UTF_8)) }
            ?: return@runCatching null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(finalUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        SaveResult(lrcName, finalUri.toString())
    }.getOrNull()

    private fun queryString(
        resolver: android.content.ContentResolver,
        uri: Uri,
        column: String
    ): String? = runCatching {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(column)
            if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    private fun lrcNameFor(audioName: String): String {
        val clean = Uri.decode(audioName.substringBefore('?'))
            .substringAfterLast('/')
            .substringAfterLast('\\')
        val base = clean.substringBeforeLast('.', clean).ifBlank { "lyrics" }
        return "$base.lrc"
    }
}
