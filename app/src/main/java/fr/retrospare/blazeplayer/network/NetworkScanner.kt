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
import java.net.MulticastSocket
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkScanner @Inject constructor() {

    companion object {
        private const val SMB_PORT = 445
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SCAN_TIMEOUT_MS = 500
        private const val SSDP_TIMEOUT_MS = 3000
    }

    // Scan complet : SMB + DLNA
    fun scan(): Flow<NetworkShare> = flow {
        val subnet = getLocalSubnet() ?: return@flow

        // SSDP/DLNA en parallèle
        val dlnaDevices = mutableListOf<NetworkShare>()
        try {
            discoverDlna().forEach { dlnaDevices.add(it) }
        } catch (e: Exception) {}
        dlnaDevices.forEach { emit(it) }

        // Scan SMB sur le sous-réseau
        coroutineScope {
            (1..254).map { i ->
                async(Dispatchers.IO) {
                    val ip = "$subnet.$i"
                    try {
                        val addr = InetAddress.getByName(ip)
                        if (addr.isReachable(SCAN_TIMEOUT_MS)) {
                            if (isSmbOpen(ip)) {
                                val hostname = try { addr.canonicalHostName.takeIf { it != ip } ?: ip } catch (e: Exception) { ip }
                                NetworkShare(
                                    id = "smb_$ip",
                                    name = hostname,
                                    host = ip,
                                    port = SMB_PORT,
                                    shareName = "",
                                    type = ShareType.SMB
                                )
                            } else null
                        } else null
                    } catch (e: Exception) { null }
                }
            }.awaitAll().filterNotNull().forEach { emit(it) }
        }
    }

    // Scan des shares d'un host SMB donné
    suspend fun listShares(host: String, username: String?, password: String?): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val client = com.hierynomus.smbj.SMBClient()
                val auth = if (username != null && username.isNotEmpty()) {
                    com.hierynomus.smbj.auth.AuthenticationContext(username, (password ?: "").toCharArray(), "")
                } else com.hierynomus.smbj.auth.AuthenticationContext.anonymous()

                client.connect(host).use { conn ->
                    conn.authenticate(auth).use { session ->
                        listOf("share", "media", "videos", "public", "data", "nas", "download", "films")
                            .filter { shareName ->
                                try { session.connectShare(shareName); true } catch (e: Exception) { false }
                            }
                    }
                }
            } catch (e: Exception) { emptyList() }
        }

    private fun isSmbOpen(ip: String): Boolean = try {
        val socket = java.net.Socket()
        socket.connect(InetSocketAddress(ip, SMB_PORT), SCAN_TIMEOUT_MS)
        socket.close()
        true
    } catch (e: Exception) { false }

    private fun getLocalSubnet(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                ?.hostAddress
                ?.substringBeforeLast('.')
        } catch (e: Exception) { null }
    }

    private suspend fun discoverDlna(): List<NetworkShare> = withContext(Dispatchers.IO) {
        val results = mutableListOf<NetworkShare>()
        try {
            val ssdpRequest = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"

            val socket = DatagramSocket()
            socket.soTimeout = SSDP_TIMEOUT_MS
            val bytes = ssdpRequest.toByteArray()
            val group = InetAddress.getByName(SSDP_ADDR)
            socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))

            val buf = ByteArray(4096)
            val endTime = System.currentTimeMillis() + SSDP_TIMEOUT_MS
            while (System.currentTimeMillis() < endTime) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length)
                    val ip = packet.address.hostAddress ?: continue
                    val name = extractDlnaName(response) ?: "DLNA $ip"
                    if (results.none { it.host == ip }) {
                        results.add(NetworkShare(
                            id = "dlna_$ip",
                            name = name,
                            host = ip,
                            shareName = "",
                            type = ShareType.DLNA
                        ))
                    }
                } catch (e: Exception) { break }
            }
            socket.close()
        } catch (e: Exception) {}
        results
    }

    private fun extractDlnaName(response: String): String? {
        return response.lines()
            .firstOrNull { it.startsWith("SERVER:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
    }
}
