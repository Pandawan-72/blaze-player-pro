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
    @param:ApplicationContext private val context: Context,
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

    private val _currentTabIndex = MutableStateFlow(0)
    val currentTabIndex: StateFlow<Int> = _currentTabIndex.asStateFlow()

    init {
        viewModelScope.launch {
            mediaRepository.getRecentItems().collect { items ->
                // Filtre les fichiers audio - ils sont gérés par Blaze Audio
                val videoOnly = items.filter { !it.mimeType.startsWith("audio/") }
                // Affichage immédiat : MediaStore pour le local (déjà instantané), badge deviné
                // depuis l'extension (ou déjà en cache) pour le réseau — plutôt que d'attendre
                // l'extraction de tout l'historique réseau avant de rien afficher.
                val fastItems = withContext(Dispatchers.IO) {
                    videoOnly.map { if (it.isNetwork) fr.retrospare.blazeplayer.player.VideoMetadataExtractor.fastDecorate(context, it) else fr.retrospare.blazeplayer.player.VideoMetadataExtractor.fastDecorate(context, enrichWithMediaStore(it)) }
                }
                allItems = fastItems
                _lastPlayedItem.value = fastItems.firstOrNull()
                applyTab(_currentTabIndex.value)

                // Enrichissement réel des items réseau en arrière-plan, un par un — chaque
                // élément prêt met à jour allItems et redéclenche l'onglet actif.
                val videoItemsForBadges = fastItems.filter { it.mimeType.startsWith("video/") }
                if (videoItemsForBadges.isNotEmpty()) {
                    fr.retrospare.blazeplayer.player.VideoMetadataExtractor.enrichVideoItemsIncremental(
                        context, videoItemsForBadges, maxConcurrent = 2
                    ) { _, enriched ->
                        val idx = allItems.indexOfFirst { it.path == enriched.path }
                        if (idx >= 0) {
                            allItems = allItems.toMutableList().also { it[idx] = enriched }
                            applyTab(_currentTabIndex.value)
                        }
                    }
                }
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
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION
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
                    val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)) / 1000L
                    return item.copy(
                        duration = if (duration > 0L) duration else item.duration,
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

    fun onTabSelected(position: Int) {
        _currentTabIndex.value = position
        applyTab(position)
    }

    private fun applyTab(tab: Int) {
        when (tab) {
            1 -> {
                // Onglet Blaze Video : historique vidéo unique, local + réseau.
                _recentLocalItems.value = allItems
                _recentNetworkItems.value = emptyList()
            }
            2 -> {
                // L'onglet Réseau n'affiche plus d'historique : il ouvre directement
                // la page de configuration/navigation des sources SMB/UPnP depuis HomeFragment.
                _recentNetworkItems.value = emptyList()
                _recentLocalItems.value = emptyList()
            }
            3 -> {
                // Onglet Blaze Gallery : pas d'historique vidéo dédié ici, la galerie gère son propre contenu.
                _recentLocalItems.value = emptyList()
                _recentNetworkItems.value = emptyList()
            }
            else -> {
                _recentLocalItems.value = allItems
                _recentNetworkItems.value = emptyList()
            }
        }
    }

    fun removeFromHistory(item: fr.retrospare.blazeplayer.data.model.MediaItem) {
        viewModelScope.launch {
            mediaRepository.removeRecentItem(item.path)
        }
    }

    fun removeFromHistory(items: List<fr.retrospare.blazeplayer.data.model.MediaItem>) {
        viewModelScope.launch {
            mediaRepository.removeRecentItems(items.map { it.path }.toSet() + items.map { it.id }.toSet())
        }
    }

}