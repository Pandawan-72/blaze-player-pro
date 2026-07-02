package fr.retrospare.blazeplayer.favorites

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Un dossier favori : chemin + nom d'affichage. Pour le réseau, on garde aussi l'identifiant
 *  (et le nom) du partage SMB auquel il appartient, pour pouvoir rouvrir directement le bon
 *  partage au bon endroit. */
data class FavoriteFolder(
    val path: String,
    val name: String,
    val shareId: String? = null,
    val shareName: String? = null
)

enum class FavoriteCategory(val prefKey: String) {
    LOCAL("local"),
    NETWORK("network")
}

object FavoritesManager {

    private const val PREFS = "blaze_favorite_folders"

    private fun key(category: FavoriteCategory) = "favorites_${category.prefKey}"

    /** Identifiant unique d'un favori : le chemin seul suffit en local ; en réseau, le même
     *  chemin relatif peut exister sur deux partages différents, d'où le shareId en plus. */
    private fun sameFolder(a: FavoriteFolder, path: String, shareId: String?) =
        a.path == path && a.shareId == shareId

    fun getFavorites(context: Context, category: FavoriteCategory): List<FavoriteFolder> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(key(category), null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                FavoriteFolder(
                    path = o.getString("path"),
                    name = o.getString("name"),
                    shareId = if (o.has("shareId")) o.optString("shareId").ifEmpty { null } else null,
                    shareName = if (o.has("shareName")) o.optString("shareName").ifEmpty { null } else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isFavorite(context: Context, category: FavoriteCategory, path: String, shareId: String? = null): Boolean =
        getFavorites(context, category).any { sameFolder(it, path, shareId) }

    /** Ajoute le dossier s'il n'y est pas déjà. Retourne false si c'était déjà un favori. */
    fun addFavorite(context: Context, category: FavoriteCategory, folder: FavoriteFolder): Boolean {
        val current = getFavorites(context, category)
        if (current.any { sameFolder(it, folder.path, folder.shareId) }) return false
        save(context, category, current + folder)
        return true
    }

    fun removeFavorite(context: Context, category: FavoriteCategory, path: String, shareId: String? = null) {
        val current = getFavorites(context, category)
        save(context, category, current.filterNot { sameFolder(it, path, shareId) })
    }

    private fun save(context: Context, category: FavoriteCategory, folders: List<FavoriteFolder>) {
        val arr = JSONArray()
        folders.forEach { f ->
            val o = JSONObject().put("path", f.path).put("name", f.name)
            f.shareId?.let { o.put("shareId", it) }
            f.shareName?.let { o.put("shareName", it) }
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(category), arr.toString())
            .apply()
    }
}
