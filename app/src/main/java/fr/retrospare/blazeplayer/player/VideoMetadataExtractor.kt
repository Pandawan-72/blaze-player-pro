package fr.retrospare.blazeplayer.player

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class VideoTechnicalInfo(
    val duration: Long = 0L,        // secondes
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val container: String = "",
    val frameRate: Float? = null,
    val hdr: Boolean = false,
    val audioTracks: Int = 0
) {
    val resolutionLabel: String get() = if (width > 0 && height > 0) "${width}×${height}" else ""

    val qualityBadge: String get() = when {
        height <= 0  -> ""
        height <= 480  -> "SD"
        height <= 720  -> "HD"
        height <= 1080 -> "FHD"
        height <= 1440 -> "QHD"
        else           -> "4K"
    }

    val formattedDuration: String get() {
        if (duration <= 0) return ""
        val h = duration / 3600
        val m = (duration % 3600) / 60
        val s = duration % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

object VideoMetadataExtractor {

    private val cache = ConcurrentHashMap<String, VideoTechnicalInfo>()

    suspend fun extract(context: Context, path: String): VideoTechnicalInfo {
        cache[path]?.let { return it }
        return withContext(Dispatchers.IO) {
            val info = extractInternal(context, path)
            cache[path] = info
            info
        }
    }

    fun getCached(path: String): VideoTechnicalInfo? = cache[path]

    fun clearCache() = cache.clear()

    private fun extractInternal(context: Context, path: String): VideoTechnicalInfo {
        var duration = 0L
        var width = 0
        var height = 0
        var videoCodec = ""
        var audioCodec = ""
        var frameRate: Float? = null
        var hdr = false
        var audioTracks = 0
        val container = path.substringAfterLast('.', "").uppercase()

        // 1. MediaMetadataRetriever pour durée et dimensions de base
        val retriever = MediaMetadataRetriever()
        try {
            if (path.startsWith("content://"))
                retriever.setDataSource(context, Uri.parse(path))
            else
                retriever.setDataSource(path)

            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.div(1000) ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
        } catch (e: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        // 2. MediaExtractor pour codecs précis et pistes audio
        val extractor = MediaExtractor()
        try {
            if (path.startsWith("content://"))
                extractor.setDataSource(context, Uri.parse(path), null)
            else
                extractor.setDataSource(path)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                when {
                    mime.startsWith("video/") -> {
                        // Codec vidéo
                        videoCodec = when {
                            mime.contains("hevc") || mime.contains("h265") -> "H.265"
                            mime.contains("avc") || mime.contains("h264") -> "H.264"
                            mime.contains("vp9") -> "VP9"
                            mime.contains("vp8") -> "VP8"
                            mime.contains("av01") || mime.contains("av1") -> "AV1"
                            mime.contains("mpeg4") -> "MPEG-4"
                            mime.contains("mpeg2") -> "MPEG-2"
                            else -> mime.substringAfter("video/").uppercase()
                        }
                        // Dimensions depuis MediaExtractor (plus fiable)
                        if (format.containsKey(MediaFormat.KEY_WIDTH))
                            width = format.getInteger(MediaFormat.KEY_WIDTH)
                        if (format.containsKey(MediaFormat.KEY_HEIGHT))
                            height = format.getInteger(MediaFormat.KEY_HEIGHT)
                        // Frame rate
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                            frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        // HDR
                        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                            val transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                            hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                                  transfer == MediaFormat.COLOR_TRANSFER_HLG
                        }
                    }
                    mime.startsWith("audio/") -> {
                        audioTracks++
                        if (audioCodec.isEmpty()) {
                            audioCodec = when {
                                mime.contains("eac3") || mime.contains("ec-3") -> "EAC3"
                                mime.contains("ac3") -> "AC3"
                                mime.contains("dtshd") -> "DTS-HD"
                                mime.contains("dts") -> "DTS"
                                mime.contains("aac") -> "AAC"
                                mime.contains("mp4a") -> "AAC"
                                mime.contains("mpeg") -> "MP3"
                                mime.contains("flac") -> "FLAC"
                                mime.contains("opus") -> "OPUS"
                                mime.contains("vorbis") -> "VORBIS"
                                else -> mime.substringAfter("audio/").uppercase()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }

        return VideoTechnicalInfo(
            duration = duration,
            width = width,
            height = height,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            container = container,
            frameRate = frameRate,
            hdr = hdr,
            audioTracks = audioTracks
        )
    }
}
