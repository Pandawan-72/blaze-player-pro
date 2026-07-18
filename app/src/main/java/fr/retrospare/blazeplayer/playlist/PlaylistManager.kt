package fr.retrospare.blazeplayer.playlist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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

/** Playlist nommée créée par l’utilisateur. */
data class NamedPlaylist(
    val id: String,
    val name: String
)

/** Catégories historiques. Les playlists nommées utilisent deux espaces indépendants :
 *  AUDIO d’un côté et VIDEO (local + réseau) de l’autre. */
enum class PlaylistCategory(val prefKey: String, val label: String) {
    LOCAL_VIDEO("local_video", "Local"),
    NETWORK_VIDEO("network_video", "Réseau"),
    AUDIO("audio", "Audio")
}

/** Nom traduit à afficher pour une catégorie de playlist. */
fun PlaylistCategory.displayLabel(context: Context): String = when (this) {
    PlaylistCategory.LOCAL_VIDEO -> context.getString(fr.retrospare.blazeplayer.R.string.tab_blaze_video)
    PlaylistCategory.NETWORK_VIDEO -> context.getString(fr.retrospare.blazeplayer.R.string.category_network)
    PlaylistCategory.AUDIO -> context.getString(fr.retrospare.blazeplayer.R.string.category_audio)
}

