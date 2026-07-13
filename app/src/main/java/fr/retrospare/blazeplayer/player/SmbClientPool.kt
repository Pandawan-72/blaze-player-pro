package fr.retrospare.blazeplayer.player

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import java.util.concurrent.TimeUnit

/**
 * Pools SMB distincts :
 * - le client partagé conserve des délais tolérants pour les scans, covers et métadonnées ;
 * - le client de lecture a des délais beaucoup plus courts afin qu'un socket SMB bloqué ne puisse
 *   jamais figer ExoPlayer pendant deux minutes avant d'accepter le morceau suivant.
 */
object SmbClientPool {

    private const val SMB_BUFFER_SIZE = 2 * 1024 * 1024

    private val sharedConfig = SmbConfig.builder()
        .withTimeout(30, TimeUnit.SECONDS)
        .withReadTimeout(120, TimeUnit.SECONDS)
        .withSoTimeout(120, TimeUnit.SECONDS)
        .withBufferSize(SMB_BUFFER_SIZE)
        .build()

    private val playbackConfig = SmbConfig.builder()
        .withTimeout(12, TimeUnit.SECONDS)
        .withReadTimeout(20, TimeUnit.SECONDS)
        .withSoTimeout(20, TimeUnit.SECONDS)
        .withBufferSize(SMB_BUFFER_SIZE)
        .build()

    @Volatile
    private var sharedClient: SMBClient? = null

    @Volatile
    private var playbackClient: SMBClient? = null

    /** Client mutualisé pour les opérations non critiques de bibliothèque et de métadonnées. */
    @Synchronized
    fun getClient(): SMBClient {
        var current = sharedClient
        if (current == null) {
            current = SMBClient(sharedConfig)
            sharedClient = current
        }
        return current
    }

    /**
     * Client dédié aux flux Media3. Ses timeouts courts évitent qu'une lecture réseau suspendue
     * retienne la file audio pendant 120 secondes. Chaque DataSource ouvre malgré tout sa propre
     * connexion, ce qui permet de l'interrompre sans casser les scans parallèles.
     */
    @Synchronized
    fun getPlaybackClient(): SMBClient {
        var current = playbackClient
        if (current == null) {
            current = SMBClient(playbackConfig)
            playbackClient = current
        }
        return current
    }

    @Synchronized
    fun resetPlayback() {
        try { playbackClient?.close() } catch (_: Exception) {}
        playbackClient = null
    }

    @Synchronized
    fun reset() {
        try { sharedClient?.close() } catch (_: Exception) {}
        try { playbackClient?.close() } catch (_: Exception) {}
        sharedClient = null
        playbackClient = null
    }
}
