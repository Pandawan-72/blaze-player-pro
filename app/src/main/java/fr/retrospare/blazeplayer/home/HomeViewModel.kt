package fr.retrospare.blazeplayer.home

import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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

    private var allItems: List<MediaItem> = emptyList()

    init {
        viewModelScope.launch {
            mediaRepository.getRecentItems().collect { items ->
                // Enrichit chaque item avec les métadonnées MediaStore
                val enriched = withContext(Dispatchers.IO) {
                    items.map { enrichWithMediaStore(it) }
                }
                allItems = enriched
                _lastPlayedItem.value = enriched.firstOrNull()
                applyTab(0)
            }
        }
    }

    private fun enrichWithMediaStore(item: MediaItem): MediaItem {
        if (item.isNetwork) return item
        try {
            val projection = arrayOf(
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DISPLAY_NAME
            )
            // Cherche par DATA path ou par ID
            val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val path = item.path
            val selection = "${MediaStore.Video.Media._ID} = ?"
            val id = path.substringAfterLast("/").substringBefore("?")
                .toLongOrNull() ?: return enrichFromExtension(item)

            context.contentResolver.query(
                android.content.ContentUris.withAppendedId(uri, id),
                projection, null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH))
                    val height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT))
                    val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)) ?: ""
                    val resolution = when {
                        height >= 2160 -> "4K"
                        height >= 1080 -> "FHD"
                        height >= 720 -> "HD"
                        height > 0 -> "SD"
                        else -> null
                    }
                    val ext = item.name.substringAfterLast('.', "").lowercase()
                    return item.copy(
                        resolution = resolution,
                        videoCodec = videoCodecFromExt(ext),
                        audioCodec = audioCodecFromExt(ext)
                    )
                }
            }
        } catch (e: Exception) { }
        return enrichFromExtension(item)
    }

    private fun enrichFromExtension(item: MediaItem): MediaItem {
        val ext = item.extension.ifEmpty {
            item.name.substringAfterLast('.', "").lowercase()
        }
        val isAudio = ext in setOf("mp3","flac","aac","ogg","opus","wav","m4a","wma","ape","dts","ac3","mka")
        return if (isAudio) {
            item.copy(videoCodec = null, audioCodec = null)
        } else {
            item.copy(
                videoCodec = videoCodecFromExt(ext),
                audioCodec = audioCodecFromExt(ext)
            )
        }
    }

    private fun videoCodecFromExt(ext: String) = when (ext.lowercase()) {
        "mkv" -> "H.265"
        "mp4", "m4v" -> "H.264"
        "avi" -> "XVID"
        "webm" -> "VP9"
        "ts" -> "H.264"
        else -> ext.uppercase().take(6)
    }

    private fun audioCodecFromExt(ext: String) = when (ext.lowercase()) {
        "mkv", "mp4", "m4v", "ts" -> "AAC"
        "avi" -> "MP3"
        "webm" -> "OPUS"
        else -> "AAC"
    }

    fun onTabSelected(position: Int) = applyTab(position)

    private fun applyTab(tab: Int) {
        when (tab) {
            0 -> {
                _showNetwork.value = true
                _showLocal.value = true
                _recentNetworkItems.value = allItems.filter { it.isNetwork }.take(3)
                _recentLocalItems.value = allItems.filter { !it.isNetwork }.take(3)
            }
            1 -> {
                _showNetwork.value = true
                _showLocal.value = false
                _recentNetworkItems.value = allItems.filter { it.isNetwork }.take(10)
                _recentLocalItems.value = emptyList()
            }
            2 -> {
                _showNetwork.value = false
                _showLocal.value = true
                _recentNetworkItems.value = emptyList()
                _recentLocalItems.value = allItems.filter { !it.isNetwork }.take(10)
            }
        }
    }

    fun removeFromHistory(item: fr.retrospare.blazeplayer.data.model.MediaItem) {
        viewModelScope.launch {
            mediaRepository.removeRecentItem(item.id)
        }
    }
}