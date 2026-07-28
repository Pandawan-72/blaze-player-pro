package fr.retrospare.blazeplayer.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Origine de la dernière version du snapshot, utile pour le diagnostic sans impacter l'UI. */
enum class AudioLibrarySnapshotOrigin {
    EMPTY,
    DISK_BOOTSTRAP,
    ROOM_BOOTSTRAP,
    MEDIASTORE,
    LOCAL_SCAN,
    NETWORK_SCAN,
    FULL_SCAN,
    METADATA,
    ARTWORK,
    FOLDER_CHANGE
}

/**
 * Bibliothèque complète, immuable et déjà indexée, conservée en mémoire pendant toute la vie du
 * processus. Room n'est plus collecté directement par les écrans : il sert uniquement à restaurer
 * ce snapshot au démarrage et à persister les changements en arrière-plan.
 */
data class AudioLibrarySnapshot(
    val tracks: List<LibraryTrack> = emptyList(),
    val tracksByPath: Map<String, LibraryTrack> = emptyMap(),
    val trackPathsByTrackOrder: List<String> = emptyList(),
    val trackPathsByTitleOrder: List<String> = emptyList(),
    val trackIndexByPath: Map<String, Int> = emptyMap(),
    val trackIndexByCanonicalPath: Map<String, Int> = emptyMap(),
    val albumTracksByKey: Map<String, List<LibraryTrack>> = emptyMap(),
    val artistTracksByName: Map<String, List<LibraryTrack>> = emptyMap(),
    val albumsByKey: Map<String, LibraryAlbum> = emptyMap(),
    val albumKeysSorted: List<String> = emptyList(),
    val artistsByName: Map<String, LibraryArtist> = emptyMap(),
    val artistNamesSorted: List<String> = emptyList(),
    val revision: Long = 0L,
    /** Révision des ajouts/suppressions et regroupements structurels. */
    val structureRevision: Long = 0L,
    /** Révision des durées, bitrates et numéros de piste uniquement. */
    val metadataRevision: Long = 0L,
    /** Révision des chemins de pochettes uniquement. */
    val artworkRevision: Long = 0L,
    val ready: Boolean = false,
    val origin: AudioLibrarySnapshotOrigin = AudioLibrarySnapshotOrigin.EMPTY,
    val createdAtElapsedMs: Long = 0L
)

/**
 * Cache mémoire process-wide partagé par l'écran téléphone, Android Auto et le service Media3.
 * Les patchs de pochette et de durée mettent à jour uniquement les listes concernées au lieu de
 * relire et regrouper toute la table Room.
 */
object AudioLibraryMemoryStore {
    private val revisionCounter = AtomicLong(0L)
    private val structureRevisionCounter = AtomicLong(0L)
    private val metadataRevisionCounter = AtomicLong(0L)
    private val artworkRevisionCounter = AtomicLong(0L)
    private val _snapshot = MutableStateFlow(AudioLibrarySnapshot())
    val snapshot: StateFlow<AudioLibrarySnapshot> = _snapshot.asStateFlow()

    fun current(): AudioLibrarySnapshot = _snapshot.value

    @Synchronized
    fun replace(
        tracks: List<LibraryTrack>,
        origin: AudioLibrarySnapshotOrigin,
        ready: Boolean = true
    ): AudioLibrarySnapshot {
        val unique = tracks.asSequence()
            .filter { it.path.isNotBlank() }
            .distinctBy { canonicalPath(it.path) }
            .toList()
        // Dès qu'une vraie image est connue pour un album, elle est réutilisée par les titres qui
        // n'ont encore qu'un chemin audio. Les vues Titres/Artistes restent ainsi cohérentes avec
        // la tuile Album sans refaire cette projection à chaque rendu.
        val normalized = propagateAlbumArtwork(unique)

        val byPath = LinkedHashMap<String, LibraryTrack>(normalized.size)
        val indexByPath = LinkedHashMap<String, Int>(normalized.size)
        val indexByCanonicalPath = LinkedHashMap<String, Int>(normalized.size)
        val albumBuckets = linkedMapOf<String, MutableList<LibraryTrack>>()
        val artistBuckets = linkedMapOf<String, MutableList<LibraryTrack>>()

        normalized.forEachIndexed { index, track ->
            byPath[track.path] = track
            indexByPath[track.path] = index
            indexByCanonicalPath[canonicalPath(track.path)] = index
            albumBuckets.getOrPut(AudioLibraryHeuristics.albumKey(track)) { mutableListOf() }.add(track)
            val artistName = artistBucketName(track)
            if (artistName.isNotBlank()) {
                artistBuckets.getOrPut(artistName) { mutableListOf() }.add(track)
            }
        }

        val immutableAlbumBuckets = albumBuckets.mapValues { (_, value) -> value.toList() }
        val immutableArtistBuckets = artistBuckets.mapValues { (_, value) -> value.toList() }
        val albumsByKey = immutableAlbumBuckets.mapValues { (key, value) -> buildAlbum(key, value) }
        val artistsByName = immutableArtistBuckets.mapValues { (name, value) -> buildArtist(name, value) }

        return AudioLibrarySnapshot(
            tracks = normalized,
            tracksByPath = byPath,
            trackPathsByTrackOrder = sortedTrackPaths(normalized, byTrackNumber = true),
            trackPathsByTitleOrder = sortedTrackPaths(normalized, byTrackNumber = false),
            trackIndexByPath = indexByPath,
            trackIndexByCanonicalPath = indexByCanonicalPath,
            albumTracksByKey = immutableAlbumBuckets,
            artistTracksByName = immutableArtistBuckets,
            albumsByKey = albumsByKey,
            albumKeysSorted = sortedAlbumKeys(albumsByKey),
            artistsByName = artistsByName,
            artistNamesSorted = sortedArtistNames(artistsByName),
            revision = revisionCounter.incrementAndGet(),
            structureRevision = structureRevisionCounter.incrementAndGet(),
            metadataRevision = metadataRevisionCounter.incrementAndGet(),
            artworkRevision = artworkRevisionCounter.incrementAndGet(),
            ready = ready,
            origin = origin,
            createdAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        ).also { _snapshot.value = it }
    }

