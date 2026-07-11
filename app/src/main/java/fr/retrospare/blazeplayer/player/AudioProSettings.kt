package fr.retrospare.blazeplayer.player

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.pow
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Source unique pour les réglages audio Pro+.
 * Les clés restent identiques à celles générées par AudioProSettingsActivity v9 afin de préserver
 * les préférences déjà écrites chez les utilisateurs.
 */
object AudioProSettings {
    const val PREFS = "blaze_audio_pro_settings"

    const val KEY_GAPLESS = "gapless"
    const val KEY_CROSSFADE = "crossfade"
    const val KEY_CROSSFADE_DURATION = "crossfade_duration"
    const val KEY_NORMALIZE = "normalize"
    const val KEY_HI_RES = "hi_res"
    const val KEY_REPLAYGAIN = "replaygain"
    const val KEY_PREAMP = "preamp"
    const val KEY_BIT_DEPTH = "bit_depth"
    const val KEY_AUTO_SCAN = "auto_scan"
    const val KEY_TRACK_ORDER = "track_order"
    const val KEY_IGNORE_SHORT = "ignore_short"
    const val KEY_DYNAMIC_THEME = "dynamic_theme"
    const val KEY_COVER_BORDER = "cover_border"
    const val KEY_LYRICS_PLAYER = "lyrics_player"
    const val KEY_ARTWORK_SIZE = "artwork_size"
    const val KEY_SYNCED_LYRICS = "synced_lyrics"
    const val KEY_DOWNLOAD_COVERS = "download_covers"
    const val KEY_WATCHED_FOLDERS = "watched_folders"
    private const val KEY_LIBRARY_REFRESH_PENDING = "library_refresh_pending"

    const val REPLAYGAIN_OFF = 0
    const val REPLAYGAIN_TRACK = 1
    const val REPLAYGAIN_ALBUM = 2

    const val ARTWORK_SMALL = 0
    const val ARTWORK_MEDIUM = 1
    const val ARTWORK_LARGE = 2

