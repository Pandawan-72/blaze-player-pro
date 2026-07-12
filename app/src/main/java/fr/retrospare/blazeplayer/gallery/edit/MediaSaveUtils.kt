package fr.retrospare.blazeplayer.gallery.edit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Enregistre les résultats des outils d'édition (filtre/recadrage photo, découpe vidéo/GIF) via
 * MediaStore, en gérant à la fois le stockage cloisonné (Android 10+, écriture par flux avec
 * IS_PENDING) et l'accès fichier direct (versions antérieures) — même approche que le reste de la
 * Galerie pour rester cohérent avec les conventions déjà en place dans l'app.
 *
 * Tout est enregistré dans un sous-dossier dédié "Blaze Gallery" (Photos ou Films selon le type),
 * jamais en écrasant le fichier d'origine : l'utilisateur retrouve le résultat comme un nouveau
 * fichier, l'original reste intact.
 */
object MediaSaveUtils {

    fun saveEditedBitmap(context: Context, bitmap: Bitmap, baseName: String): Boolean {
        val displayName = "${sanitize(baseName)}_${System.currentTimeMillis()}.jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Blaze Gallery")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                    ?: return false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Blaze Gallery")
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, displayName)
                FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, target.absolutePath)
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Publie un fichier déjà produit sur disque (par ffmpeg, dans le cache de l'app) vers la
     *  bibliothèque publique, en le référençant dans MediaStore. Couvre à la fois les vidéos
     *  découpées (mimeType vidéo) et les GIF (mimeType "image/gif", qui vont côté Images).
     *  Retourne l'Uri MediaStore du fichier publié (ou null en cas d'échec), pour permettre à
     *  l'appelant de retrouver ensuite le dossier de la galerie où il vient d'être enregistré. */
    fun publishProcessedFile(context: Context, sourceFile: File, displayName: String, mimeType: String): Uri? {
        val isGif = mimeType == "image/gif"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = if (isGif) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isGif) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES) + "/Blaze Gallery")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return null
                resolver.openOutputStream(uri)?.use { out -> sourceFile.inputStream().use { input -> input.copyTo(out) } }
                    ?: return null
                val clear = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, clear, null, null)
                uri
            } else {
                val subdir = if (isGif) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
                val dir = File(Environment.getExternalStoragePublicDirectory(subdir), "Blaze Gallery")
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, displayName)
                sourceFile.copyTo(target, overwrite = true)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, target.absolutePath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                }
                val collection = if (isGif) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                context.contentResolver.insert(collection, values)
            }
        } catch (_: Exception) {
            null
        }
    }


    /** Publie un extrait audio MP3 dans le dossier public :
     *  Documents/Blaze Audio Extractor. Sur Android 10+, le fichier est créé via MediaStore.Files
     *  avec RELATIVE_PATH afin de respecter le stockage cloisonné. Sur Android 9, le dossier est
     *  créé directement dans le répertoire public Documents.
     *
     *  [prepareSourceForDisplayName] est appelé après résolution du nom unique final et avant la
     *  copie. L'exporteur MP3 l'utilise pour écrire ce nom exact dans le titre ID3 TIT2. */
    fun publishMp3CutFile(
        context: Context,
        sourceFile: File,
        displayName: String,
        prepareSourceForDisplayName: ((String) -> Boolean)? = null
    ): Uri? {
        val folderName = "Blaze Audio Extractor"
        val mimeType = "audio/mpeg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val relativePath = Environment.DIRECTORY_DOCUMENTS + "/$folderName/"
                val filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uniqueDisplayName = findAvailableMp3Name(
                    context = context,
                    requestedName = displayName,
                    folderName = folderName,
                    collection = filesUri,
                    relativePath = relativePath
                )
                if (prepareSourceForDisplayName?.invoke(uniqueDisplayName) == false) {
                    android.util.Log.e("MediaSaveUtils", "Échec préparation ID3 du MP3 $uniqueDisplayName")
                    return null
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueDisplayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                insertAndCopy(
                    resolver = resolver,
                    collection = filesUri,
                    values = values,
                    sourceFile = sourceFile
                )
            } else {
                @Suppress("DEPRECATION")
                val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val dir = File(documentsDir, folderName)
                if (!dir.exists() && !dir.mkdirs()) return null

                val uniqueDisplayName = findAvailableMp3Name(
                    context = context,
                    requestedName = displayName,
                    folderName = folderName,
                    legacyDirectory = dir
                )
                if (prepareSourceForDisplayName?.invoke(uniqueDisplayName) == false) {
                    android.util.Log.e("MediaSaveUtils", "Échec préparation ID3 du MP3 $uniqueDisplayName")
                    return null
                }
                val target = File(dir, uniqueDisplayName)
                sourceFile.copyTo(target, overwrite = false)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, target.absolutePath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueDisplayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.TITLE, uniqueDisplayName.substringBeforeLast('.'))
                    put(MediaStore.Audio.Media.ARTIST, "Blaze Video to MP3")
                    put(MediaStore.Audio.Media.ALBUM, folderName)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                }
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaSaveUtils", "Échec publication MP3 dans Documents/$folderName", e)
            null
        }
    }

    /** Retourne le premier nom libre dans Documents/Blaze Audio Extractor.
     *  Le premier export garde le nom demandé ; les suivants reçoivent _001, _002, etc. */
    private fun findAvailableMp3Name(
        context: Context,
        requestedName: String,
        folderName: String,
        collection: Uri? = null,
        relativePath: String? = null,
        legacyDirectory: File? = null
    ): String {
        val extension = requestedName.substringAfterLast('.', "mp3")
        val baseName = requestedName.removeSuffix(".$extension")
        val existingNames = mutableSetOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && collection != null && relativePath != null) {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                null
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (nameColumn >= 0 && cursor.moveToNext()) {
                    cursor.getString(nameColumn)?.let(existingNames::add)
                }
            }
        } else {
            val directory = legacyDirectory ?: run {
                @Suppress("DEPRECATION")
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    folderName
                )
            }
            directory.listFiles()?.mapTo(existingNames) { it.name }
        }

        if (requestedName !in existingNames) return requestedName

        var index = 1
        while (true) {
            val candidate = String.format(
                Locale.US,
                "%s_%03d.%s",
                baseName,
                index,
                extension
            )
            if (candidate !in existingNames) return candidate
            index++
        }
    }

    private fun insertAndCopy(
        resolver: android.content.ContentResolver,
        collection: Uri,
        values: ContentValues,
        sourceFile: File
    ): Uri? {
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            // Une insertion refusée doit faire échouer proprement l'export : le fichier ne doit
            // jamais être redirigé vers Music, Downloads ou un autre emplacement implicite.
            android.util.Log.w("MediaSaveUtils", "Insertion MediaStore refusée pour $collection", e)
            null
        } ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: return null
            val clear = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, clear, null, null)
            uri
        } catch (e: Exception) {
            android.util.Log.e("MediaSaveUtils", "Échec copie MediaStore vers $collection", e)
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            null
        }
    }

    /** Identifiant + nom du dossier (bucket MediaStore) contenant le média désigné par [uri] —
     *  utilisé après une découpe vidéo/export GIF pour ramener l'utilisateur directement dans le
     *  dossier "Blaze Gallery" où le résultat vient d'être enregistré. */
    fun bucketInfoForUri(context: Context, uri: Uri, isGif: Boolean): Pair<String, String>? {
        val idCol = if (isGif) MediaStore.Images.Media.BUCKET_ID else MediaStore.Video.Media.BUCKET_ID
        val nameCol = if (isGif) MediaStore.Images.Media.BUCKET_DISPLAY_NAME else MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        return try {
            context.contentResolver.query(uri, arrayOf(idCol, nameCol), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getString(cursor.getColumnIndexOrThrow(idCol))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(nameCol))
                    if (id != null && name != null) id to name else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitize(name: String): String =
        name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(40).ifBlank { "Blaze" }
}