    @Synchronized
    fun merge(
        incoming: List<LibraryTrack>,
        origin: AudioLibrarySnapshotOrigin,
        ready: Boolean = true
    ): AudioLibrarySnapshot {
        val current = _snapshot.value
        if (incoming.isEmpty()) {
            if (ready && !current.ready) return replace(current.tracks, origin, true)
            return current
        }

        val updatedTracks = current.tracks.toMutableList()
        val updatedByPath = current.tracksByPath.toMutableMap()
        val updatedIndexByPath = current.trackIndexByPath.toMutableMap()
        val updatedIndexByCanonicalPath = current.trackIndexByCanonicalPath.toMutableMap()
        val affectedAlbumKeys = linkedSetOf<String>()
        val affectedArtistNames = linkedSetOf<String>()
        var changed = false

        incoming.forEach { track ->
            if (track.path.isBlank()) return@forEach
            val canonical = canonicalPath(track.path)
            val existingIndex = updatedIndexByCanonicalPath[canonical]
            if (existingIndex == null) {
                val index = updatedTracks.size
                updatedTracks += track
                updatedByPath[track.path] = track
                updatedIndexByPath[track.path] = index
                updatedIndexByCanonicalPath[canonical] = index
                affectedAlbumKeys += AudioLibraryHeuristics.albumKey(track)
                artistBucketName(track).takeIf { it.isNotBlank() }?.let(affectedArtistNames::add)
                changed = true
            } else {
                val old = updatedTracks[existingIndex]
                val next = mergeTrack(old, track)
                if (old == next) return@forEach
                updatedTracks[existingIndex] = next
                if (old.path != next.path) {
                    updatedByPath.remove(old.path)
                    updatedIndexByPath.remove(old.path)
                    updatedIndexByCanonicalPath.remove(
                        canonicalPath(old.path)
                    )
                }
                updatedByPath[next.path] = next
                updatedIndexByPath[next.path] = existingIndex
                updatedIndexByCanonicalPath[
                    canonicalPath(next.path)
                ] = existingIndex
                affectedAlbumKeys += AudioLibraryHeuristics.albumKey(old)
                affectedAlbumKeys += AudioLibraryHeuristics.albumKey(next)
                artistBucketName(old).takeIf { it.isNotBlank() }?.let(affectedArtistNames::add)
                artistBucketName(next).takeIf { it.isNotBlank() }?.let(affectedArtistNames::add)
                changed = true
            }
        }

        if (!changed) {
            if (ready && !current.ready) {
                return current.copy(
                    revision = revisionCounter.incrementAndGet(),
                    ready = true,
                    origin = origin,
                    createdAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                ).also { _snapshot.value = it }
            }
            return current
        }

        propagateAlbumArtworkInPlace(
            tracks = updatedTracks,
            albumKeys = affectedAlbumKeys,
            tracksByPath = updatedByPath,
            affectedArtistNames = affectedArtistNames
        )

        val updatedAlbums = current.albumTracksByKey.toMutableMap()
        val updatedArtists = current.artistTracksByName.toMutableMap()
        val updatedAlbumModels = current.albumsByKey.toMutableMap()
        val updatedArtistModels = current.artistsByName.toMutableMap()
        rebuildAffectedBuckets(
            tracks = updatedTracks,
            albumKeys = affectedAlbumKeys,
            artistNames = affectedArtistNames,
            albums = updatedAlbums,
            artists = updatedArtists
        )
        rebuildAffectedModels(
            albumKeys = affectedAlbumKeys,
            artistNames = affectedArtistNames,
            albumBuckets = updatedAlbums,
            artistBuckets = updatedArtists,
            albums = updatedAlbumModels,
            artists = updatedArtistModels
        )

        return current.copy(
            tracks = updatedTracks,
            tracksByPath = updatedByPath,
            trackPathsByTrackOrder = sortedTrackPaths(updatedTracks, byTrackNumber = true),
            trackPathsByTitleOrder = sortedTrackPaths(updatedTracks, byTrackNumber = false),
            trackIndexByPath = updatedIndexByPath,
            trackIndexByCanonicalPath = updatedIndexByCanonicalPath,
            albumTracksByKey = updatedAlbums,
            artistTracksByName = updatedArtists,
            albumsByKey = updatedAlbumModels,
            albumKeysSorted = sortedAlbumKeys(updatedAlbumModels),
            artistsByName = updatedArtistModels,
            artistNamesSorted = sortedArtistNames(updatedArtistModels),
            revision = revisionCounter.incrementAndGet(),
            structureRevision = structureRevisionCounter.incrementAndGet(),
            metadataRevision = current.metadataRevision,
            artworkRevision = artworkRevisionCounter.incrementAndGet(),
            ready = ready || current.ready,
            origin = origin,
            createdAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        ).also { _snapshot.value = it }
    }

