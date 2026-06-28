package fr.retrospare.blazeplayer.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PlaylistRepository {

    private const val PREFS_NAME = "blaze_playlist"
    private const val KEY_ITEMS = "items"
    private const val KEY_INDEX = "current_index"

    fun save(context: Context, items: List<PlaylistItem>, currentIndex: Int) {
        val json = JSONArray().apply {
            items.forEach { put(JSONObject().put("path", it.path).put("name", it.name)) }
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, json.toString())
            .putInt(KEY_INDEX, currentIndex)
            .commit()
    }

    fun load(context: Context): Pair<List<PlaylistItem>, Int> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ITEMS, null) ?: return Pair(emptyList(), 0)
        val index = prefs.getInt(KEY_INDEX, 0)
        return try {
            val arr = JSONArray(json)
            val items = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                PlaylistItem(obj.getString("path"), obj.getString("name"))
            }
            Pair(items, index)
        } catch (e: Exception) {
            Pair(emptyList(), 0)
        }
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
