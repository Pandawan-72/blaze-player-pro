package fr.retrospare.blazeplayer.cast

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

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

    /** Snapshot immuable de la source active, remplacé atomiquement à chaque changement de vidéo
     *  (setSource). Chaque requête HTTP capture UNE SEULE référence à ce snapshot en tout début
     *  de traitement et s'y tient pour toute sa durée — plutôt que de relire des champs mutables
     *  individuellement à plusieurs endroits, ce qui pouvait produire un mélange incohérent
     *  ancien chemin / nouvelle taille (ou l'inverse) si setSource() s'exécutait sur le thread
     *  principal EN PLEIN MILIEU du traitement d'une requête sur un thread NanoHTTPD séparé.
     *  C'est exactement le scénario "le serveur répond encore pendant qu'on change de source". */
    private data class ActiveSource(val path: String, val version: Long)

    @Volatile private var activeSource: ActiveSource? = null

    // Taille de fichier mise en cache par CHEMIN (pas par référence mutable partagée) : une
    // requête sur l'ancienne vidéo qui recalcule sa taille ne peut plus jamais écraser/lire un
    // état pensé pour la nouvelle vidéo, quel que soit l'ordre d'exécution des threads.
    private val fileSizeCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun setSource(path: String) {
        activeSource = ActiveSource(path, System.currentTimeMillis())
    }

    /** URL a donner au player local pour lire ce fichier — TOUJOURS en loopback (127.0.0.1), même
     *  quand on ne caste pas : bien plus fiable qu'une auto-connexion via l'IP réseau (observée
     *  comme peu fiable sur certains routeurs/box). L'IP réseau n'est utilisée que pour le Cast :
     *  PlayerActivity.buildMediaItem(forCast=true) remplace l'hôte de cette URL au moment de la
     *  transition vers le Chromecast (cf. onDeviceInfoChanged) — CastPlayer.Builder n'exposant
     *  aucun point d'injection pour faire cette réécriture automatiquement (Media3 1.9). */
    fun getStreamUrl(): String {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        return "http://$ip:${this.listeningPort}/stream/${activeSource?.version ?: 0L}"
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
        android.util.Log.i("LocalStreamServer", "Requête reçue de $remoteIp : ${session.method} ${session.uri}")
        android.util.Log.i("LocalStreamServer", "En-têtes : ${session.headers}")

        // Le Chromecast peut envoyer OPTIONS avant GET (pré-vérification CORS) : une 404 ici peut
        // faire abandonner certains récepteurs.
        if (session.method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCorsHeaders(response)
            return response
        }

        val source = activeSource ?: return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No source set"))
        val path = source.path
        val mimeType = guessMimeType(path)

        return try {
            val totalLength = getOrComputeFileSize(path)
            val rangeHeader = session.headers["range"]

            // Le Chromecast fait souvent un HEAD avant le GET réel pour connaître la taille/type
            // du contenu sans le télécharger — sans réponse propre à HEAD (mêmes en-têtes que
            // GET, mais sans corps), certains récepteurs refusent ensuite le média.
            if (session.method == Method.HEAD) {
                val response = newFixedLengthResponse(Response.Status.OK, mimeType, "")
                response.addHeader("Content-Length", totalLength.coerceAtLeast(0).toString())
                response.addHeader("Accept-Ranges", "bytes")
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
                repeat(3) { attempt ->
                    try {
                        val smbSource = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
                        result = smbSource.size
                        try { smbSource.close() } catch (_: Exception) {}
                        return@repeat
                    } catch (e: Exception) {
                        android.util.Log.w("LocalStreamServer", "getOrComputeFileSize échec tentative ${attempt + 1}/3 pour $path", e)
                        if (attempt < 2) try { Thread.sleep(300L) } catch (_: InterruptedException) {}
                    }
                }
                result
            }
            path.startsWith("content://") -> {
                context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { it.statSize } ?: -1L
            }
            else -> {
                val file = java.io.File(path)
                if (file.exists()) file.length() else -1L
            }
        }
        if (size > 0) fileSizeCache[path] = size
        return size
    }

    private fun openInputStreamAt(path: String, startPosition: Long): InputStream {
        return when {
            path.startsWith("smb://") -> {
                val smbSource = fr.retrospare.blazeplayer.player.SmbMediaDataSource(path)
                SmbMediaDataSourceInputStream(smbSource, startPosition)
            }
            path.startsWith("content://") -> {
                val stream = context.contentResolver.openInputStream(Uri.parse(path))
                    ?: throw java.io.IOException("Cannot open content://")
                if (startPosition > 0) stream.skip(startPosition)
                stream
            }
            else -> {
                val file = java.io.File(path)
                if (!file.exists()) throw java.io.IOException("File not found")
                val stream = file.inputStream()
                if (startPosition > 0) stream.skip(startPosition)
                stream
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
            ?: return cors(newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Invalid range"))
        val (start, end) = parsed
        val contentLength = end - start + 1

        val inputStream = openInputStreamAt(path, start)
        val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, inputStream, contentLength)
        response.addHeader("Content-Range", "bytes $start-$end/$totalLength")
        response.addHeader("Content-Length", contentLength.toString())
        response.addHeader("Accept-Ranges", "bytes")
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
    private fun getLocalIpAddress(): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (cm != null) {
                // Cherche spécifiquement un réseau de transport WIFI (ou Ethernet, pour les box
                // Android TV/téléphones reliés en filaire) parmi tous les réseaux actifs.
                for (network in cm.allNetworks) {
                    val capabilities = cm.getNetworkCapabilities(network) ?: continue
                    val isWifiOrEthernet =
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                    if (!isWifiOrEthernet) continue
                    val linkProperties = cm.getLinkProperties(network) ?: continue
                    val ipv4 = linkProperties.linkAddresses
                        .mapNotNull { it.address as? java.net.Inet4Address }
                        .firstOrNull { !it.isLoopbackAddress }
                    if (ipv4 != null) return ipv4.hostAddress
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("LocalStreamServer", "ConnectivityManager IP lookup failed", e)
        }

        // Repli : parcourt les interfaces réseau en donnant explicitement la priorité à celles
        // nommées comme du Wi-Fi (wlan/ap), pour éviter de retomber sur les données mobiles.
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val sorted = interfaces.sortedByDescending { it.name.startsWith("wlan") || it.name.startsWith("ap") }
            for (networkInterface in sorted) {
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) { null }
    }
}

/**
 * Adapte SmbMediaDataSource (lecture positionnelle) en InputStream sequentiel pour NanoHTTPD,
 * en demarrant a une position donnee (pour le support des requetes Range).
 */
private class SmbMediaDataSourceInputStream(
    private val source: fr.retrospare.blazeplayer.player.SmbMediaDataSource,
    startPosition: Long = 0
) : InputStream() {
    private var position: Long = startPosition
    private val buffer = ByteArray(8 * 1024 * 1024)
    private var bufferPos = 0
    private var bufferLen = 0

    override fun read(): Int {
        val b = ByteArray(1)
        val read = read(b, 0, 1)
        return if (read <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (bufferPos >= bufferLen) {
            bufferLen = source.readAt(position, buffer, 0, buffer.size)
            bufferPos = 0
            if (bufferLen <= 0) return -1
        }
        val toCopy = minOf(len, bufferLen - bufferPos)
        System.arraycopy(buffer, bufferPos, b, off, toCopy)
        bufferPos += toCopy
        position += toCopy
        return toCopy
    }

    override fun close() {
        try { source.close() } catch (_: Exception) {}
    }
}
