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

    private val _showNetwork = MutableStateFlow(true)
    val showNetwork: StateFlow<Boolean> = _showNetwork.asStateFlow()

    private val _showLocal = MutableStateFlow(true)
    val showLocal: StateFlow<Boolean> = _showLocal.asStateFlow()

    private val _networkLimit = MutableStateFlow(2)
    private val _localLimit = MutableStateFlow(2)

    private var allItems: List<MediaItem> = emptyList()

    init {
        viewModelScope.launch {
            mediaRepository.getRecentItems().collect { items ->
                allItems = items
                _lastPlayedItem.value = items.firstOrNull()
                applyTab(0)
            }
        }
    }

    fun onTabSelected(position: Int) = applyTab(position)

    private fun applyTab(tab: Int) {
        when (tab) {
            0 -> { // Tous — 2 par catégorie, sections réseau + local visibles
                _showNetwork.value = true
                _showLocal.value = true
                _recentNetworkItems.value = allItems.filter { it.isNetwork }.take(2)
                _recentLocalItems.value = allItems.filter { !it.isNetwork }.take(2)
            }
            1 -> { // Réseau — 10 fichiers réseau, pas de local
                _showNetwork.value = true
                _showLocal.value = false
                _recentNetworkItems.value = allItems.filter { it.isNetwork }.take(10)
                _recentLocalItems.value = emptyList()
            }
            2 -> { // Local — 10 fichiers locaux, pas de réseau
                _showNetwork.value = false
                _showLocal.value = true
                _recentNetworkItems.value = emptyList()
                _recentLocalItems.value = allItems.filter { !it.isNetwork }.take(10)
            }
        }
    }
}
