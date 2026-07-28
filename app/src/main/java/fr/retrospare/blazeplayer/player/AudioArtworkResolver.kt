package fr.retrospare.blazeplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Point d'entrée unique pour toutes les pochettes audio.
 *
 * Ordre strict :
 * 1. cover.jpg / cover.png déjà indexé dans Room pour la piste ;
 * 2. recherche cover.jpg / cover.png dans le dossier de la piste (et dossier album pour CD1/Disc 1) ;
 * 3. pochette embarquée dans le fichier audio.
 *
 * Quand une image est résolue via le chemin indexé, elle est aussi enregistrée sous le chemin de
 * la piste. Le player, les mini-players, le détail album, les navigateurs et les files réutilisent
 * alors exactement le même bitmap/cache que la grille « Mes albums ».
 */
object AudioArtworkResolver {

    private const val JPEG_QUALITY = 95
    private val indexedArtworkPaths = ConcurrentHashMap<String, String>()
    /** Évite de réécrire Room et le snapshot à chaque rebind d'une cover.jpg déjà validée. */
    private val resolvedFolderSources = ConcurrentHashMap<String, String>()

    fun invalidateIndexedPaths() {
        indexedArtworkPaths.clear()
        resolvedFolderSources.clear()
    }

    /**
     * Synchronise le cache de chemins du résolveur avec le snapshot/Room. Sans cette étape, une
     * piste sœur du même album pouvait conserver en RAM une ancienne cover embarquée et la refaire
     * apparaître dans le player malgré la nouvelle pochette persistée au niveau de l'album.
     */
    fun rememberPersistedArtworkPaths(audioPaths: Collection<String>, persistedPath: String) {
        if (persistedPath.isBlank()) return
        audioPaths.asSequence()
            .filter { it.isNotBlank() }
            .forEach { audioPath -> indexedArtworkPaths[audioPath] = persistedPath }
    }

    private fun isRemoteArtworkPath(path: String): Boolean =
        path.startsWith("http://", true) || path.startsWith("https://", true)

    private fun isContentArtworkPath(path: String): Boolean =
        path.startsWith("content://", true)

    private fun isPreferredCoverPath(path: String): Boolean =
        isRemoteArtworkPath(path) ||
            (
                AudioLibraryHeuristics.isImagePath(path) &&
                    AudioLibraryHeuristics.isPreferredCoverName(
                        AudioLibraryHeuristics.fileNameFromPath(path)
                    )
            )

    /**
     * Room peut contenir soit le cover.jpg/cover.png d'origine, soit le JPEG persistant créé par
     * l'app. Les deux sont des sources valides pour le player. L'ancienne version n'acceptait que
     * les fichiers nommés exactement cover.*, ce qui faisait perdre les pochettes déjà persistées
     * et les chemins transmis par la bibliothèque.
     */
    private fun sameMediaPath(left: String, right: String): Boolean =
        left.substringBefore('#').trim().replace('\\', '/') ==
            right.substringBefore('#').trim().replace('\\', '/')

    private fun isUsableArtworkPath(
        path: String,
        audioPath: String = ""
    ): Boolean =
        path.isNotBlank() &&
            (
                AudioLibraryHeuristics.isImagePath(path) ||
                    isRemoteArtworkPath(path) ||
                    isContentArtworkPath(path)
            ) &&
            !AudioArtworkPersistence.isLegacyPersistedPath(path) &&
            (
                audioPath.isBlank() ||
                    !sameMediaPath(path, audioPath)
            )

    private suspend fun indexedArtworkPath(context: Context, audioPath: String): String? {
        if (audioPath.isBlank()) return null
        indexedArtworkPaths[audioPath]?.let { cached ->
            return cached.takeIf {
                isUsableArtworkPath(it, audioPath)
            }
        }
        // Le dispatcher de l'appelant détermine la classe d'exécution : player, mini-player,
        // bind visible ou hydratation. Ne jamais rebondir vers Dispatchers.IO global, sinon les
        // pochettes du lecteur et la bibliothèque se retrouvent de nouveau sur le même pool.
        val indexed = runCatching {
            AudioLibraryRoomDatabase.get(context.applicationContext)
                .trackDao()
                .byPath(audioPath)
                ?.artworkPath
                .orEmpty()
        }.getOrDefault("")
        // Mémorise aussi l'absence / un artworkPath non-cover pour éviter une requête Room à
        // chaque rebind du même titre. Le refresh de bibliothèque invalide explicitement ce cache.
        val safeIndexed = indexed.takeUnless(AudioArtworkPersistence::isLegacyPersistedPath).orEmpty()
        indexedArtworkPaths[audioPath] = safeIndexed
        return safeIndexed.takeIf {
            isUsableArtworkPath(it, audioPath)
        }
    }

