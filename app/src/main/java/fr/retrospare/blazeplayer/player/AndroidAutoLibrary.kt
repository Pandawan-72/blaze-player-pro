package fr.retrospare.blazeplayer.player

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.favorites.FavoriteCategory
import fr.retrospare.blazeplayer.favorites.FavoriteFolder
import fr.retrospare.blazeplayer.favorites.FavoritesManager
import fr.retrospare.blazeplayer.playlist.PlaylistCategory
import fr.retrospare.blazeplayer.playlist.PlaylistManager
import fr.retrospare.blazeplayer.playlist.NamedPlaylist
import fr.retrospare.blazeplayer.playlist.PlaylistTrackRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Arborescence audio exposée aux clients MediaLibrarySession (Android Auto, Android Automotive,
 * Assistant et MediaBrowser). Elle lit uniquement l'index Room déjà disponible : aucun scan SMB,
 * UPnP ou stockage local n'est déclenché depuis la voiture.
 */
class AndroidAutoLibrary(
    context: Context,
    private val repository: AudioLibraryRepository
) {
    companion object {
        const val ROOT_ID = "blaze:auto:root"
        const val ALBUMS_ID = "blaze:auto:albums"
        const val ARTISTS_ID = "blaze:auto:artists"
        const val TRACKS_ID = "blaze:auto:tracks"
        const val FAVORITES_ID = "blaze:auto:favorites"
        const val RECENT_ID = "blaze:auto:recent"
        const val PLAYLISTS_ID = "blaze:auto:playlists"
        const val QUEUE_ID = "blaze:auto:queue"
        const val LOCKED_ID = "blaze:auto:locked"

        private const val ALBUM_PREFIX = "blaze:auto:album:"
        private const val ARTIST_PREFIX = "blaze:auto:artist:"
        private const val TRACK_PREFIX = "blaze:auto:track:"
        private const val FAVORITE_PREFIX = "blaze:auto:favorite:"
        private const val PLAYLIST_PREFIX = "blaze:auto:playlist:"
        private const val CACHE_TTL_MS = 5_000L
        // Les listes Android Auto transitent par Binder. Une vignette compacte par élément évite
        // de dépasser la limite de transaction lorsque plusieurs albums sont retournés.
        private const val MAX_ARTWORK_BYTES = 14_000
        private const val MAX_ARTWORK_DIMENSION = 160
    }

    private val appContext = context.applicationContext
    private val snapshotMutex = Mutex()
    @Volatile private var cachedTracks: List<LibraryTrack> = emptyList()
    @Volatile private var cachedAtElapsedMs: Long = 0L
    @Volatile private var carSessionActive: Boolean = false
    @Volatile private var pendingInvalidation: Boolean = false
    private val artworkCache = object : LruCache<String, ByteArray>(96) {}

    fun rootItem(): MediaItem = browsableItem(
        mediaId = ROOT_ID,
        title = appContext.getString(R.string.android_auto_root_title),
        subtitle = appContext.getString(R.string.android_auto_root_subtitle),
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    )

    fun lockedItem(): MediaItem = statusItem(
        mediaId = LOCKED_ID,
        title = appContext.getString(R.string.android_auto_premium_required),
        subtitle = appContext.getString(R.string.android_auto_premium_required_subtitle)
    )

    suspend fun prime() {
        snapshot(force = true)
    }

    /**
     * Fige l'instantané tant qu'Android Auto parcourt une liste. Recharger Room entre deux pages
     * peut déplacer les éléments pendant un scan progressif et fait revenir le DHU en haut.
     * Les changements reçus pendant la connexion sont appliqués au prochain raccordement.
     */
    fun setCarSessionActive(active: Boolean) {
        carSessionActive = active
        if (!active && pendingInvalidation) {
            pendingInvalidation = false
            cachedAtElapsedMs = 0L
            artworkCache.evictAll()
        }
    }

    fun invalidate() {
        if (carSessionActive && cachedTracks.isNotEmpty()) {
            pendingInvalidation = true
            return
        }
        pendingInvalidation = false
        cachedAtElapsedMs = 0L
        artworkCache.evictAll()
    }

    suspend fun children(parentId: String): List<MediaItem> {
        val tracks = snapshot()
        return when {
            parentId == ROOT_ID -> rootChildren()
            parentId == ALBUMS_ID -> albums(tracks).map { albumItem(it) }
            parentId.startsWith(ALBUM_PREFIX) -> {
                val key = decode(parentId.removePrefix(ALBUM_PREFIX)) ?: return emptyList()
                albums(tracks).firstOrNull { it.key == key }?.tracks.orEmpty().map { trackItem(it, false) }
            }
            parentId == ARTISTS_ID -> artists(tracks).map { artistItem(it) }
            parentId.startsWith(ARTIST_PREFIX) -> {
                val name = decode(parentId.removePrefix(ARTIST_PREFIX)) ?: return emptyList()
                artists(tracks).firstOrNull { it.name == name }?.tracks.orEmpty()
                    .sortedWith(trackComparator())
                    .map { trackItem(it, false) }
            }
            parentId == TRACKS_ID -> tracks.sortedWith(trackComparator()).map { trackItem(it, false) }
            parentId == FAVORITES_ID -> favoriteFolders().map { favoriteItem(it, tracks) }
            parentId.startsWith(FAVORITE_PREFIX) -> {
                val favorite = decodeFavorite(parentId.removePrefix(FAVORITE_PREFIX)) ?: return emptyList()
                tracks.filter { belongsToFavorite(it, favorite) }
                    .sortedWith(trackComparator())
                    .map { trackItem(it, false) }
            }
            parentId == RECENT_ID -> recentTracks(tracks).map { trackItem(it, false) }
            parentId == PLAYLISTS_ID -> playlistFolders(tracks)
            parentId == QUEUE_ID -> emptyList() // Fourni par BlazePlayerService depuis la timeline courante.
            parentId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = decode(parentId.removePrefix(PLAYLIST_PREFIX)) ?: return emptyList()
                playlistTracks(playlistId, tracks).map { trackItem(it, false) }
            }
            else -> emptyList()
        }
    }

    suspend fun item(mediaId: String): MediaItem? {
        return when {
            mediaId == ROOT_ID -> rootItem()
            mediaId == ALBUMS_ID -> categoryItem(ALBUMS_ID, R.string.android_auto_albums)
            mediaId == ARTISTS_ID -> categoryItem(ARTISTS_ID, R.string.android_auto_artists)
            mediaId == TRACKS_ID -> categoryItem(TRACKS_ID, R.string.android_auto_tracks)
            mediaId == FAVORITES_ID -> categoryItem(FAVORITES_ID, R.string.android_auto_favorites)
            mediaId == RECENT_ID -> categoryItem(RECENT_ID, R.string.android_auto_recent)
            mediaId == PLAYLISTS_ID -> categoryItem(PLAYLISTS_ID, R.string.android_auto_playlists)
            mediaId == QUEUE_ID -> categoryItem(QUEUE_ID, R.string.queue_short)
            mediaId == LOCKED_ID -> lockedItem()
            mediaId.startsWith(TRACK_PREFIX) -> resolveTrack(mediaId)?.let { trackItem(it, true) }
            mediaId.startsWith(ALBUM_PREFIX) -> {
                val key = decode(mediaId.removePrefix(ALBUM_PREFIX)) ?: return null
                albums(snapshot()).firstOrNull { it.key == key }?.let { albumItem(it) }
            }
            mediaId.startsWith(ARTIST_PREFIX) -> {
                val name = decode(mediaId.removePrefix(ARTIST_PREFIX)) ?: return null
                artists(snapshot()).firstOrNull { it.name == name }?.let { artistItem(it) }
            }
            mediaId.startsWith(FAVORITE_PREFIX) -> {
                val favorite = decodeFavorite(mediaId.removePrefix(FAVORITE_PREFIX)) ?: return null
                favoriteItem(favorite, snapshot())
            }
            mediaId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = decode(mediaId.removePrefix(PLAYLIST_PREFIX)) ?: return null
                val playlist = PlaylistManager.getNamedPlaylist(appContext, PlaylistCategory.AUDIO, playlistId) ?: return null
                playlistFolder(playlist, playlistTracks(playlistId, snapshot()))
            }
            else -> null
        }
    }

    suspend fun resolvePlayable(requested: MediaItem): MediaItem? {
        // Les contrôleurs internes de Blaze Player envoient déjà des MediaItem complets. Il ne
        // faut pas les reconstruire ni perdre leurs extras/pochettes.
        if (requested.localConfiguration != null && originalPath(requested).isNotBlank()) {
            return requested
        }
        val track = resolveTrack(requested.mediaId) ?: return null
        return trackItem(track, includeArtwork = true)
    }

    /**
     * Résout une demande de lecture externe. Les commandes vocales Android Auto/Assistant arrivent
     * souvent avec uniquement RequestMetadata.searchQuery et aucun URI jouable. Dans ce cas, la
     * requête est développée en une file de résultats de la bibliothèque, sans déclencher de scan.
     */
    suspend fun resolveRequestedItems(requested: List<MediaItem>): List<MediaItem> {
        val query = requested.asSequence()
            .mapNotNull { it.requestMetadata.searchQuery?.toString()?.trim() }
            .firstOrNull { it.isNotBlank() }
        if (!query.isNullOrBlank()) {
            return search(query)
        }
        return requested.mapNotNull { resolvePlayable(it) }
    }

    suspend fun search(query: String): List<MediaItem> {
        val normalizedQuery = AudioLibraryHeuristics.normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()
        return snapshot()
            .asSequence()
            .filter { track ->
                sequenceOf(
                    track.title,
                    track.artist,
                    track.album,
                    AudioLibraryHeuristics.fileNameFromPath(track.path)
                ).any { AudioLibraryHeuristics.normalize(it).contains(normalizedQuery) }
            }
            .sortedWith(
                compareByDescending<LibraryTrack> {
                    AudioLibraryHeuristics.normalize(it.title) == normalizedQuery
                }.thenByDescending {
                    AudioLibraryHeuristics.normalize(it.album) == normalizedQuery
                }.thenByDescending {
                    AudioLibraryHeuristics.normalize(it.artist) == normalizedQuery
                }.then(trackComparator())
            )
            .take(200)
            .map { trackItem(it, false) }
            .toList()
    }

    suspend fun searchCount(query: String): Int = search(query).size

    private suspend fun resolveTrack(mediaId: String): LibraryTrack? {
        val path = when {
            mediaId.startsWith(TRACK_PREFIX) -> decode(mediaId.removePrefix(TRACK_PREFIX))
            // Compatibilité : certains clients peuvent conserver le mediaId original du morceau.
            AudioRepository.isSupportedAudioPath(mediaId) -> mediaId
            else -> null
        } ?: return null
        val key = AudioLibraryHeuristics.canonicalPathKey(path)
        return snapshot().firstOrNull { AudioLibraryHeuristics.canonicalPathKey(it.path) == key }
            ?: AudioPlaybackHistory.load(appContext).firstOrNull { it.path == path }?.let { entry ->
                LibraryTrack(
                    id = path.hashCode().toLong(),
                    title = entry.title,
                    artist = entry.artist,
                    album = entry.album,
                    durationMs = entry.durationMs,
                    trackNo = entry.trackNumber,
                    path = entry.path,
                    addedAt = entry.playedAtMs,
                    container = entry.extension,
                    source = if (AudioLibraryHeuristics.isNetworkPath(path)) LibraryTrackSource.NETWORK else LibraryTrackSource.LOCAL
                )
            }
    }

    private fun rootChildren(): List<MediaItem> = listOf(
        categoryItem(QUEUE_ID, R.string.queue_short),
        categoryItem(ALBUMS_ID, R.string.android_auto_albums),
        categoryItem(ARTISTS_ID, R.string.android_auto_artists),
        categoryItem(TRACKS_ID, R.string.android_auto_tracks),
        categoryItem(FAVORITES_ID, R.string.android_auto_favorites),
        categoryItem(RECENT_ID, R.string.android_auto_recent),
        categoryItem(PLAYLISTS_ID, R.string.android_auto_playlists)
    )

    private fun categoryItem(mediaId: String, titleRes: Int): MediaItem = browsableItem(
        mediaId = mediaId,
        title = appContext.getString(titleRes),
        mediaType = when (mediaId) {
            ALBUMS_ID -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
            ARTISTS_ID -> MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
            PLAYLISTS_ID, QUEUE_ID -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        }
    )

    private fun albums(tracks: List<LibraryTrack>): List<LibraryAlbum> {
        val byTrackNumber = AudioProSettings.read(appContext).trackOrder
        return tracks.groupBy { AudioLibraryHeuristics.albumKey(it) }
            .map { (key, albumTracks) ->
                val sorted = AudioLibraryHeuristics.sortAlbumTracks(albumTracks, byTrackNumber)
                val artwork = AudioLibraryHeuristics.bestArtworkPath(sorted)
                LibraryAlbum(
                    key = key,
                    title = AudioLibraryHeuristics.bestAlbumTitle(sorted),
                    artist = AudioLibraryHeuristics.bestAlbumArtist(sorted),
                    tracks = sorted.map { track ->
                        if (track.artworkPath.isBlank() && artwork.isNotBlank()) track.copy(artworkPath = artwork) else track
                    },
                    artworkPath = artwork,
                    addedAt = sorted.maxOfOrNull { it.addedAt } ?: 0L
                )
            }
            .sortedWith(
                compareBy<LibraryAlbum> { AudioLibraryHeuristics.normalizeArtistSort(it.artist) }
                    .thenBy { AudioLibraryHeuristics.normalize(it.title) }
                    .thenBy { it.key }
            )
    }

    private fun artists(tracks: List<LibraryTrack>): List<LibraryArtist> {
        val buckets = linkedMapOf<String, MutableList<LibraryTrack>>()
        tracks.forEach { track ->
            val artist = AudioLibraryHeuristics.artistFolderNameFromPath(track.path)
                .ifBlank { track.artist }
                .ifBlank { appContext.getString(R.string.unknown_artist) }
            buckets.getOrPut(artist) { mutableListOf() }.add(track)
        }
        return buckets.map { (name, artistTracks) ->
            LibraryArtist(
                name = name,
                tracks = artistTracks.distinctBy { AudioLibraryHeuristics.canonicalPathKey(it.path) },
                albums = artistTracks.map { AudioLibraryHeuristics.albumKey(it) }.distinct().size
            )
        }.sortedBy { AudioLibraryHeuristics.normalizeArtistSort(it.name) }
    }

    private fun albumItem(album: LibraryAlbum): MediaItem = browsableItem(
        mediaId = ALBUM_PREFIX + encode(album.key),
        title = album.title.ifBlank { appContext.getString(R.string.android_auto_unknown_album) },
        subtitle = album.artist,
        artwork = cachedArtwork(album.tracks.firstOrNull()),
        mediaType = MediaMetadata.MEDIA_TYPE_ALBUM
    )

    private fun artistItem(artist: LibraryArtist): MediaItem = browsableItem(
        mediaId = ARTIST_PREFIX + encode(artist.name),
        title = artist.name,
        subtitle = appContext.getString(R.string.android_auto_artist_subtitle, artist.albums, artist.tracks.size),
        artwork = cachedArtwork(artist.tracks.firstOrNull()),
        mediaType = MediaMetadata.MEDIA_TYPE_ARTIST
    )

    private fun trackItem(track: LibraryTrack, includeArtwork: Boolean): MediaItem {
        val fileName = AudioLibraryHeuristics.fileNameFromPath(track.path).ifBlank { track.title }
        val base = AudioRepository.buildSimpleMediaItem(appContext, track.path, fileName, track.artworkPath)
        val metadata = base.mediaMetadata.buildUpon()
            .setTitle(track.title.ifBlank { fileName.substringBeforeLast('.', fileName) })
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply {
                if (includeArtwork) {
                    cachedArtwork(track)?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                }
            }
            .build()
        return base.buildUpon()
            .setMediaId(TRACK_PREFIX + encode(track.path))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun favoriteFolders(): List<FavoriteFolder> =
        FavoritesManager.getFavorites(appContext, FavoriteCategory.AUDIO)

    private fun favoriteItem(favorite: FavoriteFolder, tracks: List<LibraryTrack>): MediaItem {
        val matching = tracks.filter { belongsToFavorite(it, favorite) }
        return browsableItem(
            mediaId = FAVORITE_PREFIX + encodeFavorite(favorite),
            title = favorite.name.ifBlank { favorite.path.substringAfterLast('/') },
            subtitle = appContext.getString(R.string.android_auto_track_count, matching.size),
            artwork = cachedArtwork(matching.firstOrNull()),
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        )
    }

    private fun belongsToFavorite(track: LibraryTrack, favorite: FavoriteFolder): Boolean {
        return if (favorite.shareId != null || AudioLibraryHeuristics.isNetworkPath(favorite.path)) {
            val trackPath = AudioLibraryHeuristics.normalizeNetworkPath(track.path)
            val folderPath = AudioLibraryHeuristics.normalizeNetworkPath(favorite.path)
            folderPath.isNotBlank() && (trackPath == folderPath || trackPath.startsWith("$folderPath/") || trackPath.contains("/$folderPath/"))
        } else {
            val trackPath = track.path.removePrefix("file://").trimEnd('/')
            val folderPath = favorite.path.removePrefix("file://").trimEnd('/')
            folderPath.isNotBlank() && (trackPath == folderPath || trackPath.startsWith("$folderPath/"))
        }
    }

    private fun recentTracks(library: List<LibraryTrack>): List<LibraryTrack> {
        val indexed = library.associateBy { AudioLibraryHeuristics.canonicalPathKey(it.path) }
        return AudioPlaybackHistory.load(appContext).map { entry ->
            indexed[AudioLibraryHeuristics.canonicalPathKey(entry.path)] ?: LibraryTrack(
                id = entry.path.hashCode().toLong(),
                title = entry.title,
                artist = entry.artist,
                album = entry.album,
                durationMs = entry.durationMs,
                trackNo = entry.trackNumber,
                path = entry.path,
                addedAt = entry.playedAtMs,
                container = entry.extension,
                source = if (AudioLibraryHeuristics.isNetworkPath(entry.path)) LibraryTrackSource.NETWORK else LibraryTrackSource.LOCAL
            )
        }.distinctBy { AudioLibraryHeuristics.canonicalPathKey(it.path) }
    }

    private fun playlistFolders(library: List<LibraryTrack>): List<MediaItem> =
        PlaylistManager.getNamedPlaylists(appContext, PlaylistCategory.AUDIO).map { playlist ->
            playlistFolder(playlist, playlistTracks(playlist.id, library))
        }

    private fun playlistFolder(playlist: NamedPlaylist, tracks: List<LibraryTrack>): MediaItem = browsableItem(
        mediaId = PLAYLIST_PREFIX + encode(playlist.id),
        title = playlist.name,
        subtitle = appContext.getString(R.string.android_auto_track_count, tracks.size),
        artwork = cachedArtwork(tracks.firstOrNull()),
        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST
    )

    private fun playlistTracks(playlistId: String, library: List<LibraryTrack>): List<LibraryTrack> {
        val indexed = library.associateBy { AudioLibraryHeuristics.canonicalPathKey(it.path) }
        return PlaylistManager.getNamedPlaylistTracks(appContext, PlaylistCategory.AUDIO, playlistId)
            .map { ref -> indexed[AudioLibraryHeuristics.canonicalPathKey(ref.path)] ?: ref.toTrack() }
            .distinctBy { AudioLibraryHeuristics.canonicalPathKey(it.path) }
    }

    private fun PlaylistTrackRef.toTrack(): LibraryTrack = LibraryTrack(
        id = path.hashCode().toLong(),
        title = title.ifBlank { name.substringBeforeLast('.', name) },
        artist = artist,
        album = album,
        durationMs = durationMs,
        trackNo = trackNumber,
        path = path,
        addedAt = 0L,
        container = extension,
        sizeBytes = sizeBytes,
        source = if (AudioLibraryHeuristics.isNetworkPath(path)) LibraryTrackSource.NETWORK else LibraryTrackSource.LOCAL
    )

    private fun trackComparator(): Comparator<LibraryTrack> {
        val byTrackNumber = AudioProSettings.read(appContext).trackOrder
        val base = compareBy<LibraryTrack> {
            AudioLibraryHeuristics.normalizeArtistSort(it.artist.ifBlank { AudioLibraryHeuristics.artistFolderNameFromPath(it.path) })
        }.thenBy {
            AudioLibraryHeuristics.normalize(it.album.ifBlank { AudioLibraryHeuristics.albumFolderNameFromPath(it.path) })
        }
        return if (byTrackNumber) {
            base.thenBy { AudioLibraryHeuristics.discNumberFromPath(it.path) }
                .thenBy { AudioLibraryHeuristics.normalizedTrackNo(it.trackNo) }
                .thenBy { AudioLibraryHeuristics.normalize(it.title) }
        } else {
            base.thenBy { AudioLibraryHeuristics.normalize(it.title) }
        }
    }

    private fun browsableItem(
        mediaId: String,
        title: String,
        subtitle: String = "",
        artwork: ByteArray? = null,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .apply { artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build()
    }

    private fun statusItem(mediaId: String, title: String, subtitle: String): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun cachedArtwork(track: LibraryTrack?): ByteArray? {
        track ?: return null
        val cacheKey = AudioLibraryHeuristics.canonicalPathKey(track.artworkPath.ifBlank { track.path })
        artworkCache.get(cacheKey)?.let { return it }
        val compact = runCatching {
            val source = AudioArtworkResolver.cachedJpegBytes(appContext, track.path, track.artworkPath)
                ?.takeIf { it.isNotEmpty() }
                ?: return@runCatching null
            if (source.size <= MAX_ARTWORK_BYTES) return@runCatching source

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > MAX_ARTWORK_DIMENSION * 2 ||
                bounds.outHeight / sample > MAX_ARTWORK_DIMENSION * 2) {
                sample *= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(
                source,
                0,
                source.size,
                BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            ) ?: return@runCatching null
            val scale = minOf(
                1f,
                MAX_ARTWORK_DIMENSION.toFloat() / bitmap.width.coerceAtLeast(1),
                MAX_ARTWORK_DIMENSION.toFloat() / bitmap.height.coerceAtLeast(1)
            )
            val scaled = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap
            if (scaled !== bitmap) bitmap.recycle()

            var quality = 76
            var result: ByteArray
            do {
                val output = ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, output)
                result = output.toByteArray()
                quality -= 10
            } while (result.size > MAX_ARTWORK_BYTES && quality >= 36)
            scaled.recycle()
            result.takeIf { it.isNotEmpty() && it.size <= MAX_ARTWORK_BYTES }
        }.getOrNull()
        if (compact != null && cacheKey.isNotBlank()) artworkCache.put(cacheKey, compact)
        return compact
    }

    private suspend fun snapshot(force: Boolean = false): List<LibraryTrack> {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && cachedTracks.isNotEmpty() &&
            (carSessionActive || now - cachedAtElapsedMs <= CACHE_TTL_MS)) return cachedTracks
        return snapshotMutex.withLock {
            val insideNow = android.os.SystemClock.elapsedRealtime()
            if (!force && cachedTracks.isNotEmpty() &&
                (carSessionActive || insideNow - cachedAtElapsedMs <= CACHE_TTL_MS)) {
                return@withLock cachedTracks
            }
            val loaded = repository.loadLibrarySnapshot(appContext)
            cachedTracks = loaded
            cachedAtElapsedMs = insideNow
            loaded
        }
    }

    private fun originalPath(item: MediaItem): String =
        item.mediaMetadata.extras?.getString("blaze_original_path")
            ?: item.localConfiguration?.uri?.toString().orEmpty()

    private fun encode(value: String): String = Base64.encodeToString(
        value.toByteArray(StandardCharsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    private fun decode(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun encodeFavorite(favorite: FavoriteFolder): String = encode(
        listOf(favorite.path, favorite.name, favorite.shareId.orEmpty(), favorite.shareName.orEmpty()).joinToString("\u0000")
    )

    private fun decodeFavorite(value: String): FavoriteFolder? {
        val parts = decode(value)?.split('\u0000') ?: return null
        if (parts.isEmpty() || parts[0].isBlank()) return null
        return FavoriteFolder(
            path = parts[0],
            name = parts.getOrNull(1).orEmpty(),
            shareId = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
            shareName = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
        )
    }
}

fun <T> List<T>.androidAutoPage(page: Int, pageSize: Int): List<T> {
    if (isEmpty() || page < 0 || pageSize <= 0) return emptyList()
    val from = page.toLong() * pageSize.toLong()
    if (from >= size.toLong()) return emptyList()
    val start = from.toInt()
    val end = (from + pageSize.toLong()).coerceAtMost(size.toLong()).toInt()
    return subList(start, end)
}
