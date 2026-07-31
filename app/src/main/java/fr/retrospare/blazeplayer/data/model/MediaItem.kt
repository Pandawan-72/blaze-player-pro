package fr.retrospare.blazeplayer.data.model

data class MediaItem(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val size: Long = 0L,
    val duration: Long = 0L,
    val modifiedAt: Long = 0L,
    val lastPosition: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val isNetwork: Boolean = false,
    val networkShareId: String? = null,
    val resolution: String? = null,
    val extension: String = "",
    val mimeType: String = "",
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val previewUris: List<String> = emptyList(),
    val artist: String = "",
    val album: String = "",
    val trackNumber: Int = 0,
    /**
     * Chemin logique de la médiathèque, par exemple Artiste/Album/Titre.
     * Pour UPnP, path reste l'URL HTTP réellement lisible alors que libraryPath conserve
     * l'arborescence des containers parcourus.
     */
    val libraryPath: String = ""
) {
    /**
     * Nom affichable complet. Certains serveurs UPnP fournissent un titre sans suffixe alors que
     * le conteneur est connu séparément. L'ajouter ici garantit que le titre affiché et tronqué
     * tient bien compte du conteneur, sans dupliquer une extension déjà présente.
     */
    val displayNameWithContainer: String
        get() {
            val cleanName = name.trim()
            if (cleanName.isEmpty()) return cleanName

            // Utiliser une extension stable dès le premier affichage. Les métadonnées détaillées
            // peuvent arriver après le nom dans l'historique ; si le titre change à ce moment-là,
            // un TextView long est remesuré et produit un scintillement visible. Le nom puis le
            // chemin constituent donc les fallbacks avant la valeur enrichie.
            fun validExtension(raw: String): String = raw
                .trim()
                .removePrefix(".")
                .takeIf { it.length in 2..5 && it.all { char -> char.isLetterOrDigit() } }
                .orEmpty()

            val extensionFromName = validExtension(
                cleanName.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
            )
            val extensionFromPath = validExtension(
                path.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
            )
            val extensionFromMime = when {
                mimeType.contains("matroska", ignoreCase = true) -> "mkv"
                mimeType.contains("webm", ignoreCase = true) -> "webm"
                mimeType.contains("quicktime", ignoreCase = true) -> "mov"
                mimeType.contains("x-msvideo", ignoreCase = true) -> "avi"
                mimeType.contains("mp4", ignoreCase = true) -> "mp4"
                else -> ""
            }
            val cleanExtension = extensionFromName
                .ifEmpty { extensionFromPath }
                .ifEmpty { validExtension(extension) }
                .ifEmpty { extensionFromMime }

            if (cleanExtension.isEmpty()) return cleanName
            val suffix = ".${cleanExtension}"
            return if (cleanName.endsWith(suffix, ignoreCase = true)) cleanName else cleanName + suffix
        }

    val formattedDuration: String
        get() {
            if (duration <= 0) return ""
            val hours = duration / 3600
            val minutes = (duration % 3600) / 60
            val seconds = duration % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
}
