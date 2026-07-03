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
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    private val gson = Gson()
    private val RECENT_ITEMS_KEY = stringPreferencesKey("recent_media_items")

    fun getRecentItems(): Flow<List<MediaItem>> {
        return dataStore.data.map { prefs ->
            val json = prefs[RECENT_ITEMS_KEY] ?: return@map emptyList()
            try {
                val type = object : TypeToken<List<MediaItem>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun saveRecentItem(item: MediaItem) {
        dataStore.edit { prefs ->
            val current = try {
                val json = prefs[RECENT_ITEMS_KEY] ?: "[]"
                val type = object : TypeToken<List<MediaItem>>() {}.type
                gson.fromJson<List<MediaItem>>(json, type)?.toMutableList() ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
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
            val trimmed = current.take(50)
            prefs[RECENT_ITEMS_KEY] = gson.toJson(trimmed)
        }
    }

    suspend fun updateProgress(path: String, position: Long) {
        dataStore.edit { prefs ->
            val current = try {
                val json = prefs[RECENT_ITEMS_KEY] ?: "[]"
                val type = object : TypeToken<List<MediaItem>>() {}.type
                gson.fromJson<List<MediaItem>>(json, type)?.toMutableList() ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
            val idx = current.indexOfFirst { it.path == path }
            if (idx >= 0) {
                current[idx] = current[idx].copy(lastPosition = position)
                prefs[RECENT_ITEMS_KEY] = gson.toJson(current)
            }
        }
    }

    suspend fun removeRecentItem(id: String) {
        dataStore.edit { prefs ->
            val json = prefs[RECENT_ITEMS_KEY] ?: return@edit
            val list = gson.fromJson(json, Array<MediaItem>::class.java).toMutableList()
            list.removeAll { it.id == id || it.path == id }
            prefs[RECENT_ITEMS_KEY] = gson.toJson(list)
        }
    }

    suspend fun clearHistory() {
        dataStore.edit { prefs ->
            prefs.remove(RECENT_ITEMS_KEY)
        }
    }
}
