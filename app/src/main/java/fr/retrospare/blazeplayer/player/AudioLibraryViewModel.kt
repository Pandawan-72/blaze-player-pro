package fr.retrospare.blazeplayer.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.playlist.PlaylistCategory
import fr.retrospare.blazeplayer.playlist.PlaylistManager
import fr.retrospare.blazeplayer.playlist.PlaylistTrackRef
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.abs

/** Onglets réellement accessibles depuis la barre de la bibliothèque. L'ancien `Tab.HOME` a été
 *  retiré : il n'était jamais atteint (onCreate écrasait toujours sa valeur par défaut avant le
 *  premier rendu), c'était un enum mort. */
enum class LibraryTab { ALBUMS, ARTISTS, TITLES, PLAYLISTS }

data class LibraryPlaylist(
    val title: String,
    val subtitle: String,
    val tracks: List<LibraryTrack>,
    val isParty: Boolean = false
)

/**
 * Lignes affichables par le RecyclerView. Ne contient que les types réellement produits par
 * [AudioLibraryViewModel] : `Section`, `AlbumItem` et `AlbumBack` existaient dans l'ancien code
 * mais n'étaient jamais construits (vérifié : zéro appelant) — retirés ici plutôt que reconduits.
 */
sealed class LibraryRow(open val stableId: Long) {
    data class Status(val text: String, val detail: String = "") : LibraryRow(("status:$text:$detail").hashCode().toLong())
    data class TrackItem(val track: LibraryTrack, val indexInQueue: Int) : LibraryRow(track.path.hashCode().toLong())
    data class AlbumTrackItem(val track: LibraryTrack, val indexInAlbum: Int) : LibraryRow(("album_track:${track.path}").hashCode().toLong())
    data class AlbumTile(val album: LibraryAlbum) : LibraryRow(("album_tile:${album.key}").hashCode().toLong())
    data class ArtistItem(val artist: LibraryArtist) : LibraryRow(("artist:${artist.name}").hashCode().toLong())
    data class PlaylistItem(val playlist: LibraryPlaylist) : LibraryRow(("playlist:${playlist.title}").hashCode().toLong())
}

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.ALBUMS,
    val searchQuery: String = "",
    val rows: List<LibraryRow> = emptyList(),
    val trackCount: Int = 0,
    val albumCount: Int = 0,
    val artistCount: Int = 0,
    val openedAlbum: LibraryAlbum? = null,
    val isRefreshing: Boolean = false,
    /** true tant qu'aucune donnée (cache mémoire ou Room) n'a encore été observée. */
    val isInitialLoad: Boolean = true
)

