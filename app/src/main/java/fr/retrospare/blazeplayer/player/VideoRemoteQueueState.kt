package fr.retrospare.blazeplayer.player

import android.content.Context

/** Petit état persistant permettant à la télécommande de changer de vidéo même si l'écran du
 * lecteur a été fermé alors que la session Chromecast continue. */
object VideoRemoteQueueState {
    private const val PREFS = "blaze_cast_remote_queue"
    private const val KEY_PATHS = "paths"
    private const val KEY_NAMES = "names"
    private const val KEY_INDEX = "index"
    private const val SEPARATOR = "\u001F"

    data class Snapshot(
        val paths: List<String>,
        val names: List<String>,
        val index: Int
    ) {
        val currentPath: String? get() = paths.getOrNull(index)
        val currentName: String? get() = names.getOrNull(index)
    }

    fun save(
        context: Context,
        queuePaths: List<String>,
        queueNames: List<String>,
        index: Int,
        currentPath: String,
        currentName: String
    ) {
        val paths = if (queuePaths.isNotEmpty()) queuePaths else listOf(currentPath)
        val names = if (queuePaths.isNotEmpty()) {
            paths.mapIndexed { i, path -> queueNames.getOrNull(i)?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/') }
        } else {
            listOf(currentName.ifBlank { currentPath.substringAfterLast('/') })
        }
        val safeIndex = if (queuePaths.isNotEmpty()) index.coerceIn(paths.indices) else 0
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PATHS, paths.joinToString(SEPARATOR))
            .putString(KEY_NAMES, names.joinToString(SEPARATOR))
            .putInt(KEY_INDEX, safeIndex)
            .apply()
    }

    fun load(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val paths = prefs.getString(KEY_PATHS, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (paths.isEmpty()) return null
        val rawNames = prefs.getString(KEY_NAMES, null)?.split(SEPARATOR).orEmpty()
        val names = paths.mapIndexed { i, path -> rawNames.getOrNull(i)?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/') }
        val index = prefs.getInt(KEY_INDEX, 0).coerceIn(paths.indices)
        return Snapshot(paths, names, index)
    }

    fun updateIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_INDEX, index)
            .apply()
    }
}
