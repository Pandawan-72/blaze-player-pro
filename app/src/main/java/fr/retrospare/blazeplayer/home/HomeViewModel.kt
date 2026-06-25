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

    private val _recentVideos = MutableStateFlow<List<MediaItem>>(emptyList())
    val recentVideos: StateFlow<List<MediaItem>> = _recentVideos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadRecentVideos()
    }

    fun loadRecentVideos() {
        viewModelScope.launch {
            _isLoading.value = true
            _recentVideos.value = mediaRepository.getLocalVideos().take(20)
            _isLoading.value = false
        }
    }
}
