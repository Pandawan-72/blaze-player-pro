package fr.retrospare.blazeplayer.gallery

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import fr.retrospare.blazeplayer.R
import java.io.File

/**
 * Affiche les métadonnées (EXIF) d'une photo : dimensions, poids, date, appareil, position GPS
 * si disponible. Vit désormais dans le menu "..." de la Galerie (entrée "Informations") plutôt
 * que dans l'éditeur photo lui-même — les deux sont des besoins distincts : consulter les
 * métadonnées d'une photo n'a pas grand-chose à voir avec la retoucher.
 *
 * Utilise `android.media.ExifInterface` (natif Android) plutôt que la bibliothèque androidx
 * équivalente, pour ne pas exiger de dépendance Gradle supplémentaire.
 */
object PhotoDetailsDialog {

    fun show(context: Context, path: String) {
        val message = StringBuilder()
        try {
            val exif = if (path.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(path))?.use { android.media.ExifInterface(it) }
            } else {
                android.media.ExifInterface(path)
            }
            val width = exif?.getAttributeInt(android.media.ExifInterface.TAG_IMAGE_WIDTH, 0) ?: 0
            val height = exif?.getAttributeInt(android.media.ExifInterface.TAG_IMAGE_LENGTH, 0) ?: 0
            if (width > 0 && height > 0) {
                message.append(context.getString(R.string.photo_details_dimensions, "${width}×${height}")).append('\n')
            }
            val file = if (!path.startsWith("content://")) File(path) else null
            file?.let {
                message.append(
                    context.getString(R.string.photo_details_size, android.text.format.Formatter.formatShortFileSize(context, it.length()))
                ).append('\n')
            }
            val date = exif?.getAttribute(android.media.ExifInterface.TAG_DATETIME)
            if (!date.isNullOrBlank()) message.append(context.getString(R.string.photo_details_date, date)).append('\n')
            val make = exif?.getAttribute(android.media.ExifInterface.TAG_MAKE)
            val model = exif?.getAttribute(android.media.ExifInterface.TAG_MODEL)
            val camera = listOfNotNull(make, model).joinToString(" ").trim()
            if (camera.isNotBlank()) message.append(context.getString(R.string.photo_details_camera, camera)).append('\n')
            val latLong = FloatArray(2)
            if (exif?.getLatLong(latLong) == true) {
                message.append(context.getString(R.string.photo_details_location, "%.5f, %.5f".format(latLong[0], latLong[1])))
            }
        } catch (_: Exception) {
            // Pas de métadonnées lisibles (format non supporté, fichier distant...) : message par
            // défaut ci-dessous.
        }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.action_information))
            .setMessage(if (message.isBlank()) context.getString(R.string.photo_details_no_exif) else message.toString().trim())
            .setPositiveButton(context.getString(R.string.action_ok), null)
            .show()
    }
}
