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
            .commit()
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
     * Construit un MediaItem minimal sans ouvrir de connexion (pas de metadata, pas de cover).
     * Utilise pour un ajout immediat et rapide a la playlist (notamment reseau SMB),
     * les metadonnees etant ensuite chargees en arriere-plan.
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

    private fun localExtras(path: String): Bundle = Bundle().apply {
        putString(EXTRA_ORIGINAL_PATH, path)
        putString(EXTRA_MEDIA_KIND, "audio")
    }


    private fun mimeTypeForPath(path: String): String = when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "mp3" -> androidx.media3.common.MimeTypes.AUDIO_MPEG
        "flac" -> androidx.media3.common.MimeTypes.AUDIO_FLAC
        "m4a" -> "audio/mp4"
        "aac" -> androidx.media3.common.MimeTypes.AUDIO_AAC
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        else -> "audio/*"
    }



    fun isSupportedAudioPath(path: String): Boolean {
        val clean = path.substringBefore('?').lowercase()
        return clean.startsWith("smb://") || clean.startsWith("content://") || clean.startsWith("file://") ||
            clean.startsWith("http://") || clean.startsWith("https://") || clean.startsWith("/")
    }

    fun isAudioExtension(path: String): Boolean = when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "opus", "wma", "ape", "dts", "ac3", "mka" -> true
        else -> false
    }

    fun buildSimpleMediaItem(context: Context, path: String, fileName: String): MediaItem {
        val cached = AudioMetadataExtractor.getCached(context, path)
        val artwork = fr.retrospare.blazeplayer.ui.ThumbnailUtils.getCachedAudioArtworkJpegBytes(context, path)
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(cached?.title?.ifEmpty { null } ?: fileName.substringBeforeLast("."))
            .setArtist(cached?.artist?.ifEmpty { null })
            .setAlbumTitle(cached?.album?.ifEmpty { null } ?: "")
            .setExtras(localExtras(path))
        if (artwork != null) metaBuilder.setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        return MediaItem.Builder()
            .setMediaId(path)
            .setUri(localPlaybackUri(path))
            .setMimeType(mimeTypeForPath(path))
            .setMediaMetadata(metaBuilder.build())
            .build()
    }

    fun buildMediaItemWithMetadata(context: Context, path: String, fileName: String): MediaItem {
        val retriever = MediaMetadataRetriever()
        var smbDataSource: SmbMediaDataSource? = null
        return try {
            when {
                path.startsWith("smb://") -> {
                    smbDataSource = SmbMediaDataSource(path)
                    retriever.setDataSource(smbDataSource)
                }
                path.startsWith("content://") -> retriever.setDataSource(context, Uri.parse(path))
                else -> retriever.setDataSource(path)
            }
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.ifEmpty { null }
                ?: fileName.substringBeforeLast(".")
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.ifEmpty { null } ?: ""
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.ifEmpty { null } ?: ""
            val artworkData = retriever.embeddedPicture
                ?: fr.retrospare.blazeplayer.ui.ThumbnailUtils.getCachedAudioArtworkJpegBytes(context, path)
            fr.retrospare.blazeplayer.ui.ThumbnailUtils.cacheAudioArtworkData(context, path, artworkData)
            AudioMetadataExtractor.putCached(
                context,
                path,
                AudioTechnicalInfo(
                    artist = artist,
                    title = title,
                    album = album,
                    extension = fileName.substringAfterLast(".", "").uppercase()
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
                        .setExtras(localExtras(path))
                        .build()
                )
                .build()
        } catch (e: Exception) {
            buildSimpleMediaItem(context, path, fileName)
        } finally {
            retriever.release()
            try { smbDataSource?.close() } catch (_: Exception) {}
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
