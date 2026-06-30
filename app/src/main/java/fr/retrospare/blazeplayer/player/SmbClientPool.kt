package fr.retrospare.blazeplayer.player

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import java.util.concurrent.TimeUnit

/**
 * Pool simple pour mutualiser les SMBClient et eviter de recreer
 * un client (avec sa propre configuration et ses propres pools internes) a chaque connexion.
 * Un seul SMBClient est partage par toute l'application pour le streaming SMB.
 */
object SmbClientPool {

    private val config = SmbConfig.builder()
        .withTimeout(30, TimeUnit.SECONDS)
        .withReadTimeout(300, TimeUnit.SECONDS) // timeout etendu pour gros fichiers/remux Blu-ray/Wi-Fi faible
        .withSoTimeout(300, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var client: SMBClient? = null

    @Synchronized
    fun getClient(): SMBClient {
        var c = client
        if (c == null) {
            c = SMBClient(config)
            client = c
        }
        return c
    }

    @Synchronized
    fun reset() {
        try { client?.close() } catch (_: Exception) {}
        client = null
    }
}