    /**
     * Insertion progressive optimisée pour les squelettes découverts pendant un long scan.
     *
     * Contrairement à [merge], cette méthode ne retrie pas toute la bibliothèque à chaque lot.
     * Elle met à jour uniquement les albums/artistes touchés et ajoute les nouveaux chemins aux
     * index temporaires. [replace] effectue le tri global définitif une seule fois à la fin du scan.
     */
    @Synchronized
    fun appendSkeletons(
        incoming: List<LibraryTrack>,
        origin: AudioLibrarySnapshotOrigin,
        ready: Boolean = true
    ): AudioLibrarySnapshot {
        val current = _snapshot.value
        if (incoming.isEmpty()) {
            if (ready && !current.ready) {
                return current.copy(
                    revision = revisionCounter.incrementAndGet(),
                    ready = true,
                    origin = origin,
                    createdAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                ).also { _snapshot.value = it }
            }
            return current
        }

        val updatedTracks = current.tracks.toMutableList()
        val updatedByPath = current.tracksByPath.toMutableMap()
        val updatedIndexByPath = current.trackIndexByPath.toMutableMap()
        val updatedIndexByCanonicalPath = current.trackIndexByCanonicalPath.toMutableMap()
        val updatedAlbumBuckets = current.albumTracksByKey.toMutableMap()
        val updatedArtistBuckets = current.artistTracksByName.toMutableMap()
        val updatedAlbumModels = current.albumsByKey.toMutableMap()
        val updatedArtistModels = current.artistsByName.toMutableMap()

        data class Change(val old: LibraryTrack?, val next: LibraryTrack)

        val changes = ArrayList<Change>(incoming.size)
        val affectedAlbumKeys = linkedSetOf<String>()
        val affectedArtistNames = linkedSetOf<String>()
        val newlyAddedPaths = ArrayList<String>(incoming.size)
        var changed = false

        incoming.forEach { rawTrack ->
            if (rawTrack.path.isBlank()) return@forEach
            val track = AudioLibraryHeuristics.applyFolderMetadata(rawTrack)
            val canonical = canonicalPath(track.path)
            val existingIndex = updatedIndexByCanonicalPath[canonical]

            if (existingIndex == null) {
                val index = updatedTracks.size
                updatedTracks += track
                updatedByPath[track.path] = track
                updatedIndexByPath[track.path] = index
                updatedIndexByCanonicalPath[canonical] = index
                newlyAddedPaths += track.path
                changes += Change(null, track)
                affectedAlbumKeys += AudioLibraryHeuristics.albumKey(track)
                artistBucketName(track)
                    .takeIf { it.isNotBlank() }
                    ?.let(affectedArtistNames::add)
                changed = true
            } else {
                val old = updatedTracks[existingIndex]
                val next = mergeTrack(old, track)
                if (old == next) return@forEach

                updatedTracks[existingIndex] = next
                if (old.path != next.path) {
                    updatedByPath.remove(old.path)
                    updatedIndexByPath.remove(old.path)
                }
                updatedByPath[next.path] = next
                updatedIndexByPath[next.path] = existingIndex
                changes += Change(old, next)
                affectedAlbumKeys += AudioLibraryHeuristics.albumKey(old)
                affectedAlbumKeys += AudioLibraryHeuristics.albumKey(next)
                artistBucketName(old)
                    .takeIf { it.isNotBlank() }
                    ?.let(affectedArtistNames::add)
                artistBucketName(next)
                    .takeIf { it.isNotBlank() }
                    ?.let(affectedArtistNames::add)
                changed = true
            }
        }

        if (!changed) return current

        // Chaque bucket est reconstruit depuis son ancien petit contenu et les changements du lot,
        // jamais en reparcourant les dizaines de milliers de titres déjà indexés.
        affectedAlbumKeys.forEach { key ->
            val byCanonical = LinkedHashMap<String, LibraryTrack>()
            current.albumTracksByKey[key].orEmpty().forEach {
                byCanonical[canonicalPath(it.path)] = it
            }
            changes.forEach { change ->
                change.old?.takeIf {
                    AudioLibraryHeuristics.albumKey(it) == key
                }?.let { byCanonical.remove(canonicalPath(it.path)) }
                change.next.takeIf {
                    AudioLibraryHeuristics.albumKey(it) == key
                }?.let { byCanonical[canonicalPath(it.path)] = it }
            }

            val bucket = byCanonical.values
                .sortedWith(albumTrackComparator())
            if (bucket.isEmpty()) {
                updatedAlbumBuckets.remove(key)
                updatedAlbumModels.remove(key)
            } else {
                updatedAlbumBuckets[key] = bucket
                updatedAlbumModels[key] = buildAlbum(key, bucket)
            }
        }

        affectedArtistNames.forEach { name ->
            val byCanonical = LinkedHashMap<String, LibraryTrack>()
            current.artistTracksByName[name].orEmpty().forEach {
                byCanonical[canonicalPath(it.path)] = it
            }
            changes.forEach { change ->
                change.old?.takeIf {
                    artistBucketName(it) == name
                }?.let { byCanonical.remove(canonicalPath(it.path)) }
                change.next.takeIf {
                    artistBucketName(it) == name
                }?.let { byCanonical[canonicalPath(it.path)] = it }
            }

            val bucket = byCanonical.values.toList()
            if (bucket.isEmpty()) {
                updatedArtistBuckets.remove(name)
                updatedArtistModels.remove(name)
            } else {
                updatedArtistBuckets[name] = bucket
                updatedArtistModels[name] = buildArtist(name, bucket)
            }
        }

        val newAlbumKeys = affectedAlbumKeys.filter {
            it in updatedAlbumModels && it !in current.albumsByKey
        }
        val newArtistNames = affectedArtistNames.filter {
            it in updatedArtistModels && it !in current.artistsByName
        }

        // Pendant le scan, l'ordre de découverte suffit. Les vues restent complètes et stables ;
        // le tri global exact est recalculé par replace() à la fin de la passe.
        val nextTrackOrder = if (newlyAddedPaths.isEmpty()) {
            current.trackPathsByTrackOrder
        } else {
            current.trackPathsByTrackOrder + newlyAddedPaths
        }
        val nextTitleOrder = if (newlyAddedPaths.isEmpty()) {
            current.trackPathsByTitleOrder
        } else {
            current.trackPathsByTitleOrder + newlyAddedPaths
        }
        val nextAlbumOrder = if (newAlbumKeys.isEmpty()) {
            reconcileAlbumOrder(
                current.albumKeysSorted,
                updatedAlbumModels,
                forceResort = false
            )
        } else {
            (current.albumKeysSorted + newAlbumKeys)
                .distinct()
                .filter(updatedAlbumModels::containsKey)
        }
        val nextArtistOrder = if (newArtistNames.isEmpty()) {
            reconcileArtistOrder(
                current.artistNamesSorted,
                updatedArtistModels,
                forceResort = false
            )
        } else {
            (current.artistNamesSorted + newArtistNames)
                .distinct()
                .filter(updatedArtistModels::containsKey)
        }

        return current.copy(
            tracks = updatedTracks,
            tracksByPath = updatedByPath,
            trackPathsByTrackOrder = nextTrackOrder,
            trackPathsByTitleOrder = nextTitleOrder,
            trackIndexByPath = updatedIndexByPath,
            trackIndexByCanonicalPath = updatedIndexByCanonicalPath,
            albumTracksByKey = updatedAlbumBuckets,
            artistTracksByName = updatedArtistBuckets,
            albumsByKey = updatedAlbumModels,
            albumKeysSorted = nextAlbumOrder,
            artistsByName = updatedArtistModels,
            artistNamesSorted = nextArtistOrder,
            revision = revisionCounter.incrementAndGet(),
            structureRevision = structureRevisionCounter.incrementAndGet(),
            metadataRevision = current.metadataRevision,
            artworkRevision = artworkRevisionCounter.incrementAndGet(),
            ready = ready || current.ready,
            origin = origin,
            createdAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        ).also { _snapshot.value = it }
    }

