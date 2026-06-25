package fr.retrospare.blazeplayer.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mediaRepository: MediaRepository
) : ViewModel() {

    private val _lastPlayedItem = MutableStateFlow<MediaItem?>(null)
    val lastPlayedItem: StateFlow<MediaItem?> = _lastPlayedItem.asStateFlow()

    private val _recentNetworkItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val recentNetworkItems: StateFlow<List<MediaItem>> = _recentNetworkItems.asStateFlow()

    private val _recentLocalItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val recentLocalItems: StateFlow<List<MediaItem>> = _recentLocalItems.asStateFlow()

    private val _selectedTab = MutableStateFlow(0)

    init {
        loadRecentItems()
    }

    private fun loadRecentItems() {
        viewModelScope.launch {
            mediaRepository.getRecentItems().collect { items ->
                val networkItems = items.filter { it.isNetwork }
                val localItems = items.filter { !it.isNetwork }
                _recentNetworkItems.value = networkItems.take(2)
                _recentLocalItems.value = localItems.take(2)
                _lastPlayedItem.value = items.firstOrNull()
            }
        }
    }

    fun onTabSelected(position: Int) {
        _selectedTab.value = position
        viewModelScope.launch {
            when (position) {
                0 -> loadRecentItems()
                1 -> loadNetworkOnly()
                2 -> loadLocalOnly()
                3 -> loadRecentItems()
            }
        }
    }

    private fun loadNetworkOnly() {
        viewModelScope.launch {
            mediaRepository.getRecentItems().collect { items ->
                _recentNetworkItems.value = items.filter { it.isNetwork }.take(2)
                _recentLocalItems.value = emptyList()
            }
        }
    }

    private fun loadLocalOnly() {
        viewModelScope.launch {
            mediaRepository.getRecentItems().collect { items ->
                _recentNetworkItems.value = emptyList()
                _recentLocalItems.value = items.filter { !it.isNetwork }.take(2)
            }
        }
    }

    fun onMediaPlayed(item: MediaItem) {
        viewModelScope.launch {
            mediaRepository.saveRecentItem(item)
            loadRecentItems()
        }
    }
}
