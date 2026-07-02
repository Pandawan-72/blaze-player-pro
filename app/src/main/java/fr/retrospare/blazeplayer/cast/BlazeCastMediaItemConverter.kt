package fr.retrospare.blazeplayer.cast

import android.net.Uri
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.common.images.WebImage

/**
 * Converter Cast explicite pour BlazePlayer.
 *
 * Le DefaultMediaItemConverter de Media3 1.9 ne transforme pas les
 * MediaItem.SubtitleConfiguration sidecar en MediaTrack Cast. Résultat observé dans les logs :
 *   MediaQueueItem Cast créé ... tracks=[] active=[]
 *
 * Ce converter garde le modèle Media3 officiel (CastPlayer/RemoteCastPlayer reçoit un MediaItem),
 * mais ajoute explicitement les pistes WebVTT au MediaInfo Cast et les active par défaut.
 */
@UnstableApi
class BlazeCastMediaItemConverter : MediaItemConverter {

    private val fallback = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val local = mediaItem.localConfiguration
            ?: return fallback.toMediaQueueItem(mediaItem)

        val fallbackItem = fallback.toMediaQueueItem(mediaItem)
        val fallbackMedia = fallbackItem.media

        val contentUrl = local.uri.toString()
        val contentId = contentUrl
        val contentType = local.mimeType ?: guessContentType(local.uri)

        val castMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            mediaItem.mediaMetadata.title?.let { putString(MediaMetadata.KEY_TITLE, it.toString()) }
            mediaItem.mediaMetadata.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it.toString()) }
            mediaItem.mediaMetadata.artworkUri?.let { addImage(WebImage(it)) }
        }

        val subtitleConfigs = local.subtitleConfigurations
        val tracks = subtitleConfigs.mapIndexed { index, subtitle ->
            val id = (index + 1).toLong()
            MediaTrack.Builder(id, MediaTrack.TYPE_TEXT)
                .setName(subtitle.label ?: "Français")
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(subtitle.uri.toString())
                .setContentType(subtitle.mimeType ?: MimeTypes.TEXT_VTT)
                .setLanguage(toCastLanguage(subtitle.language))
                .build()
        }
        val activeTrackIds = tracks.map { it.id }.toLongArray()

        val mediaInfoBuilder = MediaInfo.Builder(contentId)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType)
            .setContentUrl(contentUrl)
            .setMetadata(castMetadata)
            .setCustomData(fallbackMedia?.customData)

        if (tracks.isNotEmpty()) {
            mediaInfoBuilder.setMediaTracks(tracks)
        }

        val mediaInfo = mediaInfoBuilder.build()
        val queueBuilder = MediaQueueItem.Builder(mediaInfo)
            .setAutoplay(true)
            .setPreloadTime(0.0)

        if (activeTrackIds.isNotEmpty()) {
            queueBuilder.setActiveTrackIds(activeTrackIds)
        }

        android.util.Log.i(
            "CAST",
            "MediaQueueItem Cast créé url=$contentUrl contentType=$contentType " +
                "tracks=${tracks.map { "${it.id}:${it.name}:${it.contentId}:${it.contentType}" }} " +
                "active=${activeTrackIds.toList()}"
        )

        return queueBuilder.build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        return fallback.toMediaItem(mediaQueueItem)
    }

    private fun toCastLanguage(code: String?): String {
        return when (code) {
            "fra" -> "fr"
            "eng" -> "en"
            "spa" -> "es"
            "deu" -> "de"
            "ita" -> "it"
            "jpn" -> "ja"
            "por" -> "pt"
            "nld" -> "nl"
            "rus" -> "ru"
            "zho" -> "zh"
            null, "" -> "fr"
            else -> code
        }
    }

    private fun guessContentType(uri: Uri): String {
        return when (uri.toString().substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "mkv" -> "video/x-matroska"
            "webm" -> MimeTypes.VIDEO_WEBM
            "mov" -> "video/quicktime"
            "ts", "m2ts", "mts" -> "video/mp2t"
            else -> MimeTypes.VIDEO_MP4
        }
    }
}