/** Gère les playlists sauvegardées en local. Les anciennes playlists numérotées sont conservées
 *  uniquement comme source de migration vers les playlists nommées sans limite. */
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

    @Synchronized
    fun addToBlazePartyPlaylist(context: Context, tracks: List<PlaylistTrackRef>): Int {
        val current = getBlazePartyPlaylist(context).toMutableList()
        val existingPaths = current.mapTo(HashSet(current.size + tracks.size)) { it.path }
        val addedTracks = tracks
            .filter { it.path.isNotBlank() }
            .distinctBy { it.path }
            .filter { existingPaths.add(it.path) }
        if (addedTracks.isNotEmpty()) {
            current.addAll(addedTracks)
            saveBlazePartyPlaylist(context, current)
        }
        return addedTracks.size
    }

    fun removeFromBlazePartyPlaylist(context: Context, path: String) {
        saveBlazePartyPlaylist(context, getBlazePartyPlaylist(context).filter { it.path != path })
    }

    fun clearBlazePartyPlaylist(context: Context) {
        context.getSharedPreferences(BLAZE_PARTY_PREFS, Context.MODE_PRIVATE).edit().remove(BLAZE_PARTY_KEY).commit()
    }

    @Synchronized
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
        val base = prefs.getString(key(category, slot), null)?.let { parsePlaylist(it) }.orEmpty()

        // Blaze Video utilise désormais les 5 playlists LOCAL_VIDEO comme playlists unifiées.
        // Les anciens slots réseau sont rapatriés à la première lecture pour que les vidéos NAS
        // déjà sauvegardées restent accessibles depuis l'onglet unique.
        if (category == PlaylistCategory.LOCAL_VIDEO) {
            val legacyNetwork = prefs.getString(key(PlaylistCategory.NETWORK_VIDEO, slot), null)
                ?.let { parsePlaylist(it) }
                .orEmpty()
            if (legacyNetwork.isNotEmpty()) {
                val merged = (base + legacyNetwork).distinctBy { it.path }
                savePlaylist(context, PlaylistCategory.LOCAL_VIDEO, slot, merged)
                prefs.edit().remove(key(PlaylistCategory.NETWORK_VIDEO, slot)).apply()
                return merged
            }
        }
        return base
    }

    fun getAllSlotCounts(context: Context, category: PlaylistCategory): List<Int> =
        (1..SLOT_COUNT).map { getPlaylist(context, category, it).size }

    /** Ajoute un ou plusieurs éléments à une playlist (ignore les doublons par chemin).
     *  Retourne le nombre d'éléments réellement ajoutés (hors doublons). */
    @Synchronized
    fun addToPlaylist(context: Context, category: PlaylistCategory, slot: Int, tracks: List<PlaylistTrackRef>): Int {
        val current = getPlaylist(context, category, slot).toMutableList()
        val existingPaths = current.mapTo(HashSet(current.size + tracks.size)) { it.path }
        val addedTracks = tracks
            .filter { it.path.isNotBlank() }
            .distinctBy { it.path }
            .filter { existingPaths.add(it.path) }
        if (addedTracks.isNotEmpty()) {
            current.addAll(addedTracks)
            savePlaylist(context, category, slot, current)
        }
        return addedTracks.size
    }

    fun removeFromPlaylist(context: Context, category: PlaylistCategory, slot: Int, path: String) {
        val current = getPlaylist(context, category, slot).filter { it.path != path }
        savePlaylist(context, category, slot, current)
    }

    @Synchronized
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

    // ---------------------------------------------------------------------
    // Playlists nommées (nouveau modèle sans limite de 5 emplacements)
    // ---------------------------------------------------------------------

    private fun canonicalNamedCategory(category: PlaylistCategory): PlaylistCategory =
        if (category == PlaylistCategory.AUDIO) PlaylistCategory.AUDIO else PlaylistCategory.LOCAL_VIDEO

    private fun namedSpaceKey(category: PlaylistCategory): String =
        if (canonicalNamedCategory(category) == PlaylistCategory.AUDIO) "audio" else "video"

    private fun namedIndexKey(category: PlaylistCategory) = "named_index_${namedSpaceKey(category)}"
    private fun namedMigrationKey(category: PlaylistCategory) = "named_migrated_${namedSpaceKey(category)}"
    private fun namedTracksKey(category: PlaylistCategory, id: String) =
        "named_tracks_${namedSpaceKey(category)}_$id"
    private fun namedLastPlayedKey(category: PlaylistCategory) = "named_last_played_${namedSpaceKey(category)}"

    private fun serializeTracks(tracks: List<PlaylistTrackRef>): String {
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
        return arr.toString()
    }

    private fun readNamedIndex(context: Context, category: PlaylistCategory): List<NamedPlaylist> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(namedIndexKey(category), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { index ->
                val item = arr.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                if (id.isBlank() || name.isBlank()) null else NamedPlaylist(id, name)
            }.distinctBy { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeNamedIndex(context: Context, category: PlaylistCategory, playlists: List<NamedPlaylist>) {
        val arr = JSONArray()
        playlists.forEach { playlist ->
            arr.put(JSONObject().put("id", playlist.id).put("name", playlist.name))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(namedIndexKey(category), arr.toString())
            .commit()
    }

    @Synchronized
    private fun ensureNamedMigration(context: Context, category: PlaylistCategory) {
        val canonical = canonicalNamedCategory(category)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(namedMigrationKey(canonical), false)) return

        val existing = readNamedIndex(context, canonical).toMutableList()
        (1..SLOT_COUNT).forEach { slot ->
            val tracks = getPlaylist(context, canonical, slot)
            if (tracks.isEmpty()) return@forEach
            val legacyName = context.getString(fr.retrospare.blazeplayer.R.string.playlist_slot_name, slot)
            val uniqueName = generateUniqueName(existing, legacyName)
            val playlist = NamedPlaylist(UUID.randomUUID().toString(), uniqueName)
            existing += playlist
            prefs.edit()
                .putString(namedTracksKey(canonical, playlist.id), serializeTracks(tracks))
                .commit()
        }
        writeNamedIndex(context, canonical, existing)
        prefs.edit().putBoolean(namedMigrationKey(canonical), true).commit()
    }

    private fun generateUniqueName(existing: List<NamedPlaylist>, requested: String): String {
        val base = requested.trim().ifBlank { "Playlist" }
        if (existing.none { it.name.equals(base, ignoreCase = true) }) return base
        var suffix = 2
        while (existing.any { it.name.equals("$base ($suffix)", ignoreCase = true) }) suffix++
        return "$base ($suffix)"
    }

    @Synchronized
    fun getNamedPlaylists(context: Context, category: PlaylistCategory): List<NamedPlaylist> {
        val canonical = canonicalNamedCategory(category)
        ensureNamedMigration(context, canonical)
        return readNamedIndex(context, canonical)
    }

    fun hasNamedPlaylists(context: Context, category: PlaylistCategory): Boolean =
        getNamedPlaylists(context, category).isNotEmpty()

    @Synchronized
    fun createNamedPlaylist(context: Context, category: PlaylistCategory, requestedName: String): NamedPlaylist? {
        val canonical = canonicalNamedCategory(category)
        val name = requestedName.trim()
        if (name.isBlank()) return null
        val current = getNamedPlaylists(context, canonical).toMutableList()
        if (current.any { it.name.equals(name, ignoreCase = true) }) return null
        val playlist = NamedPlaylist(UUID.randomUUID().toString(), name)
        current += playlist
        writeNamedIndex(context, canonical, current)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(namedTracksKey(canonical, playlist.id), "[]")
            .commit()
        return playlist
    }

    fun getNamedPlaylist(context: Context, category: PlaylistCategory, playlistId: String): NamedPlaylist? =
        getNamedPlaylists(context, category).firstOrNull { it.id == playlistId }

    fun getNamedPlaylistTracks(context: Context, category: PlaylistCategory, playlistId: String): List<PlaylistTrackRef> {
        val canonical = canonicalNamedCategory(category)
        ensureNamedMigration(context, canonical)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(namedTracksKey(canonical, playlistId), null) ?: return emptyList()
        return parsePlaylist(raw)
    }

    @Synchronized
    fun addToNamedPlaylist(
        context: Context,
        category: PlaylistCategory,
        playlistId: String,
        tracks: List<PlaylistTrackRef>
    ): Int {
        val canonical = canonicalNamedCategory(category)
        if (getNamedPlaylist(context, canonical, playlistId) == null) return 0
        val current = getNamedPlaylistTracks(context, canonical, playlistId).toMutableList()
        val existingPaths = current.mapTo(HashSet(current.size + tracks.size)) { it.path }
        val additions = tracks
            .filter { it.path.isNotBlank() }
            .distinctBy { it.path }
            .filter { existingPaths.add(it.path) }
        if (additions.isNotEmpty()) {
            current.addAll(additions)
            saveNamedPlaylistTracks(context, canonical, playlistId, current)
        }
        return additions.size
    }

    @Synchronized
    fun saveNamedPlaylistTracks(
        context: Context,
        category: PlaylistCategory,
        playlistId: String,
        tracks: List<PlaylistTrackRef>
    ) {
        val canonical = canonicalNamedCategory(category)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(namedTracksKey(canonical, playlistId), serializeTracks(tracks))
            .commit()
    }

    fun removeFromNamedPlaylist(
        context: Context,
        category: PlaylistCategory,
        playlistId: String,
        path: String
    ) {
        saveNamedPlaylistTracks(
            context,
            category,
            playlistId,
            getNamedPlaylistTracks(context, category, playlistId).filter { it.path != path }
        )
    }

    fun clearNamedPlaylist(context: Context, category: PlaylistCategory, playlistId: String) {
        saveNamedPlaylistTracks(context, category, playlistId, emptyList())
    }

    @Synchronized
    fun deleteNamedPlaylist(context: Context, category: PlaylistCategory, playlistId: String) {
        val canonical = canonicalNamedCategory(category)
        val updated = getNamedPlaylists(context, canonical).filterNot { it.id == playlistId }
        writeNamedIndex(context, canonical, updated)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit().remove(namedTracksKey(canonical, playlistId))
        if (prefs.getString(namedLastPlayedKey(canonical), null) == playlistId) {
            edit.remove(namedLastPlayedKey(canonical))
        }
        edit.commit()
    }

    fun setLastPlayedNamed(context: Context, category: PlaylistCategory, playlistId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(namedLastPlayedKey(category), playlistId)
            .commit()
    }

    fun getLastPlayedNamed(context: Context, category: PlaylistCategory): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(namedLastPlayedKey(category), null)

}
