package fr.retrospare.blazeplayer.network

import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.data.model.NetworkShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbBrowser @Inject constructor() {

    // Les NAS SMB supportent mal les listings concurrents venant du même client. Une recherche
    // ou un enrichissement metadata qui se chevauche avec une navigation peut laisser des handles
    // ouverts et figer l'arborescence. On sérialise les listings de dossiers, qui sont courts et
    // critiques pour l'UX.
    private val listSemaphore = Semaphore(1)

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts",
        "m4v", "webm", "mpg", "mpeg", "3gp", "divx"
    )

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "aac", "ogg", "opus", "wav", "m4a", "wma", "ape", "dts", "ac3", "mka"
    )

    // La bibliothèque audio a besoin de voir les pochettes explicites dans les dossiers NAS
    // pour pouvoir les indexer sans ouvrir les fichiers audio réseau. On ne remonte pas toutes
    // les images du partage : uniquement les noms conventionnels de cover d'album.
    private val COVER_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")
    private val COVER_IMAGE_NAMES = setOf(
        "cover", "folder", "front", "album", "albumart", "album art", "artwork", "jaquette", "pochette"
    )

    private fun lastWriteTimeMillis(info: FileIdBothDirectoryInformation): Long = runCatching {
        val value = info.javaClass.methods.firstOrNull { it.name == "getLastWriteTime" }?.invoke(info)
        val millis = value?.javaClass?.methods?.firstOrNull { it.name == "toEpochMillis" }?.invoke(value)
        (millis as? Number)?.toLong() ?: 0L
    }.getOrDefault(0L)

    private fun createClient(): SMBClient {
        val config = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withReadTimeout(60, TimeUnit.SECONDS)
            .build()
        return SMBClient(config)
    }

    suspend fun listShares(share: NetworkShare): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        listSemaphore.withPermit {
            runCatching {
                val client = createClient()
                val authContext = buildAuthContext(share)
                val items = mutableListOf<MediaItem>()

                client.connect(share.host, share.port ?: 445).use { connection ->
                    val session = connection.authenticate(authContext)
                    try {
                        val transport = com.rapid7.client.dcerpc.transport.SMBTransportFactories.SRVSVC.getTransport(session)
                        val serverService = com.rapid7.client.dcerpc.mssrvs.ServerService(transport)
                        val shares = serverService.shares0
                        shares.forEach { shareInfo ->
                            val name = shareInfo.netName
                            // Filtre les shares administratifs (ADMIN$, C$, IPC$...) et types non disque
                            if (!name.endsWith("$") && name.isNotBlank()) {
                                items.add(
                                    MediaItem(
                                        id = "smb://${share.host}/$name",
                                        name = name,
                                        path = name,
                                        mimeType = "share",
                                        extension = "",
                                        isNetwork = true,
                                        networkShareId = share.id
                                    )
                                )
                            }
                        }
                    } finally {
                        session.close()
                    }
                }
                items.sortBy { it.name.lowercase() }
                items
            }
        }
    }

    suspend fun listFiles(
        share: NetworkShare,
        path: String = "",
        includeAudioCoverImages: Boolean = false
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        // Si aucun nom de partage n'est defini, on liste les partages disponibles
        if (share.shareName.isBlank()) {
            // Le chemin "path" sert alors a stocker le nom du partage choisi + sous-chemin
            if (path.isBlank()) {
                return@withContext listShares(share)
            }
            val parts = path.split("/", limit = 2)
            val actualShareName = parts[0]
            val actualPath = if (parts.size > 1) parts[1] else ""
            return@withContext listFilesInShare(share.copy(shareName = actualShareName), actualPath, actualShareName, includeAudioCoverImages)
        }
        listFilesInShare(share, path, null, includeAudioCoverImages)
    }

    private suspend fun listFilesInShare(
        share: NetworkShare,
        path: String,
        sharePrefix: String?,
        includeAudioCoverImages: Boolean
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        listSemaphore.withPermit {
            runCatching {
                val client = createClient()
            val authContext = buildAuthContext(share)
            val host = share.host
            val port = share.port ?: 445
            val items = mutableListOf<MediaItem>()

            client.connect(host, port).use { connection ->
                connection.authenticate(authContext).use { session ->
                    (session.connectShare(share.shareName) as? DiskShare)?.use { diskShare ->
                        // [path] est toujours en '/' ici (séparateur canonique côté app) ; la
                        // conversion en '\' — celui attendu par le protocole SMB — n'a lieu
                        // qu'à cet unique endroit, juste avant l'appel réseau. Mélanger les deux
                        // séparateurs plus loin dans la construction des chemins causait des
                        // dossiers introuvables lors de la navigation dans un partage
                        // auto-détecté (mode multi-partages).
                        val smbSearchPath = path.replace("/", "\\")
                        val entries = diskShare.list(smbSearchPath)

                        entries.forEach { info ->
                            val name = info.fileName
                            if (name == "." || name == "..") return@forEach
                            if (name.startsWith(".")) return@forEach

                            // fullPath reste en '/' (canonique) pour l'affichage et la
                            // navigation ultérieure — jamais mélangé avec des '\'.
                            val fullPath = if (path.isEmpty()) name else "$path/$name"
                            val isDir = info.fileAttributes and 0x10L != 0L
                            val ext = name.substringAfterLast('.', "").lowercase()
                            // Si on navigue en mode multi-share, le "path" affiche au niveau superieur doit inclure le nom du partage
                            val displayPath = if (sharePrefix != null) "$sharePrefix/$fullPath" else fullPath

                            if (isDir) {
                                items.add(
                                    MediaItem(
                                        id = "smb://$host/${share.shareName}/$fullPath",
                                        name = name,
                                        path = displayPath,
                                        mimeType = "folder",
                                        extension = "",
                                        isNetwork = true,
                                        networkShareId = share.id
                                    )
                                )
                            } else if (ext in VIDEO_EXTENSIONS || ext in AUDIO_EXTENSIONS || (includeAudioCoverImages && isAudioCoverImage(name, ext))) {
                                val smbUri = buildSmbUri(share, fullPath)
                                items.add(
                                    MediaItem(
                                        id = smbUri,
                                        name = name,
                                        path = smbUri,
                                        size = info.endOfFile,
                                        modifiedAt = lastWriteTimeMillis(info),
                                        mimeType = if (includeAudioCoverImages && isAudioCoverImage(name, ext)) getCoverMimeType(ext) else getMimeType(ext),
                                        extension = ext,
                                        isNetwork = true,
                                        networkShareId = share.id
                                    )
                                )
                            }
                        }
                    }
                }
            }
                items.sortWith(compareBy({ it.mimeType != "folder" }, { it.name.lowercase() }))
                items
            }
        }
    }

    /** Recherche récursive de vidéos dans le dossier SMB courant. Un seul Visual/Browser ne doit
     *  pas lancer plusieurs connexions concurrentes au NAS : on réutilise le même sémaphore que le
     *  listing normal, et on limite les résultats pour garder le navigateur fluide. */
    suspend fun searchVideoFiles(
        share: NetworkShare,
        startPath: String = "",
        query: String,
        maxResults: Int = 200,
        maxDepth: Int = 8
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext Result.success(emptyList())

        if (share.shareName.isBlank()) {
            val cleanStart = startPath.trim('/').replace("\\", "/")
            if (cleanStart.isBlank()) {
                val merged = mutableListOf<MediaItem>()
                val shares = listShares(share).getOrDefault(emptyList())
                    .filter { it.mimeType == "share" }
                    .sortedBy { it.name.lowercase() }
                for (shareItem in shares) {
                    if (merged.size >= maxResults) break
                    val remaining = maxResults - merged.size
                    val found = searchVideoFilesInShare(
                        share.copy(shareName = shareItem.name),
                        "",
                        q,
                        remaining,
                        maxDepth
                    ).getOrDefault(emptyList())
                    merged += found.take(remaining)
                }
                return@withContext Result.success(merged.sortedBy { it.name.lowercase() })
            }
            val parts = cleanStart.split("/", limit = 2)
            val actualShareName = parts[0]
            val actualPath = if (parts.size > 1) parts[1] else ""
            return@withContext searchVideoFilesInShare(
                share.copy(shareName = actualShareName),
                actualPath,
                q,
                maxResults,
                maxDepth
            )
        }

        searchVideoFilesInShare(share, "", q, maxResults, maxDepth)
    }

    private suspend fun searchVideoFilesInShare(
        share: NetworkShare,
        startPath: String,
        query: String,
        maxResults: Int,
        maxDepth: Int
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        listSemaphore.withPermit {
            runCatching {
                val client = createClient()
                val authContext = buildAuthContext(share)
                val host = share.host
                val port = share.port ?: 445
                val results = mutableListOf<MediaItem>()
                val start = startPath.trim('/').replace("\\", "/")

                client.connect(host, port).use { connection ->
                    connection.authenticate(authContext).use { session ->
                        (session.connectShare(share.shareName) as? DiskShare)?.use { diskShare ->
                            val queue: java.util.ArrayDeque<Pair<String, Int>> = java.util.ArrayDeque()
                            queue.add(start to 0)
                            while (!queue.isEmpty() && results.size < maxResults) {
                                val (path, depth) = queue.removeFirst()
                                val smbSearchPath = path.replace("/", "\\")
                                val entries = runCatching { diskShare.list(smbSearchPath) }.getOrElse { emptyList<FileIdBothDirectoryInformation>() }
                                entries.forEach { info ->
                                    if (results.size >= maxResults) return@forEach
                                    val name = info.fileName
                                    if (name == "." || name == ".." || name.startsWith(".")) return@forEach
                                    val isDir = info.fileAttributes and 0x10L != 0L
                                    val fullPath = if (path.isEmpty()) name else "$path/$name"
                                    if (isDir) {
                                        if (depth < maxDepth) queue.add(fullPath to (depth + 1))
                                    } else {
                                        val ext = name.substringAfterLast('.', "").lowercase()
                                        if (ext in VIDEO_EXTENSIONS && name.contains(query, ignoreCase = true)) {
                                            val smbUri = buildSmbUri(share, fullPath)
                                            results.add(
                                                MediaItem(
                                                    id = smbUri,
                                                    name = name,
                                                    path = smbUri,
                                                    size = info.endOfFile,
                                                    modifiedAt = lastWriteTimeMillis(info),
                                                    mimeType = getMimeType(ext),
                                                    extension = ext,
                                                    isNetwork = true,
                                                    networkShareId = share.id
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                results.sortedBy { it.name.lowercase() }
            }
        }
    }

    /**
     * Recherche récursive de tous les fichiers audio dans un partage réseau, à partir de
     * [startPath] (racine par défaut). Garde UNE SEULE connexion/session SMB ouverte pour tout
     * le parcours, avec un budget de temps global et une gestion résiliente des erreurs : une
     * panne sur un sous-dossier ne fait pas perdre les résultats déjà trouvés ailleurs.
     */
    suspend fun checkConnection(share: NetworkShare): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val client = createClient()
            val authContext = buildAuthContext(share)
            client.connect(share.host, share.port ?: 445).use { connection ->
                connection.authenticate(authContext).use { session ->
                    session.connectShare(share.shareName) != null
                }
            }
        }.getOrDefault(false)
    }

    private fun buildAuthContext(share: NetworkShare): AuthenticationContext {
        return if (!share.username.isNullOrEmpty()) {
            AuthenticationContext(
                share.username,
                (share.password ?: "").toCharArray(),
                ""
            )
        } else {
            AuthenticationContext.anonymous()
        }
    }

    private fun buildSmbUri(share: NetworkShare, path: String): String {
        return fr.retrospare.blazeplayer.player.SmbDataSource.buildSmbUri(
            host = share.host,
            port = share.port ?: 445,
            shareName = share.shareName,
            filePath = path,
            username = share.username,
            password = share.password
        )
    }

    private fun isAudioCoverImage(name: String, ext: String): Boolean {
        if (ext.lowercase() !in COVER_IMAGE_EXTENSIONS) return false
        val base = name.substringBeforeLast('.', name)
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .lowercase()
        return COVER_IMAGE_NAMES.any { base == it || base.startsWith(it) }
    }

    private fun getCoverMimeType(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        else -> "image/jpeg"
    }

    private fun getMimeType(ext: String): String = when (ext) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2ts"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "wma" -> "audio/x-ms-wma"
        "ape" -> "audio/x-ape"
        "dts" -> "audio/vnd.dts"
        "ac3" -> "audio/ac3"
        "mka" -> "audio/x-matroska"
        else -> if (ext in AUDIO_EXTENSIONS) "audio/*" else "video/*"
    }
}
