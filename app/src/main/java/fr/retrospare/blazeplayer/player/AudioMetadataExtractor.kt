package fr.retrospare.blazeplayer.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    val isLossless: Boolean = false,
    val title: String = "",
    val album: String = "",
    val trackNumber: Int = 0
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
    private const val CACHE_VERSION = 3
    private val LOSSLESS_EXTENSIONS = setOf("FLAC", "WAV", "ALAC", "APE", "AIFF")
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<AudioTechnicalInfo>>()

    /**
     * Un cache peut être volontairement partiel : AudioRepository y dépose parfois seulement
     * titre/artiste/album/extension pendant la construction du MediaItem, avant que le débit ne
     * soit extrait. Avant ce correctif, extract() retournait ce cache partiel comme s'il était
     * complet : les MP3/AAC/OGG/etc. restaient donc sans badge bitrate, alors que les FLAC
     * affichaient quand même "Lossless" grâce à l'extension. On ne court-circuite maintenant
     * l'extraction que si le cache contient déjà de quoi afficher le badge qualité.
     */
    private fun hasQualityBadge(info: AudioTechnicalInfo): Boolean {
        val ext = info.extension.uppercase()
        return info.bitrate > 0L || info.isLossless || ext in LOSSLESS_EXTENSIONS
    }

    private fun mergeKnownMetadata(fresh: AudioTechnicalInfo, previous: AudioTechnicalInfo?): AudioTechnicalInfo {
        if (previous == null) return fresh
        return AudioTechnicalInfo(
            artist = fresh.artist.ifBlank { previous.artist },
            duration = if (fresh.duration > 0L) fresh.duration else previous.duration,
            bitrate = if (fresh.bitrate > 0L) fresh.bitrate else previous.bitrate,
            extension = fresh.extension.ifBlank { previous.extension },
            isLossless = fresh.isLossless || previous.isLossless,
            title = fresh.title.ifBlank { previous.title },
            album = fresh.album.ifBlank { previous.album },
            trackNumber = if (fresh.trackNumber > 0) fresh.trackNumber else previous.trackNumber
        )
    }

    suspend fun extract(context: Context, path: String, name: String): AudioTechnicalInfo {
        cache[path]?.takeIf { hasQualityBadge(it) }?.let { return it }
        return withContext(Dispatchers.IO) {
            val cached = cache[path] ?: loadFromDisk(context, path)?.also { cache[path] = it }
            if (cached != null && hasQualityBadge(cached)) return@withContext cached
            inFlight[path]?.let { return@withContext it.await() }
            val deferred = async {
                val fallbackExt = name.substringAfterLast(".", "").uppercase().ifBlank { cached?.extension.orEmpty() }
                val extracted = kotlinx.coroutines.withTimeoutOrNull(if (path.startsWith("smb://")) 3_000L else 5_000L) {
                    extractInternal(context, path, name)
                } ?: AudioTechnicalInfo(extension = fallbackExt)
                val info = mergeKnownMetadata(extracted, cached)
                cache[path] = info
                saveToDisk(context, path, info)
                info
            }
            inFlight[path] = deferred
            try { deferred.await() } finally { inFlight.remove(path, deferred) }
        }
    }

    fun getCached(path: String): AudioTechnicalInfo? = cache[path]

    /** Lecture cache mémoire+disque sans extraction, pour afficher les titres/artistes audio
     *  immédiatement à la réouverture d'un dossier ou de la file d'attente. */
    fun getCached(context: Context, path: String): AudioTechnicalInfo? {
        cache[path]?.let { return it }
        val disk = loadFromDisk(context, path) ?: return null
        cache[path] = disk
        return disk
    }

    fun clearCache() = cache.clear()

    /** Met à jour le cache mémoire + disque avec des métadonnées déjà connues par Media3 ou le player.
     *  Utilisé notamment à la restauration de la file audio : évite que l'artiste retombe sur
     *  "Unknown" quand le fichier réseau n'a pas encore été ré-ouvert. */
    fun putCached(context: Context, path: String, info: AudioTechnicalInfo) {
        if (path.isBlank()) return
        val previous = cache[path]
        val merged = AudioTechnicalInfo(
            artist = info.artist.ifBlank { previous?.artist.orEmpty() },
            duration = if (info.duration > 0L) info.duration else previous?.duration ?: 0L,
            bitrate = if (info.bitrate > 0L) info.bitrate else previous?.bitrate ?: 0L,
            extension = info.extension.ifBlank { previous?.extension.orEmpty() },
            isLossless = info.isLossless || previous?.isLossless == true,
            title = info.title.ifBlank { previous?.title.orEmpty() },
            album = info.album.ifBlank { previous?.album.orEmpty() },
            trackNumber = if (info.trackNumber > 0) info.trackNumber else previous?.trackNumber ?: 0
        )
        cache[path] = merged
        saveToDisk(context, path, merged)
    }

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

    private fun enc(value: String): String = android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    private fun dec(value: String): String = String(android.util.Base64.decode(value, android.util.Base64.NO_WRAP), Charsets.UTF_8)

    private fun loadFromDisk(context: Context, path: String): AudioTechnicalInfo? {
        val prefs = context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(diskKey(path), null) ?: return null
        return try {
            val parts = raw.split("|")
            val version = parts.firstOrNull()?.toIntOrNull()
            if (version != null && version in 2..CACHE_VERSION) {
                AudioTechnicalInfo(
                    artist = dec(parts.getOrNull(1).orEmpty()),
                    duration = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                    bitrate = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
                    extension = parts.getOrNull(4).orEmpty(),
                    isLossless = parts.getOrNull(5)?.toBoolean() ?: false,
                    title = dec(parts.getOrNull(6).orEmpty()),
                    album = dec(parts.getOrNull(7).orEmpty()),
                    trackNumber = parts.getOrNull(8)?.toIntOrNull() ?: 0
                )
            } else {
                // Compat ancien cache v1 : artist|duration|bitrate|extension|lossless
                AudioTechnicalInfo(
                    artist = parts.getOrNull(0).orEmpty(),
                    duration = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
                    bitrate = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                    extension = parts.getOrNull(3).orEmpty(),
                    isLossless = parts.getOrNull(4)?.toBoolean() ?: false
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("AudioMetadataExtractor", "Failed to read audio metadata cache", e)
            null
        }
    }

    private fun saveToDisk(context: Context, path: String, info: AudioTechnicalInfo) {
        // N'écrit sur disque que si l'extraction a effectivement trouvé quelque chose : évite de
        // mettre en cache un échec temporaire (ex: partage réseau momentanément indisponible).
        if (info.duration <= 0L && info.artist.isEmpty() && info.title.isEmpty() && info.album.isEmpty() && info.bitrate <= 0L) return
        val raw = listOf(
            CACHE_VERSION.toString(), enc(info.artist), info.duration.toString(), info.bitrate.toString(),
            info.extension, info.isLossless.toString(), enc(info.title), enc(info.album), info.trackNumber.toString()
        ).joinToString("|")
        context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(diskKey(path), raw).apply()
    }

    private fun parseTrackNumber(raw: String?): Int {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return 0
        return value.substringBefore("/")
            .filter { it.isDigit() }
            .toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 0
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
                val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim() ?: ""
                val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim() ?: ""
                val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim() ?: ""
                val trackNumber = parseTrackNumber(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
                val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

                // METADATA_KEY_BITRATE n'est fiable que pour la vidéo : pour l'audio (MP3 notamment),
                // MediaMetadataRetriever renvoie très souvent null/0 selon l'appareil/la version
                // d'Android, alors même que le fichier a bien un débit. On calcule donc un débit
                // moyen de repli à partir de la taille du fichier et de la durée (taille en bits /
                // durée en secondes) — une approximation standard pour l'affichage d'un débit
                // "moyen", y compris pour le VBR, plutôt que de ne jamais afficher de badge.
                val rawBitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                val bitrate = if (rawBitrate > 0L) {
                    rawBitrate
                } else if (durationMs > 0L) {
                    val sizeBytes = fileSizeBytes(context, path, smbDataSource)
                    if (sizeBytes > 0L) (sizeBytes * 8_000L) / durationMs else 0L
                } else 0L

                val ext = name.substringAfterLast(".", "").uppercase()
                val lossless = ext in LOSSLESS_EXTENSIONS
                AudioTechnicalInfo(
                    artist = artist,
                    duration = durationMs / 1000,
                    bitrate = bitrate,
                    extension = ext,
                    isLossless = lossless,
                    title = title,
                    album = album,
                    trackNumber = trackNumber
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

    /** Taille du fichier, tous types de chemins confondus (local, content://, smb://), pour le
     *  calcul de débit de repli. Retourne -1 si indisponible. */
    private fun fileSizeBytes(context: Context, path: String, smbDataSource: SmbMediaDataSource?): Long {
        return try {
            when {
                path.startsWith("smb://") -> smbDataSource?.size ?: -1L
                path.startsWith("content://") -> context.contentResolver
                    .openFileDescriptor(android.net.Uri.parse(path), "r")
                    ?.use { it.statSize } ?: -1L
                else -> java.io.File(path).length().takeIf { it > 0L } ?: -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }
}
