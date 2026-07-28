package fr.retrospare.blazeplayer.browser

import android.content.ContentUris
import android.media.MediaMetadataRetriever
import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.network.SmbBrowser
import fr.retrospare.blazeplayer.network.UpnpBrowser
import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType
import fr.retrospare.blazeplayer.data.repository.NetworkRepository
import kotlinx.coroutines.Dispatchers
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationContext private val context: Context,
    private val smbBrowser: SmbBrowser,
    private val upnpBrowser: UpnpBrowser,
    private val networkRepository: NetworkRepository
) : ViewModel() {

    sealed class BrowserState {
        object Loading : BrowserState()
        data class Success(val items: List<MediaItem>) : BrowserState()
        data class Error(val message: String = "", val resId: Int? = null) : BrowserState()
    }

    private val _state = MutableStateFlow<BrowserState>(BrowserState.Loading)
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    // Annule logiquement les anciens refresh : les extractions metadata d'un dossier précédent
    // peuvent continuer quelques secondes, mais elles ne doivent plus réémettre dans l'UI ni
    // faire sauter le scroll du navigateur courant.
    private var loadGeneration = 0
    private fun nextLoadGeneration(): Int = ++loadGeneration
    private fun isCurrentLoad(generation: Int): Boolean = generation == loadGeneration

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _breadcrumbs = MutableStateFlow<List<String>>(emptyList())
    val breadcrumbs: StateFlow<List<String>> = _breadcrumbs.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.NAME_ASC)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _showAudio = MutableStateFlow(false)
    val showAudio: StateFlow<Boolean> = _showAudio.asStateFlow()
    private val _audioOnlyMode = MutableStateFlow(false)
    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.data.collect { prefs ->
                _showAudio.value = prefs[booleanPreferencesKey("show_audio")] ?: false
                _showHidden.value = prefs[booleanPreferencesKey("show_hidden")] ?: false
            }
        }
    }

    fun setAudioOnlyMode(v: Boolean) { _audioOnlyMode.value = v }

    fun loadNetworkShares() {
        val generation = nextLoadGeneration()
        viewModelScope.launch {
            _state.value = BrowserState.Loading
            val shares = networkRepository.getShares().first()
            if (!isCurrentLoad(generation)) return@launch
            if (shares.isEmpty()) {
                _state.value = BrowserState.Error(resId = fr.retrospare.blazeplayer.R.string.toast_no_network_path_configured)
            } else {
                val items = shares.map { share ->
                    fr.retrospare.blazeplayer.data.model.MediaItem(
                        id = share.id,
                        name = share.name,
                        path = share.id,
                        extension = "",
                        mimeType = "network",
                        isNetwork = true
                    )
                }
                if (isCurrentLoad(generation)) _state.value = BrowserState.Success(items)
            }
        }
    }

    fun isShowAudioFromSettings(): Boolean = _showAudio.value

    fun toggleShowAudio() {
        _showAudio.value = !_showAudio.value
        loadLocalFiles(_currentPath.value)
    }

    enum class SortMode { NAME_ASC, NAME_DESC, DATE_DESC, SIZE_DESC }

    fun loadLocalFiles(path: String = "") {
        val generation = nextLoadGeneration()
        viewModelScope.launch {
            _state.value = BrowserState.Loading
            _currentPath.value = path
            try {
                val items = if (path.isEmpty()) {
                    scanRootFolders()
                } else {
                    val videoItems = if (_audioOnlyMode.value) emptyList() else scanLocalFiles(path)
                    val audioItems = if (_showAudio.value || _audioOnlyMode.value) scanLocalAudio(path) else emptyList()
                    videoItems + audioItems
                }
                if (!isCurrentLoad(generation)) return@launch
                // Affichage immédiat depuis MediaStore/cache. L'enrichissement léger complète
                // seulement les infos manquantes, sans reset complet de liste ni extraction lourde.
                var currentItems = fr.retrospare.blazeplayer.player.VideoMetadataExtractor.fastDecorateList(context, items)
                _state.value = BrowserState.Success(applySortMode(currentItems))
                fr.retrospare.blazeplayer.player.VideoMetadataExtractor.enrichVideoItemsIncremental(
                    context, currentItems, maxConcurrent = 2
                ) update@{ index, enriched ->
                    if (!isCurrentLoad(generation)) return@update
                    if (index < currentItems.size && currentItems[index] != enriched) {
                        currentItems = currentItems.toMutableList().also { it[index] = enriched }
                        _state.value = BrowserState.Success(applySortMode(currentItems))
                    }
                }
            } catch (e: Exception) {
                if (isCurrentLoad(generation)) _state.value = BrowserState.Error(e.message ?: "")
            }
        }
    }

    fun loadNetworkFilesById(shareId: String, path: String = "") {
        val generation = nextLoadGeneration()
        viewModelScope.launch {
            _state.value = BrowserState.Loading
            try {
                val share = networkRepository.getShareById(shareId)
                if (!isCurrentLoad(generation)) return@launch
                if (share == null) { _state.value = BrowserState.Error(resId = fr.retrospare.blazeplayer.R.string.toast_share_not_found); return@launch }
                currentShare = share
                loadNetworkFiles(share, path)
            } catch (e: Exception) {
                if (isCurrentLoad(generation)) _state.value = BrowserState.Error(e.message ?: "")
            }
        }
    }

    var currentShare: NetworkShare? = null

    fun loadNetworkFiles(share: NetworkShare, path: String = "") {
        val generation = nextLoadGeneration()
        viewModelScope.launch {
            _state.value = BrowserState.Loading
            _currentPath.value = path
            currentShare = share
            try {
                val result = when (share.type) {
                    fr.retrospare.blazeplayer.data.model.ShareType.UPNP -> upnpBrowser.listFiles(share, path)
                    else -> smbBrowser.listFiles(share, path)
                }
                if (!isCurrentLoad(generation)) return@launch
                result.onSuccess { items ->
                    // Affichage immédiat avec des badges devinés depuis l'extension ou déjà en cache.
                    var currentItems = fr.retrospare.blazeplayer.player.VideoMetadataExtractor.fastDecorateList(context, items)
                    _state.value = BrowserState.Success(applySortMode(currentItems))

                    // Les callbacks d'un ancien dossier sont ignorés : plus de clignotement ni de
                    // retour intempestif à une ancienne liste pendant le scroll.
                    fr.retrospare.blazeplayer.player.VideoMetadataExtractor.enrichVideoItemsIncremental(
                        context, currentItems,
                        maxConcurrent = if (share.type == fr.retrospare.blazeplayer.data.model.ShareType.UPNP) 2 else 1
                    ) update@{ index, enriched ->
                        if (!isCurrentLoad(generation)) return@update
                        if (index < currentItems.size && currentItems[index] != enriched) {
                            currentItems = currentItems.toMutableList().also { it[index] = enriched }
                            _state.value = BrowserState.Success(applySortMode(currentItems))
                        }
                    }
                }.onFailure { e ->
                    if (isCurrentLoad(generation)) _state.value = BrowserState.Error(e.message ?: "Network")
                }
            } catch (e: Exception) {
                if (isCurrentLoad(generation)) _state.value = BrowserState.Error(e.message ?: "Network")
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

    /** Recherche globale dans toute la vidéothèque locale indexée par MediaStore.
     *  On ne limite plus au dossier courant : la requête SQL sur DISPLAY_NAME reste très rapide,
     *  même avec beaucoup de dossiers, et on coupe à [maxResults] pour préserver la fluidité. */
    suspend fun searchLocalVideos(query: String, maxResults: Int = 300): List<MediaItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val items = mutableListOf<MediaItem>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.MIME_TYPE
        )
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("%$q%"),
            MediaStore.Video.Media.DISPLAY_NAME
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            while (cursor.moveToNext() && items.size < maxResults) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val filePath = cursor.getString(dataCol) ?: continue
                if (!_showHidden.value && name.startsWith(".")) continue
                items.add(
                    MediaItem(
                        id = id.toString(),
                        name = name,
                        path = filePath,
                        size = cursor.getLong(sizeCol),
                        duration = cursor.getLong(durationCol) / 1000,
                        mimeType = cursor.getString(mimeCol) ?: "video/*",
                        extension = name.substringAfterLast('.', "").lowercase(),
                        isNetwork = false
                    )
                )
            }
        }
        fr.retrospare.blazeplayer.player.VideoMetadataExtractor.fastDecorateList(context, applySortMode(items))
    }

    fun canSearchCurrentNetworkFolder(): Boolean {
        val share = currentShare ?: return false
        return share.type == ShareType.SMB && (share.shareName.isNotBlank() || _currentPath.value.isNotBlank())
    }

    /** Recherche globale dans tous les chemins SMB sauvegardés, pas seulement dans le dossier
     *  affiché. Les parcours restent séquentiels et bornés pour ne pas saturer le NAS ni l'UI. */
    suspend fun searchNetworkVideos(query: String, maxResults: Int = 240): List<MediaItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val savedShares = networkRepository.getShares().first()
            .filter { it.type == ShareType.SMB }
        val merged = mutableListOf<MediaItem>()
        for (share in savedShares) {
            if (merged.size >= maxResults) break
            val remaining = maxResults - merged.size
            val result = smbBrowser.searchVideoFiles(
                share = share,
                startPath = "",
                query = q,
                maxResults = remaining.coerceAtMost(120),
                maxDepth = 10
            ).getOrDefault(emptyList())
            merged += result.take(remaining)
        }
        fr.retrospare.blazeplayer.player.VideoMetadataExtractor.fastDecorateList(context, applySortMode(merged))
    }

    private fun applySortMode(items: List<MediaItem>): List<MediaItem> {
        val folders = items.filter { it.mimeType == "folder" || it.mimeType == "share" || it.mimeType == "network" }
        val files = items.filter { it.mimeType != "folder" && it.mimeType != "share" && it.mimeType != "network" }
        val sortedFiles = when (_sortMode.value) {
            SortMode.NAME_ASC -> files.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
            SortMode.DATE_DESC -> files.sortedByDescending { it.lastPlayedAt }
            SortMode.SIZE_DESC -> files.sortedByDescending { it.size }
        }
        return folders.sortedBy { it.name.lowercase() } + sortedFiles
    }

    private suspend fun scanLocalAudio(path: String): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<MediaItem>()
            val collection = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                android.provider.MediaStore.Audio.Media._ID,
                android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
                android.provider.MediaStore.Audio.Media.SIZE,
                android.provider.MediaStore.Audio.Media.DURATION,
                android.provider.MediaStore.Audio.Media.MIME_TYPE,
                android.provider.MediaStore.Audio.Media.ARTIST,
                android.provider.MediaStore.Audio.Media.ALBUM,
                android.provider.MediaStore.Audio.Media.TITLE
            )
            context.contentResolver.query(
                collection, projection, null, null,
                android.provider.MediaStore.Audio.Media.DISPLAY_NAME
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.SIZE)
                val durationCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION)
                val mimeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val size = cursor.getLong(sizeCol)
                    val duration = cursor.getLong(durationCol) / 1000
                    val mime = cursor.getString(mimeCol) ?: "audio/*"
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    items += MediaItem(
                        id = id.toString(),
                        name = name,
                        path = uri.toString(),
                        size = size,
                        duration = duration,
                        mimeType = mime,
                        extension = ext,
                        isNetwork = false
                    )
                }
            }
            items
        }


    private suspend fun scanRootFolders(): List<MediaItem> = withContext(Dispatchers.IO) {
        val root = android.os.Environment.getExternalStorageDirectory()
        root.listFiles()
            ?.filter { it.isDirectory && it.name.first() != '.' }
            ?.sortedBy { it.name }
            ?.map { dir ->
                MediaItem(
                    id = dir.absolutePath,
                    name = dir.name,
                    path = dir.absolutePath,
                    mimeType = "folder",
                    extension = "",
                    isNetwork = false
                )
            } ?: emptyList()
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
                    if (!_showHidden.value && name.startsWith(".")) continue
                    val size = cursor.getLong(sizeCol)
                    val duration = cursor.getLong(durationCol) / 1000
                    val mime = cursor.getString(mimeCol) ?: "video/*"
                    val width = cursor.getInt(widthCol)
                    val height = cursor.getInt(heightCol)
                    val ext = name.substringAfterLast('.', "").lowercase()
                    // En mode audioOnly : affiche UNIQUEMENT les fichiers audio
                    if (_audioOnlyMode.value) {
                        val audioExts = setOf("mp3","flac","aac","ogg","opus","wav","m4a","wma","ape","dts","ac3","mka")
                        if (ext !in audioExts) continue
                    }
                    val resolution = when {
                        height >= 2160 -> "4K"
                        height >= 1080 -> "FHD"
                        height >= 720 -> "HD"
                        height > 0 -> "SD"
                        else -> null
                    }
                    val uri = ContentUris.withAppendedId(collection, id)
                    // Codecs par extension - pas de MediaMetadataRetriever pour éviter OOM
                    val videoCodec: String? = when (ext) {
                        "mkv" -> "H.265"
                        "mp4", "m4v" -> "H.264"
                        "avi" -> "DIVX"
                        "webm" -> "VP9"
                        "ts", "mts" -> "H.264"
                        else -> ext.uppercase()
                    }
                    val audioCodec: String? = when (ext) {
                        "mkv" -> "AAC"
                        "mp4", "m4v" -> "AAC"
                        "avi" -> "MP3"
                        else -> "AAC"
                    }
                    items += MediaItem(
                        id = id.toString(),
                        name = name,
                        path = uri.toString(),
                        size = size,
                        duration = duration,
                        mimeType = mime,
                        extension = ext,
                        resolution = resolution,
                        isNetwork = false,
                        videoCodec = videoCodec,
                        audioCodec = audioCodec
                    )
                }
            }
            items
        }
}