    /**
     * Superpose au nouveau squelette les données déjà hydratées du snapshot courant. Un rescan
     * automatique ou un retour dans la bibliothèque ne peut ainsi plus remettre durée, bitrate ou
     * pochette à zéro avant une nouvelle extraction.
     */
    @Synchronized
    fun preserveHydratedFields(incoming: List<LibraryTrack>): List<LibraryTrack> {
        val current = _snapshot.value
        if (incoming.isEmpty() || current.tracks.isEmpty()) return incoming
        return incoming.map { next ->
            val old = current.tracksByPath[next.path]
                ?: current.trackIndexByCanonicalPath[canonicalPath(next.path)]
                    ?.let(current.tracks::get)
                ?: return@map next
            next.copy(
                durationMs = next.durationMs.takeIf { it > 0L } ?: old.durationMs,
                bitrate = next.bitrate.takeIf { it > 0L } ?: old.bitrate,
                trackNo = next.trackNo.takeIf { it > 0 } ?: old.trackNo,
                artworkPath = preferredArtwork(old.artworkPath, next.artworkPath),
                container = next.container.ifBlank { old.container },
                sizeBytes = next.sizeBytes.takeIf { it > 0L } ?: old.sizeBytes,
                modifiedAt = next.modifiedAt.takeIf { it > 0L } ?: old.modifiedAt
            )
        }
    }

    @Synchronized
    fun removePaths(
        paths: Set<String>,
        origin: AudioLibrarySnapshotOrigin = AudioLibrarySnapshotOrigin.FOLDER_CHANGE
    ): AudioLibrarySnapshot {
        if (paths.isEmpty()) return _snapshot.value
        val canonical = paths.mapTo(hashSetOf(), ::canonicalPath)
        return replace(
            _snapshot.value.tracks.filterNot { canonicalPath(it.path) in canonical },
            origin,
            _snapshot.value.ready
        )
    }

    @Synchronized
    fun clear(origin: AudioLibrarySnapshotOrigin = AudioLibrarySnapshotOrigin.FOLDER_CHANGE) {
        replace(emptyList(), origin, ready = true)
    }

