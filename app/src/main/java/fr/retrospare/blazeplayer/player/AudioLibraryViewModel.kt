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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.math.abs

/** Onglets réellement accessibles depuis la barre de la bibliothèque. L'ancien `Tab.HOME` a été
 *  retiré : il n'était jamais atteint (onCreate écrasait toujours sa valeur par défaut avant le
 *  premier rendu), c'était un enum mort. */
enum class LibraryTab { ALBUMS, ARTISTS, TITLES, PLAYLISTS }

/** Onglet interne de la page dédiée à un artiste. */
enum class ArtistDetailTab { ALBUMS, TITLES }

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
    val openedArtist: LibraryArtist? = null,
    val artistDetailTab: ArtistDetailTab = ArtistDetailTab.ALBUMS,
    val isRefreshing: Boolean = false,
    /** true tant que le snapshot mémoire n'a pas encore été restauré ou initialisé. */
    val isInitialLoad: Boolean = true
)

@HiltViewModel
class AudioLibraryViewModel @Inject constructor(
    private val repository: AudioLibraryRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    companion object {
        private const val MAX_RENDER_TRACK_ROWS = 2000
    }

    private val _tab = MutableStateFlow(LibraryTab.ALBUMS)
    private val _searchQuery = MutableStateFlow("")
    private val _openedAlbumKey = MutableStateFlow<String?>(null)
    private val _openedAlbumTrackPaths = MutableStateFlow<Set<String>>(emptySet())
    private val _openedArtistName = MutableStateFlow<String?>(null)
    private val _artistDetailTab = MutableStateFlow(ArtistDetailTab.ALBUMS)
    private val _isRefreshing = MutableStateFlow(false)
    private val _playlists = MutableStateFlow<List<LibraryPlaylist>>(emptyList())
    private val _librarySettings = MutableStateFlow(readLibrarySettings())
    val refreshState: StateFlow<Boolean> get() = _isRefreshing

    private val librarySnapshotFlow: StateFlow<AudioLibrarySnapshot> =
        repository.observeSnapshot()

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
            scheduleEnrichment(librarySnapshotFlow.value.tracks)
        }
    }

    private data class DetailSelection(
        val openedAlbumKey: String?,
        val openedAlbumTrackPaths: Set<String>,
        val openedArtistName: String?,
        val artistDetailTab: ArtistDetailTab
    )

    private data class Selection(
        val tab: LibraryTab,
        val query: String,
        val openedAlbumKey: String?,
        val openedAlbumTrackPaths: Set<String>,
        val openedArtistName: String?,
        val artistDetailTab: ArtistDetailTab
    )

    private data class UiInputs(
        val snapshot: AudioLibrarySnapshot,
        val selection: Selection,
        val playlists: List<LibraryPlaylist>,
        val refreshing: Boolean,
        val settings: LibraryBehaviorSettings
    )

    private val detailSelectionFlow = combine(
        _openedAlbumKey,
        _openedAlbumTrackPaths,
        _openedArtistName,
        _artistDetailTab
    ) { albumKey, albumPaths, artistName, artistTab ->
        DetailSelection(albumKey, albumPaths, artistName, artistTab)
    }

    private val selectionFlow = combine(_tab, _searchQuery, detailSelectionFlow) { tab, query, detail ->
        Selection(
            tab = tab,
            query = query,
            openedAlbumKey = detail.openedAlbumKey,
            openedAlbumTrackPaths = detail.openedAlbumTrackPaths,
            openedArtistName = detail.openedArtistName,
            artistDetailTab = detail.artistDetailTab
        )
    }

    private val renderSnapshotFlow = librarySnapshotFlow.debounce(180L)

    val uiState: StateFlow<LibraryUiState> = combine(
        renderSnapshotFlow, selectionFlow, _playlists, _isRefreshing, _librarySettings
    ) { snapshot, selection, playlists, refreshing, settings ->
        UiInputs(snapshot, selection, playlists, refreshing, settings)
    }
        // Les regroupements albums/artistes et les tris peuvent traiter plusieurs milliers de
        // titres. Ils ne doivent jamais monopoliser le thread UI (cause principale de l'ANR).
        .mapLatest { input ->
            buildUiState(
                input.snapshot,
                input.selection,
                input.playlists,
                input.refreshing,
                input.settings
            )
        }
        .flowOn(AudioLibraryBackgroundDispatchers.compute)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            buildUiState(
                snapshot = librarySnapshotFlow.value,
                selection = Selection(
                    tab = _tab.value,
                    query = _searchQuery.value,
                    openedAlbumKey = _openedAlbumKey.value,
                    openedAlbumTrackPaths = _openedAlbumTrackPaths.value,
                    openedArtistName = _openedArtistName.value,
                    artistDetailTab = _artistDetailTab.value
                ),
                playlists = _playlists.value,
                refreshing = _isRefreshing.value,
                settings = _librarySettings.value
            )
        )

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

    fun openArtistDetail(artist: LibraryArtist) {
        closeAlbumDetail()
        _searchQuery.value = ""
        _openedArtistName.value = artist.name
        _artistDetailTab.value = ArtistDetailTab.ALBUMS
    }

    fun closeArtistDetail() {
        closeAlbumDetail()
        _openedArtistName.value = null
        _artistDetailTab.value = ArtistDetailTab.ALBUMS
        _searchQuery.value = ""
    }

    /** Retour direct à l'accueil principal de la bibliothèque depuis une page album ou artiste. */
    fun returnToLibraryHome() {
        _openedAlbumKey.value = null
        _openedAlbumTrackPaths.value = emptySet()
        _openedArtistName.value = null
        _artistDetailTab.value = ArtistDetailTab.ALBUMS
        _searchQuery.value = ""
        _tab.value = LibraryTab.ALBUMS
    }

    fun setArtistDetailTab(tab: ArtistDetailTab) {
        closeAlbumDetail()
        _artistDetailTab.value = tab
    }

    fun isArtistDetailOpen(): Boolean = _openedArtistName.value != null

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
                ThumbnailUtils.invalidateAudioFolderCoverLookups()
                AudioArtworkResolver.invalidateIndexedPaths()
                tracksToEnrich = repository.refresh(
                    appContext,
                    manual,
                    isPlaybackCritical
                ).activeTracks
                AudioProSettings.consumeLibraryRefreshPending(appContext)
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
        // Le repository possède maintenant l'unique pipeline album par album. Il continue pendant
        // les accalmies d'un long scan et évite les doubles ouvertures de fichiers.
        repository.requestHydration()
    }

    /** Résout et charge la pochette d'un titre pour l'affichage d'une ligne visible (bind), en
     *  réutilisant les caches déjà chauds. Public : appelé depuis l'Adapter, pas seulement depuis
     *  l'enrichissement en arrière-plan. */
    suspend fun loadArtworkForBinding(track: LibraryTrack): android.graphics.Bitmap? {
        // Un bind RecyclerView ne doit jamais rouvrir un MP3/FLAC pendant la lecture ni pendant
        // un scan. Il peut uniquement exploiter la RAM ou le JPEG/WebP déjà persisté. L'extraction
        // embarquée reste l'unique responsabilité du pipeline d'hydratation du repository.
        if (_isRefreshing.value || AudioLibraryWorkState.isPlaybackProtected()) {
            val explicit = preferredFolderArtworkPath(
                track,
                allowFastLocalProbe = false
            )
            val primary = explicit ?: primaryArtworkPath(track)
            AudioArtworkResolver.memoryCachedBitmap(
                track.path,
                primary
            )?.let { return it }

            return withContext(AudioLibraryBackgroundDispatchers.io) {
                AudioArtworkResolver.cachedBitmap(
                    appContext,
                    track.path,
                    explicit
                )
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

    /**
     * Les index préparés sont normalement complets. Ces fonctions empêchent néanmoins toute perte
     * d'élément si l'Activity reçoit encore un snapshot créé par une ancienne version pendant une
     * hydratation : les clés manquantes sont réintégrées et retriées immédiatement.
     */
    private fun completePreparedAlbums(
        snapshot: AudioLibrarySnapshot
    ): List<LibraryAlbum> {
        if (snapshot.albumsByKey.isEmpty()) return emptyList()

        val orderIsComplete =
            snapshot.albumKeysSorted.size == snapshot.albumsByKey.size &&
                snapshot.albumKeysSorted.distinct().size == snapshot.albumKeysSorted.size &&
                snapshot.albumKeysSorted.all(snapshot.albumsByKey::containsKey)

        return if (orderIsComplete) {
            snapshot.albumKeysSorted.mapNotNull(snapshot.albumsByKey::get)
        } else {
            snapshot.albumsByKey.values.sortedWith(
                compareBy<LibraryAlbum> {
                    AudioLibraryHeuristics.normalizeArtistSort(it.artist)
                }.thenBy {
                    AudioLibraryHeuristics.normalize(it.title)
                }.thenBy { it.key }
            )
        }
    }

    private fun completePreparedArtists(
        snapshot: AudioLibrarySnapshot
    ): List<LibraryArtist> {
        if (snapshot.artistsByName.isEmpty()) return emptyList()

        val orderIsComplete =
            snapshot.artistNamesSorted.size == snapshot.artistsByName.size &&
                snapshot.artistNamesSorted.distinct().size == snapshot.artistNamesSorted.size &&
                snapshot.artistNamesSorted.all(snapshot.artistsByName::containsKey)

        return if (orderIsComplete) {
            snapshot.artistNamesSorted.mapNotNull(snapshot.artistsByName::get)
        } else {
            snapshot.artistsByName.values.sortedBy {
                AudioLibraryHeuristics.normalizeArtistSort(it.name)
            }
        }
    }

    private fun buildUiState(
        snapshot: AudioLibrarySnapshot,
        selection: Selection,
        playlists: List<LibraryPlaylist>,
        refreshing: Boolean,
        settings: LibraryBehaviorSettings
    ): LibraryUiState {
        val tracks = snapshot.tracks
        val visibleTracks = if (settings.ignoreShort) {
            // Une durée inconnue reste visible le temps de l'analyse asynchrone. Dès que le snapshot
            // reçoit la durée réelle, les fichiers de moins de 30 secondes disparaissent aussi bien en
            // local que via SMB/UPnP. Les fichiers restent indexés pour réapparaître instantanément
            // quand l'option est désactivée.
            tracks.filter { it.durationMs <= 0L || it.durationMs >= 30_000L }
        } else tracks
        val stillInitialLoad = !snapshot.ready
        if (visibleTracks.isEmpty() && selection.tab != LibraryTab.PLAYLISTS) {
            return LibraryUiState(
                tab = selection.tab,
                searchQuery = selection.query,
                rows = if (stillInitialLoad) emptyList() else listOf(
                    LibraryRow.Status(appContext.getString(R.string.audio_no_music), "")
                ),
                isRefreshing = refreshing,
                isInitialLoad = stillInitialLoad
            )
        }
        val hasQuery = selection.query.isNotBlank()
        val filtered = when {
            !hasQuery && selection.tab != LibraryTab.TITLES -> visibleTracks
            !hasQuery && selection.tab == LibraryTab.TITLES && !settings.ignoreShort -> {
                val orderedPaths = if (settings.trackOrder) {
                    snapshot.trackPathsByTrackOrder
                } else {
                    snapshot.trackPathsByTitleOrder
                }
                orderedPaths.mapNotNull(snapshot.tracksByPath::get)
            }
            else -> filteredTracks(visibleTracks, selection.query, settings.trackOrder)
        }
        val canUsePreparedModels = !settings.ignoreShort && !hasQuery

        // Le snapshot contient déjà les albums/artistes finalisés et triés. Chaque vue ne
        // matérialise que le modèle dont elle a réellement besoin.
        val albums = when {
            selection.openedArtistName != null -> emptyList()
            selection.tab != LibraryTab.ALBUMS -> emptyList()
            canUsePreparedModels -> completePreparedAlbums(snapshot)
            else -> buildAlbums(filtered, settings.trackOrder)
        }
        val artists = when {
            selection.openedArtistName != null -> emptyList()
            selection.tab != LibraryTab.ARTISTS -> emptyList()
            canUsePreparedModels -> completePreparedArtists(snapshot)
            else -> buildArtists(filtered)
        }
        val openedArtist = resolveOpenedArtist(selection.openedArtistName, snapshot, visibleTracks)
        val artistTracks = openedArtist?.tracks.orEmpty().let { source ->
            if (selection.query.isBlank()) sortTracksByArtist(source, settings.trackOrder)
            else filteredTracks(source, selection.query, settings.trackOrder)
        }
        val artistAlbums = if (openedArtist != null) buildAlbums(artistTracks, settings.trackOrder) else emptyList()
        val albumCandidates = when {
            openedArtist != null -> artistAlbums
            albums.isNotEmpty() -> albums
            else -> completePreparedAlbums(snapshot)
        }
        val openedAlbum = resolveOpenedAlbumForDetail(
            selection,
            albumCandidates,
            visibleTracks,
            settings.trackOrder
        )

        val fullTrackCount = visibleTracks.size
        val fullAlbumCount = if (!settings.ignoreShort) {
            snapshot.albumsByKey.size
        } else {
            buildAlbums(visibleTracks, settings.trackOrder).size
        }
        val fullArtistCount = if (!settings.ignoreShort) {
            snapshot.artistsByName.size
        } else {
            buildArtists(visibleTracks).size
        }

        val rows: List<LibraryRow> = when {
            openedAlbum != null -> {
                // Le héros d'album et ses actions sont déjà affichés dans le header fixe de
                // l'Activity. Le RecyclerView ne contient donc plus une seconde carte identique.
                albumPlaybackTracks(openedAlbum, settings.trackOrder)
                    .mapIndexed { index, track -> LibraryRow.AlbumTrackItem(track, index + 1) }
            }
            openedArtist != null && selection.artistDetailTab == ArtistDetailTab.ALBUMS -> {
                if (artistAlbums.isEmpty()) listOf(LibraryRow.Status(appContext.getString(R.string.audio_no_music)))
                else artistAlbums.map { LibraryRow.AlbumTile(it) }
            }
            openedArtist != null -> {
                if (artistTracks.isEmpty()) listOf(LibraryRow.Status(appContext.getString(R.string.audio_no_music)))
                else artistTracks.take(MAX_RENDER_TRACK_ROWS)
                    .mapIndexed { index, track -> LibraryRow.AlbumTrackItem(track, index + 1) }
            }
            selection.tab != LibraryTab.PLAYLISTS && filtered.isEmpty() -> listOf(
                LibraryRow.Status(
                    appContext.getString(R.string.audio_no_music),
                    appContext.getString(R.string.audio_library_empty_hint)
                )
            )
            selection.tab == LibraryTab.ALBUMS -> albums.map { LibraryRow.AlbumTile(it) }
            selection.tab == LibraryTab.ARTISTS -> artists.map { LibraryRow.ArtistItem(it) }
            selection.tab == LibraryTab.TITLES -> filtered.take(MAX_RENDER_TRACK_ROWS)
                .mapIndexed { index, track -> LibraryRow.TrackItem(track, index + 1) }
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
            openedArtist = openedArtist,
            artistDetailTab = selection.artistDetailTab,
            isRefreshing = refreshing,
            isInitialLoad = false
        )
    }

    private fun resolveOpenedArtist(
        requestedName: String?,
        snapshot: AudioLibrarySnapshot,
        sourceTracks: List<LibraryTrack>
    ): LibraryArtist? {
        val name = requestedName?.trim().orEmpty()
        if (name.isBlank()) return null
        snapshot.artistsByName[name]?.let { artist ->
            val visiblePaths = sourceTracks.asSequence().map { it.path }.toHashSet()
            return artist.copy(tracks = artist.tracks.filter { it.path in visiblePaths })
        }
        val normalized = AudioLibraryHeuristics.normalize(name)
        snapshot.artistsByName.entries.firstOrNull {
            AudioLibraryHeuristics.normalize(it.key) == normalized
        }?.value?.let { artist ->
            val visiblePaths = sourceTracks.asSequence().map { it.path }.toHashSet()
            return artist.copy(tracks = artist.tracks.filter { it.path in visiblePaths })
        }
        val fallbackTracks = sourceTracks.filter { track ->
            AudioLibraryHeuristics.normalize(AudioLibraryHeuristics.artistFolderNameFromPath(track.path)) == normalized ||
                AudioLibraryHeuristics.normalize(track.artist) == normalized
        }
        if (fallbackTracks.isEmpty()) return null
        return LibraryArtist(
            name = name,
            tracks = fallbackTracks.distinctBy { it.path },
            albums = fallbackTracks.map { AudioLibraryHeuristics.albumKey(it) }.distinct().size
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
        val tracksWithAlbumArtwork = if (AudioLibraryHeuristics.isArtworkReference(albumArtwork)) {
            sorted.map { track ->
                if (AudioLibraryHeuristics.isArtworkReference(track.artworkPath)) track
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

    private fun preferredFolderArtworkPath(track: LibraryTrack, allowFastLocalProbe: Boolean): String? {
        val indexedArtworkName = AudioLibraryHeuristics.fileNameFromPath(track.artworkPath)
        if (
            AudioLibraryHeuristics.isArtworkReference(track.artworkPath) &&
            (
                track.artworkPath.startsWith("http://", true) ||
                    track.artworkPath.startsWith("https://", true) ||
                    AudioLibraryHeuristics.isPreferredCoverName(indexedArtworkName)
            )
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