    data class Values(
        val gapless: Boolean = true,
        val crossfade: Boolean = true,
        val crossfadeDurationSec: Int = 3,
        val normalize: Boolean = true,
        val hiRes: Boolean = true,
        val replayGain: Int = REPLAYGAIN_TRACK,
        val preampDb: Int = 0,
        val bitDepth: String = "24",
        val autoScan: Boolean = true,
        val trackOrder: Boolean = true,
        val ignoreShort: Boolean = false,
        val dynamicTheme: Boolean = true,
        val coverBorder: Boolean = true,
        val lyricsPlayer: Boolean = true,
        val artworkSize: Int = ARTWORK_MEDIUM,
        val syncedLyrics: Boolean = true,
        val downloadCovers: Boolean = true
    )

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): Values {
        val p = prefs(context)
        return Values(
            gapless = p.getBoolean(KEY_GAPLESS, true),
            crossfade = p.getBoolean(KEY_CROSSFADE, true),
            crossfadeDurationSec = p.getInt(KEY_CROSSFADE_DURATION, 3).coerceIn(0, 12),
            normalize = p.getBoolean(KEY_NORMALIZE, true),
            hiRes = p.getBoolean(KEY_HI_RES, true),
            replayGain = p.getInt(KEY_REPLAYGAIN, REPLAYGAIN_TRACK).coerceIn(REPLAYGAIN_OFF, REPLAYGAIN_ALBUM),
            preampDb = p.getInt(KEY_PREAMP, 0).coerceIn(-12, 12),
            bitDepth = p.getString(KEY_BIT_DEPTH, "24") ?: "24",
            autoScan = p.getBoolean(KEY_AUTO_SCAN, true),
            trackOrder = p.getBoolean(KEY_TRACK_ORDER, true),
            ignoreShort = p.getBoolean(KEY_IGNORE_SHORT, false),
            dynamicTheme = p.getBoolean(KEY_DYNAMIC_THEME, true),
            coverBorder = p.getBoolean(KEY_COVER_BORDER, true),
            lyricsPlayer = p.getBoolean(KEY_LYRICS_PLAYER, true),
            artworkSize = p.getInt(KEY_ARTWORK_SIZE, ARTWORK_MEDIUM).coerceIn(ARTWORK_SMALL, ARTWORK_LARGE),
            syncedLyrics = p.getBoolean(KEY_SYNCED_LYRICS, true),
            downloadCovers = p.getBoolean(KEY_DOWNLOAD_COVERS, true)
        )
    }



    data class WatchedFolder(
        val name: String,
        val path: String,
        val isNetwork: Boolean = false,
        val shareId: String = "",
        val shareName: String = ""
    )

    fun watchedFolders(context: Context): List<WatchedFolder> {
        val raw = prefs(context).getString(KEY_WATCHED_FOLDERS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val isNetwork = obj.optBoolean("network", false)
                    val path = obj.optString("path").trim()
                    if (path.isBlank()) continue
                    val normalizedPath = normalizePath(path, isNetwork)
                    add(
                        WatchedFolder(
                            name = obj.optString("name").takeIf { it.isNotBlank() } ?: normalizedPath.substringAfterLast('/'),
                            path = normalizedPath,
                            isNetwork = isNetwork,
                            shareId = obj.optString("shareId").trim(),
                            shareName = obj.optString("shareName").trim()
                        )
                    )
                }
            }.distinctBy { folderKey(it) }
        }.getOrDefault(emptyList())
    }

    fun watchedFolderCount(context: Context): Int = watchedFolders(context).size

    fun addWatchedFolder(context: Context, folder: WatchedFolder): Boolean {
        val clean = normalizeFolder(folder)
        if (clean.path.isBlank()) return false
        val current = watchedFolders(context).toMutableList()
        if (current.any { sameFolder(it, clean) }) return false
        current += clean
        saveWatchedFolders(context, current)
        markLibraryRefreshPending(context)
        return true
    }


    /** Un ajout de dossier peut être réalisé depuis les réglages alors que la bibliothèque n'est
     *  pas ouverte. Ce drapeau persistant garantit que le premier retour/ouverture déclenche le
     *  scan complet une seule fois. */
    fun markLibraryRefreshPending(context: Context) {
        prefs(context).edit().putBoolean(KEY_LIBRARY_REFRESH_PENDING, true).apply()
    }

    fun consumeLibraryRefreshPending(context: Context): Boolean {
        val preferences = prefs(context)
        if (!preferences.getBoolean(KEY_LIBRARY_REFRESH_PENDING, false)) return false
        // commit() est volontaire : l'opération est minuscule et évite deux consommations si deux
        // écrans se réveillent presque simultanément.
        return preferences.edit().remove(KEY_LIBRARY_REFRESH_PENDING).commit()
    }

    fun removeWatchedFolder(context: Context, folder: WatchedFolder) {
        val clean = normalizeFolder(folder)
        saveWatchedFolders(context, watchedFolders(context).filterNot { sameFolder(it, clean) })
        runCatching { AudioWatchedLibraryCache.remove(context, clean) }
    }

    fun isWatchedFolder(context: Context, folder: WatchedFolder): Boolean {
        val clean = normalizeFolder(folder)
        return watchedFolders(context).any { sameFolder(it, clean) }
    }


    fun normalizeFolder(folder: WatchedFolder): WatchedFolder = folder.copy(
        name = folder.name.trim(),
        path = normalizePath(folder.path, folder.isNetwork),
        shareId = folder.shareId.trim(),
        shareName = folder.shareName.trim()
    )

    private fun normalizePath(path: String, isNetwork: Boolean): String {
        val clean = path.trim()
        if (clean.isBlank() || isNetwork) return clean.trimEnd('/')
        return runCatching { File(clean).canonicalFile.absolutePath }
            .getOrElse { File(clean).absolutePath }
            .trimEnd('/')
    }

    private fun folderKey(folder: WatchedFolder): String =
        "${folder.isNetwork}:${folder.shareId}:${normalizePath(folder.path, folder.isNetwork).lowercase()}"

    private fun sameFolder(a: WatchedFolder, b: WatchedFolder): Boolean = folderKey(a) == folderKey(b)

    private fun saveWatchedFolders(context: Context, folders: List<WatchedFolder>) {
        val array = JSONArray()
        folders.map { normalizeFolder(it) }.distinctBy { folderKey(it) }.forEach { folder ->
            array.put(JSONObject().apply {
                put("name", folder.name)
                put("path", folder.path)
                put("network", folder.isNetwork)
                put("shareId", folder.shareId)
                put("shareName", folder.shareName)
            })
        }
        prefs(context).edit().putString(KEY_WATCHED_FOLDERS, array.toString()).apply()
    }

    /** Volume logiciel anti-clipping. Le préampli reste appliqué via EqualizerManager quand
     *  possible ; ici on applique les corrections qui doivent rester sûres au niveau Player. */
    fun playerVolume(values: Values): Float = playerVolume(values, 0f)

    fun playerVolume(values: Values, replayGainDb: Float): Float {
        var db = replayGainDb.toDouble()
        // Petit headroom quand la normalisation est active : cela laisse de la marge au limiteur
        // Android/LoudnessEnhancer et évite que les corrections ReplayGain positives saturent.
        if (values.normalize) db -= 1.0
        // Si l'utilisateur pousse fortement le préampli, on protège un peu le volume player pour
        // éviter la saturation quand l'égaliseur système est disponible.
        if (values.preampDb > 6) db -= (values.preampDb - 6) * 0.45
        return (10.0.pow(db / 20.0)).toFloat().coerceIn(0.18f, 1.0f)
    }

    fun loudnessTargetMillibels(values: Values, replayGainDb: Float): Int {
        var gain = 0
        if (values.normalize) gain += 180
        if (replayGainDb > 0f) gain += (replayGainDb * 100f).toInt()
        if (values.preampDb > 0) gain += (values.preampDb * 55)
        // En mode 16-bit, on garde plus de marge ; en 24/32, on peut permettre un rendu un peu
        // plus ouvert. Cela reste un réglage logiciel prudent, pas une promesse DAC universelle.
        if (values.bitDepth == "16") gain = (gain * 0.75f).toInt()
        return gain.coerceIn(0, 1200)
    }

    fun eqPreampMillibels(values: Values): Int = (values.preampDb * 100).coerceIn(-1000, 1000)
}
