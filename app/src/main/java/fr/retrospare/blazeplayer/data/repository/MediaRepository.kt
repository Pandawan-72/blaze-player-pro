package fr.retrospare.blazeplayer.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.retrospare.blazeplayer.data.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    private val gson = Gson()
    private val recentItemsKey = stringPreferencesKey("recent_media_items")
    private val mirrorPrefs by lazy {
        context.getSharedPreferences(RECENT_MIRROR_PREFS, Context.MODE_PRIVATE)
    }

    /**
     * DataStore reste la source de vérité, mais sa première lecture est asynchrone et peut laisser
     * l'accueil vide pendant quelques images. Ce miroir, écrit à chaque modification de
     * l'historique, permet au ViewModel de disposer immédiatement du dernier snapshot au démarrage.
     * Dès que DataStore répond, sa valeur remplace le miroir et le resynchronise.
     */
    fun getRecentItemsSnapshot(): List<MediaItem> = parseItems(
        mirrorPrefs.getString(RECENT_MIRROR_KEY, null)
    )

    fun getRecentItems(): Flow<List<MediaItem>> = flow {
        val snapshot = getRecentItemsSnapshot()
        if (snapshot.isNotEmpty()) emit(snapshot)

        dataStore.data
            .map { prefs -> parseItems(prefs[recentItemsKey]) }
            .collect { items ->
                // Priorité au rendu : l'UI reçoit DataStore avant la resynchronisation du miroir.
                emit(items)
                writeMirror(items)
            }
    }.distinctUntilChanged()

    suspend fun saveRecentItem(item: MediaItem) {
        var updatedItems: List<MediaItem>? = null
        dataStore.edit { prefs ->
            val current = parseItems(prefs[recentItemsKey]).toMutableList()
            val previous = current.firstOrNull { it.path == item.path }
            current.removeAll { it.path == item.path }
            // Quand on ré-enregistre l'élément au lancement de la lecture, ne pas effacer la
            // position de reprise déjà connue. Sinon l'historique/UI peut revenir à une ancienne
            // valeur ou à 0 alors que la vidéo a été quittée plus loin.
            val merged = item.copy(
                lastPlayedAt = System.currentTimeMillis(),
                lastPosition = if (item.lastPosition > 0L) item.lastPosition else (previous?.lastPosition ?: 0L),
                duration = if (item.duration > 0L) item.duration else (previous?.duration ?: 0L),
                size = if (item.size > 0L) item.size else (previous?.size ?: 0L),
                resolution = item.resolution?.takeIf { it.isNotBlank() } ?: previous?.resolution,
                videoCodec = item.videoCodec?.takeIf { it.isNotBlank() } ?: previous?.videoCodec,
                audioCodec = item.audioCodec?.takeIf { it.isNotBlank() } ?: previous?.audioCodec
            )
            current.add(0, merged)
            val trimmed = current.take(MAX_RECENT_ITEMS)
            prefs[recentItemsKey] = gson.toJson(trimmed)
            updatedItems = trimmed
        }
        updatedItems?.let(::writeMirror)
    }

    suspend fun updateProgress(path: String, position: Long) {
        var updatedItems: List<MediaItem>? = null
        dataStore.edit { prefs ->
            val current = parseItems(prefs[recentItemsKey]).toMutableList()
            val idx = current.indexOfFirst { it.path == path }
            if (idx >= 0) {
                current[idx] = current[idx].copy(lastPosition = position)
                prefs[recentItemsKey] = gson.toJson(current)
                updatedItems = current
            }
        }
        updatedItems?.let(::writeMirror)
    }

    suspend fun removeRecentItem(id: String) {
        removeRecentItems(setOf(id))
    }

    suspend fun removeRecentItems(idsOrPaths: Set<String>) {
        if (idsOrPaths.isEmpty()) return
        var updatedItems: List<MediaItem>? = null
        dataStore.edit { prefs ->
            val list = parseItems(prefs[recentItemsKey]).toMutableList()
            list.removeAll { item -> item.id in idsOrPaths || item.path in idsOrPaths }
            prefs[recentItemsKey] = gson.toJson(list)
            updatedItems = list
        }
        updatedItems?.let(::writeMirror)
    }

    suspend fun clearHistory() {
        dataStore.edit { prefs ->
            prefs.remove(recentItemsKey)
        }
        writeMirror(emptyList())
    }

    private fun parseItems(json: String?): List<MediaItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<MediaItem>>() {}.type
            gson.fromJson<List<MediaItem>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeMirror(items: List<MediaItem>) {
        try {
            mirrorPrefs.edit()
                .putString(RECENT_MIRROR_KEY, gson.toJson(items.take(MAX_RECENT_ITEMS)))
                .apply()
        } catch (_: Exception) {
            // Le miroir est uniquement une optimisation : DataStore reste la source de vérité.
        }
    }

    private companion object {
        const val MAX_RECENT_ITEMS = 50
        const val RECENT_MIRROR_PREFS = "blaze_recent_media_fast_cache"
        const val RECENT_MIRROR_KEY = "recent_media_items_snapshot"
    }
}
