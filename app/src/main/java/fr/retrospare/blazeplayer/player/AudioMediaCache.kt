package fr.retrospare.blazeplayer.player

import android.content.Context
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import kotlinx.coroutines.runBlocking

/**
 * Cache audio centralisé utilisé par le player, le mini-player, la file d'attente et la
 * bibliothèque. Toute extraction coûteuse doit passer par ici :
 *
 * 1) métadonnées texte dérivées du chemin (sans ouvrir l’audio),
 * 2) réutilisation éventuelle des données techniques déjà en cache,
 * 3) extraction de la pochette uniquement via ThumbnailUtils.
 *
 * Cela évite que chaque vue relance son propre MediaMetadataRetriever ou son propre décodage de
 * pochette, ce qui est particulièrement coûteux sur NAS/SMB.
 */
object AudioMediaCache {
    data class Entry(
        val metadata: AudioTechnicalInfo = AudioTechnicalInfo(),
        val artworkJpeg: ByteArray? = null
    )

    fun getCachedMetadata(context: Context, path: String): AudioTechnicalInfo? =
        path.takeIf { it.isNotBlank() }?.let {
            folderInfo(context.applicationContext, it, AudioLibraryHeuristics.fileNameFromPath(it))
        }

    fun getMemoryCachedMetadata(path: String): AudioTechnicalInfo? {
        if (path.isBlank()) return null
        val cached = AudioMetadataExtractor.getCached(path)
        val folder = AudioLibraryHeuristics.folderMetadata(path, AudioLibraryHeuristics.fileNameFromPath(path))
        val extension = path.substringBefore('?').substringAfterLast('.', "").uppercase()
        return AudioTechnicalInfo(
            artist = folder.artist,
            duration = cached?.duration ?: 0L,
            bitrate = cached?.bitrate ?: 0L,
            extension = cached?.extension?.ifBlank { extension } ?: extension,
            isLossless = cached?.isLossless ?: (extension in setOf("FLAC", "WAV", "ALAC", "APE", "AIFF", "WV")),
            title = folder.title,
            album = folder.album,
            trackNumber = AudioLibraryHeuristics.inferTrackNo(AudioLibraryHeuristics.fileNameFromPath(path))
        )
    }

    private fun folderInfo(context: Context, path: String, name: String): AudioTechnicalInfo {
        val cached = AudioMetadataExtractor.getCached(context.applicationContext, path)
        val folder = AudioLibraryHeuristics.folderMetadata(path, name)
        val extension = path.substringBefore('?').substringAfterLast('.', "").uppercase()
        return AudioTechnicalInfo(
            artist = folder.artist,
            duration = cached?.duration ?: 0L,
            bitrate = cached?.bitrate ?: 0L,
            extension = cached?.extension?.ifBlank { extension } ?: extension,
            isLossless = cached?.isLossless ?: (extension in setOf("FLAC", "WAV", "ALAC", "APE", "AIFF", "WV")),
            title = folder.title,
            album = folder.album,
            trackNumber = AudioLibraryHeuristics.inferTrackNo(name)
        )
    }

    /** Extrait uniquement la qualité technique, puis conserve les libellés issus des dossiers. */
    suspend fun extractMetadata(context: Context, path: String, name: String): AudioTechnicalInfo {
        val technical = AudioMetadataExtractor.extractQualityOnly(context.applicationContext, path, name)
        val folder = AudioLibraryHeuristics.folderMetadata(path, name)
        return technical.copy(
            title = folder.title,
            artist = folder.artist,
            album = folder.album,
            trackNumber = AudioLibraryHeuristics.inferTrackNo(name)
        )
    }

    suspend fun extractMetadataPriority(context: Context, path: String, name: String): AudioTechnicalInfo =
        extractMetadata(context, path, name)

    fun extractMetadataBlocking(context: Context, path: String, name: String): AudioTechnicalInfo =
        runBlocking { extractMetadata(context.applicationContext, path, name) }

    fun putMetadata(context: Context, path: String, info: AudioTechnicalInfo) {
        AudioMetadataExtractor.putCached(context.applicationContext, path, info)
    }

    fun putKnownMetadata(
        context: Context,
        path: String,
        title: String = "",
        artist: String = "",
        album: String = "",
        durationSeconds: Long = 0L,
        bitrate: Long = 0L,
        extension: String = "",
        isLossless: Boolean = false,
        trackNumber: Int = 0
    ) {
        val folder = AudioLibraryHeuristics.folderMetadata(path, AudioLibraryHeuristics.fileNameFromPath(path))
        putMetadata(
            context,
            path,
            AudioTechnicalInfo(
                artist = folder.artist,
                duration = durationSeconds,
                bitrate = bitrate,
                extension = extension,
                isLossless = isLossless,
                title = folder.title,
                album = folder.album,
                trackNumber = AudioLibraryHeuristics.inferTrackNo(AudioLibraryHeuristics.fileNameFromPath(path))
            )
        )
    }


    fun invalidatePath(context: Context, path: String) {
        if (path.isBlank()) return
        AudioMetadataExtractor.removeCached(context.applicationContext, path)
        ThumbnailUtils.invalidateAudioArtwork(context.applicationContext, path)
    }

    fun getCachedArtworkJpegBytes(
        context: Context,
        path: String,
        preferredArtworkPath: String? = null
    ): ByteArray? = AudioArtworkResolver.cachedJpegBytes(
        context.applicationContext,
        path,
        preferredArtworkPath
    )

    fun cacheArtworkData(context: Context, path: String, artworkData: ByteArray?) {
        ThumbnailUtils.cacheAudioArtworkData(context.applicationContext, path, artworkData)
    }

    suspend fun extractArtworkJpegBytes(
        context: Context,
        path: String,
        preferredArtworkPath: String? = null
    ): ByteArray? = AudioArtworkResolver.resolveJpegBytes(
        context.applicationContext,
        path,
        preferredArtworkPath
    )

    fun extractArtworkJpegBytesBlocking(
        context: Context,
        path: String,
        preferredArtworkPath: String? = null
    ): ByteArray? = AudioArtworkResolver.resolveJpegBytesBlocking(
        context.applicationContext,
        path,
        preferredArtworkPath
    )

    suspend fun extractEntry(context: Context, path: String, name: String, withArtwork: Boolean): Entry {
        val metadata = extractMetadata(context, path, name)
        val artwork = if (withArtwork) extractArtworkJpegBytes(context, path) else getCachedArtworkJpegBytes(context, path)
        return Entry(metadata, artwork)
    }

    fun extractEntryBlocking(context: Context, path: String, name: String, withArtwork: Boolean): Entry {
        val metadata = extractMetadataBlocking(context, path, name)
        val artwork = if (withArtwork) extractArtworkJpegBytesBlocking(context, path) else getCachedArtworkJpegBytes(context, path)
        return Entry(metadata, artwork)
    }

    fun clearAll(context: Context) {
        AudioMetadataExtractor.clearCache()
        AudioMetadataExtractor.clearDiskCache(context.applicationContext)
        ThumbnailUtils.clearCache()
        ThumbnailUtils.clearDiskCache(context.applicationContext)
    }
}