    private fun explicitPreferredPath(
        candidate: String?,
        audioPath: String = ""
    ): String? = candidate.orEmpty().takeIf {
        isUsableArtworkPath(it, audioPath)
    }

    /** Cache RAM uniquement, sans accès disque/Room/réseau. */
    fun memoryCachedBitmap(audioPath: String, preferredArtworkPath: String? = null): Bitmap? {
        val explicit = explicitPreferredPath(preferredArtworkPath, audioPath)
        if (explicit != null && audioPath.isNotBlank()) indexedArtworkPaths[audioPath] = explicit
        val preferred = explicit ?: indexedArtworkPaths[audioPath]?.takeIf(::isUsableArtworkPath)
        preferred?.let { ThumbnailUtils.getMemoryCachedAudioArtworkBitmapNoIo(it)?.let { bitmap -> return bitmap } }
        return ThumbnailUtils.getMemoryCachedAudioArtworkBitmapNoIo(audioPath)
    }

    /** Cache RAM + disque, sans extraction ni listing de dossier. */
    fun cachedBitmap(context: Context, audioPath: String, preferredArtworkPath: String? = null): Bitmap? {
        val appContext = context.applicationContext
        val explicit = explicitPreferredPath(preferredArtworkPath, audioPath)
        if (explicit != null && audioPath.isNotBlank()) indexedArtworkPaths[audioPath] = explicit
        val preferred = explicit ?: indexedArtworkPaths[audioPath]?.takeIf(::isUsableArtworkPath)
        preferred?.let {
            ThumbnailUtils.getCachedAudioArtworkBitmapNoFolderProbe(appContext, it)?.let { bitmap ->
                if (audioPath.isNotBlank()) ThumbnailUtils.cacheResolvedAudioArtworkBitmap(appContext, audioPath, bitmap)
                return bitmap
            }
            AudioArtworkPersistence.loadBitmapFromPersistedPath(appContext, it)?.let { bitmap ->
                if (audioPath.isNotBlank()) ThumbnailUtils.cacheResolvedAudioArtworkBitmap(appContext, audioPath, bitmap)
                ThumbnailUtils.cacheResolvedAudioArtworkBitmap(appContext, it, bitmap)
                return bitmap
            }
        }
        ThumbnailUtils.getCachedAudioArtworkBitmapNoFolderProbe(appContext, audioPath)?.let { return it }
        return AudioArtworkPersistence.loadBitmap(appContext, audioPath)?.also { bitmap ->
            ThumbnailUtils.cacheResolvedAudioArtworkBitmap(appContext, audioPath, bitmap)
        }
    }

    /**
     * Charge uniquement une image externe déjà découverte par le listing.
     * Aucun fichier audio n'est ouvert : cette voie peut donc être utilisée pendant le scan.
     */
    suspend fun resolveExternalArtworkOnly(
        context: Context,
        audioPath: String,
        artworkPath: String
    ): Bitmap? {
        val explicit = explicitPreferredPath(artworkPath, audioPath)
            ?: return null
        val appContext = context.applicationContext
        memoryCachedBitmap(audioPath, explicit)?.let { bitmap ->
            AudioArtworkPersistence.existingPath(appContext, audioPath)?.let { persisted ->
                adoptPersistedArtwork(appContext, audioPath, persisted)
            }
            return bitmap
        }
        cachedBitmap(appContext, audioPath, explicit)?.let { bitmap ->
            AudioArtworkPersistence.existingPath(appContext, audioPath)?.let { persisted ->
                adoptPersistedArtwork(appContext, audioPath, persisted)
            }
            return bitmap
        }

        val bitmap = ThumbnailUtils.getExplicitArtworkBitmap(
            appContext,
            explicit
        ) ?: return null
        return persistResolved(
            appContext,
            audioPath,
            bitmap,
            explicit
        )
    }

