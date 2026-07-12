package fr.retrospare.blazeplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    fun invalidateIndexedPaths() {
        indexedArtworkPaths.clear()
    }

    private fun isPreferredCoverPath(path: String): Boolean =
        AudioLibraryHeuristics.isImagePath(path) &&
            AudioLibraryHeuristics.isPreferredCoverName(AudioLibraryHeuristics.fileNameFromPath(path))

    private suspend fun indexedArtworkPath(context: Context, audioPath: String): String? {
        if (audioPath.isBlank()) return null
        indexedArtworkPaths[audioPath]?.let { cached ->
            return cached.takeIf(::isPreferredCoverPath)
                ?.takeUnless(AudioArtworkPersistence::isLegacyPersistedPath)
        }
        val indexed = withContext(Dispatchers.IO) {
            runCatching {
                AudioLibraryRoomDatabase.get(context.applicationContext)
                    .trackDao()
                    .byPath(audioPath)
                    ?.artworkPath
                    .orEmpty()
            }.getOrDefault("")
        }
        // Mémorise aussi l'absence / un artworkPath non-cover pour éviter une requête Room à
        // chaque rebind du même titre. Le refresh de bibliothèque invalide explicitement ce cache.
        val safeIndexed = indexed.takeUnless(AudioArtworkPersistence::isLegacyPersistedPath).orEmpty()
        indexedArtworkPaths[audioPath] = safeIndexed
        return safeIndexed.takeIf(::isPreferredCoverPath)
    }

    private fun explicitPreferredPath(candidate: String?): String? =
        candidate.orEmpty()
            .takeIf(::isPreferredCoverPath)
            ?.takeUnless(AudioArtworkPersistence::isLegacyPersistedPath)

    /** Cache RAM uniquement, sans accès disque/Room/réseau. */
    fun memoryCachedBitmap(audioPath: String, preferredArtworkPath: String? = null): Bitmap? {
        val explicit = explicitPreferredPath(preferredArtworkPath)
        if (explicit != null && audioPath.isNotBlank()) indexedArtworkPaths[audioPath] = explicit
        val preferred = explicit ?: indexedArtworkPaths[audioPath]?.takeIf(::isPreferredCoverPath)?.takeUnless(AudioArtworkPersistence::isLegacyPersistedPath)
        preferred?.let { ThumbnailUtils.getMemoryCachedAudioArtworkBitmapNoIo(it)?.let { bitmap -> return bitmap } }
        return ThumbnailUtils.getMemoryCachedAudioArtworkBitmapNoIo(audioPath)
    }

    /** Cache RAM + disque, sans extraction ni listing de dossier. */
    fun cachedBitmap(context: Context, audioPath: String, preferredArtworkPath: String? = null): Bitmap? {
        val appContext = context.applicationContext
        val explicit = explicitPreferredPath(preferredArtworkPath)
        if (explicit != null && audioPath.isNotBlank()) indexedArtworkPaths[audioPath] = explicit
        val preferred = explicit ?: indexedArtworkPaths[audioPath]?.takeIf(::isPreferredCoverPath)?.takeUnless(AudioArtworkPersistence::isLegacyPersistedPath)
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

        // Si Room pointe déjà vers notre JPEG persistant, aucune lecture du NAS ou du fichier audio
        // n'est nécessaire. C'est le chemin normal après la première extraction réussie.
        if (preferred != null && AudioArtworkPersistence.isPersistedPath(appContext, preferred)) {
            AudioArtworkPersistence.loadBitmapFromPersistedPath(appContext, preferred)?.let { bitmap ->
                cacheResolved(appContext, audioPath, preferred, bitmap)
                return bitmap
            }
        }

        // Persistance stable par piste, indépendante des clés temporaires du cache et des epochs
        // SMB. Elle passe avant toute nouvelle lecture réseau ou extraction du fichier source.
        AudioArtworkPersistence.loadBitmap(appContext, audioPath)?.let { bitmap ->
            cacheResolved(appContext, audioPath, preferred, bitmap)
            if (!preferred.isNullOrBlank() && AudioArtworkPersistence.isPersistedPath(appContext, preferred)) {
                indexedArtworkPaths[audioPath] = preferred
            }
            return bitmap
        }

        if (audioPath.startsWith("smb://", true) && BlazePlayerService.isAudioPlaybackActive) return null

        if (preferred != null) {
            ThumbnailUtils.getCachedAudioArtworkBitmapNoFolderProbe(appContext, preferred)?.let { bitmap ->
                return persistResolved(appContext, audioPath, bitmap, preferred)
            }
            ThumbnailUtils.getAudioArtworkBitmap(appContext, preferred)?.let { bitmap ->
                return persistResolved(appContext, audioPath, bitmap, preferred)
            }
        }

        ThumbnailUtils.getAudioArtworkBitmap(appContext, audioPath)?.let { bitmap ->
            return persistResolved(appContext, audioPath, bitmap)
        }
        return null
    }

    private suspend fun persistResolved(
        context: Context,
        audioPath: String,
        bitmap: Bitmap,
        sourceArtworkPath: String? = null
    ): Bitmap {
        if (audioPath.isBlank()) return bitmap
        val persistedPath = withContext(Dispatchers.IO) {
            AudioArtworkPersistence.persist(context, audioPath, bitmap)
        }
        if (!persistedPath.isNullOrBlank()) {
            indexedArtworkPaths[audioPath] = persistedPath
            if (!sourceArtworkPath.isNullOrBlank() && sourceArtworkPath != persistedPath) {
                indexedArtworkPaths.entries.forEach { entry ->
                    if (entry.value == sourceArtworkPath) indexedArtworkPaths[entry.key] = persistedPath
                }
            }
            withContext(Dispatchers.IO) {
                runCatching { AudioLibraryRoomStore.updateArtworkPath(context, audioPath, persistedPath) }
                if (!sourceArtworkPath.isNullOrBlank()) {
                    runCatching {
                        AudioLibraryRoomStore.updateArtworkPathForSource(context, sourceArtworkPath, persistedPath)
                    }
                }
            }
            cacheResolved(context, audioPath, persistedPath, bitmap)
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
        cachedBitmap(context, audioPath, preferredArtworkPath)?.toJpegBytes()?.let { return it }
        if (audioPath.startsWith("smb://", true) && BlazePlayerService.isAudioPlaybackActive) return null
        val appContext = context.applicationContext
        val bytes = (explicitPreferredPath(preferredArtworkPath)?.let { preferred ->
            ThumbnailUtils.getAudioArtworkJpegBytesBlocking(appContext, preferred)
        } ?: ThumbnailUtils.getAudioArtworkJpegBytesBlocking(appContext, audioPath))
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        if (audioPath.isNotBlank()) {
            ThumbnailUtils.cacheAudioArtworkData(appContext, audioPath, bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                AudioArtworkPersistence.persist(appContext, audioPath, bitmap)?.let { persistedPath ->
                    indexedArtworkPaths[audioPath] = persistedPath
                    cacheResolved(appContext, audioPath, persistedPath, bitmap)
                }
            }
        }
        return bytes
    }

    private fun Bitmap.toJpegBytes(): ByteArray? = try {
        ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    } catch (_: Exception) {
        null
    }
}
