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
    const val KEY_OUTPUT_MODE = "output_mode"
    private const val LEGACY_KEY_BIT_DEPTH = "bit_depth"
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

    const val OUTPUT_MODE_AUTO = "auto"
    const val OUTPUT_MODE_COMPATIBILITY = "compatibility"
    const val OUTPUT_MODE_HIGH_PRECISION = "high_precision"

    const val ARTWORK_SMALL = 0
    const val ARTWORK_MEDIUM = 1
    const val ARTWORK_LARGE = 2

    data class Values(
        val gapless: Boolean = true,
        val crossfade: Boolean = true,
        val crossfadeDurationSec: Int = 3,
        val normalize: Boolean = true,
        val hiRes: Boolean = false,
        val replayGain: Int = REPLAYGAIN_TRACK,
        val outputMode: String = OUTPUT_MODE_AUTO,
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


    /** Migre l'ancien sélecteur 16/24/32 bits vers des modes qui correspondent réellement à la
     * chaîne Media3, puis retire l'ancienne clé sans perdre le choix de l'utilisateur. */
    fun migrateOutputPreferences(context: Context) {
        val audioPrefs = prefs(context)
        val rawMode = if (audioPrefs.contains(KEY_OUTPUT_MODE)) {
            audioPrefs.getString(KEY_OUTPUT_MODE, null)
        } else {
            audioPrefs.getString(LEGACY_KEY_BIT_DEPTH, null)
        }
        val normalizedMode = normalizeOutputMode(rawMode)
        val edit = audioPrefs.edit()
        var changed = false
        if (!audioPrefs.contains(KEY_HI_RES)) {
            edit.putBoolean(KEY_HI_RES, false)
            changed = true
        }
        if (!audioPrefs.contains(KEY_OUTPUT_MODE) || rawMode != normalizedMode) {
            edit.putString(KEY_OUTPUT_MODE, normalizedMode)
            changed = true
        }
        if (audioPrefs.contains(LEGACY_KEY_BIT_DEPTH)) {
            edit.remove(LEGACY_KEY_BIT_DEPTH)
            changed = true
        }
        if (changed) edit.apply()

        val eqPrefs = context.applicationContext.getSharedPreferences(EqualizerManager.PREFS_NAME, Context.MODE_PRIVATE)
        if (!eqPrefs.contains(EqualizerManager.KEY_EQ_ENABLED)) {
            eqPrefs.edit().putBoolean(EqualizerManager.KEY_EQ_ENABLED, true).apply()
        }
        // Les sorties directes/float contournent les AudioProcessor : un ancien réglage incohérent
        // est donc remis dans un état sûr dès la migration.
        if (audioPrefs.getBoolean(KEY_HI_RES, false) || normalizedMode == OUTPUT_MODE_HIGH_PRECISION) {
            if (eqPrefs.getBoolean(EqualizerManager.KEY_EQ_ENABLED, true)) {
                eqPrefs.edit().putBoolean(EqualizerManager.KEY_EQ_ENABLED, false).apply()
            }
        }
    }

    fun normalizeOutputMode(raw: String?): String = when (raw) {
        OUTPUT_MODE_AUTO, "24" -> OUTPUT_MODE_AUTO
        OUTPUT_MODE_COMPATIBILITY, "16" -> OUTPUT_MODE_COMPATIBILITY
        OUTPUT_MODE_HIGH_PRECISION, "32" -> OUTPUT_MODE_HIGH_PRECISION
        else -> OUTPUT_MODE_AUTO
    }

    /** Active la préférence de sortie directe et coupe immédiatement les effets incompatibles. */
    fun setHighQualityEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HI_RES, enabled).apply()
        if (enabled) {
            context.applicationContext
                .getSharedPreferences(EqualizerManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(EqualizerManager.KEY_EQ_ENABLED, false)
                .apply()
        }
    }

    /** Sélectionne le mode PCM réel. La haute précision requiert une sortie float sans DSP. */
    fun setOutputMode(context: Context, mode: String) {
        val normalized = normalizeOutputMode(mode)
        prefs(context).edit().putString(KEY_OUTPUT_MODE, normalized).apply()
        if (normalized == OUTPUT_MODE_HIGH_PRECISION) {
            context.applicationContext
                .getSharedPreferences(EqualizerManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(EqualizerManager.KEY_EQ_ENABLED, false)
                .apply()
        }
    }

    /** Prépare la chaîne pour les réglages son : pas d'offload imposé ni de sortie float forcée. */
    fun prepareForSoundSettings(context: Context) {
        val audioPrefs = prefs(context)
        val edit = audioPrefs.edit().putBoolean(KEY_HI_RES, false)
        if (normalizeOutputMode(audioPrefs.getString(KEY_OUTPUT_MODE, OUTPUT_MODE_AUTO)) == OUTPUT_MODE_HIGH_PRECISION) {
            edit.putString(KEY_OUTPUT_MODE, OUTPUT_MODE_AUTO)
        }
        edit.apply()
    }

    /** La sortie float est automatique lorsque le DSP est coupé, forcée en haute précision et
     * désactivée en compatibilité. */
    fun shouldUseFloatOutput(values: Values, soundSettingsEnabled: Boolean): Boolean = when (values.outputMode) {
        OUTPUT_MODE_HIGH_PRECISION -> true
        OUTPUT_MODE_COMPATIBILITY -> false
        else -> !soundSettingsEnabled
    }

    fun read(context: Context): Values {
        migrateOutputPreferences(context)
        val p = prefs(context)
        return Values(
            gapless = p.getBoolean(KEY_GAPLESS, true),
            crossfade = p.getBoolean(KEY_CROSSFADE, true),
            crossfadeDurationSec = p.getInt(KEY_CROSSFADE_DURATION, 3).coerceIn(0, 12),
            normalize = p.getBoolean(KEY_NORMALIZE, true),
            hiRes = p.getBoolean(KEY_HI_RES, false),
            replayGain = p.getInt(KEY_REPLAYGAIN, REPLAYGAIN_TRACK).coerceIn(REPLAYGAIN_OFF, REPLAYGAIN_ALBUM),
            outputMode = normalizeOutputMode(p.getString(KEY_OUTPUT_MODE, OUTPUT_MODE_AUTO)),
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

    /** Volume logiciel anti-clipping. Le préampli est exclusivement géré par EqualizerManager ;
     *  ici on applique seulement les corrections globales sûres au niveau Player. */
    fun playerVolume(values: Values): Float = playerVolume(values, 0f)

    fun playerVolume(values: Values, replayGainDb: Float): Float {
        var db = replayGainDb.toDouble()
        // Petit headroom quand la normalisation est active : cela laisse de la marge au limiteur
        // Android/LoudnessEnhancer et évite que les corrections ReplayGain positives saturent.
        if (values.normalize) db -= 1.0
        return (10.0.pow(db / 20.0)).toFloat().coerceIn(0.18f, 1.0f)
    }

    fun loudnessTargetMillibels(values: Values, replayGainDb: Float): Int {
        var gain = 0
        if (values.normalize) gain += 180
        if (replayGainDb > 0f) gain += (replayGainDb * 100f).toInt()
        // La précision de sortie est désormais gérée par le véritable AudioSink Media3. Elle ne
        // doit plus modifier artificiellement le LoudnessEnhancer.
        return gain.coerceIn(0, 1200)
    }

}
