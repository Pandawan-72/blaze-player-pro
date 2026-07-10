package fr.retrospare.blazeplayer.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject

object AudioRepository {

    private const val PREFS = "blaze_playlist_v3"
    private const val KEY_ITEMS = "items"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION_MS = "positionMs"
    private const val KEY_REPEAT_MODE = "repeatMode"
    private const val KEY_SHUFFLE = "shuffle"

    data class AudioQueueState(
        val items: List<PlaylistItem>,
        val index: Int,
        val positionMs: Long,
        val repeatMode: Int,
        val shuffle: Boolean
    )

    private fun sanitizeAudioItems(items: List<PlaylistItem>): List<PlaylistItem> =
        items.filter { item ->
            val path = item.path.trim()
            path.isNotEmpty() && !path.contains(":8928/video/") && isAudioExtension(path)
        }

    /**
     * Persistance critique : commit() volontairement synchrone.
     * apply() peut perdre la derniere file/position si l'app est swipée juste apres une action.
     */
    fun save(
        context: Context,
        items: List<PlaylistItem>,
        index: Int,
        positionMs: Long = 0L,
        repeatMode: Int = androidx.media3.common.Player.REPEAT_MODE_OFF,
        shuffle: Boolean = false
    ) {
        val audioOnlyItems = sanitizeAudioItems(items)
        if (audioOnlyItems.isEmpty()) return
        val arr = JSONArray()
        audioOnlyItems.forEach { arr.put(JSONObject().put("path", it.path).put("name", it.name)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .putInt(KEY_INDEX, index.coerceIn(0, (audioOnlyItems.size - 1).coerceAtLeast(0)))
            .putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0L))
            .putInt(KEY_REPEAT_MODE, repeatMode)
            .putBoolean(KEY_SHUFFLE, shuffle)
            .apply()
    }

