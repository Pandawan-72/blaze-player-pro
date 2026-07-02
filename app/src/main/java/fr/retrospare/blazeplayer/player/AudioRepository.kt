package fr.retrospare.blazeplayer.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import fr.retrospare.blazeplayer.cast.AudioStreamServerManager
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
    private const val EXTRA_CAST_URL = "blaze_cast_url"

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

    /**
     * URL uniquement destinee au Chromecast. Elle est stockee dans les extras metadata et lue par
     * BlazeCastMediaItemConverter. Le player local garde lui l'URI originale.
     */
    private const val EXTRA_CAST_ARTWORK_URL = "blaze_cast_artwork_url"

    private fun castAudioUrl(context: Context, path: String): String =
        AudioStreamServerManager.registerAndGetUrl(context, path)

    private fun castArtworkUrl(context: Context, path: String): Uri =
        Uri.parse(AudioStreamServerManager.registerCoverAndGetUrl(context, path))

    private fun castExtras(context: Context, path: String): Bundle = Bundle().apply {
        putString(EXTRA_CAST_URL, castAudioUrl(context, path))
        putString(EXTRA_CAST_ARTWORK_URL, castArtworkUrl(context, path).toString())
    }

    fun buildSimpleMediaItem(context: Context, path: String, fileName: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(path)
            .setUri(localPlaybackUri(path))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(fileName.substringBeforeLast("."))
                    .setArtist(context.getString(fr.retrospare.blazeplayer.R.string.unknown_artist))
                    .setArtworkUri(castArtworkUrl(context, path))
                    .setExtras(castExtras(context, path))
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
                ?: context.getString(fr.retrospare.blazeplayer.R.string.unknown_artist)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.ifEmpty { null } ?: ""
            val artworkData = retriever.embeddedPicture
            MediaItem.Builder()
                .setMediaId(path)
                .setUri(localPlaybackUri(path))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .setArtworkUri(castArtworkUrl(context, path))
                        .setExtras(castExtras(context, path))
                        .build()
                )
                .build()
        } catch (e: Exception) {
            MediaItem.Builder()
                .setMediaId(path)
                .setUri(localPlaybackUri(path))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(fileName.substringBeforeLast("."))
                        .setArtist(context.getString(fr.retrospare.blazeplayer.R.string.unknown_artist))
                        .setArtworkUri(castArtworkUrl(context, path))
                        .setExtras(castExtras(context, path))
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