    /** Résolution complète et partagée par tous les écrans. */
    suspend fun resolveBitmap(
        context: Context,
        audioPath: String,
        preferredArtworkPath: String? = null
    ): Bitmap? {
        if (audioPath.isBlank() && preferredArtworkPath.isNullOrBlank()) return null
        val appContext = context.applicationContext
        val explicit = explicitPreferredPath(preferredArtworkPath)
        if (explicit != null && audioPath.isNotBlank()) indexedArtworkPaths[audioPath] = explicit
        val preferred = explicit ?: indexedArtworkPath(appContext, audioPath)

        // La cover JPG/PNG du dossier est la source prioritaire absolue, même lorsqu'une ancienne
        // pochette embedded a déjà été persistée pour cette piste. C'était la cause principale des
        // albums dont la grille affichait cover.jpg alors que le player réutilisait encore un cache
        // historique différent (et calculait donc parfois la mauvaise couleur ou aucune couleur).
        val folderCover = when {
            explicit != null && isPreferredCoverPath(explicit) -> explicit
            else -> ThumbnailUtils.fastPreferredFolderCoverPathForAudioPath(audioPath)
                ?: ThumbnailUtils.preferredFolderCoverPathForAudioPath(audioPath)
        }
        if (!folderCover.isNullOrBlank()) {
            val folderBitmap = ThumbnailUtils.getCachedAudioArtworkBitmapNoFolderProbe(appContext, folderCover)
                ?: ThumbnailUtils.getExplicitArtworkBitmap(
                    appContext,
                    folderCover
                )
            if (folderBitmap != null) {
                // Alias immédiats pour la grille : les prochains binds retrouvent la cover sans I/O,
                // même si le snapshot pointe encore temporairement vers l'ancien chemin persistant.
                ThumbnailUtils.cacheResolvedAudioArtworkBitmap(appContext, folderCover, folderBitmap)
                ThumbnailUtils.cacheResolvedAudioArtworkBitmap(appContext, audioPath, folderBitmap)

                if (resolvedFolderSources[audioPath] == folderCover) {
                    preferred?.takeIf { AudioArtworkPersistence.isPersistedPath(appContext, it) }?.let {
                        ThumbnailUtils.cacheResolvedAudioArtworkBitmap(appContext, it, folderBitmap)
                    }
                    return folderBitmap
                }
                return persistResolved(appContext, audioPath, folderBitmap, folderCover).also {
                    resolvedFolderSources[audioPath] = folderCover
                }
            }
        }

        // Si Room pointe déjà vers notre JPEG persistant, il s'agit maintenant du meilleur repli
        // disponible après vérification de la cover de dossier. Il peut provenir d'une ancienne
        // extraction embedded ou d'une cover de dossier déjà normalisée.
        if (preferred != null && AudioArtworkPersistence.isPersistedPath(appContext, preferred)) {
            AudioArtworkPersistence.loadBitmapFromPersistedPath(appContext, preferred)?.let { bitmap ->
                cacheResolved(appContext, audioPath, preferred, bitmap)
                adoptPersistedArtwork(appContext, audioPath, preferred)
                return bitmap
            }
        }

        // Persistance stable par piste : repli rapide quand aucun JPG/PNG de dossier n'existe.
        AudioArtworkPersistence.loadBitmap(appContext, audioPath)?.let { bitmap ->
            val persisted = AudioArtworkPersistence.existingPath(appContext, audioPath)
                ?: preferred?.takeIf { AudioArtworkPersistence.isPersistedPath(appContext, it) }
            cacheResolved(appContext, audioPath, persisted ?: preferred, bitmap)
            if (!persisted.isNullOrBlank()) {
                indexedArtworkPaths[audioPath] = persisted
                adoptPersistedArtwork(appContext, audioPath, persisted)
            }
            return bitmap
        }

        if (audioPath.startsWith("smb://", true) && BlazePlayerService.isAudioPlaybackActive) return null

        if (preferred != null && preferred != folderCover) {
            ThumbnailUtils.getCachedAudioArtworkBitmapNoFolderProbe(appContext, preferred)?.let { bitmap ->
                return persistResolved(appContext, audioPath, bitmap, preferred)
            }
            ThumbnailUtils.getExplicitArtworkBitmap(
                appContext,
                preferred
            )?.let { bitmap ->
                return persistResolved(
                    appContext,
                    audioPath,
                    bitmap,
                    preferred
                )
            }
        }

        // Aucun fichier image explicite exploitable : extraction embarquée forcée. Une URL UPnP
        // sans extension reste bien traitée comme un fichier audio, jamais comme une albumArtURI.
        return resolveEmbeddedArtworkOnly(
            appContext,
            audioPath,
            AudioLibraryHeuristics.containerFrom(
                "",
                audioPath
            )
        )
    }

