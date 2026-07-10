package fr.retrospare.blazeplayer.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Cache léger de la bibliothèque issue des dossiers surveillés.
 *
 * Objectif UX : ouvrir la bibliothèque instantanément avec le dernier index connu, puis rafraîchir
 * les dossiers locaux/réseau en arrière-plan. Les métadonnées lourdes restent dans
 * AudioMetadataExtractor ; ce cache garde surtout la liste des fichiers et les champs nécessaires
 * pour afficher Albums / Artistes / Titres sans attendre un scan NAS complet.
 */
object AudioWatchedLibraryCache {
    private const val PREFS = "blaze_audio_watched_library_cache"
    private const val VERSION = 3

    data class Entry(
        val path: String,
        val name: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val trackNumber: Int,
        val addedAt: Long,
        val extension: String,
        val isNetwork: Boolean,
        val shareId: String,
        val sourceLabel: String,
        val artworkPath: String = ""
    )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(folder: AudioProSettings.WatchedFolder): String {
        val raw = "${folder.isNetwork}|${folder.shareId}|${folder.path}"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun load(context: Context, folder: AudioProSettings.WatchedFolder): List<Entry> {
        val raw = prefs(context).getString(key(folder), null) ?: return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("version") != VERSION) return@runCatching emptyList<Entry>()
            val arr = root.optJSONArray("items") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val path = obj.optString("path").trim()
                    if (path.isBlank()) continue
                    add(
                        Entry(
                            path = path,
                            name = obj.optString("name"),
                            title = obj.optString("title"),
                            artist = obj.optString("artist"),
                            album = obj.optString("album"),
                            durationMs = obj.optLong("durationMs", 0L),
                            trackNumber = obj.optInt("trackNumber", 0),
                            addedAt = obj.optLong("addedAt", 0L),
                            extension = obj.optString("extension"),
                            isNetwork = obj.optBoolean("network", folder.isNetwork),
                            shareId = obj.optString("shareId", folder.shareId),
                            sourceLabel = obj.optString("sourceLabel", folder.shareName.ifBlank { folder.name }),
                            artworkPath = obj.optString("artworkPath")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun loadAll(context: Context, folders: List<AudioProSettings.WatchedFolder>): List<Entry> =
        folders.flatMap { load(context, it) }

    fun save(context: Context, folder: AudioProSettings.WatchedFolder, entries: List<Entry>) {
        val arr = JSONArray()
        entries.distinctBy { it.path }.forEach { entry ->
            arr.put(JSONObject().apply {
                put("path", entry.path)
                put("name", entry.name)
                put("title", entry.title)
                put("artist", entry.artist)
                put("album", entry.album)
                put("durationMs", entry.durationMs)
                put("trackNumber", entry.trackNumber)
                put("addedAt", entry.addedAt)
                put("extension", entry.extension)
                put("network", entry.isNetwork)
                put("shareId", entry.shareId)
                put("sourceLabel", entry.sourceLabel)
                put("artworkPath", entry.artworkPath)
            })
        }
        val root = JSONObject().apply {
            put("version", VERSION)
            put("updatedAt", System.currentTimeMillis())
            put("items", arr)
        }
        prefs(context).edit().putString(key(folder), root.toString()).apply()
    }

    fun remove(context: Context, folder: AudioProSettings.WatchedFolder) {
        prefs(context).edit().remove(key(folder)).apply()
    }
}
