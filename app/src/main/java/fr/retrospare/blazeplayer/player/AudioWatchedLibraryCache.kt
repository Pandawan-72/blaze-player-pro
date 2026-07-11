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
    private const val VERSION = 4
    private const val FAST_INDEX_KEY = "__fast_index_v1"
    private const val FAST_INDEX_VERSION = 2
    private const val MAX_FAST_INDEX_ITEMS = 2400

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


    private fun entryFromJson(obj: JSONObject, folder: AudioProSettings.WatchedFolder? = null): Entry? {
        val path = obj.optString("path").trim()
        if (path.isBlank()) return null
        return Entry(
            path = path,
            name = obj.optString("name"),
            title = obj.optString("title"),
            artist = obj.optString("artist"),
            album = obj.optString("album"),
            durationMs = obj.optLong("durationMs", 0L),
            trackNumber = obj.optInt("trackNumber", 0),
            addedAt = obj.optLong("addedAt", 0L),
            extension = obj.optString("extension"),
            isNetwork = obj.optBoolean("network", folder?.isNetwork ?: false),
            shareId = obj.optString("shareId", folder?.shareId.orEmpty()),
            sourceLabel = obj.optString("sourceLabel", folder?.let { it.shareName.ifBlank { it.name } }.orEmpty()),
            artworkPath = obj.optString("artworkPath")
        )
    }

    private fun JSONObject.putEntry(entry: Entry) {
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
    }

    /**
     * Petit index global destiné à l'ouverture de l'écran bibliothèque.
     * Contrairement aux caches par dossier, il est toujours borné : on évite donc le cas NAS où
     * Android doit parser un énorme JSON SharedPreferences avant d'afficher la moindre ligne.
     */
    fun loadFastIndex(context: Context, maxItems: Int = MAX_FAST_INDEX_ITEMS): List<Entry> {
        if (maxItems <= 0) return emptyList()
        val raw = prefs(context).getString(FAST_INDEX_KEY, null) ?: return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("version") != FAST_INDEX_VERSION) return@runCatching emptyList<Entry>()
            val arr = root.optJSONArray("items") ?: JSONArray()
            buildList {
                val limit = minOf(arr.length(), maxItems.coerceAtMost(MAX_FAST_INDEX_ITEMS))
                for (i in 0 until limit) {
                    val obj = arr.optJSONObject(i) ?: continue
                    entryFromJson(obj)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveFastIndex(context: Context, entries: List<Entry>, maxItems: Int = MAX_FAST_INDEX_ITEMS) {
        val limit = maxItems.coerceIn(0, MAX_FAST_INDEX_ITEMS)
        if (limit <= 0) return
        val compact = entries
            .asSequence()
            .filter { it.path.isNotBlank() }
            .distinctBy { it.path }
            .take(limit)
            .toList()
        val arr = JSONArray()
        compact.forEach { entry -> arr.put(JSONObject().apply { putEntry(entry) }) }
        val root = JSONObject().apply {
            put("version", FAST_INDEX_VERSION)
            put("updatedAt", System.currentTimeMillis())
            put("items", arr)
        }
        prefs(context).edit().putString(FAST_INDEX_KEY, root.toString()).commit()
    }

    fun mergeFastIndex(context: Context, entries: List<Entry>, maxItems: Int = MAX_FAST_INDEX_ITEMS) {
        if (entries.isEmpty()) return
        val current = loadFastIndex(context, maxItems)
        // Les nouvelles entrées passent devant : après un scan NAS, les albums fraîchement indexés
        // apparaissent dès la prochaine ouverture, sans reparser les caches complets par dossier.
        saveFastIndex(context, (entries + current).distinctBy { it.path }, maxItems)
    }

    /**
     * Reconstruit le petit index d'ouverture depuis les caches par dossier.
     * Important : quand un fichier/dossier disparaît du serveur, un scan confirmé remplace le
     * cache du dossier par la nouvelle liste. Le fast index ne doit donc pas conserver les anciens
     * chemins, sinon la bibliothèque les réaffichera au prochain démarrage.
     */
    private fun rebuildFastIndexFromFolderCaches(context: Context, maxItems: Int = MAX_FAST_INDEX_ITEMS) {
        val allEntries = ArrayList<Entry>()
        prefs(context).all.forEach { (key, value) ->
            if (key == FAST_INDEX_KEY || value !is String) return@forEach
            runCatching {
                val root = JSONObject(value)
                if (root.optInt("version") != VERSION) return@runCatching
                val arr = root.optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    entryFromJson(obj)?.let { allEntries += it }
                }
            }
        }
        saveFastIndex(context, allEntries.sortedByDescending { it.addedAt }, maxItems)
    }

    fun load(context: Context, folder: AudioProSettings.WatchedFolder, maxItems: Int = Int.MAX_VALUE): List<Entry> {
        val raw = prefs(context).getString(key(folder), null) ?: return emptyList()
        val safeLimit = maxItems.coerceAtLeast(0)
        if (safeLimit == 0) return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("version") != VERSION) return@runCatching emptyList<Entry>()
            val arr = root.optJSONArray("items") ?: JSONArray()
            buildList {
                val limit = minOf(arr.length(), safeLimit)
                for (i in 0 until limit) {
                    val obj = arr.optJSONObject(i) ?: continue
                    entryFromJson(obj, folder)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun loadAll(context: Context, folders: List<AudioProSettings.WatchedFolder>, maxItems: Int = Int.MAX_VALUE): List<Entry> {
        if (folders.isEmpty() || maxItems == 0) return emptyList()
        val out = ArrayList<Entry>()
        for (folder in folders) {
            val remaining = if (maxItems == Int.MAX_VALUE) Int.MAX_VALUE else maxItems - out.size
            if (remaining <= 0) break
            out += load(context, folder, remaining)
        }
        return out
    }

    fun save(context: Context, folder: AudioProSettings.WatchedFolder, entries: List<Entry>) {
        val arr = JSONArray()
        entries.distinctBy { it.path }.forEach { entry ->
            arr.put(JSONObject().apply { putEntry(entry) })
        }
        val root = JSONObject().apply {
            put("version", VERSION)
            put("updatedAt", System.currentTimeMillis())
            put("items", arr)
        }
        prefs(context).edit().putString(key(folder), root.toString()).commit()
        // Remplacement strict du dossier : une liste vide est valide et signifie que le dossier
        // ne contient plus de titres. On reconstruit le fast index pour purger les chemins morts.
        rebuildFastIndexFromFolderCaches(context)
    }

    fun remove(context: Context, folder: AudioProSettings.WatchedFolder) {
        prefs(context).edit().remove(key(folder)).commit()
        rebuildFastIndexFromFolderCaches(context)
    }

    fun clearFastIndex(context: Context) {
        prefs(context).edit().remove(FAST_INDEX_KEY).commit()
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().commit()
    }

    fun replaceAllFromFolders(
        context: Context,
        folderEntries: Map<AudioProSettings.WatchedFolder, List<Entry>>
    ) {
        val editor = prefs(context).edit().clear()
        folderEntries.forEach { (folder, entries) ->
            val arr = JSONArray()
            entries.distinctBy { it.path }.forEach { entry ->
                arr.put(JSONObject().apply { putEntry(entry) })
            }
            val root = JSONObject().apply {
                put("version", VERSION)
                put("updatedAt", System.currentTimeMillis())
                put("items", arr)
            }
            editor.putString(key(folder), root.toString())
        }
        editor.commit()
        rebuildFastIndexFromFolderCaches(context)
    }
}

