package fr.retrospare.blazeplayer.data.model

data class MediaItem(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val size: Long = 0L,
    val duration: Long = 0L,
    val lastPosition: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val isNetwork: Boolean = false,
    val networkShareId: String? = null,
    val resolution: String? = null,
    val extension: String = "",
    val mimeType: String = "",
    val videoCodec: String? = null,
    val audioCodec: String? = null
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

    val formattedSize: String
        get() {
            return when {
                size >= 1_000_000_000 -> "%.1f Go".format(size / 1_000_000_000.0)
                size >= 1_000_000 -> "%.1f Mo".format(size / 1_000_000.0)
                size >= 1_000 -> "%.1f Ko".format(size / 1_000.0)
                else -> "$size o"
            }
        }
}
