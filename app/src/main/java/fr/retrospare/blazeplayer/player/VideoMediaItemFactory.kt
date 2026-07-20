package fr.retrospare.blazeplayer.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import fr.retrospare.blazeplayer.cast.VideoStreamServerManager
import fr.retrospare.blazeplayer.debug.CrashReporter
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import java.io.File

/** Construction partagée des MediaItem vidéo pour le player et la télécommande Cast. */
object VideoMediaItemFactory {

    fun build(
        context: Context,
        path: String,
        name: String,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList()
    ): MediaItem {
        try {
            if (!path.startsWith("http://", true) && !path.startsWith("https://", true)) {
                VideoStreamServerManager.startServer(context.applicationContext, path)
            }
        } catch (e: Exception) {
            CrashReporter.log(context.applicationContext, "Failed to refresh cast fallback stream for $path", e)
        }

        val uri = when {
            path.startsWith("content://", true) || path.startsWith("file://", true) ||
                path.startsWith("smb://", true) || path.startsWith("ftp://", true) ||
                path.startsWith("http://", true) || path.startsWith("https://", true) -> Uri.parse(path)
            else -> Uri.fromFile(File(path))
        }

        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(path)
            .setMimeType(guessMimeType(path))
            .setSubtitleConfigurations(subtitleConfigurations)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MOVIE)
                    .apply {
                        ThumbnailUtils.getCachedThumbnailJpegBytes(context.applicationContext, path)?.let { bytes ->
                            setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        }
                    }
                    .build()
            )
            .build()
    }

    private fun guessMimeType(path: String): String {
        return when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "mkv" -> "video/x-matroska"
            "webm" -> MimeTypes.VIDEO_WEBM
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "ts", "m2ts", "mts" -> "video/mp2t"
            else -> MimeTypes.VIDEO_MP4
        }
    }
}
