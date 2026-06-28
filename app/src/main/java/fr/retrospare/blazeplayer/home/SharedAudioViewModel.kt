package fr.retrospare.blazeplayer.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class SharedAudioViewModel : ViewModel() {
    private val _addToPlaylist = MutableSharedFlow<Pair<String, String>>(replay = 0)
    val addToPlaylist = _addToPlaylist.asSharedFlow()

    fun addToPlaylist(path: String, name: String) {
        viewModelScope.launch {
            _addToPlaylist.emit(Pair(path, name))
        }
    }
}
