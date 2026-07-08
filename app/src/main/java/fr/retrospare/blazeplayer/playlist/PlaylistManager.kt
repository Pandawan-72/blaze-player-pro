package fr.retrospare.blazeplayer.playlist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Référence légère vers un fichier (chemin + nom), enrichie si besoin par les métadonnées
 *  audio déjà connues. Les champs optionnels servent surtout à Blaze Party côté invité : le
 *  téléphone client ne peut pas relire le fichier local/SMB de l’hôte, donc l’hôte doit lui
 *  transmettre artiste, conteneur, débit, lossless et durée avec la file partagée. */
data class PlaylistTrackRef(
    val path: String,
    val name: String,
    val artist: String = "",
    val title: String = "",
    val album: String = "",
    val trackNumber: Int = 0,
    val extension: String = "",
    val bitrate: Long = 0L,
    val isLossless: Boolean = false,
    val durationMs: Long = 0L,
    val playedOrder: Int = 0,
    val videoQuality: String = "",
    val videoCodec: String = "",
    val audioCodec: String = "",
    val sizeBytes: Long = 0L
)

/** Les contextes qui ont chacun leurs 5 playlists indépendantes (1/2/3/4/5). */
enum class PlaylistCategory(val prefKey: String, val label: String) {
    LOCAL_VIDEO("local_video", "Local"),
    NETWORK_VIDEO("network_video", "Réseau"),
    AUDIO("audio", "Audio")
}

/** Nom traduit à afficher pour une catégorie de playlist. */
fun PlaylistCategory.displayLabel(context: Context): String = when (this) {
    PlaylistCategory.LOCAL_VIDEO -> context.getString(fr.retrospare.blazeplayer.R.string.category_local)
    PlaylistCategory.NETWORK_VIDEO -> context.getString(fr.retrospare.blazeplayer.R.string.category_network)
    PlaylistCategory.AUDIO -> context.getString(fr.retrospare.blazeplayer.R.string.category_audio)
}

/** Gère les playlists sauvegardées (5 emplacements par catégorie), en local via
 *  SharedPreferences (même approche que AudioRepository/SharedAudioViewModel dans ce projet). */
object PlaylistManager {

    private const val PREFS = "blaze_saved_playlists"
    const val SLOT_COUNT = 5

    private fun key(category: PlaylistCategory, slot: Int) = "${category.prefKey}_$slot"

    private const val BLAZE_PARTY_PREFS = "blaze_party_dedicated_playlist"
    private const val BLAZE_PARTY_KEY = "queue"