    suspend fun resolveEmbeddedArtworkOnly(
        context: Context,
        audioPath: String,
        declaredExtension: String
    ): Bitmap? {
        if (audioPath.isBlank()) return null
        val appContext = context.applicationContext
        cachedBitmap(appContext, audioPath, null)?.let { bitmap ->
            AudioArtworkPersistence.existingPath(appContext, audioPath)?.let { persisted ->
                adoptPersistedArtwork(appContext, audioPath, persisted)
            }
            return bitmap
        }
        val bitmap =
            ThumbnailUtils.getEmbeddedAudioArtworkBitmap(
                appContext,
                audioPath,
                declaredExtension
            ) ?: return null
        return persistResolved(
            appContext,
            audioPath,
            bitmap
        )
    }

    /**
     * Un JPEG persistant peut déjà être chaud dans les caches alors que le snapshot provient encore
     * d'un ancien squelette. On adopte ce chemin dans le snapshot et Room avant de rendre le bitmap,
     * afin qu'une navigation suivante ne relance jamais l'extraction embarquée.
     */
    private suspend fun adoptPersistedArtwork(
        context: Context,
        audioPath: String,
        persistedPath: String
    ) {
        if (audioPath.isBlank() || persistedPath.isBlank()) return
        indexedArtworkPaths[audioPath] = persistedPath
        val snapshot = AudioLibraryMemoryStore.current()
        val canonicalAudioPath = AudioLibraryHeuristics.canonicalPathKey(audioPath)
        val track = snapshot.tracksByPath[audioPath]
            ?: snapshot.trackIndexByCanonicalPath[canonicalAudioPath]
                ?.let(snapshot.tracks::get)
            ?: snapshot.tracks.firstOrNull {
                AudioLibraryHeuristics.canonicalPathKey(it.path) == canonicalAudioPath
            }
        val adoption = if (track == null) {
            Pair(listOf(audioPath), false)
        } else {
            val albumTracks = snapshot.albumTracksByKey[
                AudioLibraryHeuristics.albumKey(track)
            ].orEmpty()
            val targets = albumTracks.map(LibraryTrack::path).ifEmpty { listOf(track.path) }
            val alreadyAdopted = targets.all { targetPath ->
                val target = snapshot.tracksByPath[targetPath]
                    ?: snapshot.trackIndexByCanonicalPath[
                        AudioLibraryHeuristics.canonicalPathKey(targetPath)
                    ]?.let(snapshot.tracks::get)
                target?.artworkPath == persistedPath
            }
            if (!alreadyAdopted) {
                AudioLibraryMemoryStore.updateArtwork(audioPath, persistedPath)
            }
            Pair(targets, !alreadyAdopted)
        }
        val albumPaths = adoption.first
        rememberPersistedArtworkPaths(albumPaths, persistedPath)
        if (adoption.second) {
            runCatching {
                AudioLibraryRoomStore.updateArtworkPaths(
                    context.applicationContext,
                    albumPaths,
                    persistedPath
                )
            }
        }
    }

    private suspend fun persistResolved(
        context: Context,
        audioPath: String,
        bitmap: Bitmap,
        sourceArtworkPath: String? = null
    ): Bitmap {
        if (audioPath.isBlank()) return bitmap
        val persistedPath = AudioArtworkPersistence.persist(context, audioPath, bitmap)
        if (!persistedPath.isNullOrBlank()) {
            indexedArtworkPaths[audioPath] = persistedPath
            if (!sourceArtworkPath.isNullOrBlank() && sourceArtworkPath != persistedPath) {
                indexedArtworkPaths.entries.forEach { entry ->
                    if (entry.value == sourceArtworkPath) indexedArtworkPaths[entry.key] = persistedPath
                }
            }
            // Le snapshot mémoire est patché immédiatement ; Room est seulement la persistance.
            // La projection albums/artistes reste hors du thread principal, même si le résolveur
            // est appelé depuis une vue ou un contrôleur Media3.
            // updateArtwork propage maintenant la pochette uniquement au bucket de l'album.
            // Un second parcours global par source n'est plus nécessaire.
            AudioLibraryMemoryStore.updateArtwork(audioPath, persistedPath)
            val snapshot = AudioLibraryMemoryStore.current()
            val track = snapshot.tracksByPath[audioPath]
                ?: snapshot.tracks.firstOrNull {
                    AudioLibraryHeuristics.canonicalPathKey(
                        it.path
                    ) ==
                        AudioLibraryHeuristics.canonicalPathKey(
                            audioPath
                        )
                }
            val albumPaths = track?.let {
                snapshot.albumTracksByKey[
                    AudioLibraryHeuristics.albumKey(it)
                ]?.map(LibraryTrack::path)
            }.orEmpty()
            val targetPaths = albumPaths.ifEmpty { listOf(audioPath) }
            rememberPersistedArtworkPaths(targetPaths, persistedPath)
            targetPaths.forEach { targetPath ->
                cacheResolved(context, targetPath, persistedPath, bitmap)
            }
            runCatching {
                if (albumPaths.isNotEmpty()) {
                    AudioLibraryRoomStore.updateArtworkPaths(
                        context,
                        albumPaths,
                        persistedPath
                    )
                } else {
                    AudioLibraryRoomStore.updateArtworkPath(
                        context,
                        audioPath,
                        persistedPath
                    )
                }
            }
        } else {
            cacheResolved(context, audioPath, null, bitmap)
        }
        return bitmap
    }

