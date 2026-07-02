package fr.retrospare.blazeplayer.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Informations audio "brutes" — jamais de texte déjà formaté/traduit ici (pas de "Lossless" ni
 *  de "320 kbps" tout fait), pour que l'affichage reste correct quelle que soit la langue active
 *  au moment de la lecture depuis le cache, potentiellement bien après l'extraction initiale. */
data class AudioTechnicalInfo(
    val artist: String = "",
    val duration: Long = 0L,   // secondes
    val bitrate: Long = 0L,    // bits par seconde
    val extension: String = "",
    val isLossless: Boolean = false
)

/** Extraction + cache (mémoire et disque) des métadonnées texte des fichiers audio — artiste,
 *  durée, débit, extension. Reprend le même schéma que [VideoMetadataExtractor] : sans ça,
 *  chaque écran (file d'attente, navigateur audio...) refaisait sa propre extraction
 *  MediaMetadataRetriever à chaque affichage, sans jamais persister le résultat, ce qui était
 *  particulièrement lent sur les fichiers réseau à chaque réouverture de l'app.
 *
 *  Les pochettes (bitmap) restent gérées par [fr.retrospare.blazeplayer.ui.ThumbnailUtils], qui a
 *  déjà un cache disque dédié aux images — pas la peine de dupliquer cette logique ici. */
object AudioMetadataExtractor {

    private val cache = ConcurrentHashMap<String, AudioTechnicalInfo>()
    private const val DISK_CACHE_PREFS = "blaze_audio_metadata_cache"
    private val LOSSLESS_EXTENSIONS = setOf("FLAC", "WAV", "ALAC", "APE", "AIFF")

    suspend fun extract(context: Context, path: String, name: String): AudioTechnicalInfo {
        cache[path]?.let { return it }
        return withContext(Dispatchers.IO) {
            loadFromDisk(context, path)?.let {
                cache[path] = it
                return@withContext it
            }
            val info = extractInternal(context, path, name)
            cache[path] = info
            saveToDisk(context, path, info)
            info
        }
    }

    fun getCached(path: String): AudioTechnicalInfo? = cache[path]

    fun clearCache() = cache.clear()

    /** Vide aussi le cache disque persistant. */
    fun clearDiskCache(context: Context) {
        try {
            context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {
            android.util.Log.w("AudioMetadataExtractor", "Failed to clear disk metadata cache", e)
        }
    }

    /** Clé de cache disque : hash du chemin (évite les soucis de longueur/caractères spéciaux
     *  des chemins réseau smb://, tout en restant stable pour un même fichier). */
    private fun diskKey(path: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(path.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun loadFromDisk(context: Context, path: String): AudioTechnicalInfo? {
        val prefs = context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(diskKey(path), null) ?: return null
        return try {
            val parts = raw.split("|")
            AudioTechnicalInfo(
                artist = parts[0],
                duration = parts[1].toLong(),
                bitrate = parts[2].toLong(),
                extension = parts[3],
                isLossless = parts[4].toBoolean()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToDisk(context: Context, path: String, info: AudioTechnicalInfo) {
        // N'écrit sur disque que si l'extraction a effectivement trouvé quelque chose : évite de
        // mettre en cache un échec temporaire (ex: partage réseau momentanément indisponible).
        if (info.duration <= 0L && info.artist.isEmpty() && info.bitrate <= 0L) return
        val raw = listOf(info.artist, info.duration, info.bitrate, info.extension, info.isLossless)
            .joinToString("|")
        context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(diskKey(path), raw).apply()
    }

    private fun extractInternal(context: Context, path: String, name: String): AudioTechnicalInfo {
        var smbDataSource: SmbMediaDataSource? = null
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                when {
                    path.startsWith("smb://") -> {
                        smbDataSource = SmbMediaDataSource(path)
                        retriever.setDataSource(smbDataSource)
                    }
                    path.startsWith("content://") -> retriever.setDataSource(context, android.net.Uri.parse(path))
                    else -> retriever.setDataSource(path)
                }
                val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim() ?: ""
                val bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val ext = name.substringAfterLast(".", "").uppercase()
                val lossless = ext in LOSSLESS_EXTENSIONS
                AudioTechnicalInfo(
                    artist = artist,
                    duration = durationMs / 1000,
                    bitrate = bitrate,
                    extension = ext,
                    isLossless = lossless
                )
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            AudioTechnicalInfo(extension = name.substringAfterLast(".", "").uppercase())
        } finally {
            try { smbDataSource?.close() } catch (_: Exception) {}
        }
    }
}