    fun getBlazePartyPlaylist(context: Context): List<PlaylistTrackRef> {
        val prefs = context.getSharedPreferences(BLAZE_PARTY_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(BLAZE_PARTY_KEY, null) ?: return emptyList()
        return parsePlaylist(json)
    }

    fun addToBlazePartyPlaylist(context: Context, tracks: List<PlaylistTrackRef>): Int {
        val current = getBlazePartyPlaylist(context).toMutableList()
        val existingPaths = current.map { it.path }.toHashSet()
        var added = 0
        tracks.forEach { track ->
            if (existingPaths.add(track.path)) {
                current.add(track)
                added++
            }
        }
        if (added > 0) saveBlazePartyPlaylist(context, current)
        return added
    }

    fun removeFromBlazePartyPlaylist(context: Context, path: String) {
        saveBlazePartyPlaylist(context, getBlazePartyPlaylist(context).filter { it.path != path })
    }

    fun clearBlazePartyPlaylist(context: Context) {
        context.getSharedPreferences(BLAZE_PARTY_PREFS, Context.MODE_PRIVATE).edit().remove(BLAZE_PARTY_KEY).commit()
    }

    private fun saveBlazePartyPlaylist(context: Context, tracks: List<PlaylistTrackRef>) {
        val arr = JSONArray()
        tracks.forEach { ref ->
            arr.put(JSONObject().apply {
                put("path", ref.path)
                put("name", ref.name)
                if (ref.artist.isNotBlank()) put("artist", ref.artist)
                if (ref.title.isNotBlank()) put("title", ref.title)
                if (ref.album.isNotBlank()) put("album", ref.album)
                if (ref.trackNumber > 0) put("trackNumber", ref.trackNumber)
                if (ref.extension.isNotBlank()) put("extension", ref.extension)
                if (ref.bitrate > 0L) put("bitrate", ref.bitrate)
                if (ref.isLossless) put("isLossless", true)
                if (ref.durationMs > 0L) put("durationMs", ref.durationMs)
                if (ref.playedOrder > 0) put("playedOrder", ref.playedOrder)
                if (ref.videoQuality.isNotBlank()) put("videoQuality", ref.videoQuality)
                if (ref.videoCodec.isNotBlank()) put("videoCodec", ref.videoCodec)
                if (ref.audioCodec.isNotBlank()) put("audioCodec", ref.audioCodec)
                if (ref.sizeBytes > 0L) put("sizeBytes", ref.sizeBytes)
            })
        }
        context.getSharedPreferences(BLAZE_PARTY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(BLAZE_PARTY_KEY, arr.toString())
            .commit()
    }

    private fun parsePlaylist(json: String): List<PlaylistTrackRef> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PlaylistTrackRef(
                path = o.getString("path"),
                name = o.getString("name"),
                artist = o.optString("artist"),
                title = o.optString("title"),
                album = o.optString("album"),
                trackNumber = o.optInt("trackNumber", 0),
                extension = o.optString("extension"),
                bitrate = o.optLong("bitrate", 0L),
                isLossless = o.optBoolean("isLossless", false),
                durationMs = o.optLong("durationMs", 0L),
                playedOrder = o.optInt("playedOrder", 0),
                videoQuality = o.optString("videoQuality"),
                videoCodec = o.optString("videoCodec"),
                audioCodec = o.optString("audioCodec"),
                sizeBytes = o.optLong("sizeBytes", 0L)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun getPlaylist(context: Context, category: PlaylistCategory, slot: Int): List<PlaylistTrackRef> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(key(category, slot), null) ?: return emptyList()
        return parsePlaylist(json)
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
        tracks.forEach { ref ->
            arr.put(JSONObject().apply {
                put("path", ref.path)
                put("name", ref.name)
                if (ref.artist.isNotBlank()) put("artist", ref.artist)
                if (ref.title.isNotBlank()) put("title", ref.title)
                if (ref.album.isNotBlank()) put("album", ref.album)
                if (ref.trackNumber > 0) put("trackNumber", ref.trackNumber)
                if (ref.extension.isNotBlank()) put("extension", ref.extension)
                if (ref.bitrate > 0L) put("bitrate", ref.bitrate)
                if (ref.isLossless) put("isLossless", true)
                if (ref.durationMs > 0L) put("durationMs", ref.durationMs)
                if (ref.playedOrder > 0) put("playedOrder", ref.playedOrder)
                if (ref.videoQuality.isNotBlank()) put("videoQuality", ref.videoQuality)
                if (ref.videoCodec.isNotBlank()) put("videoCodec", ref.videoCodec)
                if (ref.audioCodec.isNotBlank()) put("audioCodec", ref.audioCodec)
                if (ref.sizeBytes > 0L) put("sizeBytes", ref.sizeBytes)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(category, slot), arr.toString())
            .commit()
    }

    fun clearPlaylist(context: Context, category: PlaylistCategory, slot: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(category, slot))
            .commit()
    }

    /** Mémorise la dernière playlist (par catégorie) mise en lecture via "Jouer la playlist",
     *  pour que l'UI puisse la mettre en surbrillance — indépendamment du simple fait qu'une
     *  playlist contienne des éléments ou non. */
    fun setLastPlayed(context: Context, category: PlaylistCategory, slot: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("last_played_${category.prefKey}", slot)
            .commit()
    }

    /** Emplacement (1..5) de la dernière playlist jouée pour cette catégorie, ou 0 si aucune. */
    fun getLastPlayed(context: Context, category: PlaylistCategory): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("last_played_${category.prefKey}", 0)
}
