package fr.retrospare.blazeplayer.browser

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.network.SmbBrowser
import fr.retrospare.blazeplayer.data.model.NetworkShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
    private val smbBrowser: SmbBrowser
) : ViewModel() {

    sealed class BrowserState {
        object Loading : BrowserState()
        data class Success(val items: List<MediaItem>) : BrowserState()
        data class Error(val message: String) : BrowserState()
    }

    private val _state = MutableStateFlow<BrowserState>(BrowserState.Loading)
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _breadcrumbs = MutableStateFlow<List<String>>(emptyList())
    val breadcrumbs: StateFlow<List<String>> = _breadcrumbs.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.NAME_ASC)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    enum class SortMode { NAME_ASC, NAME_DESC, DATE_DESC, SIZE_DESC }

    fun loadLocalFiles(path: String = "") {
        viewModelScope.launch {
            _state.value = BrowserState.Loading
            _currentPath.value = path
            try {
                val items = scanLocalFiles(path)
                _state.value = BrowserState.Success(applySortMode(items))
            } catch (e: Exception) {
                _state.value = BrowserState.Error(e.message ?: "Erreur de lecture")
            }
        }
    }

    fun loadNetworkFiles(share: NetworkShare, path: String = "") {
        viewModelScope.launch {
            _state.value = BrowserState.Loading
            _currentPath.value = path
            try {
                val items = smbBrowser.listFiles(share, path)
                _state.value = BrowserState.Success(applySortMode(items))
            } catch (e: Exception) {
                _state.value = BrowserState.Error(e.message ?: "Erreur réseau")
            }
        }
    }

    fun cycleSortMode() {
        val next = when (_sortMode.value) {
            SortMode.NAME_ASC -> SortMode.NAME_DESC
            SortMode.NAME_DESC -> SortMode.DATE_DESC
            SortMode.DATE_DESC -> SortMode.SIZE_DESC
            SortMode.SIZE_DESC -> SortMode.NAME_ASC
        }
        _sortMode.value = next
        val current = (_state.value as? BrowserState.Success)?.items ?: return
        _state.value = BrowserState.Success(applySortMode(current))
    }

    fun sortLabel(): String = when (_sortMode.value) {
        SortMode.NAME_ASC -> "Nom A–Z"
        SortMode.NAME_DESC -> "Nom Z–A"
        SortMode.DATE_DESC -> "Date récente"
        SortMode.SIZE_DESC -> "Taille"
    }

    private fun applySortMode(items: List<MediaItem>): List<MediaItem> {
        val folders = items.filter { it.mimeType == "folder" }
        val files = items.filter { it.mimeType != "folder" }
        val sortedFiles = when (_sortMode.value) {
            SortMode.NAME_ASC -> files.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
            SortMode.DATE_DESC -> files.sortedByDescending { it.lastPlayedAt }
            SortMode.SIZE_DESC -> files.sortedByDescending { it.size }
        }
        return folders.sortedBy { it.name.lowercase() } + sortedFiles
    }

    private suspend fun scanLocalFiles(path: String): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<MediaItem>()
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DATE_MODIFIED
            )
            val selection = if (path.isNotEmpty())
                "${MediaStore.Video.Media.DATA} LIKE ?"
            else null
            val selectionArgs = if (path.isNotEmpty())
                arrayOf("$path%")
            else null

            context.contentResolver.query(
                collection, projection, selection, selectionArgs,
                MediaStore.Video.Media.DISPLAY_NAME
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val filePath = cursor.getString(dataCol) ?: continue
                    val size = cursor.getLong(sizeCol)
                    val duration = cursor.getLong(durationCol) / 1000
                    val mime = cursor.getString(mimeCol) ?: "video/*"
                    val width = cursor.getInt(widthCol)
                    val height = cursor.getInt(heightCol)
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val resolution = when {
                        height >= 2160 -> "4K"
                        height >= 1080 -> "1080p"
                        height >= 720 -> "720p"
                        else -> null
                    }
                    val uri = ContentUris.withAppendedId(collection, id)
                    items += MediaItem(
                        id = id.toString(),
                        name = name,
                        path = uri.toString(),
                        size = size,
                        duration = duration,
                        mimeType = mime,
                        extension = ext,
                        resolution = resolution,
                        isNetwork = false
                    )
                }
            }
            items
        }
}
