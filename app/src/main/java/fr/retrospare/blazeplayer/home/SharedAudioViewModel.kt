package fr.retrospare.blazeplayer.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AudioTrack(val path: String, val name: String)

class SharedAudioViewModel(app: Application) : AndroidViewModel(app) {
    // Playlist persistante dans le ViewModel
    private val _playlist = MutableStateFlow<List<AudioTrack>>(emptyList())
    val playlist = _playlist.asStateFlow()

    private val _pendingTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val pendingTracks = _pendingTracks.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex = _currentIndex.asStateFlow()

    fun addToPlaylist(path: String, name: String) {
        val track = AudioTrack(path, name)
        if (_playlist.value.none { it.path == path }) {
            _playlist.value = _playlist.value + track
        }
        _pendingTracks.value = _pendingTracks.value + track
        // Sauvegarde immédiate dans SharedPreferences
        saveToPrefs()
    }

    private fun saveToPrefs() {
        val prefs = getApplication<Application>().getSharedPreferences("blaze_playlist", android.content.Context.MODE_PRIVATE)
        val json = org.json.JSONArray().apply {
            _playlist.value.forEach { put(org.json.JSONObject().put("path", it.path).put("name", it.name)) }
        }
        prefs.edit().putString("items", json.toString()).apply()
    }

    fun loadFromPrefs() {
        val prefs = getApplication<Application>().getSharedPreferences("blaze_playlist", android.content.Context.MODE_PRIVATE)
        val json = prefs.getString("items", null) ?: return
        try {
            val arr = org.json.JSONArray(json)
            val tracks = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AudioTrack(obj.getString("path"), obj.getString("name"))
            }
            _playlist.value = tracks
        } catch (e: Exception) {}
    }

    fun consumePendingTracks(): List<AudioTrack> {
        val tracks = _pendingTracks.value
        _pendingTracks.value = emptyList()
        return tracks
    }

    fun setPlaylist(tracks: List<AudioTrack>) {
        _playlist.value = tracks
    }

    fun setCurrentIndex(index: Int) {
        _currentIndex.value = index
    }

    fun removeTrack(path: String) {
        _playlist.value = _playlist.value.filter { it.path != path }
    }

    fun clearPlaylist() {
        _playlist.value = emptyList()
        _currentIndex.value = 0
    }
}
