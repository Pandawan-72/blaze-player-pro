package fr.retrospare.blazeplayer.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import fr.retrospare.blazeplayer.data.model.MediaItem
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.TrackGroupArray

data class VideoTechnicalInfo(
    val duration: Long = 0L,        // secondes
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val container: String = "",
    val frameRate: Float? = null,
    val hdr: Boolean = false,
    val audioTracks: Int = 0,
    val sizeBytes: Long = 0L
) {
    val resolutionLabel: String get() = if (width > 0 && height > 0) "${width}×${height}" else ""

    val qualityBadge: String get() = when {
        height <= 0  -> ""
        height <= 480  -> "SD"
        height <= 720  -> "HD"
        height <= 1080 -> "FHD"
        height <= 1440 -> "QHD"
        else           -> "4K"
    }

    val formattedDuration: String get() {
        if (duration <= 0) return ""
        val h = duration / 3600
        val m = (duration % 3600) / 60
        val s = duration % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/**
 * Extraction et cache des métadonnées techniques vidéo (durée, résolution, codecs).
 *
 * Architecture en deux niveaux, pensée pour l'affichage en liste :
 * - [extractLight] : durée + résolution seulement (MediaMetadataRetriever, avec repli Media3
 *   uniquement si ça manque encore) — pas de passage Media3 pour les codecs, la partie la plus
 *   lente. Les badges de codec en liste viennent d'une estimation instantanée depuis
 *   l'extension ([fastDecorate]), pas d'une extraction réelle.
 * - [extractFull] : la totalité, y compris les vrais codecs via Media3 — plus lente, réservée à
 *   l'écran "Informations" (l'utilisateur a explicitement demandé le détail) ou en repli si
 *   [extractLight] n'a vraiment rien trouvé.
 *
 * [fastDecorate] permet d'afficher une liste immédiatement (extension + cache déjà en mémoire),
 * et [enrichVideoItemsIncremental] complète ensuite chaque ligne en arrière-plan au fur et à
 * mesure, plutôt que de faire attendre tout l'affichage sur le fichier le plus lent du dossier.
 */
object VideoMetadataExtractor {

    private val cache = ConcurrentHashMap<String, VideoTechnicalInfo>()
    private const val DISK_CACHE_PREFS = "blaze_video_metadata_cache"

    // Incrémenté à chaque changement du format de sérialisation disque : une entrée écrite par
    // une version antérieure est ignorée proprement (traitée comme un cache manquant) plutôt que
    // mal interprétée si l'ordre ou le nombre de champs a changé.
    private const val CACHE_VERSION = 3

    // Déduplique les extractions concurrentes du même fichier (clé = cacheKey) : sans ça, un
    // défilement rapide peut redemander l'extraction du même élément plusieurs fois avant que la
    // première tentative soit terminée, inutilement coûteux en réseau/CPU. Séparé léger/complet
    // car ce sont deux travaux de coûts très différents.
    private val inFlightLight = ConcurrentHashMap<String, Deferred<VideoTechnicalInfo>>()
    private val inFlightFull = ConcurrentHashMap<String, Deferred<VideoTechnicalInfo>>()

    /** La clé de cache ne peut pas être le chemin seul : un fichier remplacé au même chemin
     *  (même nom, contenu différent) garderait sinon d'anciens badges indéfiniment. On ajoute la
     *  taille quand elle est connue — l'appelant l'a en général déjà depuis le listing du
     *  dossier (SmbBrowser/MediaStore la fournit gratuitement), pas la peine d'aller la
     *  rechercher spécialement pour ça. */
    private fun cacheKey(path: String, knownSizeBytes: Long): String =
        if (knownSizeBytes > 0L) "$path|$knownSizeBytes" else path

    /**
     * Extraction légère : durée + résolution (MediaMetadataRetriever, repli Media3 seulement si
     * elles manquent encore). Pas d'extraction de codecs Media3 ici — utiliser [extractFull]
     * pour ça. C'est la fonction à utiliser pour enrichir une liste affichée.
     */
    suspend fun extractLight(context: Context, path: String, knownSizeBytes: Long = 0L): VideoTechnicalInfo {
        val key = cacheKey(path, knownSizeBytes)
        // 1. Cache mémoire — immédiat.
        cache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            // 2. Cache disque — rapide, pas de réseau/extraction lourde.
            loadFromDisk(context, key)?.let {
                cache[key] = it
                return@withContext it
            }
            // 3. Extraction réelle, seulement si rien en cache — dédupliquée si déjà en cours.
            inFlightLight[key]?.let { return@withContext it.await() }
            val deferred = async {
                val info = kotlinx.coroutines.withTimeoutOrNull(if (path.startsWith("smb://")) 3_000L else 5_000L) {
                    extractLightInternal(context, path)
                } ?: run {
                    android.util.Log.w("VideoMetadataExtractor", "Light metadata timeout for $path")
                    VideoTechnicalInfo()
                }
                cache[key] = info
                saveToDisk(context, key, info)
                info
            }
            inFlightLight[key] = deferred
            try {
                deferred.await()
            } finally {
                inFlightLight.remove(key, deferred)
            }
        }
    }

    /**
     * Extraction complète (durée, résolution, VRAIS codecs via Media3) — plus lente, pour
     * l'écran "Informations" ou un repli explicite. Si une entrée légère est déjà en cache mais
     * sans codec, celui-ci est complété et le cache mis à jour.
     */
    suspend fun extractFull(context: Context, path: String, knownSizeBytes: Long = 0L): VideoTechnicalInfo {
        val key = cacheKey(path, knownSizeBytes)
        cache[key]?.let { if (it.videoCodec.isNotEmpty() || it.audioCodec.isNotEmpty()) return it }
        return withContext(Dispatchers.IO) {
            loadFromDisk(context, key)?.let {
                if (it.videoCodec.isNotEmpty() || it.audioCodec.isNotEmpty()) {
                    cache[key] = it
                    return@withContext it
                }
            }
            inFlightFull[key]?.let { return@withContext it.await() }
            val deferred = async {
                val info = kotlinx.coroutines.withTimeoutOrNull(if (path.startsWith("smb://")) 8_000L else 12_000L) {
                    extractFullInternal(context, path)
                } ?: run {
                    android.util.Log.w("VideoMetadataExtractor", "Full metadata timeout for $path")
                    VideoTechnicalInfo()
                }
                cache[key] = info
                saveToDisk(context, key, info)
                info
            }
            inFlightFull[key] = deferred
            try {
                deferred.await()
            } finally {
                inFlightFull.remove(key, deferred)
            }
        }
    }

    /** Ancien point d'entrée, conservé pour compatibilité avec le code existant — équivaut à
     *  [extractFull]. Préférer [extractLight] pour un usage en liste. */
    suspend fun extract(context: Context, path: String, knownSizeBytes: Long = 0L): VideoTechnicalInfo =
        extractFull(context, path, knownSizeBytes)

    fun getCached(path: String, knownSizeBytes: Long = 0L): VideoTechnicalInfo? =
        cache[cacheKey(path, knownSizeBytes)] ?: if (knownSizeBytes > 0L) cache[path] else null

    /** Lecture cache mémoire+disque strictement rapide, sans extraction réseau. Utile pour
     *  réouvrir un dossier SMB avec les badges déjà visibles immédiatement. */
    fun getCached(context: Context, path: String, knownSizeBytes: Long = 0L): VideoTechnicalInfo? {
        getCached(path, knownSizeBytes)?.let { return it }
        val keys = if (knownSizeBytes > 0L) listOf(cacheKey(path, knownSizeBytes), path) else listOf(path)
        for (key in keys) {
            val disk = loadFromDisk(context, key) ?: continue
            cache[key] = disk
            return disk
        }
        return null
    }

    fun clearCache() = cache.clear()

    /** Vide aussi le cache disque persistant. */
    fun clearDiskCache(context: Context) {
        try {
            context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {
            android.util.Log.w("VideoMetadataExtractor", "Failed to clear disk metadata cache", e)
        }
    }

    /** Décoration instantanée et strictement synchrone (aucune extraction) : badge codec deviné
     *  depuis l'extension, et données déjà en cache mémoire s'il y en a. Sert à afficher la
     *  liste immédiatement — l'enrichissement réel arrive ensuite en arrière-plan, ligne par
     *  ligne, via [enrichVideoItemsIncremental]. */
    fun fastDecorate(item: MediaItem): MediaItem = fastDecorate(null, item)

    fun fastDecorate(context: Context?, item: MediaItem): MediaItem {
        if (item.mimeType == "folder" || item.mimeType == "share" || !item.mimeType.startsWith("video/")) return item
        val ext = item.extension.lowercase().ifEmpty { item.name.substringAfterLast('.', "").lowercase() }
        val cached = if (context != null) getCached(context, item.path, item.size) else getCached(item.path, item.size)
        if (cached != null) {
            return item.copy(
                resolution = cached.qualityBadge.ifEmpty { normalizeResolution(item.resolution) },
                videoCodec = cached.videoCodec.ifEmpty { item.videoCodec ?: guessVideoCodecFromExt(ext) },
                audioCodec = cached.audioCodec.ifEmpty { item.audioCodec ?: guessAudioCodecFromExt(ext) },
                duration = if (cached.duration > 0) cached.duration else item.duration
            )
        }
        return item.copy(
            resolution = normalizeResolution(item.resolution),
            videoCodec = item.videoCodec ?: guessVideoCodecFromExt(ext),
            audioCodec = item.audioCodec ?: guessAudioCodecFromExt(ext)
        )
    }

    /** Convertit un ancien format "1920x1080" ou "1920×1080" (persisté par une version
     *  antérieure de l'app, avant l'unification des badges) en palier SD/HD/FHD/QHD/4K — pour
     *  qu'un élément qui n'a pas encore été ré-enrichi n'affiche jamais un format différent des
     *  autres. Une valeur déjà au bon format (ou vide) est retournée telle quelle. */
    private fun normalizeResolution(resolution: String?): String? {
        if (resolution.isNullOrEmpty()) return resolution
        if (!resolution.contains("x", ignoreCase = true) && !resolution.contains("×")) return resolution
        val height = resolution.replace("×", "x").substringAfter("x", "").toIntOrNull() ?: return null
        return when {
            height <= 0 -> null
            height <= 480 -> "SD"
            height <= 720 -> "HD"
            height <= 1080 -> "FHD"
            height <= 1440 -> "QHD"
            else -> "4K"
        }
    }

    /** Version liste : applique [fastDecorate] à chaque élément vidéo, synchrone, pour un
     *  affichage immédiat avant tout enrichissement en arrière-plan. */
    fun fastDecorateList(items: List<MediaItem>): List<MediaItem> = items.map { fastDecorate(it) }
    fun fastDecorateList(context: Context, items: List<MediaItem>): List<MediaItem> = items.map { fastDecorate(context, it) }

    /**
     * Enrichit une liste déjà affichée (via [fastDecorateList]) en arrière-plan, élément par
     * élément, en appelant [onItemReady] dès que chacun est prêt — plutôt que d'attendre toute
     * la liste avant de rien afficher, ce qui faisait dépendre toute l'UX du fichier le plus
     * lent du dossier. Les éléments déjà complets (résolution ET durée connues, typiquement
     * depuis le cache) sont sautés. [maxConcurrent] volontairement bas : SMB + extraction en
     * parallèle coûte plus cher qu'un simple listing de dossier, une valeur élevée dégrade
     * l'expérience plutôt que de l'accélérer.
     */
    suspend fun enrichVideoItemsIncremental(
        context: Context,
        items: List<MediaItem>,
        maxConcurrent: Int = 2,
        onItemReady: suspend (index: Int, enriched: MediaItem) -> Unit
    ) = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(maxConcurrent)
        // Le travail d'extraction reste parallèle (jusqu'à maxConcurrent), mais l'appel à
        // onItemReady est sérialisé : les appelants mettent typiquement à jour une liste
        // partagée puis réémettent un état (StateFlow, notifyItemChanged...) — sans cette
        // protection, deux extractions terminées au même instant pourraient se marcher dessus
        // et faire perdre la mise à jour de l'une des deux.
        val callbackMutex = kotlinx.coroutines.sync.Mutex()
        items.forEachIndexed { index, item ->
            if (item.mimeType == "folder" || item.mimeType == "share" || !item.mimeType.startsWith("video/")) return@forEachIndexed
            val cached = getCached(context, item.path, item.size)
            if (cached != null) {
                val ext = item.extension.lowercase().ifEmpty { item.name.substringAfterLast('.', "").lowercase() }
                val enriched = item.copy(
                    resolution = cached.qualityBadge.ifEmpty { normalizeResolution(item.resolution) },
                    videoCodec = cached.videoCodec.ifEmpty { item.videoCodec ?: guessVideoCodecFromExt(ext) },
                    audioCodec = cached.audioCodec.ifEmpty { item.audioCodec ?: guessAudioCodecFromExt(ext) },
                    duration = if (cached.duration > 0) cached.duration else item.duration
                )
                launch { callbackMutex.withLock { onItemReady(index, enriched) } }
                // Si le cache contient déjà les vrais codecs + durée/résolution, rien à refaire.
                if (cached.duration > 0L && cached.height > 0 && cached.videoCodec.isNotEmpty() && cached.audioCodec.isNotEmpty()) {
                    return@forEachIndexed
                }
            }
            // Ne pas sauter uniquement parce que l'UI possède déjà des badges devinés : on veut
            // quand même remplir le cache persistant avec les vrais codecs/durée dès que possible.
            launch {
                semaphore.withPermit {
                    try {
                        val ext = item.extension.lowercase().ifEmpty { item.name.substringAfterLast('.', "").lowercase() }
                        // Pour les badges unifiés (accueil + navigateurs), on tente l'extraction
                        // complète en arrière-plan : durée/résolution + vrais codecs. En attendant,
                        // fastDecorate() affiche déjà des badges instantanés depuis l'extension/cache.
                        // Le résultat complet est persisté par extractFull(), donc les prochains
                        // affichages sont immédiats.
                        val info = extractFull(context, item.path, item.size)
                        val hasSomething = info.duration > 0L || info.width > 0 || info.videoCodec.isNotEmpty() || info.audioCodec.isNotEmpty()
                        if (!hasSomething) return@withPermit
                        val enriched = item.copy(
                            resolution = info.qualityBadge.ifEmpty { normalizeResolution(item.resolution) },
                            videoCodec = info.videoCodec.ifEmpty { item.videoCodec ?: guessVideoCodecFromExt(ext) },
                            audioCodec = info.audioCodec.ifEmpty { item.audioCodec ?: guessAudioCodecFromExt(ext) },
                            duration = if (info.duration > 0) info.duration else item.duration
                        )
                        callbackMutex.withLock { onItemReady(index, enriched) }
                    } catch (e: Exception) {
                        // Le badge deviné (extension) reste affiché, mais on loggue pour éviter les
                        // échecs silencieux en debug terrain.
                        android.util.Log.w("VideoMetadataExtractor", "Incremental metadata failed for ${item.path}", e)
                    }
                }
            }
        }
    }

    /** Devine un badge codec plausible depuis l'extension du fichier — pas de vraie extraction,
     *  juste une estimation instantanée et fiable, suffisante pour un badge de liste. */
    private fun guessVideoCodecFromExt(ext: String) = when (ext) {
        "mkv" -> "H.265"
        "mp4", "m4v" -> "H.264"
        "avi" -> "DIVX"
        "webm" -> "VP9"
        "ts", "mts" -> "H.264"
        else -> ext.uppercase()
    }

    private fun guessAudioCodecFromExt(ext: String) = when (ext) {
        "mkv" -> "AAC"
        "mp4", "m4v" -> "AAC"
        "avi" -> "MP3"
        else -> "AAC"
    }

    private fun diskKey(key: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun loadFromDisk(context: Context, key: String): VideoTechnicalInfo? {
        val prefs = context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(diskKey(key), null) ?: return null
        return try {
            val parts = raw.split("|")
            val version = parts[0].toIntOrNull() ?: return null
            if (version != CACHE_VERSION) return null // format différent, on ignore plutôt que mal interpréter
            VideoTechnicalInfo(
                duration = parts[1].toLong(),
                width = parts[2].toInt(),
                height = parts[3].toInt(),
                videoCodec = parts[4],
                audioCodec = parts[5],
                container = parts[6],
                frameRate = parts[7].toFloatOrNull(),
                hdr = parts[8].toBoolean(),
                audioTracks = parts[9].toInt(),
                sizeBytes = parts.getOrNull(10)?.toLongOrNull() ?: 0L
            )
        } catch (e: Exception) {
            android.util.Log.w("VideoMetadataExtractor", "Failed to read metadata disk cache", e)
            null
        }
    }

    private fun saveToDisk(context: Context, key: String, info: VideoTechnicalInfo) {
        // N'écrit sur disque que si l'extraction a effectivement trouvé quelque chose : évite de
        // mettre en cache un échec temporaire (ex: partage réseau momentanément indisponible).
        if (info.duration <= 0L && info.videoCodec.isEmpty() && info.audioCodec.isEmpty()) return
        val raw = listOf(
            CACHE_VERSION, info.duration, info.width, info.height, info.videoCodec, info.audioCodec,
            info.container, info.frameRate?.toString() ?: "", info.hdr, info.audioTracks, info.sizeBytes
        ).joinToString("|")
        val editor = context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE).edit()
            .putString(diskKey(key), raw)
        // Double index pour fiabiliser les badges réseau : certains listings SMB remontent size=0
        // puis l'extracteur connaît la taille réelle, ou inversement. Le fallback par chemin
        // permet d'afficher le cache immédiatement malgré cette différence.
        val pathOnly = key.substringBefore("|")
        if (pathOnly.isNotEmpty() && pathOnly != key) editor.putString(diskKey(pathOnly), raw)
        editor.apply()
    }

    private fun mapVideoCodec(mime: String): String = when {
        mime.contains("dolby-vision") || mime.contains("dvhe") || mime.contains("dvav") -> "Dolby Vision"
        mime.contains("hevc") || mime.contains("h265") -> "H.265"
        mime.contains("avc") || mime.contains("h264") -> "H.264"
        mime.contains("vvc") || mime.contains("h266") -> "H.266"
        mime.contains("vp9") -> "VP9"
        mime.contains("vp8") -> "VP8"
        mime.contains("av01") || mime.contains("av1") -> "AV1"
        mime.contains("mpeg4") -> "MPEG-4"
        mime.contains("mpeg2") -> "MPEG-2"
        mime.contains("mpeg") -> "MPEG-1"
        mime.contains("vc1") -> "VC-1"
        mime.contains("mjpeg") || mime.contains("jpeg") -> "MJPEG"
        mime.contains("divx") -> "DivX"
        mime.contains("xvid") -> "Xvid"
        mime.contains("wmv") -> "WMV"
        mime.contains("theora") -> "Theora"
        mime.contains("flv") -> "FLV1"
        mime.contains("rv") -> "RealVideo"
        else -> mime.substringAfter("video/").ifEmpty { "INCONNU" }.uppercase()
    }

    private fun mapAudioCodec(mime: String): String = when {
        mime.contains("truehd") -> "TrueHD"
        mime.contains("eac3-joc") -> "EAC3-JOC"
        mime.contains("eac3") || mime.contains("ec-3") || mime.contains("ec3") -> "EAC3"
        mime.contains("ac4") -> "AC-4"
        mime.contains("ac3") -> "AC3"
        mime.contains("dts-hd") || mime.contains("dtshd") -> "DTS-HD"
        mime.contains("dts-express") -> "DTS Express"
        mime.contains("dts") -> "DTS"
        mime.contains("aac") -> "AAC"
        mime.contains("mp4a") -> "AAC"
        mime.contains("mp3") || mime.contains("mpeg") -> "MP3"
        mime.contains("mp2") -> "MP2"
        mime.contains("flac") -> "FLAC"
        mime.contains("opus") -> "Opus"
        mime.contains("vorbis") -> "Vorbis"
        mime.contains("alac") -> "ALAC"
        mime.contains("wma") -> "WMA"
        mime.contains("pcm") || mime.contains("raw") -> "PCM"
        mime.contains("amr") -> "AMR"
        mime.contains("speex") -> "Speex"
        mime.contains("g711") || mime.contains("alaw") || mime.contains("mlaw") -> "G.711"
        else -> mime.substringAfter("audio/").ifEmpty { "INCONNU" }.uppercase()
    }

    @UnstableApi
    private fun extractCodecsViaMedia3(
        context: Context,
        path: String,
        onTrack: (mime: String, isVideo: Boolean, width: Int, height: Int, frameRate: Float?) -> Unit
    ) {
        try {
            val isSmb = path.startsWith("smb://")
            val dataSourceFactory = if (isSmb) {
                androidx.media3.datasource.DefaultDataSource.Factory(
                    context,
                    fr.retrospare.blazeplayer.player.SmbDataSource.Factory()
                )
            } else {
                androidx.media3.datasource.DefaultDataSource.Factory(context)
            }
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                .setDataSourceFactory(dataSourceFactory)
            val mediaItem = Media3MediaItem.fromUri(android.net.Uri.parse(path))
            val future = androidx.media3.exoplayer.MetadataRetriever.retrieveMetadata(mediaSourceFactory, mediaItem)
            val trackGroups: TrackGroupArray = future.get(15, java.util.concurrent.TimeUnit.SECONDS)
            for (i in 0 until trackGroups.length) {
                val group = trackGroups[i]
                for (j in 0 until group.length) {
                    val format = group.getFormat(j)
                    val mime = format.sampleMimeType ?: continue
                    val isVideo = mime.startsWith("video/")
                    val isAudio = mime.startsWith("audio/")
                    if (isVideo || isAudio) {
                        onTrack(mime, isVideo, format.width, format.height, if (format.frameRate > 0) format.frameRate else null)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("META_TRACK", "Media3 metadata extraction failed for $path", e)
        }
    }

    private data class Media3FallbackResult(val durationUs: Long, val width: Int, val height: Int)

    /** Repli quand MediaMetadataRetriever échoue silencieusement (observé sur des MP4 réseau
     *  volumineux avec métadonnées en fin de fichier — "moov atom" placé après les données plutôt
     *  qu'au début) : couvre la durée ET les dimensions dans le même appel, car un fichier qui
     *  perd l'une perd généralement l'autre pour la même raison structurelle. Réutilise la même
     *  infrastructure Media3 (SmbDataSource pour le SMB) déjà éprouvée pour la lecture. */
    @UnstableApi
    private fun extractDurationAndDimensionsViaMedia3(context: Context, path: String): Media3FallbackResult {
        return try {
            val isSmb = path.startsWith("smb://")
            val dataSourceFactory = if (isSmb) {
                androidx.media3.datasource.DefaultDataSource.Factory(
                    context,
                    fr.retrospare.blazeplayer.player.SmbDataSource.Factory()
                )
            } else {
                androidx.media3.datasource.DefaultDataSource.Factory(context)
            }
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                .setDataSourceFactory(dataSourceFactory)
            val mediaItem = Media3MediaItem.fromUri(android.net.Uri.parse(path))
            androidx.media3.exoplayer.MetadataRetriever.Builder(context, mediaItem)
                .setMediaSourceFactory(mediaSourceFactory)
                .build().use { retriever ->
                    val durationUs = try {
                        retriever.retrieveDurationUs().get(15, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (e: Exception) { null }
                    var width = 0
                    var height = 0
                    try {
                        val trackGroups = retriever.retrieveTrackGroups().get(15, java.util.concurrent.TimeUnit.SECONDS)
                        outer@ for (i in 0 until trackGroups.length) {
                            val group = trackGroups[i]
                            for (j in 0 until group.length) {
                                val format = group.getFormat(j)
                                if (format.sampleMimeType?.startsWith("video/") == true && format.width > 0 && format.height > 0) {
                                    width = format.width
                                    height = format.height
                                    break@outer
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("META_TRACK", "Repli dimensions Media3 a échoué pour $path", e)
                    }
                    val validDuration = if (durationUs != null && durationUs != androidx.media3.common.C.TIME_UNSET && durationUs > 0) {
                        durationUs / 1_000_000L
                    } else {
                        0L
                    }
                    Media3FallbackResult(validDuration, width, height)
                }
        } catch (e: Exception) {
            android.util.Log.w("META_TRACK", "Repli durée/dimensions Media3 a échoué pour $path", e)
            Media3FallbackResult(0L, 0, 0)
        }
    }

    /** Ouvre le retriever natif et récupère durée/dimensions/taille — étape commune aux deux
     *  modes d'extraction (légère et complète). */
    private fun extractBaseInfo(context: Context, path: String): VideoTechnicalInfo {
        var duration = 0L
        var width = 0
        var height = 0
        var sizeBytes = 0L
        val container = path.substringAfterLast('.', "").uppercase()
        val isSmb = path.startsWith("smb://")
        var smbDataSourceMeta: SmbMediaDataSource? = null

        val retriever = MediaMetadataRetriever()
        try {
            if (isSmb) {
                smbDataSourceMeta = SmbMediaDataSource(path)
                retriever.setDataSource(smbDataSourceMeta)
                sizeBytes = smbDataSourceMeta.size
            } else if (path.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(path))
                sizeBytes = try {
                    context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { it.statSize } ?: 0L
                } catch (e: Exception) { 0L }
            } else if (path.startsWith("http://", true) || path.startsWith("https://", true)) {
                retriever.setDataSource(path, emptyMap())
                sizeBytes = 0L
            } else {
                retriever.setDataSource(path)
                sizeBytes = try { java.io.File(path).length() } catch (e: Exception) { 0L }
            }

            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.div(1000) ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            android.util.Log.w("VideoMetadataExtractor", "Base metadata extraction failed for $path", e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { smbDataSourceMeta?.close() } catch (_: Exception) {}
        }

        return VideoTechnicalInfo(duration = duration, width = width, height = height, container = container, sizeBytes = sizeBytes)
    }

    /** Extraction légère : MediaMetadataRetriever pour durée/résolution, avec repli Media3
     *  seulement si celles-ci manquent encore. Pas de passage Media3 pour les codecs. */
    @UnstableApi
    private fun extractLightInternal(context: Context, path: String): VideoTechnicalInfo {
        val base = extractBaseInfo(context, path)
        if (base.duration > 0L && base.width > 0 && base.height > 0) return base

        // Niveau 1 anti-freeze : en affichage liste, ne lance pas le repli Media3 sur SMB.
        // Il peut faire plusieurs accès réseau profonds et consommer des workers pendant que la
        // lecture démarre. Les vrais détails restent disponibles via extractFull()/écran Info.
        if (path.startsWith("smb://")) return base

        val fallback = extractDurationAndDimensionsViaMedia3(context, path)
        return base.copy(
            duration = if (base.duration > 0L) base.duration else fallback.durationUs,
            width = if (base.width > 0) base.width else fallback.width,
            height = if (base.height > 0) base.height else fallback.height
        )
    }

    /** Extraction complète : base (durée/résolution/taille) + vrais codecs via Media3 + repli
     *  Media3 pour durée/dimensions si toujours manquantes. */
    @UnstableApi
    private fun extractFullInternal(context: Context, path: String): VideoTechnicalInfo {
        val base = extractBaseInfo(context, path)
        var duration = base.duration
        var width = base.width
        var height = base.height
        var videoCodec = ""
        var audioCodec = ""
        var frameRate: Float? = null
        var audioTracks = 0

        extractCodecsViaMedia3(context, path) { mime, isVideo, fmtWidth, fmtHeight, fmtFrameRate ->
            if (isVideo) {
                videoCodec = mapVideoCodec(mime)
                if (fmtWidth > 0) width = fmtWidth
                if (fmtHeight > 0) height = fmtHeight
                if (fmtFrameRate != null) frameRate = fmtFrameRate
            } else {
                audioTracks++
                if (audioCodec.isEmpty()) audioCodec = mapAudioCodec(mime)
            }
        }

        if (duration <= 0L || width <= 0 || height <= 0) {
            val fallback = extractDurationAndDimensionsViaMedia3(context, path)
            if (duration <= 0L) duration = fallback.durationUs
            if (width <= 0 && fallback.width > 0) width = fallback.width
            if (height <= 0 && fallback.height > 0) height = fallback.height
        }

        return base.copy(
            duration = duration,
            width = width,
            height = height,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            frameRate = frameRate,
            audioTracks = audioTracks
        )
    }
}
