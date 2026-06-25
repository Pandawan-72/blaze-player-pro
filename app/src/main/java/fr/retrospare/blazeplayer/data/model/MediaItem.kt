package fr.retrospare.blazeplayer.data.model

data class MediaItem(
    val id: String,
    val title: String,
    val path: String,
    val mimeType: String,
    val size: Long = 0L,
    val duration: Long = 0L,
    val thumbnailPath: String? = null,
    val source: MediaSource = MediaSource.LOCAL
)

enum class MediaSource {
    LOCAL,
    SMB,
    DLNA
}
