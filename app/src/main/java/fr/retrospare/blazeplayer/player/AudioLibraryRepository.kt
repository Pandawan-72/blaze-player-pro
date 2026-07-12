package fr.retrospare.blazeplayer.player

import android.content.Context
import android.provider.MediaStore
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType
import fr.retrospare.blazeplayer.data.repository.NetworkRepository
import fr.retrospare.blazeplayer.network.SmbBrowser
import fr.retrospare.blazeplayer.network.UpnpBrowser
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Point d'entrée unique pour tout ce qui touche à la bibliothèque audio persistée :
 * - lecture/observation depuis Room (source de vérité) ;
 * - scan local (MediaStore + parcours de dossiers) et réseau (SMB/UPnP) ;
 * - écriture des squelettes et mise en cache des pochettes dans Room.
 *
 * Remplace l'ancien éparpillement sur 4-5 Executors distincts dans AudioLibraryActivity par deux
 * dispatchers clairement délimités : un pour le scan (I/O fichiers/réseau), un pour les écritures
 * Room (courtes, ne doivent jamais attendre sur un scan réseau lent).
 */
@Singleton
class AudioLibraryRepository @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val smbBrowser: SmbBrowser,
    private val upnpBrowser: UpnpBrowser
) {
    companion object {
        private const val MAX_LOCAL_SCAN_TRACKS = 12000
        private const val MAX_NETWORK_SCAN_TRACKS = 8000
        private const val MAX_MANUAL_NETWORK_SCAN_TRACKS = 200000
        private const val MAX_NETWORK_DEPTH = 12
        private const val NETWORK_PROGRESS_BATCH_SIZE = 80
        private const val NETWORK_PROGRESS_MAX_DELAY_MS = 700L

        /** Nombre de titres traités en parallèle pendant l'enrichissement optionnel/cover. Les
         *  latences réseau NAS se chevauchent au lieu de s'additionner titre par titre. */
        const val ENRICHMENT_CONCURRENCY = 1
    }

    private val scanDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(3) { runnable ->
        Thread {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Exception) {}
            runnable.run()
        }.apply { name = "BlazeLibraryScan"; isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
    }.asCoroutineDispatcher()

    private val roomDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(2) { runnable ->
        Thread {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Exception) {}
            runnable.run()
        }.apply { name = "BlazeLibraryRoom"; isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
    }.asCoroutineDispatcher()

    data class NetworkFolderScanResult(
        val tracks: List<LibraryTrack>,
        val confirmedFolders: List<AudioProSettings.WatchedFolder>
    )

    data class RefreshResult(
        val activeTracks: List<LibraryTrack>,
        val scannedTrackCount: Int,
        val prunedCount: Int
    )

    private class NetworkProgressEmitter(
        private val onBatch: suspend (List<LibraryTrack>) -> Unit
    ) {
        private var emittedCount = 0
        private var lastEmitAt = android.os.SystemClock.elapsedRealtime()

        suspend fun emitIfNeeded(source: List<LibraryTrack>, force: Boolean = false) {
            val pendingCount = source.size - emittedCount
            if (pendingCount <= 0) return
            val now = android.os.SystemClock.elapsedRealtime()
            val delayedEnough = now - lastEmitAt >= NETWORK_PROGRESS_MAX_DELAY_MS
            if (!force && pendingCount < NETWORK_PROGRESS_BATCH_SIZE && !delayedEnough) return
            val batch = source.subList(emittedCount, source.size).toList()
            emittedCount = source.size
            lastEmitAt = now
            onBatch(batch)
        }
    }

    /** Source de vérité pour l'UI : émet à chaque écriture Room (skeleton pass ou enrichissement).
     *  Debounce + distinctUntilChanged : l'enrichissement de pochettes peut écrire titre par titre,
     *  et sans ça chaque écriture individuelle relirait + regrouperait toute la bibliothèque
     *  côté ViewModel. 120 ms reste imperceptible mais absorbe des dizaines d'écritures groupées. */
    fun observeLibrary(context: Context): Flow<List<LibraryTrack>> =
        AudioLibraryRoomStore.observeActive(context.applicationContext)
            .debounce(120L)
            .distinctUntilChanged()
            .map { entities -> entities.map { it.toLibraryTrack(context) } }

    suspend fun loadLibrarySnapshot(context: Context, limit: Int = Int.MAX_VALUE): List<LibraryTrack> =
        AudioLibraryRoomStore.loadActive(context.applicationContext, limit).map { it.toLibraryTrack(context) }

    /**
     * Scan complet : MediaStore + dossiers locaux + réseau (SMB/UPnP), puis remplacement du
     * squelette Room. Les dossiers dont le scan réseau a échoué (timeout, Wi-Fi coupé) ne sont
     * jamais purgés : on ne perd jamais la bibliothèque à cause d'une coupure réseau passagère.
     */
    suspend fun refresh(
        context: Context,
        manual: Boolean,
        isPlaybackCritical: () -> Boolean = { false }
    ): RefreshResult = withContext(scanDispatcher) {
        val appContext = context.applicationContext
        val watched = AudioProSettings.watchedFolders(appContext)
        val localFolders = watched.filterNot { it.isNetwork }
        val generation = System.currentTimeMillis()

        val (mediaStoreTracks, localTracks, networkScan) = coroutineScope {
            val mediaStoreDeferred = async { queryMediaStoreWatchedTracks(appContext) }
            val localDeferred = async { scanWatchedLocalFolders(appContext) }
            val networkDeferred = async {
                scanWatchedNetworkFolders(
                    appContext,
                    manual,
                    isPlaybackCritical,
                    onProgress = { batch ->
                        persistProgressiveSkeletonBatch(appContext, batch, generation, watched)
                    }
                )
            }

            // Le stockage local est généralement disponible en quelques millisecondes. On écrit
            // cette première tranche dès qu'elle est prête, sans attendre le NAS.
            val mediaStore = mediaStoreDeferred.await()
            val local = localDeferred.await()
            persistProgressiveSkeletonBatch(
                appContext,
                AudioLibraryHeuristics.mergeTracks(mediaStore + local, emptyList(), appContext),
                generation,
                watched
            )
            Triple(mediaStore, local, networkDeferred.await())
        }

        val merged = AudioLibraryHeuristics.mergeTracks(mediaStoreTracks + localTracks, networkScan.tracks, appContext)
        val scanned = AudioLibraryHeuristics.canonicalLibraryTracks(appContext, merged)
        val confirmedFolders = (localFolders + networkScan.confirmedFolders).map { AudioWatchedLibraryCache.key(it) }.toSet()

        val entities = scanned.mapNotNull { it.toRoomEntity(appContext, generation, watched) }
        val replace = withContext(roomDispatcher) {
            AudioLibraryRoomStore.replaceSkeletonPass(appContext, entities, confirmedFolders, generation)
        }
        replace.changedPaths.forEach { path -> runCatching { AudioMediaCache.invalidatePath(appContext, path) } }

        RefreshResult(
            activeTracks = loadLibrarySnapshot(appContext),
            scannedTrackCount = entities.size,
            prunedCount = replace.removedCount
        )
    }

    /** Écriture non destructive utilisée pendant le scan progressif. */
    private suspend fun persistProgressiveSkeletonBatch(
        context: Context,
        tracks: List<LibraryTrack>,
        generation: Long,
        watchedFolders: List<AudioProSettings.WatchedFolder>
    ) {
        if (tracks.isEmpty()) return
        val canonical = AudioLibraryHeuristics.canonicalLibraryTracks(
            context,
            tracks.distinctBy { it.path }
        )
        val entities = canonical.mapNotNull { it.toRoomEntity(context, generation, watchedFolders) }
        if (entities.isEmpty()) return
        withContext(roomDispatcher) {
            AudioLibraryRoomStore.upsertSkeletonBatch(context, entities, generation)
        }
        // Les invalidations de caches sont volontairement regroupées à la passe finale. Les faire
        // pour chaque lot réseau doublerait les accès disque pendant le premier indexage.
    }

    /**
     * API conservée pour les seules données techniques optionnelles. Les champs texte écrits en
     * base restent systématiquement recalculés depuis l'arborescence et aucun écran ne déclenche
     * cette passe automatiquement.
     */
    suspend fun enrichMetadata(
        context: Context,
        candidates: List<LibraryTrack>,
        extractMetadata: suspend (LibraryTrack) -> AudioTechnicalInfo?,
        concurrency: Int = ENRICHMENT_CONCURRENCY,
        batchSize: Int = 4,
        onBatchWritten: suspend (writtenCount: Int) -> Unit = {}
    ) {
        val appContext = context.applicationContext
        var pendingMetadata = mutableListOf<AudioLibraryTrackEntity>()
        for (batch in candidates.chunked(concurrency.coerceAtLeast(1))) {
            if (!currentCoroutineContext().isActive) break
            AudioLibraryWorkState.awaitEnrichmentWindow()
            if (!currentCoroutineContext().isActive) break
            val results = coroutineScope {
                batch.map { track ->
                    async(scanDispatcher) {
                        track to runCatching { extractMetadata(track) }.getOrNull()
                    }
                }.awaitAll()
            }
            results.forEach { (track, info) ->
                if (info != null) pendingMetadata += track.toMetadataUpdateEntity(info)
            }
            if (pendingMetadata.size >= batchSize.coerceAtLeast(1)) {
                withContext(roomDispatcher) { AudioLibraryRoomStore.upsertMetadata(appContext, pendingMetadata) }
                onBatchWritten(pendingMetadata.size)
                pendingMetadata = mutableListOf()
            }
        }
        if (pendingMetadata.isNotEmpty()) {
            withContext(roomDispatcher) { AudioLibraryRoomStore.upsertMetadata(appContext, pendingMetadata) }
            onBatchWritten(pendingMetadata.size)
        }
    }

    /** Préchargement optionnel des pochettes, indépendant des noms d'album et d'artiste. */
    suspend fun enrichArtwork(
        candidates: List<LibraryTrack>,
        loadArtwork: suspend (LibraryTrack) -> Boolean,
        concurrency: Int = 2
    ) {
        for (batch in candidates.chunked(concurrency.coerceAtLeast(1))) {
            if (!currentCoroutineContext().isActive) break
            AudioLibraryWorkState.awaitEnrichmentWindow()
            if (!currentCoroutineContext().isActive) break
            coroutineScope {
                batch.map { track -> async(scanDispatcher) { runCatching { loadArtwork(track) } } }.awaitAll()
            }
        }
    }

    /** Compatibilité interne : données techniques facultatives, puis pochettes. */
    suspend fun enrichMetadataAndArtwork(
        context: Context,
        candidates: List<LibraryTrack>,
        loadArtwork: suspend (LibraryTrack) -> Boolean,
        extractMetadata: suspend (LibraryTrack) -> AudioTechnicalInfo?,
        concurrency: Int = ENRICHMENT_CONCURRENCY,
        batchSize: Int = 4,
        onBatchWritten: suspend (writtenCount: Int) -> Unit = {}
    ) {
        enrichMetadata(context, candidates, extractMetadata, concurrency, batchSize, onBatchWritten)
        enrichArtwork(candidates, loadArtwork, concurrency = 2)
    }

    suspend fun updateArtworkPath(context: Context, path: String, artworkPath: String) {
        withContext(roomDispatcher) { AudioLibraryRoomStore.updateArtworkPath(context.applicationContext, path, artworkPath) }
    }

    suspend fun deleteFolders(context: Context, folderKeys: Set<String>) {
        withContext(roomDispatcher) { AudioLibraryRoomStore.deleteFolders(context.applicationContext, folderKeys) }
    }

    suspend fun clear(context: Context) {
        withContext(roomDispatcher) { AudioLibraryRoomStore.clear(context.applicationContext) }
    }

    // ---------------------------------------------------------------------
    // Scan MediaStore (local, indexé par Android)
    // ---------------------------------------------------------------------

    private fun queryMediaStoreWatchedTracks(context: Context): List<LibraryTrack> {
        val settings = AudioProSettings.read(context)
        if (!settings.autoScan) return emptyList()
        val watched = AudioProSettings.watchedFolders(context).filterNot { it.isNetwork }
        if (watched.isEmpty()) return emptyList()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val selection = if (settings.ignoreShort) "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000" else "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val out = ArrayList<LibraryTrack>()
        runCatching {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val durIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val addedIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (c.moveToNext() && out.size < MAX_LOCAL_SCAN_TRACKS) {
                    val path = c.getString(dataIdx).orEmpty()
                    if (path.isBlank() || watched.none { AudioLibraryHeuristics.belongsToLocalFolder(path, it) }) continue
                    val folderMeta = AudioLibraryHeuristics.folderMetadata(
                        path,
                        AudioLibraryHeuristics.fileNameFromPath(path)
                    )
                    out += LibraryTrack(
                        id = c.getLong(idIdx),
                        title = folderMeta.title,
                        artist = folderMeta.artist,
                        album = folderMeta.album,
                        durationMs = c.getLong(durIdx),
                        trackNo = AudioLibraryHeuristics.inferTrackNo(AudioLibraryHeuristics.fileNameFromPath(path)),
                        path = path,
                        addedAt = c.getLong(addedIdx),
                        artworkPath = ThumbnailUtils.preferredFolderCoverPathForAudioPath(path) ?: path,
                        source = LibraryTrackSource.LOCAL,
                        sourceLabel = "Local",
                        titleFromTag = false,
                        albumFromTag = false,
                        artistFromTag = false,
                        container = AudioLibraryHeuristics.containerFrom("", path),
                        sizeBytes = runCatching { File(path).length() }.getOrDefault(0L),
                        modifiedAt = runCatching { File(path).lastModified() }.getOrDefault(0L)
                    )
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Scan local (parcours de dossiers, hors index MediaStore)
    // ---------------------------------------------------------------------

    private fun scanWatchedLocalFolders(context: Context): List<LibraryTrack> {
        val watched = AudioProSettings.watchedFolders(context).filterNot { it.isNetwork }
        if (watched.isEmpty()) return emptyList()
        val out = ArrayList<LibraryTrack>()
        val seen = HashSet<String>()
        watched.forEach { folder ->
            val rootFile = File(folder.path)
            if (!rootFile.exists() || !rootFile.isDirectory) return@forEach
            runCatching {
                rootFile.walkTopDown()
                    .onEnter { !it.name.startsWith(".") }
                    .filter { it.isFile && it.extension.lowercase(Locale.getDefault()) in AudioLibraryHeuristics.audioExtensions }
                    .take(MAX_LOCAL_SCAN_TRACKS - out.size)
                    .forEach { file ->
                        if (!seen.add(file.absolutePath)) return@forEach
                        // Passe 1 : squelette fichier uniquement, jamais l'ancien cache métadonnées
                        // (une suppression/renommage NAS/local ne doit pas réinjecter de vieux libellés).
                        val folderMeta = AudioLibraryHeuristics.folderMetadata(file.absolutePath, file.name)
                        out += LibraryTrack(
                            id = -abs(file.absolutePath.hashCode()).toLong(),
                            title = folderMeta.title,
                            artist = folderMeta.artist,
                            album = folderMeta.album,
                            durationMs = 0L,
                            trackNo = AudioLibraryHeuristics.inferTrackNo(file.name),
                            path = file.absolutePath,
                            addedAt = file.lastModified() / 1000L,
                            artworkPath = ThumbnailUtils.preferredFolderCoverPathForAudioPath(file.absolutePath)
                                ?: file.absolutePath,
                            source = LibraryTrackSource.LOCAL,
                            sourceLabel = folder.name.ifBlank { "Local" },
                            titleFromTag = false,
                            albumFromTag = false,
                            artistFromTag = false,
                            container = AudioLibraryHeuristics.containerFrom("", file.absolutePath),
                            sizeBytes = file.length(),
                            modifiedAt = file.lastModified()
                        )
                    }
            }
        }
        return out.distinctBy { it.path }
    }

    // ---------------------------------------------------------------------
    // Scan réseau (SMB / UPnP)
    // ---------------------------------------------------------------------

    private suspend fun scanWatchedNetworkFolders(
        context: Context,
        manual: Boolean,
        isPlaybackCritical: () -> Boolean,
        onProgress: suspend (List<LibraryTrack>) -> Unit
    ): NetworkFolderScanResult {
        val watched = AudioProSettings.watchedFolders(context).filter { it.isNetwork }
        if (watched.isEmpty()) return NetworkFolderScanResult(emptyList(), emptyList())
        val shares = runCatching { networkRepository.getShares().first().associateBy { it.id } }.getOrDefault(emptyMap())
        val settings = AudioProSettings.read(context)
        val allTracks = mutableListOf<LibraryTrack>()
        val confirmedFolders = mutableListOf<AudioProSettings.WatchedFolder>()

        // Un seul parcours NAS à la fois. Plusieurs listings SMB concurrents entraient en
        // compétition avec le flux audio et pouvaient provoquer des rebufferings, voire une
        // pression mémoire suffisante pour recréer l'Activity.
        for (folder in watched) {
            if (!currentCoroutineContext().isActive) break
            AudioLibraryWorkState.awaitPlaybackIdle(isPlaybackCritical)
            if (!currentCoroutineContext().isActive) break

            val share = shares[folder.shareId] ?: continue
            val result = mutableListOf<LibraryTrack>()
            val seen = mutableSetOf<String>()
            val progress = NetworkProgressEmitter(onProgress)
            val confirmed = scanNetworkFolder(
                context,
                share,
                folder,
                folder.path,
                0,
                result,
                seen,
                settings,
                inheritedCover = "",
                manual = manual,
                isPlaybackCritical = isPlaybackCritical,
                progress = progress
            )
            progress.emitIfNeeded(result, force = true)
            allTracks += result
            if (confirmed) confirmedFolders += folder
        }

        return NetworkFolderScanResult(
            tracks = allTracks.distinctBy { it.path },
            confirmedFolders = confirmedFolders
        )
    }

    private suspend fun scanNetworkFolder(
        context: Context,
        share: NetworkShare,
        watchedFolder: AudioProSettings.WatchedFolder,
        browsePath: String,
        depth: Int,
        result: MutableList<LibraryTrack>,
        seen: MutableSet<String>,
        settings: AudioProSettings.Values,
        inheritedCover: String,
        manual: Boolean,
        isPlaybackCritical: () -> Boolean,
        progress: NetworkProgressEmitter
    ): Boolean {
        val scanLimit = if (manual) MAX_MANUAL_NETWORK_SCAN_TRACKS else MAX_NETWORK_SCAN_TRACKS
        if (!currentCoroutineContext().isActive || depth > MAX_NETWORK_DEPTH || result.size >= scanLimit) return false
        AudioLibraryWorkState.awaitPlaybackIdle(isPlaybackCritical)
        if (!currentCoroutineContext().isActive) return false
        val items = withTimeoutOrNull(if (manual) 30_000L else 6_000L) {
            runCatching {
                if (share.type == ShareType.UPNP) upnpBrowser.listFiles(share, browsePath.ifBlank { "0" }).getOrThrow()
                else smbBrowser.listFiles(share, browsePath, includeAudioCoverImages = true).getOrThrow()
            }.getOrDefault(emptyList())
        } ?: return false
        val currentFolderName = browsePath.replace('\\', '/').trimEnd('/').substringAfterLast('/', "")
        val folderCover = AudioLibraryHeuristics.pickNetworkFolderCover(items).ifBlank {
            // Une cover de dossier parent ne doit être héritée que par CD1/Disc 1/etc. Propager
            // l'image d'un dossier Artiste vers tous ses albums produisait des pochettes erronées
            // et empêchait ensuite le repli direct vers le cover.jpg réellement présent.
            inheritedCover.takeIf { AudioLibraryHeuristics.isDiscFolderName(currentFolderName) }.orEmpty()
        }
        val files = items.filter { AudioLibraryHeuristics.isAudioItem(it.extension, it.mimeType, it.path) }
        files.forEach { item ->
            if (result.size >= scanLimit || !seen.add(item.path)) return@forEach
            val name = item.name.ifBlank { AudioLibraryHeuristics.fileNameFromPath(item.path) }
            // Passe réseau : path/name/size/dossiers immédiatement ; seule la pochette peut être
            // enrichie ensuite, jamais les libellés texte.
            val networkLabel = context.getString(R.string.tab_network)
            val folderMeta = AudioLibraryHeuristics.folderMetadata(item.path, name)
            result += LibraryTrack(
                id = -abs(item.path.hashCode()).toLong(),
                title = folderMeta.title,
                artist = folderMeta.artist,
                album = folderMeta.album,
                durationMs = 0L,
                trackNo = AudioLibraryHeuristics.inferTrackNo(name),
                path = item.path,
                addedAt = System.currentTimeMillis() / 1000L,
                artworkPath = folderCover,
                source = LibraryTrackSource.NETWORK,
                sourceLabel = networkLabel,
                titleFromTag = false,
                albumFromTag = false,
                artistFromTag = false,
                container = AudioLibraryHeuristics.containerFrom(item.extension, item.path),
                sizeBytes = item.size,
                modifiedAt = item.modifiedAt
            )
        }
        progress.emitIfNeeded(result)
        val folders = items.filter { it.mimeType == "folder" || it.mimeType == "share" }
        var complete = result.size < scanLimit
        for (folder in folders) {
            if (result.size >= scanLimit) {
                complete = false
                break
            }
            if (!scanNetworkFolder(
                    context,
                    share,
                    watchedFolder,
                    folder.path,
                    depth + 1,
                    result,
                    seen,
                    settings,
                    folderCover,
                    manual,
                    isPlaybackCritical,
                    progress
                )) {
                complete = false
            }
        }
        return complete
    }
}

// ---------------------------------------------------------------------
// Conversions Room <-> domaine, extraites de AudioLibraryActivity.
// ---------------------------------------------------------------------

private fun LibraryTrack.toMetadataUpdateEntity(info: AudioTechnicalInfo): AudioLibraryTrackEntity {
    val folderMeta = AudioLibraryHeuristics.folderMetadata(path, AudioLibraryHeuristics.fileNameFromPath(path))
    return AudioLibraryTrackEntity(
        path = path,
        name = AudioLibraryHeuristics.fileNameFromPath(path),
        title = folderMeta.title,
        artist = folderMeta.artist,
        album = folderMeta.album,
        durationMs = if (info.duration > 0L) info.duration * 1000L else 0L,
        trackNumber = AudioLibraryHeuristics.inferTrackNo(AudioLibraryHeuristics.fileNameFromPath(path)),
        addedAt = addedAt,
        extension = info.extension,
        isNetwork = source == LibraryTrackSource.NETWORK,
        shareId = "",
        sourceLabel = sourceLabel,
        // Volontairement vide : les données techniques ne doivent jamais toucher la pochette déjà
        // connue en base. Elle est mise à jour séparément, après résolution embedded/cover.jpg/png.
        artworkPath = "",
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        folderKey = "",
        seenGeneration = 0L,
        titleFromTag = false,
        artistFromTag = false,
        albumFromTag = false,
        metadataVersion = 0,
        artworkVersion = 0,
        deleted = false,
        albumSortKey = "",
        artistSortKey = "",
        titleSortKey = ""
    )
}

private fun AudioLibraryTrackEntity.toLibraryTrack(context: Context): LibraryTrack {
    val folderMeta = AudioLibraryHeuristics.folderMetadata(path, name.ifBlank { AudioLibraryHeuristics.fileNameFromPath(path) })
    return LibraryTrack(
        id = -abs(path.hashCode()).toLong(),
        title = folderMeta.title,
        artist = folderMeta.artist,
        album = folderMeta.album,
        durationMs = durationMs,
        trackNo = AudioLibraryHeuristics.inferTrackNo(name.ifBlank { AudioLibraryHeuristics.fileNameFromPath(path) }),
        path = path,
        addedAt = addedAt,
        artworkPath = artworkPath.ifBlank { path },
        source = if (isNetwork) LibraryTrackSource.NETWORK else LibraryTrackSource.LOCAL,
        sourceLabel = sourceLabel.ifBlank { if (isNetwork) context.getString(R.string.tab_network) else "Local" },
        titleFromTag = false,
        albumFromTag = false,
        artistFromTag = false,
        container = AudioLibraryHeuristics.containerFrom(extension, path),
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt
    )
}

private fun LibraryTrack.toRoomEntity(context: Context, generation: Long, watchedFolders: List<AudioProSettings.WatchedFolder>): AudioLibraryTrackEntity? {
    val folder = watchedFolders.firstOrNull {
        if (it.isNetwork) AudioLibraryHeuristics.belongsToNetworkFolder(path, it) else AudioLibraryHeuristics.belongsToLocalFolder(path, it)
    } ?: return null
    val localFile = if (!folder.isNetwork) runCatching { File(path) }.getOrNull() else null
    val size = sizeBytes.takeIf { it > 0L } ?: localFile?.takeIf { it.exists() }?.length() ?: 0L
    val mtime = modifiedAt.takeIf { it > 0L } ?: localFile?.takeIf { it.exists() }?.lastModified() ?: 0L
    val ext = AudioLibraryHeuristics.containerLabel(this).ifBlank { path.substringBefore('?').substringAfterLast('.', "") }
    val folderMeta = AudioLibraryHeuristics.folderMetadata(path, AudioLibraryHeuristics.fileNameFromPath(path))
    return AudioLibraryTrackEntity(
        path = path,
        name = AudioLibraryHeuristics.fileNameFromPath(path),
        title = folderMeta.title,
        artist = folderMeta.artist,
        album = folderMeta.album,
        durationMs = durationMs,
        trackNumber = AudioLibraryHeuristics.inferTrackNo(AudioLibraryHeuristics.fileNameFromPath(path)),
        addedAt = addedAt.takeIf { it > 0L } ?: (System.currentTimeMillis() / 1000L),
        extension = ext,
        isNetwork = folder.isNetwork || source == LibraryTrackSource.NETWORK || AudioLibraryHeuristics.isNetworkPath(path),
        shareId = folder.shareId,
        sourceLabel = if (folder.isNetwork) context.getString(R.string.tab_network) else folder.name.ifBlank { "Local" },
        artworkPath = artworkPath,
        sizeBytes = size,
        modifiedAt = mtime,
        folderKey = AudioWatchedLibraryCache.key(folder),
        seenGeneration = generation,
        titleFromTag = false,
        artistFromTag = false,
        albumFromTag = false,
        metadataVersion = if (durationMs > 0L || trackNo > 0) 1 else 0,
        artworkVersion = if (artworkPath.isNotBlank()) 1 else 0,
        deleted = false,
        albumSortKey = "",
        artistSortKey = "",
        titleSortKey = ""
    )
}
