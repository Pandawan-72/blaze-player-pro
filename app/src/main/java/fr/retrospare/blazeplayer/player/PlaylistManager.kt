package fr.retrospare.blazeplayer.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedPlaylist(val id: String, val name: String, val items: List<PlaylistItem>)

object PlaylistManager {

    private const val PREFS = "saved_playlists"
    private const val KEY_LIST = "playlists"

    fun getAll(context: Context): List<SavedPlaylist> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val itemsArr = obj.getJSONArray("items")
                val items = (0 until itemsArr.length()).map { j ->
                    val item = itemsArr.getJSONObject(j)
                    PlaylistItem(item.getString("path"), item.getString("name"))
                }.filter { !it.path.startsWith("smb://") && !it.path.startsWith("ftp://") }
                SavedPlaylist(obj.getString("id"), obj.getString("name"), items)
            }
        } catch (e: Exception) { emptyList() }
    }

    fun save(context: Context, playlist: SavedPlaylist) {
        val all = getAll(context).toMutableList()
        val idx = all.indexOfFirst { it.id == playlist.id }
        if (idx >= 0) all[idx] = playlist else all.add(playlist)
        persist(context, all)
    }

    fun delete(context: Context, id: String) {
        val all = getAll(context).filter { it.id != id }
        persist(context, all)
    }

    fun rename(context: Context, id: String, newName: String) {
        val all = getAll(context).map { if (it.id == id) it.copy(name = newName) else it }
        persist(context, all)
    }

    fun createNew(context: Context, name: String, items: List<PlaylistItem>): SavedPlaylist {
        val localOnly = items.filter { !it.path.startsWith("smb://") && !it.path.startsWith("ftp://") }
        val playlist = SavedPlaylist(System.currentTimeMillis().toString(), name, localOnly)
        save(context, playlist)
        return playlist
    }

    private fun persist(context: Context, playlists: List<SavedPlaylist>) {
        val arr = JSONArray()
        playlists.forEach { pl ->
            val obj = JSONObject()
            obj.put("id", pl.id)
            obj.put("name", pl.name)
            val itemsArr = JSONArray()
            pl.items.forEach { item ->
                itemsArr.put(JSONObject().put("path", item.path).put("name", item.name))
            }
            obj.put("items", itemsArr)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
