package fr.retrospare.blazeplayer.player

import android.content.Context
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.playlist.PlaylistCategory
import fr.retrospare.blazeplayer.playlist.PlaylistTrackRef
import org.json.JSONArray
import org.json.JSONObject

/**
 * File d'attente vidéo persistante, indépendante par onglet :
 * - LOCAL_VIDEO : vidéos locales
 * - NETWORK_VIDEO : vidéos réseau
 *
 * Contrairement à la file audio, ajouter des vidéos ici ne démarre jamais la lecture. Le lancement
 * se fait seulement quand l'utilisateur clique une ligne de la file, puis PlayerActivity enchaîne
 * automatiquement les éléments suivants grâce aux extras queuePaths/queueNames existants.
 */
object VideoQueueManager {
    private const val PREFS = "blaze_video_queues"

    private fun key(category: PlaylistCategory): String = when (category) {
        PlaylistCategory.NETWORK_VIDEO -> "network_video_queue"
        else -> "local_video_queue"
    }

    fun getQueue(context: Context, category: PlaylistCategory): List<PlaylistTrackRef> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(category), null) ?: return emptyList()
        return parseQueue(json)
    }

    fun addToQueue(context: Context, category: PlaylistCategory, tracks: List<PlaylistTrackRef>): Int {
        if (tracks.isEmpty()) return 0
        val current = getQueue(context, category).toMutableList()
        val existing = current.map { it.path }.toHashSet()
        var added = 0
        tracks.forEach { ref ->
            if (ref.path.isNotBlank() && existing.add(ref.path)) {
                current.add(ref)
                added++
            }
        }
        if (added > 0) saveQueue(context, category, current)
        return added
    }

    fun removeFromQueue(context: Context, category: PlaylistCategory, path: String) {
        saveQueue(context, category, getQueue(context, category).filter { it.path != path })
    }

    fun clearQueue(context: Context, category: PlaylistCategory) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(category)).commit()
    }

    fun saveQueue(context: Context, category: PlaylistCategory, tracks: List<PlaylistTrackRef>) {
        val arr = JSONArray()
        tracks.forEach { ref ->
            arr.put(JSONObject().apply {
                put("path", ref.path)
                put("name", ref.name)
                if (ref.extension.isNotBlank()) put("extension", ref.extension)
                if (ref.durationMs > 0L) put("durationMs", ref.durationMs)
                if (ref.videoQuality.isNotBlank()) put("videoQuality", ref.videoQuality)
                if (ref.videoCodec.isNotBlank()) put("videoCodec", ref.videoCodec)
                if (ref.audioCodec.isNotBlank()) put("audioCodec", ref.audioCodec)
                if (ref.sizeBytes > 0L) put("sizeBytes", ref.sizeBytes)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(category), arr.toString())
            .commit()
    }

    fun fromMediaItem(item: MediaItem): PlaylistTrackRef {
        val ext = item.extension.ifBlank {
            item.name.substringAfterLast('.', "").ifBlank {
                item.path.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
            }
        }
        return PlaylistTrackRef(
            path = item.path,
            name = item.name,
            extension = ext.uppercase(),
            durationMs = item.duration.coerceAtLeast(0L) * 1000L,
            videoQuality = item.resolution.orEmpty(),
            videoCodec = item.videoCodec.orEmpty(),
            audioCodec = item.audioCodec.orEmpty(),
            sizeBytes = item.size.coerceAtLeast(0L)
        )
    }

    private fun parseQueue(json: String): List<PlaylistTrackRef> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { index ->
            val o = arr.optJSONObject(index) ?: return@mapNotNull null
            val path = o.optString("path")
            if (path.isBlank()) return@mapNotNull null
            PlaylistTrackRef(
                path = path,
                name = o.optString("name", path.substringAfterLast('/')),
                extension = o.optString("extension"),
                durationMs = o.optLong("durationMs", 0L),
                videoQuality = o.optString("videoQuality"),
                videoCodec = o.optString("videoCodec"),
                audioCodec = o.optString("audioCodec"),
                sizeBytes = o.optLong("sizeBytes", 0L)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