    private fun cacheResolved(context: Context, audioPath: String, persistedPath: String?, bitmap: Bitmap) {
        if (audioPath.isNotBlank()) ThumbnailUtils.cacheResolvedAudioArtworkBitmap(context, audioPath, bitmap)
        if (!persistedPath.isNullOrBlank()) ThumbnailUtils.cacheResolvedAudioArtworkBitmap(context, persistedPath, bitmap)
    }

    fun cachedJpegBytes(context: Context, audioPath: String, preferredArtworkPath: String? = null): ByteArray? =
        cachedBitmap(context, audioPath, preferredArtworkPath)?.toJpegBytes()

    suspend fun resolveJpegBytes(
        context: Context,
        audioPath: String,
        preferredArtworkPath: String? = null
    ): ByteArray? = resolveBitmap(context, audioPath, preferredArtworkPath)?.toJpegBytes()

    /** Variante pour les workers/services déjà exécutés hors du thread principal. */
    fun resolveJpegBytesBlocking(
        context: Context,
        audioPath: String,
        preferredArtworkPath: String? = null
    ): ByteArray? {
        if (audioPath.startsWith("smb://", true) && BlazePlayerService.isAudioPlaybackActive) return null
        val appContext = context.applicationContext
        val explicit = explicitPreferredPath(preferredArtworkPath)
        val folderCover = when {
            explicit != null && isPreferredCoverPath(explicit) -> explicit
            else -> ThumbnailUtils.fastPreferredFolderCoverPathForAudioPath(audioPath)
                ?: ThumbnailUtils.preferredFolderCoverPathForAudioPath(audioPath)
        }
        // Ne consulte le cache persistant qu'après le probe de cover.jpg/png. Sinon une ancienne
        // embedded mise en cache court-circuiterait encore la vraie pochette du dossier.
        val bytes = (folderCover?.let { coverPath ->
            ThumbnailUtils.getCachedAudioArtworkJpegBytesNoFolderProbe(appContext, coverPath)
                ?: ThumbnailUtils.getAudioArtworkJpegBytesBlocking(appContext, coverPath)
        } ?: cachedBitmap(context, audioPath, preferredArtworkPath)?.toJpegBytes()
            ?: explicit?.takeUnless { AudioArtworkPersistence.isPersistedPath(appContext, it) }?.let { preferred ->
                ThumbnailUtils.getAudioArtworkJpegBytesBlocking(appContext, preferred)
            } ?: ThumbnailUtils.getAudioArtworkJpegBytesBlocking(appContext, audioPath))
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        if (audioPath.isNotBlank()) {
            ThumbnailUtils.cacheAudioArtworkData(appContext, audioPath, bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                try {
                    AudioArtworkPersistence.persist(appContext, audioPath, bitmap)?.let { persistedPath ->
                        rememberPersistedArtworkPaths(listOf(audioPath), persistedPath)
                        cacheResolved(appContext, audioPath, persistedPath, bitmap)
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }
        return bytes
    }

    /**
     * Les covers externes chargées par Coil/MediaStore peuvent être des bitmaps HARDWARE. Leur
     * compression directe est susceptible d'échouer ; on crée alors une copie logicielle ARGB_8888
     * avant de générer les octets partagés avec Media3 et les mini-players.
     */
    private fun Bitmap.toJpegBytes(): ByteArray? {
        if (isRecycled || width <= 0 || height <= 0) return null
        val softwareCopy = if (config == Bitmap.Config.HARDWARE || config == null) {
            runCatching { copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        } else null
        val source = softwareCopy ?: this
        if (source.config == Bitmap.Config.HARDWARE) return null
        return try {
            ByteArrayOutputStream().use { out ->
                if (!source.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (_: Throwable) {
            null
        } finally {
            if (softwareCopy != null && softwareCopy !== this && !softwareCopy.isRecycled) {
                softwareCopy.recycle()
            }
        }
    }
}
