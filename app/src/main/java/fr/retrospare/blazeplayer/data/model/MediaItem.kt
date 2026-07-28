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
