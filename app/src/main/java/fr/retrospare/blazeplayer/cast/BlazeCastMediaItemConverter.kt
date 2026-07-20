package fr.retrospare.blazeplayer.cast

import android.content.Context
import android.net.Uri
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import fr.retrospare.blazeplayer.player.ExternalSubtitleManager
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
class BlazeCastMediaItemConverter(context: Context) : MediaItemConverter {

    private val appContext = context.applicationContext

    private val fallback = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val local = mediaItem.localConfiguration
            ?: return fallback.toMediaQueueItem(mediaItem)

        val fallbackItem = fallback.toMediaQueueItem(mediaItem)
        val fallbackMedia = fallbackItem.media

        // Cast vidéo uniquement : ne jamais lire d'URL relais ou d'extra audio ici.
        // Le lecteur audio ne possède plus d'implémentation Chromecast afin d'éviter tout mélange
        // d'état/métadonnées avec la vidéo.
        val originalUrl = local.uri.toString()
        val originalSourcePath = mediaItem.mediaId.takeIf { it.isNotBlank() } ?:
            VideoStreamServerManager.currentSourcePath.takeIf { it.isNotBlank() } ?: originalUrl
        val preparedPath = ChromecastFallbackManager.preparedPath(originalSourcePath)
        val castSourcePath = preparedPath ?: originalSourcePath
        val sourceUri = if (preparedPath != null) Uri.fromFile(java.io.File(preparedPath)) else local.uri
        val sourceUrl = sourceUri.toString()
        val scheme = sourceUri.scheme?.lowercase()
        val needsRelay = preparedPath != null || sourceUrl.contains("127.0.0.1") ||
            sourceUrl.contains("localhost") || scheme !in setOf("http", "https")
        val contentUrl = if (needsRelay) {
            // Chaque MediaItem obtient sa propre URL versionnée. Le fallback MP4 et les sources
            // originales restent enregistrés séparément dans le même serveur HTTP.
            runCatching { VideoStreamServerManager.getLanStreamUrlFor(appContext, castSourcePath) }
                .onFailure { android.util.Log.e("CAST", "Impossible de préparer le relais pour $castSourcePath", it) }
                .getOrNull() ?: sourceUrl
        } else sourceUrl
        val contentId = contentUrl
        val contentType = if (preparedPath != null) MimeTypes.VIDEO_MP4 else (local.mimeType ?: guessContentType(local.uri))

        val castMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            mediaItem.mediaMetadata.title?.let { putString(MediaMetadata.KEY_TITLE, it.toString()) }
            mediaItem.mediaMetadata.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it.toString()) }
            mediaItem.mediaMetadata.artworkUri?.let { addImage(WebImage(it)) }
        }

        val subtitleConfigs = local.subtitleConfigurations
        val tracks = subtitleConfigs.mapIndexedNotNull { index, subtitle ->
            val selection = ExternalSubtitleManager.Selection(
                uri = subtitle.uri.toString(),
                mimeType = subtitle.mimeType ?: MimeTypes.TEXT_VTT,
                label = subtitle.label ?: "Sous-titres"
            )
            val preparedVtt = ExternalSubtitleManager.preparedWebVttPath(selection)
            val subtitleUrl = when {
                subtitle.mimeType == MimeTypes.TEXT_VTT && subtitle.uri.scheme?.lowercase() in setOf("http", "https") ->
                    subtitle.uri.toString()
                preparedVtt != null -> runCatching {
                    VideoStreamServerManager.getLanStreamUrlFor(appContext, preparedVtt)
                }.getOrNull()
                else -> null
            } ?: return@mapIndexedNotNull null
            val id = (index + 1).toLong()
            MediaTrack.Builder(id, MediaTrack.TYPE_TEXT)
                .setName(selection.label)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(subtitleUrl)
                .setContentType(MimeTypes.TEXT_VTT)
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
            "MediaQueueItem Cast créé url=$contentUrl contentType=$contentType fallback=${preparedPath != null} " +
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
            "avi" -> "video/x-msvideo"
            "ts", "m2ts", "mts" -> "video/mp2t"
            else -> MimeTypes.VIDEO_MP4
        }
    }
}
