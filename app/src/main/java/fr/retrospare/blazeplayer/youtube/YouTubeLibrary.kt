package fr.retrospare.blazeplayer.youtube

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Favoris et historique des vidéos YouTube, persistés localement (même approche que
 *  PlaylistManager dans ce projet — SharedPreferences + JSON, pas de base de données). */
object YouTubeLibrary {

    private const val PREFS = "blaze_youtube_library"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_HISTORY = "history"
    private const val KEY_METADATA_PREFIX = "meta_"
    private const val HISTORY_MAX = 50

    /** Cache titre/chaîne/miniature par videoId : les playlists (PlaylistManager, système
     *  générique partagé avec Local/Réseau/Audio) ne stockent que chemin+nom, donc juste
     *  videoId+titre pour YouTube. Ce cache permet de retrouver le reste au moment de la lecture
     *  ou de l'affichage, sans avoir à étendre le système générique. Alimenté automatiquement dès
     *  qu'un item complet est vu (recherche, favoris, historique). */
    fun cacheMetadata(context: Context, item: YouTubeVideoItem) {
        if (item.videoId.isBlank() || (item.channelTitle.isBlank() && item.thumbnailUrl.isBlank())) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                KEY_METADATA_PREFIX + item.videoId,
                JSONObject().apply {
                    put("title", item.title)
                    put("channelTitle", item.channelTitle)
                    put("thumbnailUrl", item.thumbnailUrl)
                }.toString()
            ).apply()
    }

    /** Complète un item partiel (ex: issu d'une playlist, seulement videoId+titre) avec les
     *  métadonnées mises en cache si disponibles — sinon retourne l'item tel quel. */
    fun enrichFromCache(context: Context, item: YouTubeVideoItem): YouTubeVideoItem {
        if (item.channelTitle.isNotBlank() && item.thumbnailUrl.isNotBlank()) return item
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_METADATA_PREFIX + item.videoId, null) ?: return item
        return try {
            val o = JSONObject(raw)
            item.copy(
                title = item.title.ifBlank { o.optString("title") },
                channelTitle = item.channelTitle.ifBlank { o.optString("channelTitle") },
                thumbnailUrl = item.thumbnailUrl.ifBlank { o.optString("thumbnailUrl") }
            )
        } catch (e: Exception) {
            item
        }
    }

    private fun serialize(items: List<YouTubeVideoItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("videoId", item.videoId)
                put("title", item.title)
                put("channelTitle", item.channelTitle)
                put("thumbnailUrl", item.thumbnailUrl)
                put("timestamp", item.timestamp)
            })
        }
        return arr.toString()
    }

    private fun deserialize(json: String?): List<YouTubeVideoItem> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                YouTubeVideoItem(
                    videoId = o.getString("videoId"),
                    title = o.optString("title"),
                    channelTitle = o.optString("channelTitle"),
                    thumbnailUrl = o.optString("thumbnailUrl"),
                    timestamp = o.optLong("timestamp", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- Favoris ---

    fun getFavorites(context: Context): List<YouTubeVideoItem> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return deserialize(prefs.getString(KEY_FAVORITES, null)).sortedByDescending { it.timestamp }
    }

    fun isFavorite(context: Context, videoId: String): Boolean =
        getFavorites(context).any { it.videoId == videoId }

    fun toggleFavorite(context: Context, item: YouTubeVideoItem): Boolean {
        cacheMetadata(context, item)
        val enriched = enrichFromCache(context, item)
        val current = getFavorites(context).toMutableList()
        val existingIndex = current.indexOfFirst { it.videoId == enriched.videoId }
        val nowFavorite: Boolean
        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
            nowFavorite = false
        } else {
            current.add(0, enriched.copy(timestamp = System.currentTimeMillis()))
            nowFavorite = true
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_FAVORITES, serialize(current)).apply()
        return nowFavorite
    }

    // --- Historique ---

    fun getHistory(context: Context): List<YouTubeVideoItem> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return deserialize(prefs.getString(KEY_HISTORY, null)).sortedByDescending { it.timestamp }
    }

    fun addToHistory(context: Context, item: YouTubeVideoItem) {
        cacheMetadata(context, item)
        val enriched = enrichFromCache(context, item)
        val current = getHistory(context).toMutableList()
        current.removeAll { it.videoId == enriched.videoId }
        current.add(0, enriched.copy(timestamp = System.currentTimeMillis()))
        val trimmed = current.take(HISTORY_MAX)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, serialize(trimmed)).apply()
    }

    fun removeFromHistory(context: Context, videoId: String) {
        val current = getHistory(context).toMutableList()
        current.removeAll { it.videoId == videoId }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, serialize(current)).apply()
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply()
    }
}
