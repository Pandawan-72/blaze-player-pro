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


    /** Publie un extrait audio MP3 dans un dossier public à la racine du stockage partagé :
     *  /storage/emulated/0/Blaze MP3 Cut sur les appareils où le stockage partagé correspond à
     *  ce chemin. Sur Android 10+, MediaStore crée le dossier via RELATIVE_PATH sans demander
     *  d'accès fichier brut ; sur Android 9, on crée le dossier directement. */
    fun publishMp3CutFile(context: Context, sourceFile: File, displayName: String): Uri? {
        val folderName = "Blaze MP3 Cut"
        val mimeType = "audio/mpeg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver

                // Objectif demandé : créer le dossier directement à la racine du stockage partagé
                // sous le nom "Blaze MP3 Cut". Sur Android 10+, l'accès fichier direct à la
                // racine est cloisonné ; on passe donc par MediaStore.Files, qui accepte mieux les
                // chemins relatifs génériques que MediaStore.Audio sur certains appareils.
                val rootValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$folderName/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                insertAndCopy(resolver = resolver, collection = filesUri, values = rootValues, sourceFile = sourceFile)?.let { return it }

                // Fallback Android strict : depuis le stockage cloisonné, Android peut refuser
                // tout dossier de premier niveau personnalisé. Pour un MP3 avec cover, on tente
                // d'abord Music via MediaStore.Audio : Android indexe alors le fichier comme audio
                // et relit mieux les tags ID3/APIC que lorsqu'il est publié comme fichier générique
                // dans Documents.
                val musicValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/$folderName/")
                    put(MediaStore.Audio.Media.TITLE, displayName.substringBeforeLast('.'))
                    put(MediaStore.Audio.Media.ARTIST, "Blaze Video to MP3")
                    put(MediaStore.Audio.Media.ALBUM, folderName)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                insertAndCopy(resolver = resolver, collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values = musicValues, sourceFile = sourceFile)?.let { return it }

                val documentsValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/$folderName/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                insertAndCopy(resolver = resolver, collection = filesUri, values = documentsValues, sourceFile = sourceFile)?.let { return it }

                // Dernier filet de sécurité : Downloads est presque toujours accepté par MediaStore.
                val downloadsValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$folderName/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                insertAndCopy(resolver = resolver, collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI, values = downloadsValues, sourceFile = sourceFile)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStorageDirectory(), folderName)
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, displayName)
                sourceFile.copyTo(target, overwrite = true)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, target.absolutePath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.TITLE, displayName.substringBeforeLast('.'))
                    put(MediaStore.Audio.Media.ARTIST, "Blaze Video to MP3")
                    put(MediaStore.Audio.Media.ALBUM, folderName)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                }
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaSaveUtils", "Échec publication MP3", e)
            null
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
            // Certains Android refusent une RELATIVE_PATH hors dossiers publics autorisés
            // (ex. "Blaze MP3 Cut/" à la racine). On retourne null pour laisser
            // publishMp3CutFile essayer les dossiers de secours au lieu de faire échouer l'export.
            android.util.Log.w("MediaSaveUtils", "Insertion MediaStore refusée pour $collection, tentative suivante", e)
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
