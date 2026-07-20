package fr.retrospare.blazeplayer.cast

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Petit serveur HTTP local embarque qui relaie un fichier local (content://, /storage/...)
 * ou reseau SMB (smb://) en HTTP, pour le rendre accessible au Chromecast (qui ne sait lire que HTTP/HTTPS).
 * Supporte les requetes Range (HTTP partial content), indispensable pour que le Chromecast puisse
 * faire du buffering progressif et du seek sans devoir telecharger tout le fichier d'un coup.
 */
class LocalStreamServer(
    private val context: Context,
    port: Int = 8927
) : NanoHTTPD(port) {

    companion object {
        /** Logs HTTP désactivés par défaut : un Chromecast peut générer beaucoup de requêtes
         *  Range/HEAD. Logger tous les en-têtes dégrade inutilement le débit et pollue logcat. */
        private const val LOG_VERBOSE = false
        const val SMB_STREAM_BUFFER_SIZE = 4 * 1024 * 1024 // 4 Mo : plus stable pour les flux 4K SMB/UPnP relayés
    }

    /** Snapshot immuable de la source active, remplacé atomiquement à chaque changement de vidéo
     *  (setSource). Chaque requête HTTP capture UNE SEULE référence à ce snapshot en tout début
     *  de traitement et s'y tient pour toute sa durée — plutôt que de relire des champs mutables
     *  individuellement à plusieurs endroits, ce qui pouvait produire un mélange incohérent
     *  ancien chemin / nouvelle taille (ou l'inverse) si setSource() s'exécutait sur le thread
     *  principal EN PLEIN MILIEU du traitement d'une requête sur un thread NanoHTTPD séparé.
     *  C'est exactement le scénario "le serveur répond encore pendant qu'on change de source". */
    private data class ActiveSource(val path: String, val version: Long)

    @Volatile private var activeSource: ActiveSource? = null
    private val sourceVersionCounter = AtomicLong(System.currentTimeMillis())
    private val sourcesByVersion = ConcurrentHashMap<Long, String>()

    // Taille de fichier mise en cache par CHEMIN (pas par référence mutable partagée) : une
    // requête sur l'ancienne vidéo qui recalcule sa taille ne peut plus jamais écraser/lire un
    // état pensé pour la nouvelle vidéo, quel que soit l'ordre d'exécution des threads.
    private val fileSizeCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun setSource(path: String): Long {
        val version = registerSource(path)
        activeSource = ActiveSource(path, version)
        return version
    }

    /** Enregistre une source sans remplacer la source active. Utile lorsqu'une ancienne
     * conversion Cast se termine après qu'une nouvelle vidéo a déjà été sélectionnée : chaque
     * URL versionnée continue alors de pointer vers le bon fichier, sans course entre les deux. */
    fun registerSource(path: String): Long {
        val version = sourceVersionCounter.incrementAndGet()
        sourcesByVersion[version] = path
        if (sourcesByVersion.size > 16) {
            sourcesByVersion.keys.sorted().take(sourcesByVersion.size - 12).forEach { sourcesByVersion.remove(it) }
        }
        return version
    }

    /** URL a donner au player local pour lire ce fichier — TOUJOURS en loopback (127.0.0.1), même
     *  quand on ne caste pas : bien plus fiable qu'une auto-connexion via l'IP réseau (observée
     *  comme peu fiable sur certains routeurs/box). L'IP réseau n'est utilisée que pour le Cast :
     *  PlayerActivity.buildMediaItem(forCast=true) remplace l'hôte de cette URL au moment de la
     *  transition vers le Chromecast (cf. onDeviceInfoChanged) — CastPlayer.Builder n'exposant
     *  aucun point d'injection pour faire cette réécriture automatiquement (Media3 1.9). */
    fun getStreamUrl(): String = getStreamUrl(activeSource?.version ?: 0L)

    /** URL loopback versionnée. Elle permet à FFmpeg de lire une source locale, content:// ou SMB
     *  sans copier préalablement le fichier et sans dépendre d'un chemin que FFmpeg ne comprend pas. */
    fun getStreamUrl(version: Long): String {
        // Lecture locale dans l'app : toujours passer par loopback. Utiliser l'IP Wi‑Fi du téléphone
        // forçait certains appareils/box à sortir puis revenir par le réseau local, ce qui créait
        // des gels sur les gros fichiers 4K et pouvait laisser le player bloqué au redémarrage.
        return "http://127.0.0.1:${this.listeningPort}/stream/$version"
    }

    fun getLanStreamUrl(): String? = activeSource?.let { getLanStreamUrl(it.version) }

    fun getLanStreamUrl(version: Long): String? {
        // Ne jamais fournir 127.0.0.1 au Chromecast : cette adresse désignerait le Chromecast
        // lui-même et donnerait exactement le symptôme « chargement puis arrêt ».
        val ip = getLocalIpAddress() ?: return null
        return "http://$ip:${this.listeningPort}/stream/$version"
    }

    fun clearSource() {
        activeSource = null
        sourcesByVersion.clear()
    }

    /** Adresse IP réseau (Wi-Fi/Ethernet) du téléphone, publique pour que
     *  [fr.retrospare.blazeplayer.player.PlayerActivity] puisse l'utiliser afin de remplacer
     *  l'hôte loopback par une adresse réellement joignable par le Chromecast. */
    fun localNetworkIpAddress(): String? = getLocalIpAddress()

    /** En-têtes CORS complets (Origin + Headers + Methods), appliqués systématiquement à TOUTE
     *  réponse — pas seulement OPTIONS. */
    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range, Accept-Encoding")
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        response.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
    }

    private fun cors(response: Response): Response {
        addCorsHeaders(response)
        return response
    }

    override fun serve(session: IHTTPSession): Response {
        val remoteIp = session.remoteIpAddress
        if (LOG_VERBOSE) {
            android.util.Log.i("LocalStreamServer", "Requête reçue de $remoteIp : ${session.method} ${session.uri}")
            android.util.Log.i("LocalStreamServer", "En-têtes : ${session.headers}")
        }

        // Le Chromecast peut envoyer OPTIONS avant GET (pré-vérification CORS) : une 404 ici peut
        // faire abandonner certains récepteurs.
        if (session.method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCorsHeaders(response)
            return response
        }

        val requestedVersion = session.uri.substringAfterLast('/').toLongOrNull()
        val source = requestedVersion?.let { version ->
            sourcesByVersion[version]?.let { path -> ActiveSource(path, version) }
        } ?: activeSource
            ?: return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No source set"))
        val path = source.path
        val mimeType = guessMimeType(path)

        return try {
            val totalLength = getOrComputeFileSize(path)
            val rangeHeader = session.headers["range"]

            // Le Chromecast fait souvent un HEAD avant le GET réel pour connaître la taille/type
            // du contenu sans le télécharger — sans réponse propre à HEAD (mêmes en-têtes que
            // GET, mais sans corps), certains récepteurs refusent ensuite le média.
            if (session.method == Method.HEAD) {
                // Certains Chromecast sondent d'abord le début puis la fin d'un MP4 afin de
                // localiser l'atome `moov`. Une requête HEAD contenant Range doit donc recevoir
                // les mêmes informations de plage qu'un GET, sans corps. Ne jamais annoncer
                // Content-Length: 0 lorsque la taille est inconnue : le récepteur interprète cela
                // comme un fichier vide et abandonne avant même son premier GET.
                val requestedRange = rangeHeader?.takeIf { totalLength > 0 }?.let {
                    parseRange(it, totalLength)
                }
                val response = if (requestedRange != null) {
                    newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, "").apply {
                        val (start, end) = requestedRange
                        addHeader("Content-Range", "bytes $start-$end/$totalLength")
                        addHeader("Content-Length", (end - start + 1).toString())
                    }
                } else {
                    newFixedLengthResponse(Response.Status.OK, mimeType, "").apply {
                        if (totalLength > 0) addHeader("Content-Length", totalLength.toString())
                    }
                }
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Cache-Control", "no-store, no-transform")
                response.addHeader("X-Content-Type-Options", "nosniff")
                addCorsHeaders(response)
                return response
            }

            if (rangeHeader != null && totalLength > 0) {
                serveRange(path, rangeHeader, totalLength, mimeType)
            } else {
                serveFull(path, totalLength, mimeType)
            }
        } catch (e: Exception) {
            android.util.Log.e("LocalStreamServer", "Failed to serve $path", e)
            cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}"))
        }
    }

    private fun getOrComputeFileSize(path: String): Long {
        fileSizeCache[path]?.let { if (it > 0) return it }
        val size = when {
            path.startsWith("smb://") -> {
                var result = -1L
                // Une couche de tentative supplémentaire ICI, en plus de celle déjà présente dans
                // SmbMediaDataSource : sous contention soutenue (observé avec de gros MP4 non
                // optimisés), les deux tentatives internes peuvent toutes les deux échouer avec
                // "DiskShare has already been closed" si un autre consommateur invalide la
                // session au mauvais moment. Un court délai avant de retenter laisse la chance à
                // l'autre consommateur de terminer son propre cycle ouverture/lecture.
                for (attempt in 0 until 3) {
                    try {
                        val smbSource = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
                        try {
                            result = smbSource.size
                        } finally {
                            try { smbSource.close() } catch (_: Exception) {}
                        }
                        if (result > 0L) break
                    } catch (e: Exception) {
                        android.util.Log.w("LocalStreamServer", "getOrComputeFileSize échec tentative ${attempt + 1}/3 pour $path", e)
                        if (attempt < 2) try { Thread.sleep(300L) } catch (_: InterruptedException) {}
                    }
                }
                result
            }
            path.startsWith("content://") -> contentLength(Uri.parse(path))
            path.startsWith("file://") -> {
                val file = Uri.parse(path).path?.let { java.io.File(it) }
                if (file?.exists() == true) file.length() else -1L
            }
            else -> {
                val file = java.io.File(path)
                if (file.exists()) file.length() else -1L
            }
        }
        if (size > 0) fileSizeCache[path] = size
        return size
    }

    private fun openInputStreamAt(path: String, startPosition: Long, maxLength: Long? = null): InputStream {
        val safeStart = startPosition.coerceAtLeast(0L)
        return when {
            path.startsWith("smb://") -> {
                val smbSource = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
                SmbMediaDataSourceInputStream(smbSource, safeStart, maxLength)
            }
            path.startsWith("content://") -> openContentInputStreamAt(Uri.parse(path), safeStart)
            else -> {
                val file = if (path.startsWith("file://")) {
                    Uri.parse(path).path?.let { java.io.File(it) }
                } else java.io.File(path)
                val existingFile = file?.takeIf { it.exists() }
                    ?: throw java.io.IOException("File not found: $path")
                FileInputStream(existingFile).also { stream ->
                    // FileChannel.position() est un seek exact. InputStream.skip(), utilisé avant,
                    // peut avancer de moins d'octets que demandé ; sur un MP4 dont `moov` est en
                    // fin de fichier, le Chromecast recevait alors les mauvais octets pour sa
                    // requête Range et refusait le média.
                    stream.channel.position(safeStart)
                }
            }
        }
    }

    private fun openContentInputStreamAt(uri: Uri, startPosition: Long): InputStream {
        // MediaStore et la majorité des DocumentsProvider exposent un descripteur seekable. On
        // utilise AssetFileDescriptor afin de respecter également un éventuel startOffset.
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
        if (descriptor != null) {
            try {
                val stream = FileInputStream(descriptor.fileDescriptor)
                stream.channel.position(descriptor.startOffset + startPosition)
                return DescriptorInputStream(stream) { descriptor.close() }
            } catch (e: Exception) {
                try { descriptor.close() } catch (_: Exception) {}
                android.util.Log.w(
                    "LocalStreamServer",
                    "content URI non seekable, fallback séquentiel uri=$uri offset=$startPosition",
                    e
                )
            }
        }

        // Repli pour les rares providers renvoyant un pipe : plus lent sur une plage située loin
        // dans le fichier, mais strictement exact. Une seule invocation de skip() ne suffit pas.
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Cannot open content URI: $uri")
        try {
            skipFully(stream, startPosition)
            return stream
        } catch (e: Exception) {
            try { stream.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun contentLength(uri: Uri): Long {
        val descriptorSize = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0 }
                    ?: descriptor.parcelFileDescriptor.statSize.takeIf { it > 0 }
            }
        }.getOrNull()
        if (descriptorSize != null && descriptorSize > 0) return descriptorSize

        // Certains providers publient statSize=-1 mais renseignent correctement OpenableColumns.SIZE.
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun skipFully(stream: InputStream, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                // Force une progression ou détecte proprement EOF lorsque skip() retourne 0.
                if (stream.read() == -1) {
                    throw java.io.EOFException(
                        "Unable to seek to $byteCount; EOF after ${byteCount - remaining} bytes"
                    )
                }
                remaining--
            }
        }
    }

    private fun parseRange(rangeHeader: String, totalLength: Long): Pair<Long, Long>? {
        if (totalLength <= 0 || !rangeHeader.startsWith("bytes=")) return null
        val range = rangeHeader.removePrefix("bytes=").substringBefore(',').trim()
        val parts = range.split("-", limit = 2)
        if (parts.size != 2) return null

        val start: Long
        val end: Long
        if (parts[0].isBlank()) {
            val suffixLength = parts[1].toLongOrNull() ?: return null
            if (suffixLength <= 0) return null
            start = (totalLength - suffixLength).coerceAtLeast(0)
            end = totalLength - 1
        } else {
            start = parts[0].toLongOrNull() ?: return null
            end = parts[1].takeIf { it.isNotBlank() }?.toLongOrNull() ?: (totalLength - 1)
        }
        if (start < 0 || start >= totalLength || end < start) return null
        return start to end.coerceAtMost(totalLength - 1)
    }

    /** Repond a une requete Range (ex: "bytes=1000-") avec le statut 206 Partial Content. */
    private fun serveRange(path: String, rangeHeader: String, totalLength: Long, mimeType: String): Response {
        val parsed = parseRange(rangeHeader, totalLength)
            ?: return cors(newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Invalid range").apply {
                addHeader("Content-Range", "bytes */$totalLength")
                addHeader("Accept-Ranges", "bytes")
            })
        val (start, end) = parsed
        val contentLength = end - start + 1

        val inputStream = openInputStreamAt(path, start, contentLength)
        val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, inputStream, contentLength)
        response.addHeader("Content-Range", "bytes $start-$end/$totalLength")
        response.addHeader("Content-Length", contentLength.toString())
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "no-store, no-transform")
        response.addHeader("X-Content-Type-Options", "nosniff")
        addCorsHeaders(response)
        return response
    }

    private fun serveFull(path: String, totalLength: Long, mimeType: String): Response {
        val inputStream = openInputStreamAt(path, 0)
        val response = if (totalLength > 0) {
            newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, totalLength)
        } else {
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        }
        if (totalLength > 0) response.addHeader("Content-Length", totalLength.toString())
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "no-store, no-transform")
        response.addHeader("X-Content-Type-Options", "nosniff")
        addCorsHeaders(response)
        return response
    }

    private fun guessMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").substringBefore('?').lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "ts", "mts", "m2ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "vtt" -> "text/vtt; charset=utf-8"
            "srt" -> "application/x-subrip; charset=utf-8"
            "ass", "ssa" -> "text/x-ssa; charset=utf-8"
            // Repli sur un type concret plutôt qu'un joker ("video/*") : certains récepteurs
            // Chromecast refusent un flux dont le Content-Type n'est pas un type MIME précis.
            else -> "video/mp4"
        }
    }

    /**
     * Retourne l'adresse IP du réseau Wi-Fi actif spécifiquement, via ConnectivityManager. C'est
     * crucial : itérer bêtement toutes les interfaces réseau (ancienne implémentation) pouvait
     * retourner l'IP des données mobiles, d'un VPN ou d'une autre interface non joignable par le
     * Chromecast (qui n'est que sur le Wi-Fi local) si plusieurs interfaces étaient actives en
     * même temps — ce qui cassait le cast pour TOUTES les sources (locales et réseau), puisque
     * l'URL HTTP générée était injoignable dans les deux cas.
     */
    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (cm != null) {
                fun ipv4For(network: android.net.Network?): String? {
                    if (network == null) return null
                    val capabilities = cm.getNetworkCapabilities(network) ?: return null
                    val isWifiOrEthernet =
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                    if (!isWifiOrEthernet || capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                        return null
                    }
                    return cm.getLinkProperties(network)?.linkAddresses
                        ?.asSequence()
                        ?.mapNotNull { it.address as? java.net.Inet4Address }
                        ?.firstOrNull {
                            !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress
                        }
                        ?.hostAddress
                }

                // L'ordre de allNetworks n'est pas garanti. Le réseau actif doit être testé en
                // premier ; sinon, selon le téléphone, une ancienne interface Wi-Fi conservée par
                // Android pouvait être choisie et produire une URL joignable une fois sur deux.
                ipv4For(cm.activeNetwork)?.let { return it }

                val candidates = cm.allNetworks
                    .mapNotNull { network ->
                        val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                        val isWifiOrEthernet =
                            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                        if (!isWifiOrEthernet || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                            return@mapNotNull null
                        }
                        val score =
                            (if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 4 else 0) +
                                (if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) 2 else 0) +
                                (if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) 1 else 0)
                        network to score
                    }
                    .sortedByDescending { it.second }
                for ((network, _) in candidates) {
                    ipv4For(network)?.let { return it }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("LocalStreamServer", "ConnectivityManager IP lookup failed", e)
        }

        // Repli pour certains firmwares qui ne publient pas correctement LinkProperties mais
        // exposent encore l'adresse IPv4 via WifiManager.
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val raw = wifi?.connectionInfo?.ipAddress ?: 0
            if (raw != 0) {
                val ip = listOf(
                    raw and 0xff,
                    raw shr 8 and 0xff,
                    raw shr 16 and 0xff,
                    raw shr 24 and 0xff
                ).joinToString(".")
                if (ip != "0.0.0.0" && ip != "127.0.0.1") return ip
            }
        } catch (e: Exception) {
            android.util.Log.w("LocalStreamServer", "WifiManager IP lookup failed", e)
        }

        // Dernier repli : uniquement les interfaces actives, non virtuelles, avec priorité au
        // Wi-Fi/Ethernet. On exclut explicitement rmnet, tun et autres interfaces non joignables
        // depuis le Chromecast.
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
                .filterNot {
                    val n = it.name.lowercase()
                    n.startsWith("rmnet") || n.startsWith("tun") || n.startsWith("ppp") ||
                        n.startsWith("pdp") || n.startsWith("dummy")
                }
                .sortedByDescending {
                    val n = it.name.lowercase()
                    n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("eth")
                }
            for (networkInterface in interfaces) {
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress && !address.isAnyLocalAddress
                    ) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

