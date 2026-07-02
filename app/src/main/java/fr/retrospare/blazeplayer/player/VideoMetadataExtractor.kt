package fr.retrospare.blazeplayer.player

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.TrackGroupArray

data class VideoTechnicalInfo(
    val duration: Long = 0L,        // secondes
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val container: String = "",
    val frameRate: Float? = null,
    val hdr: Boolean = false,
    val audioTracks: Int = 0,
    val sizeBytes: Long = 0L
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
    private const val DISK_CACHE_PREFS = "blaze_video_metadata_cache"

    suspend fun extract(context: Context, path: String): VideoTechnicalInfo {
        cache[path]?.let { return it }
        return withContext(Dispatchers.IO) {
            loadFromDisk(context, path)?.let {
                cache[path] = it
                return@withContext it
            }
            val info = extractInternal(context, path)
            cache[path] = info
            saveToDisk(context, path, info)
            info
        }
    }

    fun getCached(path: String): VideoTechnicalInfo? = cache[path]

    fun clearCache() = cache.clear()

    /** Clé de cache disque : hash du chemin (évite les soucis de longueur/caractères spéciaux
     *  des chemins réseau smb://, tout en restant stable pour un même fichier). */
    private fun diskKey(path: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(path.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun loadFromDisk(context: Context, path: String): VideoTechnicalInfo? {
        val prefs = context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(diskKey(path), null) ?: return null
        return try {
            val parts = raw.split("|")
            VideoTechnicalInfo(
                duration = parts[0].toLong(),
                width = parts[1].toInt(),
                height = parts[2].toInt(),
                videoCodec = parts[3],
                audioCodec = parts[4],
                container = parts[5],
                frameRate = parts[6].toFloatOrNull(),
                hdr = parts[7].toBoolean(),
                audioTracks = parts[8].toInt(),
                sizeBytes = parts.getOrNull(9)?.toLongOrNull() ?: 0L
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToDisk(context: Context, path: String, info: VideoTechnicalInfo) {
        // N'écrit sur disque que si l'extraction a effectivement trouvé quelque chose : évite de
        // mettre en cache un échec temporaire (ex: partage réseau momentanément indisponible).
        if (info.duration <= 0L && info.videoCodec.isEmpty() && info.audioCodec.isEmpty()) return
        val raw = listOf(
            info.duration, info.width, info.height, info.videoCodec, info.audioCodec,
            info.container, info.frameRate?.toString() ?: "", info.hdr, info.audioTracks, info.sizeBytes
        ).joinToString("|")
        context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(diskKey(path), raw).apply()
    }

    private fun mapVideoCodec(mime: String): String = when {
        mime.contains("dolby-vision") || mime.contains("dvhe") || mime.contains("dvav") -> "Dolby Vision"
        mime.contains("hevc") || mime.contains("h265") -> "H.265"
        mime.contains("avc") || mime.contains("h264") -> "H.264"
        mime.contains("vvc") || mime.contains("h266") -> "H.266"
        mime.contains("vp9") -> "VP9"
        mime.contains("vp8") -> "VP8"
        mime.contains("av01") || mime.contains("av1") -> "AV1"
        mime.contains("mpeg4") -> "MPEG-4"
        mime.contains("mpeg2") -> "MPEG-2"
        mime.contains("mpeg") -> "MPEG-1"
        mime.contains("vc1") -> "VC-1"
        mime.contains("mjpeg") || mime.contains("jpeg") -> "MJPEG"
        mime.contains("divx") -> "DivX"
        mime.contains("xvid") -> "Xvid"
        mime.contains("wmv") -> "WMV"
        mime.contains("theora") -> "Theora"
        mime.contains("flv") -> "FLV1"
        mime.contains("rv") -> "RealVideo"
        else -> mime.substringAfter("video/").ifEmpty { "INCONNU" }.uppercase()
    }

    private fun mapAudioCodec(mime: String): String = when {
        mime.contains("truehd") -> "TrueHD"
        mime.contains("eac3-joc") -> "EAC3-JOC"
        mime.contains("eac3") || mime.contains("ec-3") || mime.contains("ec3") -> "EAC3"
        mime.contains("ac4") -> "AC-4"
        mime.contains("ac3") -> "AC3"
        mime.contains("dts-hd") || mime.contains("dtshd") -> "DTS-HD"
        mime.contains("dts-express") -> "DTS Express"
        mime.contains("dts") -> "DTS"
        mime.contains("aac") -> "AAC"
        mime.contains("mp4a") -> "AAC"
        mime.contains("mp3") || mime.contains("mpeg") -> "MP3"
        mime.contains("mp2") -> "MP2"
        mime.contains("flac") -> "FLAC"
        mime.contains("opus") -> "Opus"
        mime.contains("vorbis") -> "Vorbis"
        mime.contains("alac") -> "ALAC"
        mime.contains("wma") -> "WMA"
        mime.contains("pcm") || mime.contains("raw") -> "PCM"
        mime.contains("amr") -> "AMR"
        mime.contains("speex") -> "Speex"
        mime.contains("g711") || mime.contains("alaw") || mime.contains("mlaw") -> "G.711"
        else -> mime.substringAfter("audio/").ifEmpty { "INCONNU" }.uppercase()
    }

    @UnstableApi
    private fun extractCodecsViaMedia3(
        context: Context,
        path: String,
        onTrack: (mime: String, isVideo: Boolean, width: Int, height: Int, frameRate: Float?) -> Unit
    ) {
        try {
            val isSmb = path.startsWith("smb://")
            val dataSourceFactory = if (isSmb) {
                androidx.media3.datasource.DefaultDataSource.Factory(
                    context,
                    fr.retrospare.blazeplayer.player.SmbDataSource.Factory()
                )
            } else {
                androidx.media3.datasource.DefaultDataSource.Factory(context)
            }
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                .setDataSourceFactory(dataSourceFactory)
            val mediaItem = Media3MediaItem.fromUri(android.net.Uri.parse(path))
            val future = androidx.media3.exoplayer.MetadataRetriever.retrieveMetadata(mediaSourceFactory, mediaItem)
            val trackGroups: TrackGroupArray = future.get(15, java.util.concurrent.TimeUnit.SECONDS)
            for (i in 0 until trackGroups.length) {
                val group = trackGroups[i]
                for (j in 0 until group.length) {
                    val format = group.getFormat(j)
                    val mime = format.sampleMimeType ?: continue
                    val isVideo = mime.startsWith("video/")
                    val isAudio = mime.startsWith("audio/")
                    if (isVideo || isAudio) {
                        onTrack(mime, isVideo, format.width, format.height, if (format.frameRate > 0) format.frameRate else null)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("META_TRACK", "Media3 metadata extraction failed for $path", e)
        }
    }

    /** Repli pour la durée quand MediaMetadataRetriever échoue silencieusement (observé sur des
     *  MP4 réseau volumineux avec métadonnées en fin de fichier). Réutilise la même infrastructure
     *  Media3 (SmbDataSource pour le SMB) déjà éprouvée pour la lecture. */
    @UnstableApi
    private fun extractDurationViaMedia3(context: Context, path: String): Long {
        return try {
            val isSmb = path.startsWith("smb://")
            val dataSourceFactory = if (isSmb) {
                androidx.media3.datasource.DefaultDataSource.Factory(
                    context,
                    fr.retrospare.blazeplayer.player.SmbDataSource.Factory()
                )
            } else {
                androidx.media3.datasource.DefaultDataSource.Factory(context)
            }
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                .setDataSourceFactory(dataSourceFactory)
            val mediaItem = Media3MediaItem.fromUri(android.net.Uri.parse(path))
            androidx.media3.exoplayer.MetadataRetriever.Builder(context, mediaItem)
                .setMediaSourceFactory(mediaSourceFactory)
                .build().use { retriever ->
                    val durationUs = retriever.retrieveDurationUs().get(15, java.util.concurrent.TimeUnit.SECONDS)
                    if (durationUs != null && durationUs != androidx.media3.common.C.TIME_UNSET && durationUs > 0) {
                        durationUs / 1_000_000L
                    } else {
                        0L
                    }
                }
        } catch (e: Exception) {
            android.util.Log.w("META_TRACK", "Repli durée Media3 a échoué pour $path", e)
            0L
        }
    }

    private fun extractInternal(context: Context, path: String): VideoTechnicalInfo {
        var duration = 0L
        var width = 0
        var height = 0
        var videoCodec = ""
        var audioCodec = ""
        var frameRate: Float? = null
        var hdr = false
        var audioTracks = 0
        var sizeBytes = 0L
        val container = path.substringAfterLast('.', "").uppercase()

        val isSmb = path.startsWith("smb://")
        var smbDataSourceMeta: SmbMediaDataSource? = null

        // 1. MediaMetadataRetriever pour durée et dimensions de base
        val retriever = MediaMetadataRetriever()
        try {
            if (isSmb) {
                smbDataSourceMeta = SmbMediaDataSource(path)
                retriever.setDataSource(smbDataSourceMeta)
                sizeBytes = smbDataSourceMeta.size
            } else if (path.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(path))
                sizeBytes = try {
                    context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { it.statSize } ?: 0L
                } catch (e: Exception) { 0L }
            } else {
                retriever.setDataSource(path)
                sizeBytes = try { java.io.File(path).length() } catch (e: Exception) { 0L }
            }

            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.div(1000) ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
        } catch (e: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { smbDataSourceMeta?.close() } catch (_: Exception) {}
        }

        // 2. Codecs precis et pistes audio - unifie via Media3 (local et reseau)
        extractCodecsViaMedia3(context, path) { mime, isVideo, fmtWidth, fmtHeight, fmtFrameRate ->
            if (isVideo) {
                videoCodec = mapVideoCodec(mime)
                if (fmtWidth > 0) width = fmtWidth
                if (fmtHeight > 0) height = fmtHeight
                if (fmtFrameRate != null) frameRate = fmtFrameRate
            } else {
                audioTracks++
                if (audioCodec.isEmpty()) audioCodec = mapAudioCodec(mime)
            }
        }

        // 3. Repli durée via Media3 si MediaMetadataRetriever n'a rien trouvé : observé sur des
        // MP4 volumineux non optimisés (métadonnées "moov" en fin de fichier plutôt qu'au début),
        // où l'extraction native échoue silencieusement sur un partage réseau. Media3 passe par
        // la même infrastructure de lecture déjà éprouvée pour la lecture elle-même, donc plus
        // fiable ici que l'extracteur natif Android pour ce cas précis.
        if (duration <= 0L) {
            duration = extractDurationViaMedia3(context, path)
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
            audioTracks = audioTracks,
            sizeBytes = sizeBytes
        )
    }
}
