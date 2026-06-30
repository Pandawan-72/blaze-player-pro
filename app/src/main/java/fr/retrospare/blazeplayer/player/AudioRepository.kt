package fr.retrospare.blazeplayer.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject

object AudioRepository {

    private const val PREFS = "blaze_playlist_v3"
    private const val KEY_ITEMS = "items"
    private const val KEY_INDEX = "index"

    fun save(context: Context, items: List<PlaylistItem>, index: Int) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("path", it.path).put("name", it.name)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .putInt(KEY_INDEX, index)
            .apply()
    }

    fun load(context: Context): Pair<List<PlaylistItem>, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ITEMS, null) ?: return Pair(emptyList(), 0)
        val index = prefs.getInt(KEY_INDEX, 0)
        return try {
            val arr = JSONArray(json)
            val items = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                PlaylistItem(o.getString("path"), o.getString("name"))
            }
            Pair(items, index)
        } catch (e: Exception) { Pair(emptyList(), 0) }
    }

    /**
     * Construit un MediaItem minimal sans ouvrir de connexion (pas de metadata, pas de cover).
     * Utilise pour un ajout immediat et rapide a la playlist (notamment reseau SMB),
     * les metadonnees etant ensuite chargees en arriere-plan.
     */
    fun buildSimpleMediaItem(path: String, fileName: String): MediaItem {
        return MediaItem.Builder()
            .setUri(Uri.parse(path))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(fileName.substringBeforeLast("."))
                    .setArtist("Artiste inconnu")
                    .build()
            )
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
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.ifEmpty { null }
                ?: "Artiste inconnu"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.ifEmpty { null } ?: ""
            val artworkData = retriever.embeddedPicture
            MediaItem.Builder()
                .setUri(Uri.parse(path))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build()
                )
                .build()
        } catch (e: Exception) {
            MediaItem.Builder()
                .setUri(Uri.parse(path))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(fileName.substringBeforeLast("."))
                        .setArtist("Artiste inconnu")
                        .build()
                )
                .build()
        } finally {
            retriever.release()
            try { smbDataSource?.close() } catch (_: Exception) {}
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
