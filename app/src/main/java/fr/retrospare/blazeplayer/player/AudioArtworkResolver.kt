package fr.retrospare.blazeplayer.player

import android.content.Context
import android.graphics.Bitmap
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
        indexedArtworkPaths[audioPath] = indexed
        return indexed.takeIf(::isPreferredCoverPath)
    }

    private fun explicitPreferredPath(candidate: String?): String? =
        candidate.orEmpty().takeIf(::isPreferredCoverPath)

    /** Cache RAM uniquement, sans accès disque/Room/réseau. */
    fun memoryCachedBitmap(audioPath: String, preferredArtworkPath: String? = null): Bitmap? {
        val explicit = explicitPreferredPath(preferredArtworkPath)
        if (explicit != null && audioPath.isNotBlank()) indexedArtworkPaths[audioPath] = explicit
        val preferred = explicit ?: indexedArtworkPaths[audioPath]?.takeIf(::isPreferredCoverPath)
        preferred?.let { ThumbnailUtils.getMemoryCachedAudioArtworkBitmapNoIo(it)?.let { bitmap -> return bitmap } }
        return ThumbnailUtils.getMemoryCachedAudioArtworkBitmapNoIo(audioPath)
    }

    /** Cache RAM + disque, sans extraction ni listing de dossier. */
    fun cachedBitmap(context: Context, audioPath: String, preferredArtworkPath: String? = null): Bitmap? {
        val explicit = explicitPreferredPath(preferredArtworkPath)
        if (explicit != null && audioPath.isNotBlank()) indexedArtworkPaths[audioPath] = explicit
        val preferred = explicit ?: indexedArtworkPaths[audioPath]?.takeIf(::isPreferredCoverPath)
        preferred?.let {
            ThumbnailUtils.getCachedAudioArtworkBitmapNoFolderProbe(context.applicationContext, it)?.let { bitmap ->
                if (audioPath.isNotBlank()) ThumbnailUtils.cacheResolvedAudioArtworkBitmap(context, audioPath, bitmap)
                return bitmap
            }
        }
        return ThumbnailUtils.getCachedAudioArtworkBitmapNoFolderProbe(context.applicationContext, audioPath)
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

        if (preferred != null) {
            ThumbnailUtils.getCachedAudioArtworkBitmapNoFolderProbe(appContext, preferred)?.let { bitmap ->
                if (audioPath.isNotBlank()) ThumbnailUtils.cacheResolvedAudioArtworkBitmap(appContext, audioPath, bitmap)
                return bitmap
            }
            ThumbnailUtils.getAudioArtworkBitmap(appContext, preferred)?.let { bitmap ->
                if (audioPath.isNotBlank()) ThumbnailUtils.cacheResolvedAudioArtworkBitmap(appContext, audioPath, bitmap)
                return bitmap
            }
        }

        return ThumbnailUtils.getAudioArtworkBitmap(appContext, audioPath)
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
        explicitPreferredPath(preferredArtworkPath)?.let { preferred ->
            ThumbnailUtils.getAudioArtworkJpegBytesBlocking(context.applicationContext, preferred)?.let { bytes ->
                if (audioPath.isNotBlank()) ThumbnailUtils.cacheAudioArtworkData(context, audioPath, bytes)
                return bytes
            }
        }
        return ThumbnailUtils.getAudioArtworkJpegBytesBlocking(context.applicationContext, audioPath)
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
