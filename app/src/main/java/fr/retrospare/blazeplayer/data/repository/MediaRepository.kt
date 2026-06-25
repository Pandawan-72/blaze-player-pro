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
            current.removeAll { it.path == item.path }
            current.add(0, item.copy(lastPlayedAt = System.currentTimeMillis()))
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

    suspend fun clearHistory() {
        dataStore.edit { prefs ->
            prefs.remove(RECENT_ITEMS_KEY)
        }
    }
}
