package fr.retrospare.blazeplayer.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Historique léger dédié à la bibliothèque audio.
 *
 * Il est volontairement séparé des gros caches de scan : chaque lecture écrit une petite entrée
 * JSON, puis la bibliothèque peut afficher instantanément les derniers titres/albums joués sans
 * rescanner les dossiers surveillés ni extraire des métadonnées lourdes.
 */
object AudioPlaybackHistory {
    data class Entry(
        val path: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val trackNumber: Int,
        val extension: String,
        val playedAtMs: Long
    )

    private const val PREFS = "blaze_audio_playback_history"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 80

    fun markPlayed(
        context: Context,
        path: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long = 0L,
        trackNumber: Int = 0,
        extension: String = ""
    ) {
        val safePath = path.trim()
        if (safePath.isBlank()) return
        val now = System.currentTimeMillis()
        val current = load(context).filterNot { it.path == safePath }.toMutableList()
        current.add(
            0,
            Entry(
                path = safePath,
                title = title.ifBlank { safePath.substringBefore('?').substringAfterLast('/').substringBeforeLast('.') },
                artist = artist,
                album = album,
                durationMs = durationMs.coerceAtLeast(0L),
                trackNumber = trackNumber.coerceAtLeast(0),
                extension = extension,
                playedAtMs = now
            )
        )
        save(context, current.take(MAX_ITEMS))
    }

    fun load(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until minOf(arr.length(), MAX_ITEMS)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val path = o.optString("path")
                    if (path.isBlank()) continue
                    add(
                        Entry(
                            path = path,
                            title = o.optString("title"),
                            artist = o.optString("artist"),
                            album = o.optString("album"),
                            durationMs = o.optLong("durationMs", 0L),
                            trackNumber = o.optInt("trackNumber", 0),
                            extension = o.optString("extension"),
                            playedAtMs = o.optLong("playedAtMs", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, entries: List<Entry>) {
        runCatching {
            val arr = JSONArray()
            entries.take(MAX_ITEMS).forEach { entry ->
                arr.put(JSONObject().apply {
                    put("path", entry.path)
                    put("title", entry.title)
                    put("artist", entry.artist)
                    put("album", entry.album)
                    put("durationMs", entry.durationMs)
                    put("trackNumber", entry.trackNumber)
                    put("extension", entry.extension)
                    put("playedAtMs", entry.playedAtMs)
                })
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS, arr.toString())
                .apply()
        }
    }
}