    fun updateArtwork(path: String, artworkPath: String) {
        if (path.isBlank() || artworkPath.isBlank()) return
        val current = _snapshot.value
        val canonical = canonicalPath(path)
        val representative = current.tracksByPath[path]
            ?: current.trackIndexByCanonicalPath[canonical]?.let(current.tracks::get)
            ?: return
        val albumKey = AudioLibraryHeuristics.albumKey(representative)
        val albumTracks = current.albumTracksByKey[albumKey].orEmpty()
        val targets = if (albumTracks.isEmpty()) listOf(representative) else albumTracks

        // Une pochette résolue est une propriété de l'album, pas uniquement du fichier qui a servi
        // à l'extraction. Forcer le même chemin sur tout le bucket empêche la vue album, le player
        // et la couleur dynamique de reprendre chacun une ancienne embedded différente.
        val changes = targets.associate { track ->
            track.path to { currentTrack: LibraryTrack ->
                currentTrack.copy(artworkPath = artworkPath)
            }
        }
        patchTracks(
            changes = changes,
            origin = AudioLibrarySnapshotOrigin.ARTWORK,
            sortOrderMayChange = false
        )
    }

    fun updateArtworkForSource(sourceArtworkPath: String, persistedPath: String) {
        if (sourceArtworkPath.isBlank() || persistedPath.isBlank() || sourceArtworkPath == persistedPath) return
        val changes: Map<String, (LibraryTrack) -> LibraryTrack> =
            _snapshot.value.tracks.asSequence()
                .filter { track -> track.artworkPath == sourceArtworkPath }
                .associate { track ->
                    track.path to { current: LibraryTrack ->
                        current.copy(artworkPath = persistedPath)
                    }
                }
        patchTracks(changes, AudioLibrarySnapshotOrigin.ARTWORK, sortOrderMayChange = false)
    }

    fun updateMetadata(updates: Map<String, AudioTechnicalInfo>) {
        if (updates.isEmpty()) return
        val changes: Map<String, (LibraryTrack) -> LibraryTrack> = updates.mapValues { (_, info) ->
            { track: LibraryTrack ->
                track.copy(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = if (info.duration > 0L) info.duration * 1000L else track.durationMs,
                    bitrate = if (info.bitrate > 0L) info.bitrate else track.bitrate,
                    trackNo = if (info.trackNumber > 0) info.trackNumber else track.trackNo,
                    container = info.extension.ifBlank { track.container },
                    titleFromTag = false,
                    artistFromTag = false,
                    albumFromTag = false
                )
            }
        }
        val sortOrderMayChange = updates.values.any { info ->
            info.trackNumber > 0
        }
        patchTracks(
            changes,
            AudioLibrarySnapshotOrigin.METADATA,
            sortOrderMayChange = sortOrderMayChange
        )
    }

