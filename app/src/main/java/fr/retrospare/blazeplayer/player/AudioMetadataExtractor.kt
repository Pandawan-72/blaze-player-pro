package fr.retrospare.blazeplayer.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
    private const val CACHE_VERSION = 5
    private val LOSSLESS_EXTENSIONS = setOf("FLAC", "WAV", "ALAC", "APE", "AIFF", "WV")
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<AudioTechnicalInfo>>()
    private val durationInFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Long>>()
    private val qualityInFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<AudioTechnicalInfo>>()
    private val metadataDispatcher = Executors.newFixedThreadPool(4) { runnable ->
        Thread {
            // Pool technique borné : les durées manquantes sont calculées hors UI, sans modifier
            // les noms issus des dossiers. Priorité normale pour ne pas gêner la lecture audio.
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT) } catch (_: Exception) {}
            runnable.run()
        }.apply {
            name = "BlazeAudioMetadataPriority"
            isDaemon = true
            priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
        }
    }.asCoroutineDispatcher()

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

    /**
     * Suffisant pour éviter une ré-extraction lourde. Les anciennes versions du cache pouvaient
     * contenir uniquement bitrate/extension : utile pour le badge qualité, mais insuffisant pour
     * alimenter correctement Albums / Artistes / Titres. On exige donc au moins des métadonnées
     * d'affichage ou un numéro de piste en plus de la qualité/durée.
     */
    private fun isCompleteEnough(info: AudioTechnicalInfo): Boolean {
        val hasDisplayTags = info.title.isNotBlank() || info.artist.isNotBlank() || info.album.isNotBlank() || info.trackNumber > 0
        return (info.bitrate > 0L || info.isLossless) && info.duration > 0L && hasDisplayTags
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

    suspend fun extract(context: Context, path: String, name: String): AudioTechnicalInfo =
        extractCached(context, path, name, highPriority = false)

    /** Extraction prioritaire utilisée par l'accueil « Mes albums ». Elle partage le même cache et
     *  le même verrou in-flight que l'extraction normale, mais obtient les premiers créneaux du pool
     *  de tags et tolère davantage la latence d'un NAS avant de rendre une valeur vide. */
    suspend fun extractHighPriority(context: Context, path: String, name: String): AudioTechnicalInfo =
        extractCached(context, path, name, highPriority = true)

    private suspend fun extractCached(
        context: Context,
        path: String,
        name: String,
        highPriority: Boolean
    ): AudioTechnicalInfo {
        cache[path]?.takeIf { isCompleteEnough(it) }?.let { return it }
        return withContext(metadataDispatcher) {
            val cached = cache[path] ?: loadFromDisk(context, path)?.also { cache[path] = it }
            if (cached != null && isCompleteEnough(cached)) return@withContext cached
            inFlight[path]?.let { return@withContext it.await() }
            val deferred = async {
                val fallbackExt = name.substringAfterLast(".", "").uppercase().ifBlank { cached?.extension.orEmpty() }
                val timeoutMs = when {
                    path.startsWith("smb://", true) && highPriority -> 10_000L
                    path.startsWith("smb://", true) -> 4_000L
                    highPriority -> 7_000L
                    else -> 5_000L
                }
                val extracted = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
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



    /**
     * Extrait uniquement les informations nécessaires au badge qualité : durée, débit moyen,
     * extension et caractère lossless. Aucun tag texte (titre/artiste/album) n'est lu ni utilisé.
     * Cette passe est déclenchée à la demande par les vues qui affichent déjà le badge conteneur.
     */
    suspend fun extractQualityOnly(context: Context, path: String, name: String): AudioTechnicalInfo {
        if (path.isBlank()) return AudioTechnicalInfo()
        val appContext = context.applicationContext
        val fallbackExt = name.substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "").uppercase()
            .ifBlank {
                path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").uppercase()
            }
        val previous = cache[path] ?: loadFromDisk(appContext, path)?.also { cache[path] = it }
        previous?.takeIf { hasQualityBadge(it) }?.let { return it }

        // Le statut lossless est déterministe à partir du conteneur : inutile d'ouvrir le fichier.
        if (fallbackExt in LOSSLESS_EXTENSIONS) {
            val merged = mergeKnownMetadata(
                AudioTechnicalInfo(extension = fallbackExt, isLossless = true),
                previous
            )
            cache[path] = merged
            saveToDisk(appContext, path, merged)
            return merged
        }

        return withContext(metadataDispatcher) {
            qualityInFlight[path]?.let { return@withContext it.await() }
            val deferred = async {
                val timeoutMs = if (path.startsWith("smb://", true)) 8_000L else 5_000L
                val fresh = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                    extractQualityInternal(appContext, path, name)
                } ?: AudioTechnicalInfo(extension = fallbackExt)
                val merged = mergeKnownMetadata(fresh, previous)
                cache[path] = merged
                saveToDisk(appContext, path, merged)
                merged
            }
            qualityInFlight[path] = deferred
            try { deferred.await() } finally { qualityInFlight.remove(path, deferred) }
        }
    }

    /** Calcule uniquement la durée, sans lire ni utiliser les tags titre/artiste/album. Le
     *  résultat est fusionné dans le cache technique existant et réutilisé par Room. */
    suspend fun extractDurationOnly(context: Context, path: String): Long {
        cache[path]?.duration?.takeIf { it > 0L }?.let { return it }
        return withContext(metadataDispatcher) {
            val previous = cache[path] ?: loadFromDisk(context, path)?.also { cache[path] = it }
            previous?.duration?.takeIf { it > 0L }?.let { return@withContext it }
            durationInFlight[path]?.let { return@withContext it.await() }
            val deferred = async {
                val timeoutMs = if (path.startsWith("smb://", true)) 8_000L else 5_000L
                val durationMs = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                    extractDurationMsInternal(context.applicationContext, path)
                } ?: 0L
                val seconds = if (durationMs > 0L) ((durationMs + 500L) / 1000L).coerceAtLeast(1L) else 0L
                if (seconds > 0L) {
                    val merged = mergeKnownMetadata(
                        AudioTechnicalInfo(
                            duration = seconds,
                            extension = path.substringBefore('?').substringBefore('#')
                                .substringAfterLast('.', "").uppercase()
                        ),
                        previous
                    )
                    cache[path] = merged
                    saveToDisk(context, path, merged)
                }
                seconds
            }
            durationInFlight[path] = deferred
            try { deferred.await() } finally { durationInFlight.remove(path, deferred) }
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


    /** Invalidation ciblée quand Room détecte qu'un fichier a changé (size/mtime).
     *  Le cache disque metadata est indexé par path ; sans ça, un fichier remplacé sur le NAS
     *  avec le même chemin pouvait réinjecter les anciens tags dans la bibliothèque. */
    fun removeCached(context: Context, path: String) {
        if (path.isBlank()) return
        cache.remove(path)
        inFlight.remove(path)
        durationInFlight.remove(path)
        qualityInFlight.remove(path)
        try {
            context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(diskKey(path))
                .commit()
        } catch (e: Exception) {
            android.util.Log.w("AudioMetadataExtractor", "Failed to remove metadata cache for ${SmbDataSource.redactForLog(path)}", e)
        }
    }

    fun clearCache() {
        cache.clear()
        inFlight.clear()
        durationInFlight.clear()
        qualityInFlight.clear()
    }

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
            context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
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
            if (version == CACHE_VERSION) {
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



    private fun extractQualityInternal(context: Context, path: String, name: String): AudioTechnicalInfo {
        var smbDataSource: SmbMediaDataSource? = null
        var closeable: AutoCloseable? = null
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            when {
                path.startsWith("smb://", true) -> {
                    smbDataSource = SmbMediaDataSource(path)
                    retriever.setDataSource(smbDataSource)
                }
                path.startsWith("content://", true) -> {
                    val uri = android.net.Uri.parse(path)
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        retriever.setDataSource(pfd.fileDescriptor)
                        closeable = pfd
                    } else retriever.setDataSource(context, uri)
                }
                path.startsWith("file://", true) -> retriever.setDataSource(context, android.net.Uri.parse(path))
                path.startsWith("http://", true) || path.startsWith("https://", true) -> retriever.setDataSource(path, emptyMap())
                else -> retriever.setDataSource(path)
            }
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val rawBitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val bitrate = when {
                rawBitrate > 0L -> rawBitrate
                durationMs > 0L -> {
                    val sizeBytes = fileSizeBytes(context, path, smbDataSource)
                    if (sizeBytes > 0L) (sizeBytes * 8_000L) / durationMs else 0L
                }
                else -> 0L
            }
            val ext = name.substringBefore('?').substringBefore('#')
                .substringAfterLast('.', "").uppercase()
                .ifBlank { path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").uppercase() }
            AudioTechnicalInfo(
                duration = if (durationMs > 0L) ((durationMs + 500L) / 1000L).coerceAtLeast(1L) else 0L,
                bitrate = bitrate,
                extension = ext,
                isLossless = ext in LOSSLESS_EXTENSIONS
            )
        } catch (_: Exception) {
            val ext = name.substringBefore('?').substringBefore('#')
                .substringAfterLast('.', "").uppercase()
                .ifBlank { path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").uppercase() }
            AudioTechnicalInfo(extension = ext, isLossless = ext in LOSSLESS_EXTENSIONS)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { closeable?.close() } catch (_: Exception) {}
            try { smbDataSource?.close() } catch (_: Exception) {}
        }
    }

    private fun extractDurationMsInternal(context: Context, path: String): Long {
        var smbDataSource: SmbMediaDataSource? = null
        var closeable: AutoCloseable? = null
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            when {
                path.startsWith("smb://", true) -> {
                    smbDataSource = SmbMediaDataSource(path)
                    retriever.setDataSource(smbDataSource)
                }
                path.startsWith("content://", true) -> {
                    val uri = android.net.Uri.parse(path)
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        retriever.setDataSource(pfd.fileDescriptor)
                        closeable = pfd
                    } else retriever.setDataSource(context, uri)
                }
                path.startsWith("file://", true) -> retriever.setDataSource(context, android.net.Uri.parse(path))
                path.startsWith("http://", true) || path.startsWith("https://", true) -> retriever.setDataSource(path, emptyMap())
                else -> retriever.setDataSource(path)
            }
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { closeable?.close() } catch (_: Exception) {}
            try { smbDataSource?.close() } catch (_: Exception) {}
        }
    }

    private fun extractInternal(context: Context, path: String, name: String): AudioTechnicalInfo {
        var smbDataSource: SmbMediaDataSource? = null
        var closeable: AutoCloseable? = null
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                when {
                    path.startsWith("smb://", true) -> {
                        smbDataSource = SmbMediaDataSource(path)
                        retriever.setDataSource(smbDataSource)
                    }
                    path.startsWith("content://", true) -> {
                        val uri = android.net.Uri.parse(path)
                        try {
                            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                            if (pfd != null) {
                                retriever.setDataSource(pfd.fileDescriptor)
                                closeable = pfd
                            } else {
                                retriever.setDataSource(context, uri)
                            }
                        } catch (_: Exception) {
                            retriever.setDataSource(context, uri)
                        }
                    }
                    path.startsWith("file://", true) -> retriever.setDataSource(context, android.net.Uri.parse(path))
                    path.startsWith("http://", true) || path.startsWith("https://", true) -> retriever.setDataSource(path, emptyMap())
                    else -> retriever.setDataSource(path)
                }
                val title = cleanMetadataValue(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE))
                val artist = cleanMetadataValue(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST))
                val album = cleanMetadataValue(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM))
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
                try { closeable?.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            AudioTechnicalInfo(extension = name.substringAfterLast(".", "").uppercase())
        } finally {
            try { smbDataSource?.close() } catch (_: Exception) {}
        }
    }

    private fun cleanMetadataValue(raw: String?): String {
        val value = raw?.trim().orEmpty()
        return when {
            value.isBlank() -> ""
            value.equals("<unknown>", true) -> ""
            value.equals("unknown", true) -> ""
            value.equals("null", true) -> ""
            else -> value
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
