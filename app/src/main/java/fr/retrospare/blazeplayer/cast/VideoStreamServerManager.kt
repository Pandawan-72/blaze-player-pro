package fr.retrospare.blazeplayer.cast

import android.content.Context

/**
 * Gère le cycle de vie du petit serveur HTTP local qui relaie fichiers locaux/SMB, utilisé pour
 * TOUTE lecture vidéo (locale ET Chromecast, cf. doc de VideoPlaybackService). Contrairement à
 * l'ancien VideoCastManager, cette classe n'envoie plus jamais de LOAD manuel au Chromecast :
 * CastPlayer s'en charge tout seul dès que le MediaItem (déjà valide pour les deux contextes) lui
 * est transmis.
 */
object VideoStreamServerManager {

    // Serveur HTTP local PARTAGÉ entre toutes les instances de PlayerActivity (une seule à la
    // fois en pratique, mais partagé pour survivre à une recréation d'écran sans couper un cast
    // en cours).
    @Volatile private var sharedServer: LocalStreamServer? = null
    @Volatile private var sharedSourcePath: String = ""

    val currentSourcePath: String get() = sharedSourcePath

    /** Démarre (ou réutilise) le serveur pour ce fichier. À appeler avant de construire le
     *  MediaItem, qui a besoin de l'URL retournée par [getStreamUrl]. */
    @Synchronized
    fun startServer(context: Context, sourcePath: String) {
        sharedSourcePath = sourcePath
        var server = sharedServer
        if (server == null) {
            server = LocalStreamServer(context.applicationContext)
            try {
                // Timeout socket généreux : sur un flux 4K haut débit, une pause de buffering un
                // peu longue peut dépasser un timeout court et couper la connexion prématurément.
                server.start(90_000, false)
                sharedServer = server
            } catch (e: Exception) {
                android.util.Log.e("VideoStreamServerManager", "Failed to start HTTP server", e)
                return
            }
        }
        server.setSource(sourcePath)
    }

    fun stopServer() {
        try { sharedServer?.stop() } catch (_: Exception) {}
        sharedServer = null
        sharedSourcePath = ""
    }

    fun getStreamUrl(): String? = sharedServer?.getStreamUrl()

    /** Adresse IP réseau du téléphone, utilisée uniquement par [fr.retrospare.blazeplayer.player.PlayerActivity]
     *  pour réécrire l'URL loopback en une adresse joignable par le Chromecast. */
    fun getLanIpAddress(): String? = sharedServer?.localNetworkIpAddress()
}