    @Synchronized
    private fun patchTracks(
        changes: Map<String, (LibraryTrack) -> LibraryTrack>,
        origin: AudioLibrarySnapshotOrigin,
        sortOrderMayChange: Boolean
    ) {
        if (changes.isEmpty()) return
        val current = _snapshot.value
        if (current.tracks.isEmpty()) return

        data class TrackChange(
            val old: LibraryTrack,
            val next: LibraryTrack
        )

        val updatedTracks = current.tracks.toMutableList()
        val updatedByPath = current.tracksByPath.toMutableMap()
        val updatedIndexByPath = current.trackIndexByPath.toMutableMap()
        val updatedIndexByCanonicalPath =
            current.trackIndexByCanonicalPath.toMutableMap()
        val updatedAlbums = current.albumTracksByKey.toMutableMap()
        val updatedArtists = current.artistTracksByName.toMutableMap()
        val updatedAlbumModels = current.albumsByKey.toMutableMap()
        val updatedArtistModels = current.artistsByName.toMutableMap()
        val affectedAlbumKeys = linkedSetOf<String>()
        val affectedArtistNames = linkedSetOf<String>()
        val effectiveChanges = ArrayList<TrackChange>(changes.size)
        var changed = false

        changes.forEach { (path, transform) ->
            val index = current.trackIndexByPath[path]
                ?: current.trackIndexByCanonicalPath[canonicalPath(path)]
                ?: return@forEach
            val old = updatedTracks[index]
            val next = transform(old)
            if (next == old) return@forEach

            updatedTracks[index] = next
            if (old.path != next.path) {
                updatedByPath.remove(old.path)
                updatedIndexByPath.remove(old.path)
                updatedIndexByCanonicalPath.remove(
                    canonicalPath(old.path)
                )
            }
            updatedByPath[next.path] = next
            updatedIndexByPath[next.path] = index
            updatedIndexByCanonicalPath[
                canonicalPath(next.path)
            ] = index

            affectedAlbumKeys += AudioLibraryHeuristics.albumKey(old)
            affectedAlbumKeys += AudioLibraryHeuristics.albumKey(next)
            artistBucketName(old)
                .takeIf { it.isNotBlank() }
                ?.let(affectedArtistNames::add)
            artistBucketName(next)
                .takeIf { it.isNotBlank() }
                ?.let(affectedArtistNames::add)
            effectiveChanges += TrackChange(old, next)
            changed = true
        }
        if (!changed) return

        // Mise à jour et propagation de la pochette uniquement dans les petits buckets d'albums
        // touchés. L'ancienne implémentation reparcourait toute la bibliothèque pour chaque cover.
        affectedAlbumKeys.forEach { albumKey ->
            val byCanonical = LinkedHashMap<String, LibraryTrack>()
            current.albumTracksByKey[albumKey].orEmpty().forEach { track ->
                byCanonical[canonicalPath(track.path)] =
                    updatedByPath[track.path] ?: track
            }

            effectiveChanges.forEach { change ->
                if (
                    AudioLibraryHeuristics.albumKey(change.old) ==
                    albumKey
                ) {
                    byCanonical.remove(canonicalPath(change.old.path))
                }
                if (
                    AudioLibraryHeuristics.albumKey(change.next) ==
                    albumKey
                ) {
                    byCanonical[
                        canonicalPath(change.next.path)
                    ] = change.next
                }
            }

            var bucket = byCanonical.values.toList()
            val albumArtwork =
                AudioLibraryHeuristics.bestArtworkPath(bucket)
            if (
                AudioLibraryHeuristics.isArtworkReference(albumArtwork)
            ) {
                bucket = bucket.map { track ->
                    if (
                        AudioLibraryHeuristics.isArtworkReference(
                            track.artworkPath
                        )
                    ) {
                        track
                    } else {
                        val next = track.copy(
                            artworkPath = albumArtwork
                        )
                        val index =
                            updatedIndexByCanonicalPath[
                                canonicalPath(track.path)
                            ]
                        if (index != null) {
                            updatedTracks[index] = next
                            updatedByPath[next.path] = next
                            val artistName = artistBucketName(next)
                            if (artistName.isNotBlank()) {
                                affectedArtistNames += artistName
                            }
                            effectiveChanges += TrackChange(track, next)
                        }
                        next
                    }
                }
            }

            val sortedBucket = bucket.sortedWith(
                albumTrackComparator()
            )
            if (sortedBucket.isEmpty()) {
                updatedAlbums.remove(albumKey)
                updatedAlbumModels.remove(albumKey)
            } else {
                updatedAlbums[albumKey] = sortedBucket
                updatedAlbumModels[albumKey] =
                    buildAlbum(albumKey, sortedBucket)
            }
        }

        // Même stratégie pour les artistes : uniquement leurs petits buckets existants.
        affectedArtistNames.forEach { artistName ->
            val byCanonical = LinkedHashMap<String, LibraryTrack>()
            current.artistTracksByName[artistName]
                .orEmpty()
                .forEach { track ->
                    val currentTrack =
                        updatedByPath[track.path] ?: track
                    if (
                        artistBucketName(currentTrack) ==
                        artistName
                    ) {
                        byCanonical[
                            canonicalPath(currentTrack.path)
                        ] = currentTrack
                    }
                }

            effectiveChanges.forEach { change ->
                if (artistBucketName(change.old) == artistName) {
                    byCanonical.remove(
                        canonicalPath(change.old.path)
                    )
                }
                if (artistBucketName(change.next) == artistName) {
                    byCanonical[
                        canonicalPath(change.next.path)
                    ] = change.next
                }
            }

            val bucket = byCanonical.values.toList()
            if (bucket.isEmpty()) {
                updatedArtists.remove(artistName)
                updatedArtistModels.remove(artistName)
            } else {
                updatedArtists[artistName] = bucket
                updatedArtistModels[artistName] =
                    buildArtist(artistName, bucket)
            }
        }

        _snapshot.value = current.copy(
            tracks = updatedTracks,
            tracksByPath = updatedByPath,
            trackPathsByTrackOrder = if (sortOrderMayChange) {
                sortedTrackPaths(
                    updatedTracks,
                    byTrackNumber = true
                )
            } else {
                current.trackPathsByTrackOrder
            },
            trackPathsByTitleOrder = if (sortOrderMayChange) {
                sortedTrackPaths(
                    updatedTracks,
                    byTrackNumber = false
                )
            } else {
                current.trackPathsByTitleOrder
            },
            trackIndexByPath = updatedIndexByPath,
            trackIndexByCanonicalPath =
                updatedIndexByCanonicalPath,
            albumTracksByKey = updatedAlbums,
            artistTracksByName = updatedArtists,
            albumsByKey = updatedAlbumModels,
            albumKeysSorted = reconcileAlbumOrder(
                current.albumKeysSorted,
                updatedAlbumModels,
                forceResort = sortOrderMayChange
            ),
            artistsByName = updatedArtistModels,
            artistNamesSorted = reconcileArtistOrder(
                current.artistNamesSorted,
                updatedArtistModels,
                forceResort = sortOrderMayChange
            ),
            revision = revisionCounter.incrementAndGet(),
            structureRevision = current.structureRevision,
            metadataRevision = if (origin == AudioLibrarySnapshotOrigin.METADATA) {
                metadataRevisionCounter.incrementAndGet()
            } else {
                current.metadataRevision
            },
            artworkRevision = if (origin == AudioLibrarySnapshotOrigin.ARTWORK) {
                artworkRevisionCounter.incrementAndGet()
            } else {
                current.artworkRevision
            },
            origin = origin,
            createdAtElapsedMs =
                android.os.SystemClock.elapsedRealtime()
        )
    }


    private fun rebuildAffectedModels(
        albumKeys: Set<String>,
        artistNames: Set<String>,
        albumBuckets: Map<String, List<LibraryTrack>>,
        artistBuckets: Map<String, List<LibraryTrack>>,
        albums: MutableMap<String, LibraryAlbum>,
        artists: MutableMap<String, LibraryArtist>
    ) {
        albumKeys.forEach { key ->
            val tracks = albumBuckets[key]
            if (tracks.isNullOrEmpty()) albums.remove(key) else albums[key] = buildAlbum(key, tracks)
        }
        artistNames.forEach { name ->
            val tracks = artistBuckets[name]
            if (tracks.isNullOrEmpty()) artists.remove(name) else artists[name] = buildArtist(name, tracks)
        }
    }

