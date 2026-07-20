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
    @Volatile private var desiredSourcePath: String = ""
    private val sourceVersions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    val currentSourcePath: String get() = sharedSourcePath
    val requestedSourcePath: String get() = desiredSourcePath

    /** Démarre (ou réutilise) le serveur pour ce fichier. À appeler avant de construire le
     *  MediaItem, qui a besoin de l'URL retournée par [getStreamUrl]. */
    @Synchronized
    fun startServer(context: Context, sourcePath: String) {
        desiredSourcePath = sourcePath
        activateSource(context, sourcePath)
    }

    /** Retourne une URL LAN propre à CE MediaItem sans autoriser une conversion Cast tardive à
     *  remplacer la source active demandée par l'utilisateur. Les anciennes et nouvelles URLs
     *  restent valides simultanément grâce au registre versionné de [LocalStreamServer]. */
    @Synchronized
    fun getLanStreamUrlFor(context: Context, sourcePath: String): String? {
        val server = ensureServer(context) ?: return null
        val version = if (desiredSourcePath == sourcePath) {
            if (sharedSourcePath != sourcePath) {
                sharedSourcePath = sourcePath
                server.setSource(sourcePath).also { sourceVersions[sourcePath] = it }
            } else {
                sourceVersions[sourcePath] ?: server.setSource(sourcePath).also { sourceVersions[sourcePath] = it }
            }
        } else {
            sourceVersions[sourcePath] ?: server.registerSource(sourcePath).also { sourceVersions[sourcePath] = it }
        }
        return server.getLanStreamUrl(version)
    }


    /** URL loopback propre à une source précise. Utilisée uniquement par le pipeline FFmpeg Cast :
     *  le serveur sait déjà lire file://, content:// et SMB avec des accès Range exacts. */
    @Synchronized
    fun getLoopbackStreamUrlFor(context: Context, sourcePath: String): String? {
        val server = ensureServer(context) ?: return null
        val version = sourceVersions[sourcePath]
            ?: server.registerSource(sourcePath).also { sourceVersions[sourcePath] = it }
        return server.getStreamUrl(version)
    }

    private fun ensureServer(context: Context): LocalStreamServer? {
        var server = sharedServer
        if (server == null) {
            server = LocalStreamServer(context.applicationContext)
            try {
                server.start(90_000, false)
                sharedServer = server
            } catch (e: Exception) {
                android.util.Log.e("VideoStreamServerManager", "Failed to start HTTP server", e)
                return null
            }
        }
        return server
    }

    private fun activateSource(context: Context, sourcePath: String) {
        val server = ensureServer(context) ?: return
        sharedSourcePath = sourcePath
        sourceVersions[sourcePath] = server.setSource(sourcePath)
    }

    @Synchronized
    fun stopServer() {
        val server = sharedServer
        sharedServer = null
        sharedSourcePath = ""
        desiredSourcePath = ""
        sourceVersions.clear()
        try { server?.clearSource() } catch (_: Exception) {}
        // L'ancien arrêt asynchrone permettait à un nouveau serveur d'essayer de reprendre le port
        // 8927 avant que l'ancien ne l'ait réellement libéré. Pendant une transition Cast, cela
        // pouvait produire une URL valide quelques instants puis un flux brutalement coupé.
        // NanoHTTPD.stop() est court : on le termine donc avant d'autoriser un redémarrage.
        try { server?.stop() } catch (_: Exception) {}
    }

    fun getStreamUrl(): String? = sharedServer?.getStreamUrl()
    fun getLanStreamUrl(): String? = sharedServer?.getLanStreamUrl()

    /** Adresse IP réseau du téléphone, utilisée uniquement par [fr.retrospare.blazeplayer.player.PlayerActivity]
     *  pour réécrire l'URL loopback en une adresse joignable par le Chromecast. */
    fun getLanIpAddress(): String? = sharedServer?.localNetworkIpAddress()
}
