package fr.retrospare.blazeplayer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val SMB_PORT = 445
        private const val NETBIOS_PORT = 137
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SCAN_TIMEOUT_MS = 300
        private const val SSDP_TIMEOUT_MS = 4000
        /** Nombre maximum de connexions simultanées pendant le scan du sous-réseau. Sans cette
         *  limite, jusqu'à 254 connexions étaient lancées en parallèle, ce qui peut causer des
         *  lenteurs, des timeouts, ou une charge excessive sur certains téléphones/NAS/box. */
        private const val MAX_CONCURRENT_SCANS = 24
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
        } catch (e: Exception) {}

        // 2. Scan SMB en parallèle sur tout le sous-réseau, avec une concurrence limitée pour
        // ne pas ouvrir jusqu'à 254 connexions simultanées.
        val semaphore = Semaphore(MAX_CONCURRENT_SCANS)
        coroutineScope {
            (1..254).map { i ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val ip = "$subnet.$i"
                        try {
                            if (isSmbOpen(ip)) {
                                val name = getHostName(ip)
                                DiscoveredDevice(ip = ip, name = name, type = ShareType.SMB)
                            } else null
                        } catch (e: Exception) { null }
                    }
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


    /** Détermine le sous-réseau à scanner à partir du réseau *actif* signalé par
     *  [ConnectivityManager] (Wi-Fi ou Ethernet en priorité), plutôt que la première adresse
     *  IPv4 non-loopback trouvée sur l'appareil — cette dernière approche pouvait tomber sur un
     *  VPN, un partage de connexion, une interface Docker/émulateur, etc., et scanner le
     *  mauvais sous-réseau (donc ne rien trouver du tout). */
    fun getLocalSubnet(): String? {
        connectivityManagerSubnet()?.let { return it }
        // Repli sur l'ancienne méthode si ConnectivityManager ne renvoie rien d'exploitable
        // (cas limite, mais mieux vaut un résultat approximatif que rien).
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                ?.hostAddress
                ?.substringBeforeLast('.')
        } catch (e: Exception) { null }
    }

    private fun connectivityManagerSubnet(): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val activeNetwork = cm.activeNetwork ?: return null
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return null
            // Ne considère que Wi-Fi ou Ethernet : un VPN ou une connexion cellulaire actifs en
            // parallèle du Wi-Fi pourraient sinon être choisis à la place du bon réseau local.
            val isRelevant = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isRelevant) return null
            val linkProperties = cm.getLinkProperties(activeNetwork) ?: return null
            linkProperties.linkAddresses
                .mapNotNull { it.address as? java.net.Inet4Address }
                .firstOrNull { !it.isLoopbackAddress }
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