    private fun buildAlbum(key: String, tracks: List<LibraryTrack>): LibraryAlbum = LibraryAlbum(
        key = key,
        title = AudioLibraryHeuristics.bestAlbumTitle(tracks),
        artist = AudioLibraryHeuristics.bestAlbumArtist(tracks),
        tracks = tracks,
        artworkPath = AudioLibraryHeuristics.bestArtworkPath(tracks),
        addedAt = tracks.maxOfOrNull { it.addedAt } ?: 0L
    )

    private fun buildArtist(name: String, tracks: List<LibraryTrack>): LibraryArtist = LibraryArtist(
        name = name,
        tracks = tracks,
        albums = tracks.asSequence().map(AudioLibraryHeuristics::albumKey).distinct().count()
    )

    private fun albumTrackComparator(): Comparator<LibraryTrack> =
        compareBy<LibraryTrack> {
            AudioLibraryHeuristics.discNumberFromPath(
                AudioLibraryHeuristics.structuralPath(it)
            )
        }.thenBy {
            AudioLibraryHeuristics.normalizedTrackNo(it.trackNo)
        }.thenBy {
            AudioLibraryHeuristics.normalize(it.title)
        }.thenBy { it.path }

    private fun sortedTrackPaths(
        tracks: List<LibraryTrack>,
        byTrackNumber: Boolean
    ): List<String> {
        val base = compareBy<LibraryTrack> {
            AudioLibraryHeuristics.normalizeArtistSort(
                AudioLibraryHeuristics.artistFolderNameFromPath(
                    AudioLibraryHeuristics.structuralPath(it)
                )
            )
        }.thenBy {
            AudioLibraryHeuristics.normalize(
                AudioLibraryHeuristics.albumFolderNameFromPath(
                    AudioLibraryHeuristics.structuralPath(it)
                )
            )
        }
        val comparator = if (byTrackNumber) {
            base.thenBy {
                AudioLibraryHeuristics.discNumberFromPath(
                    AudioLibraryHeuristics.structuralPath(it)
                )
            }
                .thenBy { AudioLibraryHeuristics.normalizedTrackNo(it.trackNo) }
                .thenBy { AudioLibraryHeuristics.normalize(it.title) }
        } else {
            base.thenBy { AudioLibraryHeuristics.normalize(it.title) }
        }
        return tracks.sortedWith(comparator).map { it.path }
    }

    /**
     * Répare également un snapshot déjà incohérent provenant d'une version antérieure.
     * La liste triée doit contenir exactement une fois chaque clé présente dans la Map.
     */
    private fun reconcileAlbumOrder(
        currentOrder: List<String>,
        albums: Map<String, LibraryAlbum>,
        forceResort: Boolean
    ): List<String> {
        val orderIsComplete =
            currentOrder.size == albums.size &&
                currentOrder.distinct().size == currentOrder.size &&
                currentOrder.all(albums::containsKey)

        return if (!forceResort && orderIsComplete) {
            currentOrder
        } else {
            sortedAlbumKeys(albums)
        }
    }

    private fun reconcileArtistOrder(
        currentOrder: List<String>,
        artists: Map<String, LibraryArtist>,
        forceResort: Boolean
    ): List<String> {
        val orderIsComplete =
            currentOrder.size == artists.size &&
                currentOrder.distinct().size == currentOrder.size &&
                currentOrder.all(artists::containsKey)

        return if (!forceResort && orderIsComplete) {
            currentOrder
        } else {
            sortedArtistNames(artists)
        }
    }

    private fun sortedAlbumKeys(albums: Map<String, LibraryAlbum>): List<String> = albums.values
        .sortedWith(
            compareBy<LibraryAlbum> {
                AudioLibraryHeuristics.normalizeArtistSort(it.artist)
            }.thenBy {
                AudioLibraryHeuristics.normalize(it.title)
            }.thenBy { it.key }
        )
        .map { it.key }

    private fun sortedArtistNames(artists: Map<String, LibraryArtist>): List<String> = artists.keys
        .sortedBy(AudioLibraryHeuristics::normalizeArtistSort)