private class DescriptorInputStream(
    stream: InputStream,
    private val closeDescriptor: () -> Unit
) : FilterInputStream(stream) {
    override fun close() {
        try {
            super.close()
        } finally {
            try { closeDescriptor() } catch (_: Exception) {}
        }
    }
}

/**
 * Adapte SmbMediaDataSource (lecture positionnelle) en InputStream sequentiel pour NanoHTTPD,
 * en demarrant a une position donnee (pour le support des requetes Range).
 */
private class SmbMediaDataSourceInputStream(
    private var source: fr.retrospare.blazeplayer.player.SmbMediaDataSource,
    startPosition: Long = 0,
    maxBytes: Long? = null
) : InputStream() {
    private val originalPath = sourcePathFrom(source)
    private var position: Long = startPosition
    private var remaining: Long = maxBytes?.coerceAtLeast(0L) ?: Long.MAX_VALUE
    private val buffer = ByteArray(LocalStreamServer.SMB_STREAM_BUFFER_SIZE)
    private var bufferPos = 0
    private var bufferLen = 0
    private var reopenBudget = 12

    override fun read(): Int {
        val b = ByteArray(1)
        val read = read(b, 0, 1)
        return if (read <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining <= 0L) return -1
        if (bufferPos >= bufferLen) {
            // Les MP4 non « fast start » déclenchent plusieurs petites lectures aléatoires pour
            // trouver l'atome moov. Ne pas précharger systématiquement 4 Mo pour une sonde de
            // quelques Ko : cela multipliait les accès SMB et retardait fortement le démarrage.
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            if (requested <= 0) return -1
            bufferLen = source.readAt(position, buffer, 0, requested)
            bufferPos = 0
            while (bufferLen <= 0 && position < source.size && reopenBudget > 0) {
                reopenBudget--
                try { Thread.sleep(250L) } catch (_: InterruptedException) {}
                try { source.close() } catch (_: Exception) {}
                source = fr.retrospare.blazeplayer.player.SmbMediaDataSource(originalPath)
                bufferLen = source.readAt(position, buffer, 0, requested)
            }
            if (bufferLen <= 0 && position < source.size) {
                throw java.io.IOException("SMB stream interrupted before EOF at $position/${source.size}")
            }
            if (bufferLen <= 0) return -1
        }
        val toCopy = minOf(len, bufferLen - bufferPos, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (toCopy <= 0) return -1
        System.arraycopy(buffer, bufferPos, b, off, toCopy)
        bufferPos += toCopy
        position += toCopy
        remaining -= toCopy.toLong()
        return toCopy
    }

    override fun close() {
        try { source.close() } catch (_: Exception) {}
    }

    companion object {
        private fun sourcePathFrom(source: fr.retrospare.blazeplayer.player.SmbMediaDataSource): String = source.originalUri
    }
}
