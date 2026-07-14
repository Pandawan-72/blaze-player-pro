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

    // Snapshot synchrone minuscule, miroir du DataStore : la StateFlow possède déjà l'historique
    // avant même que HomeFragment commence à la collecter. Cela supprime l'image initiale vide
    // suivie de l'apparition tardive des tuiles au démarrage.
    private val initialHistoryItems: List<MediaItem> = mediaRepository.getRecentItemsSnapshot()
        .filter { !it.mimeType.startsWith("audio/") }
        .map { fr.retrospare.blazeplayer.player.VideoMetadataExtractor.fastDecorate(it) }

    private val _lastPlayedItem = MutableStateFlow<MediaItem?>(initialHistoryItems.firstOrNull())
    val lastPlayedItem: StateFlow<MediaItem?> = _lastPlayedItem.asStateFlow()

    private val _recentNetworkItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val recentNetworkItems: StateFlow<List<MediaItem>> = _recentNetworkItems.asStateFlow()

    private val _recentLocalItems = MutableStateFlow(initialHistoryItems)
    val recentLocalItems: StateFlow<List<MediaItem>> = _recentLocalItems.asStateFlow()


    private val _showNetwork = MutableStateFlow(true)
    val showNetwork: StateFlow<Boolean> = _showNetwork.asStateFlow()

    private val _showLocal = MutableStateFlow(true)
    val showLocal: StateFlow<Boolean> = _showLocal.asStateFlow()

    private var allItems: List<MediaItem> = initialHistoryItems

    private val _currentTabIndex = MutableStateFlow(0)
    val currentTabIndex: StateFlow<Int> = _currentTabIndex.asStateFlow()

    private var historyLoadGeneration: Long = 0L

    init {
        if (initialHistoryItems.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                fr.retrospare.blazeplayer.ui.ThumbnailUtils.prewarmCachedVideoThumbnails(
                    context,
                    initialHistoryItems.map { it.path },
                    limit = 24
                )
            }
        }
        viewModelScope.launch {
            mediaRepository.getRecentItems().collect { items ->
                val generation = ++historyLoadGeneration
                // Publie le snapshot brut immédiatement. fastDecorate() sans Context ne fait
                // aucune I/O : il utilise seulement le cache mémoire et les informations déjà
                // persistées dans MediaItem. L'utilisateur voit donc l'historique dès que le
                // snapshot SharedPreferences/DataStore arrive, sans attendre MediaStore ni le
                // cache de métadonnées disque.
                val videoOnly = items.filter { !it.mimeType.startsWith("audio/") }
                val immediateItems = videoOnly.map {
                    fr.retrospare.blazeplayer.player.VideoMetadataExtractor.fastDecorate(it)
                }
                publishHistory(immediateItems)

                // Réchauffe en parallèle les miniatures déjà présentes sur disque. Cela donne une
                // longueur d'avance au premier bind RecyclerView sans ouvrir de vidéo ni de NAS.
                viewModelScope.launch(Dispatchers.IO) {
                    fr.retrospare.blazeplayer.ui.ThumbnailUtils.prewarmCachedVideoThumbnails(
                        context,
                        immediateItems.map { it.path },
                        limit = 24
                    )
                }

                // Complète ensuite les métadonnées locales/cache disque hors du thread principal.
                // Ce travail n'empêche plus l'affichage initial et un ancien résultat ne peut pas
                // remplacer un historique plus récent.
                viewModelScope.launch(Dispatchers.IO) {
                    val decorated = videoOnly.map { item ->
                        val enrichedBase = if (item.isNetwork) item else enrichWithMediaStore(item)
                        fr.retrospare.blazeplayer.player.VideoMetadataExtractor.fastDecorate(context, enrichedBase)
                    }
                    withContext(Dispatchers.Main) {
                        if (generation == historyLoadGeneration) {
                            val currentByPath = allItems.associateBy { it.path }
                            val merged = decorated.map { candidate ->
                                val current = currentByPath[candidate.path] ?: return@map candidate
                                candidate.copy(
                                    duration = candidate.duration.takeIf { it > 0L } ?: current.duration,
                                    size = candidate.size.takeIf { it > 0L } ?: current.size,
                                    resolution = candidate.resolution?.takeIf { it.isNotBlank() } ?: current.resolution,
                                    videoCodec = candidate.videoCodec?.takeIf { it.isNotBlank() } ?: current.videoCodec,
                                    audioCodec = candidate.audioCodec?.takeIf { it.isNotBlank() } ?: current.audioCodec
                                )
                            }
                            if (merged != allItems) publishHistory(merged)
                        }
                    }
                }

                // Enrichissement réel des items vidéo en arrière-plan, un par un. Chaque résultat
                // met à jour uniquement sa ligne via DiffUtil dans HomeFragment.
                val videoItemsForBadges = immediateItems.filter { it.mimeType.startsWith("video/") }
                if (videoItemsForBadges.isNotEmpty()) {
                    fr.retrospare.blazeplayer.player.VideoMetadataExtractor.enrichVideoItemsIncremental(
                        context, videoItemsForBadges, maxConcurrent = 2
                    ) { _, enriched ->
                        withContext(Dispatchers.Main) {
                            if (generation != historyLoadGeneration) return@withContext
                            val idx = allItems.indexOfFirst { it.path == enriched.path }
                            if (idx >= 0 && allItems[idx] != enriched) {
                                allItems = allItems.toMutableList().also { it[idx] = enriched }
                                applyTab(_currentTabIndex.value)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun publishHistory(items: List<MediaItem>) {
        allItems = items
        _lastPlayedItem.value = items.firstOrNull()
        applyTab(_currentTabIndex.value)
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