    private fun mergeTrack(existing: LibraryTrack, incoming: LibraryTrack): LibraryTrack {
        val existingScore = AudioLibraryHeuristics.trackCompletenessScore(existing)
        val incomingScore = AudioLibraryHeuristics.trackCompletenessScore(incoming)
        return when {
            incomingScore > existingScore -> incoming.copy(
                addedAt = maxOf(incoming.addedAt, existing.addedAt),
                artworkPath = preferredArtwork(incoming.artworkPath, existing.artworkPath),
                durationMs = incoming.durationMs.takeIf { it > 0L } ?: existing.durationMs,
                bitrate = incoming.bitrate.takeIf { it > 0L } ?: existing.bitrate,
                sizeBytes = incoming.sizeBytes.takeIf { it > 0L } ?: existing.sizeBytes,
                modifiedAt = incoming.modifiedAt.takeIf { it > 0L } ?: existing.modifiedAt
            )
            incomingScore == existingScore -> existing.copy(
                addedAt = maxOf(existing.addedAt, incoming.addedAt),
                artworkPath = preferredArtwork(existing.artworkPath, incoming.artworkPath),
                durationMs = existing.durationMs.takeIf { it > 0L } ?: incoming.durationMs,
                bitrate = existing.bitrate.takeIf { it > 0L } ?: incoming.bitrate,
                container = existing.container.ifBlank { incoming.container },
                sizeBytes = existing.sizeBytes.takeIf { it > 0L } ?: incoming.sizeBytes,
                modifiedAt = maxOf(existing.modifiedAt, incoming.modifiedAt)
            )
            else -> existing.copy(
                // Une passe MediaStore plus pauvre peut malgré tout découvrir un vrai cover.jpg.
                // On conserve tous les champs enrichis existants mais on accepte cette meilleure
                // pochette et les informations d'identité de fichier plus récentes.
                artworkPath = preferredArtwork(existing.artworkPath, incoming.artworkPath),
                durationMs = existing.durationMs.takeIf { it > 0L } ?: incoming.durationMs,
                bitrate = existing.bitrate.takeIf { it > 0L } ?: incoming.bitrate,
                sizeBytes = incoming.sizeBytes.takeIf { it > 0L } ?: existing.sizeBytes,
                modifiedAt = maxOf(existing.modifiedAt, incoming.modifiedAt),
                addedAt = maxOf(existing.addedAt, incoming.addedAt)
            )
        }
    }

    private fun preferredArtwork(primary: String, fallback: String): String = when {
        AudioLibraryHeuristics.isArtworkReference(primary) -> primary
        AudioLibraryHeuristics.isArtworkReference(fallback) -> fallback
        primary.isNotBlank() -> primary
        else -> fallback
    }

    private fun propagateAlbumArtworkInPlace(
        tracks: MutableList<LibraryTrack>,
        albumKeys: Set<String>,
        tracksByPath: MutableMap<String, LibraryTrack>,
        affectedArtistNames: MutableSet<String>
    ) {
        if (albumKeys.isEmpty()) return
        val indicesByAlbum = albumKeys.associateWithTo(linkedMapOf()) { mutableListOf<Int>() }
        tracks.forEachIndexed { index, track ->
            indicesByAlbum[AudioLibraryHeuristics.albumKey(track)]?.add(index)
        }
        indicesByAlbum.values.forEach album@ { indices ->
            val albumArtwork = AudioLibraryHeuristics.bestArtworkPath(indices.map(tracks::get))
            if (!AudioLibraryHeuristics.isArtworkReference(albumArtwork)) return@album
            indices.forEach { index ->
                val track = tracks[index]
                if (!AudioLibraryHeuristics.isArtworkReference(track.artworkPath)) {
                    val next = track.copy(artworkPath = albumArtwork)
                    tracks[index] = next
                    tracksByPath[next.path] = next
                    artistBucketName(next).takeIf { it.isNotBlank() }?.let(affectedArtistNames::add)
                }
            }
        }
    }

    private fun rebuildAffectedBuckets(
        tracks: List<LibraryTrack>,
        albumKeys: Set<String>,
        artistNames: Set<String>,
        albums: MutableMap<String, List<LibraryTrack>>,
        artists: MutableMap<String, List<LibraryTrack>>
    ) {
        val albumBuckets = albumKeys.associateWithTo(linkedMapOf()) { mutableListOf<LibraryTrack>() }
        val artistBuckets = artistNames.associateWithTo(linkedMapOf()) { mutableListOf<LibraryTrack>() }
        tracks.forEach { track ->
            albumBuckets[AudioLibraryHeuristics.albumKey(track)]?.add(track)
            artistBuckets[artistBucketName(track)]?.add(track)
        }
        albumBuckets.forEach { (key, bucket) ->
            if (bucket.isEmpty()) albums.remove(key) else albums[key] = bucket
        }
        artistBuckets.forEach { (name, bucket) ->
            if (bucket.isEmpty()) artists.remove(name) else artists[name] = bucket
        }
    }

    private fun propagateAlbumArtwork(tracks: List<LibraryTrack>): List<LibraryTrack> {
        if (tracks.size < 2) return tracks
        val result = tracks.toMutableList()
        val indicesByAlbum = linkedMapOf<String, MutableList<Int>>()
        tracks.forEachIndexed { index, track ->
            indicesByAlbum.getOrPut(AudioLibraryHeuristics.albumKey(track)) { mutableListOf() }
                .add(index)
        }
        indicesByAlbum.values.forEach { indices ->
            val artwork = AudioLibraryHeuristics.bestArtworkPath(indices.map(result::get))
            if (!AudioLibraryHeuristics.isArtworkReference(artwork)) return@forEach
            indices.forEach { index ->
                val track = result[index]
                if (!AudioLibraryHeuristics.isArtworkReference(track.artworkPath)) {
                    result[index] = track.copy(artworkPath = artwork)
                }
            }
        }
        return result
    }

    private fun artistBucketName(track: LibraryTrack): String =
        AudioLibraryHeuristics.artistFolderNameFromPath(
            AudioLibraryHeuristics.structuralPath(track)
        ).ifBlank { track.artist.trim() }

    private fun canonicalPath(path: String): String = path
        .substringBefore('?')
        .substringBefore('#')
        .replace('\\', '/')
        .trim()
        .trimEnd('/')
        .lowercase()
}