    fun loadState(context: Context): AudioQueueState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ITEMS, null) ?: return AudioQueueState(emptyList(), 0, 0L, androidx.media3.common.Player.REPEAT_MODE_OFF, false)
        return try {
            val arr = JSONArray(json)
            val items = sanitizeAudioItems((0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                PlaylistItem(o.getString("path"), o.getString("name"))
            })
            if (items.isEmpty()) return AudioQueueState(emptyList(), 0, 0L, androidx.media3.common.Player.REPEAT_MODE_OFF, false)
            AudioQueueState(
                items = items,
                index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                positionMs = prefs.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L),
                repeatMode = prefs.getInt(KEY_REPEAT_MODE, androidx.media3.common.Player.REPEAT_MODE_OFF),
                shuffle = prefs.getBoolean(KEY_SHUFFLE, false)
            )
        } catch (e: Exception) {
            AudioQueueState(emptyList(), 0, 0L, androidx.media3.common.Player.REPEAT_MODE_OFF, false)
        }
    }

    fun load(context: Context): Pair<List<PlaylistItem>, Int> {
        val state = loadState(context)
        return Pair(state.items, state.index)
    }

    /**
     * Construit un MediaItem minimal sans I/O. Important pour les intents Android externes
     * (Ouvrir avec depuis un gestionnaire de fichiers) : cette methode peut etre appelee
     * depuis le thread UI pendant le demarrage de l'app. Elle ne doit donc ni lire le disque
     * (cache artwork/metadonnees), ni ouvrir de ContentResolver/MediaMetadataRetriever.
     *
     * Les metadonnees et pochettes sont enrichies ensuite depuis AudioPlayerFragment sur
     * Dispatchers.IO via buildMediaItemWithMetadata().
     */
    /**
     * URI utilisee par le player LOCAL. Important : ne pas router la lecture locale via le
     * relais HTTP Chromecast. En V22, les MediaItem audio utilisaient toujours l'URL HTTP locale
     * du relais Cast ; sur certains appareils, le bouton Lecture ne demarrait plus car ExoPlayer
     * tentait de lire http://IP:8928 au lieu du fichier smb:// / content:// / file local.
     */
    private fun localPlaybackUri(path: String): Uri = when {
        path.startsWith("content://") || path.startsWith("smb://") || path.startsWith("http://") || path.startsWith("https://") -> Uri.parse(path)
        else -> Uri.fromFile(java.io.File(path))
    }

    /** Extras purement locaux pour restaurer un chemin audio fiable. */
    private const val EXTRA_ORIGINAL_PATH = "blaze_original_path"
    private const val EXTRA_MEDIA_KIND = "blaze_media_kind"
    const val EXTRA_CONTAINER_EXTENSION = "blaze_container_extension"

    private fun localExtras(path: String, containerExtension: String = ""): Bundle = Bundle().apply {
        putString(EXTRA_ORIGINAL_PATH, path)
        putString(EXTRA_MEDIA_KIND, "audio")
        if (containerExtension.isNotBlank()) putString(EXTRA_CONTAINER_EXTENSION, containerExtension.uppercase())
    }


    private fun extensionForAudio(path: String, fileName: String = ""): String {
        fun cleanExt(value: String): String {
            val ext = value.substringBefore('?').substringAfterLast('.', "").trim().uppercase()
            return ext.takeIf { it.length in 2..5 && it.all { ch -> ch.isLetterOrDigit() } }.orEmpty()
        }
        cleanExt(fileName).takeIf { it.isNotBlank() }?.let { return it }
        // Les URI SAF contiennent souvent des segments encodés avec des points dans
        // l'adresse mail ou le provider. Ne jamais les transformer en badge conteneur.
        if (path.startsWith("content://", ignoreCase = true)) return ""
        return cleanExt(path)
    }

    private fun mimeTypeForExtension(ext: String): String = when (ext.lowercase()) {
        "mp3" -> androidx.media3.common.MimeTypes.AUDIO_MPEG
        "flac" -> androidx.media3.common.MimeTypes.AUDIO_FLAC
        "m4a" -> "audio/mp4"
        "aac" -> androidx.media3.common.MimeTypes.AUDIO_AAC
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wma" -> "audio/x-ms-wma"
        "ac3" -> "audio/ac3"
        "eac3" -> "audio/eac3"
        "dts" -> "audio/vnd.dts"
        "mka" -> "audio/x-matroska"
        else -> "audio/*"
    }

    private fun mimeTypeForPath(path: String): String = mimeTypeForExtension(extensionForAudio(path))



    fun isSupportedAudioPath(path: String): Boolean {
        val clean = path.substringBefore('?').lowercase()
        return clean.startsWith("smb://") || clean.startsWith("content://") || clean.startsWith("file://") ||
            clean.startsWith("http://") || clean.startsWith("https://") || clean.startsWith("/")
    }

    fun isAudioExtension(path: String): Boolean {
        val clean = path.substringBefore('?')
        val lower = clean.lowercase()

        // Les fichiers ouverts depuis Android arrivent souvent en content:// sans extension
        // visible dans l'URI (Galerie, DocumentsUI, Google Photos, etc.). À ce stade cette
        // fonction est appelée uniquement par le pipeline audio : il ne faut donc pas rejeter
        // ces URI, sinon le morceau est ajouté à l'écran mais ignoré par la playlist ExoPlayer
        // et par la sauvegarde.
        if (lower.startsWith("content://")) return true

        val ext = clean.substringAfterLast('.', "").lowercase()
        if (ext in setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "opus", "wma", "ape", "dts", "ac3", "mka", "wv", "aiff", "alac")) return true
        // Les morceaux ajoutés depuis un chemin réseau doivent persister exactement comme
        // les fichiers locaux, même quand le serveur ne fournit pas d'extension dans l'URL/URI.
        // Le navigateur réseau a déjà filtré les médias audio en amont ; ici on évite surtout
        // de les supprimer de la file sauvegardée et de la file Blaze Party.
        return (path.startsWith("smb://", true) ||
            path.startsWith("http://", true) ||
            path.startsWith("https://", true)) && ext.isBlank()
    }

    fun buildSimpleMediaItem(context: Context, path: String, fileName: String): MediaItem {
        val containerExtension = extensionForAudio(path, fileName)
        val safeTitle = fileName.substringBeforeLast(".").ifBlank {
            Uri.parse(path).lastPathSegment?.substringAfterLast('/')?.substringBeforeLast(".")
        }.orEmpty().ifBlank { "Audio" }
        return MediaItem.Builder()
            .setMediaId(path)
            .setUri(localPlaybackUri(path))
            .setMimeType(mimeTypeForExtension(containerExtension))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(safeTitle)
                    .setAlbumTitle("")
                    .setExtras(localExtras(path, containerExtension))
                    .build()
            )
            .build()
    }

    private fun buildMediaItemFromCachedInfo(context: Context, path: String, fileName: String, info: AudioTechnicalInfo): MediaItem {
        val rawTitle = info.title.ifBlank { fileName.substringBeforeLast(".").ifBlank { "Audio" } }
        val rawArtist = info.artist
        val rawAlbum = info.album
        val override = AudioLocalEnhancements.applyOverride(context, path, rawTitle, rawArtist, rawAlbum)
        val artworkData = fr.retrospare.blazeplayer.ui.ThumbnailUtils.getCachedAudioArtworkJpegBytes(context, path)
        return MediaItem.Builder()
            .setMediaId(path)
            .setUri(localPlaybackUri(path))
            .setMimeType(mimeTypeForPath(path))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(override.title.ifBlank { rawTitle })
                    .setArtist(override.artist.ifBlank { rawArtist })
                    .setAlbumTitle(override.album.ifBlank { rawAlbum })
                    .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .setExtras(localExtras(path, extensionForAudio(path, fileName).ifBlank { info.extension }))
                    .build()
            )
            .build()
    }

    fun buildMediaItemWithMetadata(context: Context, path: String, fileName: String): MediaItem {
        val cachedInfo = AudioMetadataExtractor.getCached(context, path)
        val retriever = MediaMetadataRetriever()
        var smbDataSource: SmbMediaDataSource? = null
        var closeable: AutoCloseable? = null
        return try {
            when {
                path.startsWith("smb://", true) -> {
                    smbDataSource = SmbMediaDataSource(path)
                    retriever.setDataSource(smbDataSource)
                }
                path.startsWith("content://", true) -> {
                    val uri = Uri.parse(path)
                    try {
                        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        if (pfd != null) {
                            retriever.setDataSource(pfd.fileDescriptor)
                            closeable = pfd
                        } else {
                            retriever.setDataSource(context, uri)
                        }
                    } catch (_: Exception) {
                        retriever.setDataSource(context, uri)
                    }
                }
                path.startsWith("file://", true) -> retriever.setDataSource(context, Uri.parse(path))
                path.startsWith("http://", true) || path.startsWith("https://", true) -> retriever.setDataSource(path, emptyMap())
                else -> retriever.setDataSource(path)
            }
            val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.ifEmpty { null }
                ?: cachedInfo?.title?.ifBlank { null }
                ?: fileName.substringBeforeLast(".")
            val rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.ifEmpty { null }
                ?: cachedInfo?.artist?.ifBlank { null }
                ?: ""
            val rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.ifEmpty { null }
                ?: cachedInfo?.album?.ifBlank { null }
                ?: ""
            val override = AudioLocalEnhancements.applyOverride(context, path, rawTitle, rawArtist, rawAlbum)
            val title = override.title
            val artist = override.artist
            val album = override.album
            val trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore("/")
                ?.filter { it.isDigit() }
                ?.toIntOrNull()
                ?: cachedInfo?.trackNumber
                ?: 0
            // Priorité réelle aux covers dossier : on force l'extraction depuis le thread de fond du service.
            // Si aucune image jpg/jpeg/png n'est trouvée près du morceau, ThumbnailUtils retombe sur
            // l'artwork embarqué MediaMetadataRetriever puis ID3/APIC pour les MP3 récalcitrants.
            val artworkData = fr.retrospare.blazeplayer.ui.ThumbnailUtils.getAudioArtworkJpegBytesBlocking(context, path)
                ?: retriever.embeddedPicture
            fr.retrospare.blazeplayer.ui.ThumbnailUtils.cacheAudioArtworkData(context, path, artworkData)
            AudioMetadataExtractor.putCached(
                context,
                path,
                AudioTechnicalInfo(
                    artist = artist,
                    title = title,
                    album = album,
                    trackNumber = trackNumber,
                    extension = fileName.substringAfterLast(".", "").uppercase(),
                    duration = cachedInfo?.duration ?: 0L,
                    bitrate = cachedInfo?.bitrate ?: 0L,
                    isLossless = cachedInfo?.isLossless ?: false
                )
            )
            MediaItem.Builder()
                .setMediaId(path)
                .setUri(localPlaybackUri(path))
                .setMimeType(mimeTypeForPath(path))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .setExtras(localExtras(path, fileName.substringAfterLast(".", "").uppercase()))
                        .build()
                )
                .build()
        } catch (e: Exception) {
            if (cachedInfo != null && (cachedInfo.title.isNotBlank() || cachedInfo.artist.isNotBlank() || cachedInfo.album.isNotBlank())) {
                buildMediaItemFromCachedInfo(context, path, fileName, cachedInfo)
            } else {
                buildSimpleMediaItem(context, path, fileName)
            }
        } finally {
            retriever.release()
            try { closeable?.close() } catch (_: Exception) {}
            try { smbDataSource?.close() } catch (_: Exception) {}
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
