package fr.retrospare.blazeplayer.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioTrack(val path: String, val name: String)

class SharedAudioViewModel(app: Application) : AndroidViewModel(app) {
    // Playlist persistante dans le ViewModel
    private val _playlist = MutableStateFlow<List<AudioTrack>>(emptyList())
    val playlist = _playlist.asStateFlow()

    private val _pendingTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val pendingTracks = _pendingTracks.asStateFlow()

    // Demande spéciale pour les intents externes Android ("Ouvrir avec").
    // Contrairement aux ajouts internes à la playlist, le fichier cliqué doit remplacer
    // immédiatement la file active et démarrer, même si le MediaController n'est pas encore prêt.
    private val _pendingPriorityExternalTrack = MutableStateFlow<AudioTrack?>(null)

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

    fun requestPriorityExternalPlayback(path: String, name: String) {
        _pendingPriorityExternalTrack.value = AudioTrack(path, name)
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
            _currentIndex.value = prefs.getInt("currentIndex", 0).coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        } catch (e: Exception) {}
    }

    fun consumePendingTracks(): List<AudioTrack> {
        val tracks = _pendingTracks.value
        _pendingTracks.value = emptyList()
        return tracks
    }

    fun consumePriorityExternalTrack(): AudioTrack? {
        val track = _pendingPriorityExternalTrack.value
        _pendingPriorityExternalTrack.value = null
        return track
    }

    fun setPlaylist(tracks: List<AudioTrack>) {
        _playlist.value = tracks
        saveToPrefs()
    }

    fun setCurrentIndex(index: Int) {
        _currentIndex.value = index.coerceAtLeast(0)
        getApplication<Application>().getSharedPreferences("blaze_playlist", android.content.Context.MODE_PRIVATE)
            .edit().putInt("currentIndex", _currentIndex.value).apply()
    }

    fun removeTrack(path: String) {
        _playlist.value = _playlist.value.filter { it.path != path }
        if (_currentIndex.value >= _playlist.value.size) _currentIndex.value = (_playlist.value.size - 1).coerceAtLeast(0)
        saveToPrefs()
    }

    fun clearPlaylist() {
        _playlist.value = emptyList()
        _pendingTracks.value = emptyList()
        _pendingPriorityExternalTrack.value = null
        _currentIndex.value = 0
        getApplication<Application>().getSharedPreferences("blaze_playlist", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
