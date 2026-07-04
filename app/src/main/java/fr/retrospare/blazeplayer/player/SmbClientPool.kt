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
        .withReadTimeout(120, TimeUnit.SECONDS) // stable sur Wi-Fi faible, sans bloquer indéfiniment
        .withSoTimeout(120, TimeUnit.SECONDS)
        // Buffer SMB2 négocié à 2 Mo (défaut smbj : 1 Mo), aligné sur DEFAULT_READ_BUFFER_BYTES
        // de SmbDataSource. Il était auparavant à 8 Mo : le serveur pouvait alors répondre à une
        // seule lecture (notamment via SmbMediaDataSource.readAt, utilisé par
        // MediaMetadataRetriever/MediaExtractor pour sonder un conteneur MKV/MP4) avec un paquet
        // de plusieurs Mo, que smbj doit allouer d'un bloc côté client. Cumulé au tampon de
        // lecture de la vidéo en cours (2 Mo) et aux miniatures/métadonnées lues en parallèle,
        // ça a provoqué un OutOfMemoryError (tas cible 256 Mo, appli sans largeHeap) dans
        // Buffer.readRawBytes lors de la lecture d'une réponse SMB2READ de ~4 Mo pendant une
        // lecture vidéo. 2 Mo suffit largement pour saturer un lien Wi-Fi en 4K tout en gardant
        // une marge de sécurité mémoire.
        .withBufferSize(2 * 1024 * 1024)
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
