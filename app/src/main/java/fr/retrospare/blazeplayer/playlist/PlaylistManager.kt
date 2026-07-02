package fr.retrospare.blazeplayer.playlist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Référence légère vers un fichier (chemin + nom), suffisante pour retrouver/relire le média. */
data class PlaylistTrackRef(val path: String, val name: String)

/** Les 3 contextes qui ont chacun leurs 3 playlists indépendantes (1/2/3). */
enum class PlaylistCategory(val prefKey: String, val label: String) {
    LOCAL_VIDEO("local_video", "Local"),
    NETWORK_VIDEO("network_video", "Réseau"),
    AUDIO("audio", "Audio"),
    YOUTUBE("youtube", "Blaze Tube")
}

/** Gère les 9 playlists sauvegardées (3 catégories x 3 emplacements), en local via
 *  SharedPreferences (même approche que AudioRepository/SharedAudioViewModel dans ce projet). */
object PlaylistManager {

    private const val PREFS = "blaze_saved_playlists"
    const val SLOT_COUNT = 3

    private fun key(category: PlaylistCategory, slot: Int) = "${category.prefKey}_$slot"

    fun getPlaylist(context: Context, category: PlaylistCategory, slot: Int): List<PlaylistTrackRef> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(key(category, slot), null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                PlaylistTrackRef(o.getString("path"), o.getString("name"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getAllSlotCounts(context: Context, category: PlaylistCategory): List<Int> =
        (1..SLOT_COUNT).map { getPlaylist(context, category, it).size }

    /** Ajoute un ou plusieurs éléments à une playlist (ignore les doublons par chemin).
     *  Retourne le nombre d'éléments réellement ajoutés (hors doublons). */
    fun addToPlaylist(context: Context, category: PlaylistCategory, slot: Int, tracks: List<PlaylistTrackRef>): Int {
        val current = getPlaylist(context, category, slot).toMutableList()
        val existingPaths = current.map { it.path }.toHashSet()
        var added = 0
        tracks.forEach { track ->
            if (existingPaths.add(track.path)) {
                current.add(track)
                added++
            }
        }
        if (added > 0) savePlaylist(context, category, slot, current)
        return added
    }

    fun removeFromPlaylist(context: Context, category: PlaylistCategory, slot: Int, path: String) {
        val current = getPlaylist(context, category, slot).filter { it.path != path }
        savePlaylist(context, category, slot, current)
    }

    fun savePlaylist(context: Context, category: PlaylistCategory, slot: Int, tracks: List<PlaylistTrackRef>) {
        val arr = JSONArray()
        tracks.forEach { arr.put(JSONObject().put("path", it.path).put("name", it.name)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(category, slot), arr.toString())
            .apply()
    }

    fun clearPlaylist(context: Context, category: PlaylistCategory, slot: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(category, slot))
            .apply()
    }

    /** Mémorise la dernière playlist (par catégorie) mise en lecture via "Jouer la playlist",
     *  pour que l'UI puisse la mettre en surbrillance — indépendamment du simple fait qu'une
     *  playlist contienne des éléments ou non. */
    fun setLastPlayed(context: Context, category: PlaylistCategory, slot: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("last_played_${category.prefKey}", slot)
            .apply()
    }

    /** Emplacement (1..3) de la dernière playlist jouée pour cette catégorie, ou 0 si aucune. */
    fun getLastPlayed(context: Context, category: PlaylistCategory): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("last_played_${category.prefKey}", 0)
}
