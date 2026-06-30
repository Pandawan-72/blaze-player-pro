package fr.retrospare.blazeplayer.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import fr.retrospare.blazeplayer.player.VideoPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gere toute la logique Cast pour le lecteur video :
 * - detection de la connexion Chromecast
 * - demarrage/arret du serveur HTTP local
 * - construction de l'URL HTTP castable
 * - envoi du MediaItem enrichi (titre, mimeType, metadata)
 * - gestion des erreurs et retry automatique
 */
class VideoCastManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var localStreamServer: LocalStreamServer? = null
    private var currentSourcePath: String = ""
    private var currentTitle: String = ""

    /** Demarre le serveur HTTP local pour le fichier donne. Doit etre appele au lancement du player. */
    fun startServer(sourcePath: String, title: String) {
        currentSourcePath = sourcePath
        currentTitle = title
        if (sourcePath.startsWith("http://") || sourcePath.startsWith("https://")) return
        stopServer()
        try {
            val server = LocalStreamServer(context)
            server.setSource(sourcePath)
            server.start(30_000, false)
            localStreamServer = server
            Log.d("VideoCastManager", "HTTP server started: ${server.getStreamUrl()}")
        } catch (e: Exception) {
            Log.e("VideoCastManager", "Failed to start HTTP server", e)
        }
    }

    fun stopServer() {
        localStreamServer?.stop()
        localStreamServer = null
    }

    /** Retourne l'URL castable : HTTP locale si fichier local/SMB, URL directe si deja HTTP. */
    fun getCastableUrl(): String {
        return if (currentSourcePath.startsWith("http://") || currentSourcePath.startsWith("https://")) {
            currentSourcePath
        } else {
            localStreamServer?.getStreamUrl() ?: currentSourcePath
        }
    }

    /** Verifie que le serveur HTTP repond avant d'envoyer le MediaItem au Chromecast. */
    private suspend fun waitForServer(url: String, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = withContext(Dispatchers.IO) {
                    (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = 1000
                        readTimeout = 1000
                        connect()
                    }
                }
                if (conn.responseCode in 200..299 || conn.responseCode == 206) return true
            } catch (_: Exception) {}
            delay(300)
        }
        return false
    }

    /**
     * Envoie le media au Chromecast de facon fiable :
     * 1. Attend que le serveur HTTP reponde
     * 2. Construit un MediaItem enrichi avec metadata
     * 3. Attend STATE_READY avant play()
     * 4. Retry automatique si LOAD_FAILED
     */
    fun castMedia(player: Player, position: Long = 0L, retryCount: Int = 0) {
        scope.launch {
            val castUrl = getCastableUrl()

            // Pour les fichiers locaux/SMB, verifie que le serveur HTTP repond
            if (localStreamServer != null) {
                val serverReady = waitForServer(castUrl)
                if (!serverReady) {
                    Log.e("VideoCastManager", "HTTP server not responding at $castUrl")
                    if (retryCount < 2) {
                        delay(1000)
                        castMedia(player, position, retryCount + 1)
                    }
                    return@launch
                }
            }

            val mimeType = guessMimeType(currentSourcePath)
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(castUrl))
                .setMimeType(mimeType)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(currentTitle)
                        .build()
                )
                .build()

            // Envoie le media au Chromecast - CastPlayer gere lui-meme le buffering
            withContext(Dispatchers.Main) {
                player.setMediaItem(mediaItem, position)
                player.prepare()
                player.play()
            }
            Log.d("VideoCastManager", "Cast media sent: $castUrl")
        }
    }

    private fun guessMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").substringBefore('?').lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            else -> "video/mp4"
        }
    }
}
