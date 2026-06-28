package fr.retrospare.blazeplayer.network

import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkScanner @Inject constructor() {

    companion object {
        private const val SMB_PORT = 445
        private const val NETBIOS_PORT = 137
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SCAN_TIMEOUT_MS = 300
        private const val SSDP_TIMEOUT_MS = 4000
    }

    data class DiscoveredDevice(
        val ip: String,
        val name: String,
        val type: ShareType,
        val extra: String = "" // modèle, version, etc.
    )

    fun scan(): Flow<DiscoveredDevice> = flow {
        val subnet = getLocalSubnet() ?: return@flow

        // 1. DLNA/SSDP d'abord - rapide et fiable
        try {
            discoverDlna().forEach { emit(it) }
        } catch (e: Exception) {}

        // 2. Scan SMB en parallèle sur tout le sous-réseau
        coroutineScope {
            (1..254).map { i ->
                async(Dispatchers.IO) {
                    val ip = "$subnet.$i"
                    try {
                        if (isSmbOpen(ip)) {
                            val name = getHostName(ip)
                            DiscoveredDevice(ip = ip, name = name, type = ShareType.SMB)
                        } else null
                    } catch (e: Exception) { null }
                }
            }.awaitAll().filterNotNull().forEach { emit(it) }
        }
    }

    private fun isSmbOpen(ip: String): Boolean = try {
        val socket = java.net.Socket()
        socket.connect(InetSocketAddress(ip, SMB_PORT), SCAN_TIMEOUT_MS)
        socket.close()
        true
    } catch (e: Exception) { false }

    private fun getHostName(ip: String): String {
        // 1. Essai NetBIOS Name Service (port 137 UDP)
        val netbiosName = tryNetBiosName(ip)
        if (netbiosName != null) return netbiosName

        // 2. Reverse DNS
        return try {
            val addr = InetAddress.getByName(ip)
            val host = addr.canonicalHostName
            if (host != ip) host.substringBefore(".").uppercase()
            else ip
        } catch (e: Exception) { ip }
    }

    private fun tryNetBiosName(ip: String): String? {
        return try {
            // Requête NetBIOS Node Status
            val query = byteArrayOf(
                0x00, 0x00, // Transaction ID
                0x00, 0x00, // Flags
                0x00, 0x01, // Questions: 1
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Answer/Auth/Additional RRs
                0x20, // Name length
                // Encoded "*" (CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA)
                0x43, 0x4B, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x00, // End of name
                0x00, 0x21, // Type: NBSTAT
                0x00, 0x01  // Class: IN
            )
            val socket = DatagramSocket()
            socket.soTimeout = 500
            val packet = DatagramPacket(query, query.size, InetAddress.getByName(ip), NETBIOS_PORT)
            socket.send(packet)
            val response = ByteArray(1024)
            val responsePacket = DatagramPacket(response, response.size)
            socket.receive(responsePacket)
            socket.close()

            // Parse le nom NetBIOS depuis la réponse
            parseNetBiosName(response, responsePacket.length)
        } catch (e: Exception) { null }
    }

    private fun parseNetBiosName(data: ByteArray, len: Int): String? {
        if (len < 57) return null
        return try {
            // Le nombre de noms est à l'offset 56
            val numNames = data[56].toInt() and 0xFF
            if (numNames == 0) return null
            // Premier nom commence à l'offset 57
            val name = String(data, 57, 15).trim()
            if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }) name
            else null
        } catch (e: Exception) { null }
    }

    private suspend fun discoverDlna(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DiscoveredDevice>()
        try {
            val ssdpRequest = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"

            val socket = DatagramSocket()
            socket.soTimeout = 1000
            val bytes = ssdpRequest.toByteArray()
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(SSDP_ADDR), SSDP_PORT))

            val buf = ByteArray(4096)
            val endTime = System.currentTimeMillis() + SSDP_TIMEOUT_MS
            while (System.currentTimeMillis() < endTime) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length)
                    val ip = packet.address.hostAddress ?: continue
                    if (results.any { it.ip == ip }) continue

                    val name = extractDlnaFriendlyName(response) ?: extractDlnaServerName(response) ?: "DLNA $ip"
                    val extra = extractDlnaServerName(response) ?: ""
                    results.add(DiscoveredDevice(ip = ip, name = name, type = ShareType.DLNA, extra = extra))
                } catch (e: Exception) { break }
            }
            socket.close()
        } catch (e: Exception) {}
        results
    }

    // Fetch la description XML UPnP pour récupérer le vrai nom
    private fun extractDlnaFriendlyName(ssdpResponse: String): String? {
        val location = ssdpResponse.lines()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: return null
        return try {
            val url = java.net.URL(location)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val xml = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            // Parse <friendlyName>
            Regex("<friendlyName>([^<]+)</friendlyName>").find(xml)?.groupValues?.get(1)?.trim()
        } catch (e: Exception) { null }
    }

    private fun extractDlnaServerName(response: String): String? {
        return response.lines()
            .firstOrNull { it.startsWith("SERVER:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
    }

    fun getLocalSubnet(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                ?.hostAddress
                ?.substringBeforeLast('.')
        } catch (e: Exception) { null }
    }

    suspend fun listShares(host: String, username: String?, password: String?): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val client = com.hierynomus.smbj.SMBClient()
                val auth = if (!username.isNullOrEmpty()) {
                    com.hierynomus.smbj.auth.AuthenticationContext(username, (password ?: "").toCharArray(), "")
                } else com.hierynomus.smbj.auth.AuthenticationContext.anonymous()
                client.connect(host).use { conn ->
                    conn.authenticate(auth).use { session ->
                        listOf("share", "media", "videos", "public", "data", "nas", "download", "films", "video", "partage")
                            .filter { shareName ->
                                try { session.connectShare(shareName); true } catch (e: Exception) { false }
                            }
                    }
                }
            } catch (e: Exception) { emptyList() }
        }
}