@HiltViewModel
class AudioLibraryViewModel @Inject constructor(
    private val repository: AudioLibraryRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    companion object {
        private const val MAX_RENDER_TRACK_ROWS = 2000
        private const val MAX_ARTWORK_ENRICH_TRACKS = 2400
    }

    private val _tab = MutableStateFlow(LibraryTab.ALBUMS)
    private val _searchQuery = MutableStateFlow("")
    private val _openedAlbumKey = MutableStateFlow<String?>(null)
    private val _openedAlbumTrackPaths = MutableStateFlow<Set<String>>(emptySet())
    private val _isRefreshing = MutableStateFlow(false)
    private val _playlists = MutableStateFlow<List<LibraryPlaylist>>(emptyList())
    private val _librarySettings = MutableStateFlow(readLibrarySettings())
    val refreshState: StateFlow<Boolean> get() = _isRefreshing
    private val hasObservedTracksOnce = AtomicBoolean(false)

    // L'enrichissement lance plusieurs workers en parallèle. Des HashSet classiques pouvaient
    // être modifiés simultanément et perdre des entrées (voire corrompre leur état interne).
    private val artworkAttemptedThisSession = ConcurrentHashMap.newKeySet<String>()
    private val durationAttemptedThisSession = ConcurrentHashMap.newKeySet<String>()
    private var artworkEnrichmentJob: Job? = null
    private var durationEnrichmentJob: Job? = null

    private val libraryTracksFlow: StateFlow<List<LibraryTrack>> = repository.observeLibrary(appContext)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Les durées ne sont pas des tags texte : elles sont calculées en arrière-plan depuis les
        // pistes manquantes, puis persistées dans Room. La bibliothèque peut ainsi afficher la
        // durée réelle des albums sans retarder le premier rendu ni rouvrir les fichiers à chaque
        // visite.
        viewModelScope.launch {
            libraryTracksFlow.collect { tracks ->
                // Pendant un scan NAS, ne pas ouvrir en parallèle chaque fichier pour calculer sa
                // durée : cela multiplie les requêtes SMB et ralentit fortement l'indexation.
                // L'enrichissement est lancé explicitement à la fin de refresh().
                if (tracks.isNotEmpty() && !_isRefreshing.value) scheduleDurationEnrichment(tracks)
            }
        }
    }

    private data class LibraryBehaviorSettings(
        val trackOrder: Boolean,
        val ignoreShort: Boolean
    )

    private fun readLibrarySettings(): LibraryBehaviorSettings {
        val values = AudioProSettings.read(appContext)
        return LibraryBehaviorSettings(
            trackOrder = values.trackOrder,
            ignoreShort = values.ignoreShort
        )
    }

    fun reloadLibrarySettings() {
        val next = readLibrarySettings()
        if (next == _librarySettings.value) return
        _librarySettings.value = next
        if (next.ignoreShort) {
            durationEnrichmentJob?.cancel()
            durationEnrichmentJob = null
            durationAttemptedThisSession.clear()
            scheduleDurationEnrichment(libraryTracksFlow.value)
        }
    }

    private data class Selection(
        val tab: LibraryTab,
        val query: String,
        val openedAlbumKey: String?,
        val openedAlbumTrackPaths: Set<String>
    )

    private data class UiInputs(
        val tracks: List<LibraryTrack>,
        val selection: Selection,
        val playlists: List<LibraryPlaylist>,
        val refreshing: Boolean,
        val settings: LibraryBehaviorSettings
    )

    private val selectionFlow = combine(_tab, _searchQuery, _openedAlbumKey, _openedAlbumTrackPaths) { tab, query, key, paths ->
        Selection(tab, query, key, paths)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryTracksFlow, selectionFlow, _playlists, _isRefreshing, _librarySettings
    ) { tracks, selection, playlists, refreshing, settings ->
        UiInputs(tracks, selection, playlists, refreshing, settings)
    }
        // Les regroupements albums/artistes et les tris peuvent traiter plusieurs milliers de
        // titres. Ils ne doivent jamais monopoliser le thread UI (cause principale de l'ANR).
        .mapLatest { input ->
            if (input.tracks.isNotEmpty()) hasObservedTracksOnce.set(true)
            buildUiState(input.tracks, input.selection, input.playlists, input.refreshing, input.settings)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setTab(tab: LibraryTab) {
        _tab.value = tab
        if (tab == LibraryTab.PLAYLISTS) loadPlaylists()
    }

    fun setSearchQuery(query: String) {
        closeAlbumDetail()
        _searchQuery.value = query.trim()
    }

    fun openAlbumDetail(album: LibraryAlbum) {
        _openedAlbumTrackPaths.value = album.tracks.map { it.path }.filter { it.isNotBlank() }.toSet()
        _openedAlbumKey.value = albumDetailKey(album)
    }

    fun closeAlbumDetail() {
        _openedAlbumKey.value = null
        _openedAlbumTrackPaths.value = emptySet()
    }

    fun isAlbumDetailOpen(): Boolean = _openedAlbumKey.value != null

    /** Lance un scan complet (MediaStore + local + réseau), puis précharge les pochettes. */
    fun refresh(manual: Boolean, isPlaybackCritical: () -> Boolean = { false }): Boolean {
        if (_isRefreshing.value) return false
        // Positionné avant le launch : deux clics/retours lifecycle consécutifs ne peuvent plus
        // démarrer deux scans concurrents avant que la coroutine ait eu le temps de s'exécuter.
        _isRefreshing.value = true
        viewModelScope.launch {
            AudioLibraryWorkState.beginIndexing()
            var tracksToEnrich: List<LibraryTrack> = emptyList()
            try {
                artworkAttemptedThisSession.clear()
                durationEnrichmentJob?.cancel()
                durationAttemptedThisSession.clear()
                ThumbnailUtils.invalidateAudioFolderCoverLookups()
                AudioArtworkResolver.invalidateIndexedPaths()
                tracksToEnrich = repository.refresh(appContext, manual, isPlaybackCritical).activeTracks
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Une erreur SMB/Room ne doit jamais remonter jusqu'au handler principal et
                // redémarrer le processus sur l'onglet Accueil. Les lots déjà écrits restent
                // disponibles et le prochain refresh pourra reprendre.
                fr.retrospare.blazeplayer.debug.CrashReporter.log(
                    appContext,
                    "Audio library refresh failed",
                    error
                )
            } finally {
                _isRefreshing.value = false
                AudioLibraryWorkState.endIndexing()
            }
            if (tracksToEnrich.isNotEmpty()) scheduleEnrichment(tracksToEnrich)
        }
        return true
    }

    fun onFolderRemoved(folderKeys: Set<String>) {
        viewModelScope.launch { repository.deleteFolders(appContext, folderKeys) }
    }

    /** Persiste la pochette réellement résolue pour un titre visible, au moment du bind. */
    fun persistArtworkPath(path: String, artworkPath: String) {
        viewModelScope.launch { repository.updateArtworkPath(appContext, path, artworkPath) }
    }

    /**
     * Précharge uniquement les pochettes de la bibliothèque. Les noms d'album et d'artiste sont
     * désormais fournis immédiatement par la structure des dossiers et ne déclenchent plus une
     * extraction automatique des tags audio.
     */
    fun scheduleEnrichment(source: List<LibraryTrack>) {
        if (source.isEmpty()) return
        scheduleDurationEnrichment(source)

        artworkEnrichmentJob?.cancel()
        artworkEnrichmentJob = viewModelScope.launch {
            AudioLibraryWorkState.awaitEnrichmentWindow()
            val artworkCandidates = withContext(Dispatchers.Default) {
                source.asSequence()
                    // Une seule extraction automatique par album suffit à la grille. Les autres
                    // titres ne sont ouverts que lorsqu'ils deviennent visibles ou sont lus.
                    .distinctBy { AudioLibraryHeuristics.albumKey(it) }
                    .filter { it.path.isNotBlank() && needsArtworkExtraction(it) }
                    .sortedWith(
                        compareByDescending<LibraryTrack> { it.source != LibraryTrackSource.NETWORK }
                            .thenByDescending { it.addedAt }
                    )
                    .take(MAX_ARTWORK_ENRICH_TRACKS)
                    .toList()
            }
            if (artworkCandidates.isEmpty()) return@launch
            repository.enrichArtwork(
                candidates = artworkCandidates,
                loadArtwork = { track ->
                    if (artworkAttemptedThisSession.add(track.path)) loadArtworkBitmapForTrack(track) != null else false
                },
                concurrency = 1
            )
        }
    }

    private fun scheduleDurationEnrichment(source: List<LibraryTrack>) {
        if (durationEnrichmentJob?.isActive == true) return
        val candidates = source.asSequence()
            .filter { it.path.isNotBlank() && it.durationMs <= 0L }
            .filter { it.path !in durationAttemptedThisSession }
            .sortedWith(
                compareByDescending<LibraryTrack> { it.source != LibraryTrackSource.NETWORK }
                    .thenBy { AudioLibraryHeuristics.albumKey(it) }
                    .thenBy { AudioLibraryHeuristics.normalizedTrackNo(it.trackNo) }
            )
            .toList()
        if (candidates.isEmpty()) return

        val job = viewModelScope.launch {
            AudioLibraryWorkState.awaitEnrichmentWindow()
            repository.enrichMetadata(
                context = appContext,
                candidates = candidates,
                extractMetadata = { track ->
                    if (!durationAttemptedThisSession.add(track.path)) {
                        null
                    } else {
                        val seconds = AudioMetadataExtractor.extractDurationOnly(appContext, track.path)
                        if (seconds > 0L) {
                            AudioTechnicalInfo(
                                duration = seconds,
                                extension = track.container.ifBlank {
                                    AudioLibraryHeuristics.containerFrom("", track.path)
                                }
                            )
                        } else null
                    }
                },
                concurrency = 1,
                batchSize = 8
            )
        }
        durationEnrichmentJob = job
        job.invokeOnCompletion { cause ->
            if (cause == null) {
                viewModelScope.launch { scheduleDurationEnrichment(libraryTracksFlow.value) }
            }
        }
    }

    /** Résout et charge la pochette d'un titre pour l'affichage d'une ligne visible (bind), en
     *  réutilisant les caches déjà chauds. Public : appelé depuis l'Adapter, pas seulement depuis
     *  l'enrichissement en arrière-plan. */
    suspend fun loadArtworkForBinding(track: LibraryTrack): android.graphics.Bitmap? {
        // Pendant le premier parcours NAS, ne pas ouvrir en parallèle chaque audio/cover visible :
        // le listing réseau doit rester prioritaire. Une pochette déjà persistée reste disponible,
        // sinon le placeholder est remplacé automatiquement après l'enrichissement post-scan.
        if ((_isRefreshing.value || BlazePlayerService.isAudioPlaybackActive) &&
            track.source == LibraryTrackSource.NETWORK
        ) {
            val primary = primaryArtworkPath(track)
            AudioArtworkResolver.memoryCachedBitmap(track.path, primary)?.let { return it }
            val persisted = AudioArtworkPersistence.existingPath(appContext, track.path) ?: return null
            return withTimeoutOrNull(750L) {
                AudioArtworkResolver.resolveBitmap(appContext, track.path, persisted)
            }
        }
        return loadArtworkBitmapForTrack(track)
    }

    fun primaryArtworkPathFor(track: LibraryTrack): String = primaryArtworkPath(track)

    fun fallbackArtworkPathFor(track: LibraryTrack): String? = fallbackArtworkPath(track)

    private fun loadPlaylists() {
        viewModelScope.launch {
            _playlists.value = buildPlaylistSummaries()
        }
    }

    private fun buildPlaylistSummaries(): List<LibraryPlaylist> {
        val regular = PlaylistManager.getNamedPlaylists(appContext, PlaylistCategory.AUDIO).map { playlist ->
            val refs = PlaylistManager.getNamedPlaylistTracks(appContext, PlaylistCategory.AUDIO, playlist.id)
            val tracks = refs.mapIndexed { index, ref -> playlistRefToTrack(ref, index) }
            val count = appContext.resources.getQuantityString(R.plurals.playlist_item_count, tracks.size, tracks.size)
            val first = tracks.firstOrNull()?.title.orEmpty()
            LibraryPlaylist(playlist.name, listOf(count, first).filter { it.isNotBlank() }.joinToString(" • "), tracks)
        }
        val partyRefs = PlaylistManager.getBlazePartyPlaylist(appContext)
        val partyTracks = partyRefs.mapIndexed { index, ref -> playlistRefToTrack(ref, index) }
        val party = LibraryPlaylist("Blaze Party", "${partyTracks.size} titres", partyTracks, isParty = true)
        return regular + party
    }

    private fun playlistRefToTrack(ref: PlaylistTrackRef, index: Int): LibraryTrack {
        val name = ref.name.ifBlank { AudioLibraryHeuristics.fileNameFromPath(ref.path) }
        val folderMeta = AudioLibraryHeuristics.folderMetadata(ref.path, name)
        return LibraryTrack(
            id = -abs(ref.path.hashCode()).toLong(),
            title = folderMeta.title,
            artist = folderMeta.artist,
            album = folderMeta.album,
            durationMs = ref.durationMs,
            trackNo = AudioLibraryHeuristics.inferTrackNo(name),
            path = ref.path,
            addedAt = System.currentTimeMillis() / 1000L - index,
            artworkPath = ref.path,
            source = LibraryTrackSource.SNAPSHOT,
            sourceLabel = "Playlist",
            titleFromTag = false,
            albumFromTag = false,
            artistFromTag = false,
            container = AudioLibraryHeuristics.containerFrom(ref.extension, ref.path)
        )
    }

    // -----------------------------------------------------------------
    // Construction de l'état d'écran à partir des titres bruts.
    // -----------------------------------------------------------------

    private fun buildUiState(
        tracks: List<LibraryTrack>,
        selection: Selection,
        playlists: List<LibraryPlaylist>,
        refreshing: Boolean,
        settings: LibraryBehaviorSettings
    ): LibraryUiState {
        val visibleTracks = if (settings.ignoreShort) {
            // Une durée inconnue reste visible le temps de l'analyse asynchrone. Dès que Room reçoit
            // la durée réelle, les fichiers de moins de 30 secondes disparaissent aussi bien en
            // local que via SMB/UPnP. Les fichiers restent indexés pour réapparaître instantanément
            // quand l'option est désactivée.
            tracks.filter { it.durationMs <= 0L || it.durationMs >= 30_000L }
        } else tracks
        val stillInitialLoad = !hasObservedTracksOnce.get() && tracks.isEmpty()
        if (visibleTracks.isEmpty()) {
            return LibraryUiState(
                tab = selection.tab,
                searchQuery = selection.query,
                rows = if (stillInitialLoad) emptyList() else listOf(LibraryRow.Status(appContext.getString(R.string.audio_no_music), "")),
                isRefreshing = refreshing,
                isInitialLoad = stillInitialLoad
            )
        }
        val filtered = filteredTracks(visibleTracks, selection.query, settings.trackOrder)
        val albums = buildAlbums(filtered, settings.trackOrder)
        // La carte album connaît parfois une cover commune alors que certaines lignes de titres
        // n'ont encore qu'un artworkPath vide / égal au chemin audio. Réinjecter ici la cover
        // d'album dans toutes les représentations UI garantit que le clic depuis Titres, Artistes
        // ou le détail Album transmet exactement la pochette affichée par la bibliothèque.
        val albumTrackByPath = albums.asSequence()
            .flatMap { it.tracks.asSequence() }
            .associateBy { it.path }
        val filteredWithArtwork = filtered.map { albumTrackByPath[it.path] ?: it }
        val artists = buildArtists(filteredWithArtwork)
        val openedAlbum = resolveOpenedAlbumForDetail(selection, albums, visibleTracks, settings.trackOrder)

        val fullTrackCount = visibleTracks.distinctBy { it.path }.size
        // Sans recherche, albums/artistes viennent déjà de la liste complète : ne pas refaire
        // les deux regroupements coûteux à chaque émission Room.
        val hasQuery = selection.query.isNotBlank()
        val fullAlbumCount = if (hasQuery) buildAlbums(visibleTracks, settings.trackOrder).size else albums.size
        val fullArtistCount = if (hasQuery) buildArtists(visibleTracks).size else artists.size

        val rows: List<LibraryRow> = when {
            filtered.isEmpty() -> listOf(LibraryRow.Status(appContext.getString(R.string.audio_no_music), "Essaie une autre recherche ou lance un scan."))
            selection.tab == LibraryTab.ALBUMS && openedAlbum != null -> {
                // Le héros d'album et ses actions sont déjà affichés dans le header fixe de
                // l'Activity. Le RecyclerView ne contient donc plus une seconde carte identique.
                albumPlaybackTracks(openedAlbum, settings.trackOrder)
                    .mapIndexed { index, track -> LibraryRow.AlbumTrackItem(track, index + 1) }
            }
            selection.tab == LibraryTab.ALBUMS -> albums.map { LibraryRow.AlbumTile(it) }
            selection.tab == LibraryTab.ARTISTS -> artists.map { LibraryRow.ArtistItem(it) }
            selection.tab == LibraryTab.TITLES -> filteredWithArtwork.take(MAX_RENDER_TRACK_ROWS).mapIndexed { index, track -> LibraryRow.TrackItem(track, index + 1) }
            selection.tab == LibraryTab.PLAYLISTS -> playlists.map { LibraryRow.PlaylistItem(it) }
            else -> emptyList()
        }

        return LibraryUiState(
            tab = selection.tab,
            searchQuery = selection.query,
            rows = rows,
            trackCount = fullTrackCount,
            albumCount = fullAlbumCount,
            artistCount = fullArtistCount,
            openedAlbum = openedAlbum,
            isRefreshing = refreshing,
            isInitialLoad = false
        )
    }

    private fun resolveOpenedAlbumForDetail(
        selection: Selection,
        candidateAlbums: List<LibraryAlbum>,
        sourceTracks: List<LibraryTrack>,
        trackOrder: Boolean
    ): LibraryAlbum? {
        val key = selection.openedAlbumKey ?: return null
        candidateAlbums.firstOrNull { album -> album.key == key || albumDetailKey(album) == key }?.let { return it }
        if (selection.openedAlbumTrackPaths.isNotEmpty()) {
            candidateAlbums.firstOrNull { album -> album.tracks.any { it.path in selection.openedAlbumTrackPaths } }?.let { return it }
            // Fallback robuste : reconstruit le détail depuis les chemins exacts capturés au clic.
            val selectedTracks = sourceTracks.asSequence()
                .filter { it.path in selection.openedAlbumTrackPaths }
                .distinctBy { it.path }
                .toList()
            if (selectedTracks.isNotEmpty()) return buildAlbumFromTracks("selected:$key", selectedTracks, trackOrder)
        }
        return null
    }

    private fun albumDetailKey(album: LibraryAlbum): String = album.key.ifBlank {
        "${AudioLibraryHeuristics.normalize(album.artist)}|${AudioLibraryHeuristics.normalize(album.title)}"
    }

    private fun albumPlaybackTracks(album: LibraryAlbum, trackOrder: Boolean): List<LibraryTrack> =
        AudioLibraryHeuristics.sortAlbumTracks(album.tracks, trackOrder)

    private fun filteredTracks(input: List<LibraryTrack>, query: String, trackOrder: Boolean): List<LibraryTrack> {
        val q = AudioLibraryHeuristics.normalize(query)
        val base = if (q.isBlank()) input else input.filter { track ->
            listOf(
                track.title,
                track.artist,
                track.album,
                AudioLibraryHeuristics.albumFolderNameFromPath(track.path),
                AudioLibraryHeuristics.artistFolderNameFromPath(track.path),
                AudioLibraryHeuristics.fileNameFromPath(track.path)
            ).any { AudioLibraryHeuristics.normalize(it).contains(q) }
        }
        return sortTracksByArtist(base, trackOrder)
    }

    private fun sortTracksByArtist(input: List<LibraryTrack>, trackOrder: Boolean): List<LibraryTrack> {
        val base = compareBy<LibraryTrack> {
            AudioLibraryHeuristics.normalizeArtistSort(AudioLibraryHeuristics.artistFolderNameFromPath(it.path))
        }.thenBy {
            AudioLibraryHeuristics.normalize(AudioLibraryHeuristics.albumFolderNameFromPath(it.path))
        }
        return if (trackOrder) {
            input.sortedWith(
                base.thenBy { AudioLibraryHeuristics.discNumberFromPath(it.path) }
                    .thenBy { AudioLibraryHeuristics.normalizedTrackNo(it.trackNo) }
                    .thenBy { AudioLibraryHeuristics.normalize(it.title) }
            )
        } else {
            input.sortedWith(base.thenBy { AudioLibraryHeuristics.normalize(it.title) })
        }
    }

    private fun buildAlbums(tracks: List<LibraryTrack>, trackOrder: Boolean): List<LibraryAlbum> {
        val albums = AudioLibraryHeuristics.canonicalLibraryTracks(appContext, tracks)
            .asSequence()
            .groupBy { AudioLibraryHeuristics.albumKey(it) }
            .map { (key, albumTracks) -> buildAlbumFromTracks(key, albumTracks, trackOrder) }

        // Ordre unique de la grille : dossier artiste A→Z, puis dossier album A→Z.
        return albums.sortedWith(
            compareBy<LibraryAlbum> { albumArtistSortKey(it) }
                .thenBy { albumTitleSortKey(it) }
                .thenBy { it.key }
        )
    }

    private fun buildAlbumFromTracks(key: String, albumTracks: List<LibraryTrack>, trackOrder: Boolean): LibraryAlbum {
        val sorted = AudioLibraryHeuristics.sortAlbumTracks(albumTracks, trackOrder)
        val albumArtwork = AudioLibraryHeuristics.bestArtworkPath(sorted)
        val tracksWithAlbumArtwork = if (AudioLibraryHeuristics.isImagePath(albumArtwork)) {
            sorted.map { track ->
                if (AudioLibraryHeuristics.isImagePath(track.artworkPath)) track
                else track.copy(artworkPath = albumArtwork)
            }
        } else sorted
        return LibraryAlbum(
            key = key,
            title = AudioLibraryHeuristics.bestAlbumTitle(sorted),
            artist = AudioLibraryHeuristics.bestAlbumArtist(sorted),
            tracks = tracksWithAlbumArtwork,
            artworkPath = albumArtwork,
            addedAt = sorted.maxOfOrNull { it.addedAt } ?: 0L
        )
    }

    private fun albumTitleSortKey(album: LibraryAlbum): String = AudioLibraryHeuristics.normalize(album.title)

    private fun albumArtistSortKey(album: LibraryAlbum): String =
        AudioLibraryHeuristics.normalizeArtistSort(album.artist)

    private fun buildArtists(tracks: List<LibraryTrack>): List<LibraryArtist> {
        val buckets = linkedMapOf<String, MutableList<LibraryTrack>>()
        tracks.forEach { track ->
            val folderArtist = AudioLibraryHeuristics.artistFolderNameFromPath(track.path)
            if (folderArtist.isNotBlank()) buckets.getOrPut(folderArtist) { mutableListOf() }.add(track)
        }
        return buckets.map { (name, artistTracks) ->
            LibraryArtist(
                name = name,
                tracks = artistTracks.distinctBy { it.path },
                albums = artistTracks.map { AudioLibraryHeuristics.albumKey(it) }.distinct().size
            )
        }.sortedWith(compareBy { AudioLibraryHeuristics.normalizeArtistSort(it.name) })
    }

    // -----------------------------------------------------------------
    // Enrichissement des pochettes uniquement.
    // -----------------------------------------------------------------

    private fun needsArtworkExtraction(track: LibraryTrack): Boolean {
        if (track.path.isBlank()) return false
        if (AudioArtworkPersistence.existingPath(appContext, track.path) != null) return false
        val primary = primaryArtworkPath(track)
        if (ThumbnailUtils.getMemoryCachedAudioArtworkBitmapNoIo(primary) != null) return false
        if (ThumbnailUtils.getCachedAudioArtworkBitmapNoFolderProbe(appContext, primary) != null) return false
        val fallback = fallbackArtworkPath(track)
        if (fallback != null) {
            if (ThumbnailUtils.getMemoryCachedAudioArtworkBitmapNoIo(fallback) != null) return false
            if (ThumbnailUtils.getCachedAudioArtworkBitmapNoFolderProbe(appContext, fallback) != null) return false
        }
        return true
    }

    private fun preferredFolderArtworkPath(track: LibraryTrack, allowFastLocalProbe: Boolean): String? {
        val indexedArtworkName = AudioLibraryHeuristics.fileNameFromPath(track.artworkPath)
        if (AudioLibraryHeuristics.isImagePath(track.artworkPath) &&
            AudioLibraryHeuristics.isPreferredCoverName(indexedArtworkName)
        ) return track.artworkPath
        if (allowFastLocalProbe && track.source != LibraryTrackSource.NETWORK) {
            ThumbnailUtils.fastPreferredFolderCoverPathForAudioPath(track.path)?.let { return it }
        }
        return null
    }

    private fun primaryArtworkPath(track: LibraryTrack): String =
        preferredFolderArtworkPath(track, allowFastLocalProbe = true)
            ?: track.path.takeIf { it.isNotBlank() }
            ?: track.artworkPath

    private fun fallbackArtworkPath(track: LibraryTrack): String? {
        val primary = primaryArtworkPath(track)
        return track.path.takeIf { it.isNotBlank() && it != primary }
    }

    private suspend fun loadArtworkBitmapForTrack(track: LibraryTrack): android.graphics.Bitmap? {
        val indexedCover = preferredFolderArtworkPath(track, allowFastLocalProbe = true)
        val timeoutMs = when {
            track.path.startsWith("smb://", true) || indexedCover?.startsWith("smb://", true) == true -> 9_000L
            track.path.startsWith("http://", true) || track.path.startsWith("https://", true) -> 5_000L
            else -> 6_000L
        }
        return withTimeoutOrNull(timeoutMs) {
            AudioArtworkResolver.resolveBitmap(appContext, track.path, indexedCover)
        }
    }
}
