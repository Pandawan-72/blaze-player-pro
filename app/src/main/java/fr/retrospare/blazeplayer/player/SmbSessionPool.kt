package fr.retrospare.blazeplayer.player

import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.util.concurrent.ConcurrentHashMap

/**
 * Pool mutualisant les connexions/sessions/shares SMB par (host, username, shareName)
 * pour eviter de reconnecter/reauthentifier a chaque fichier ouvert (vidéo suivante, miniature, metadata...).
 * Les ressources sont conservees tant qu'elles fonctionnent ; en cas d'erreur elles sont recreees.
 */
object SmbSessionPool {

    data class ConnKey(val host: String, val port: Int)
    data class SessionKey(val host: String, val port: Int, val username: String?, val password: String?)
    data class ShareKey(val host: String, val port: Int, val username: String?, val password: String?, val shareName: String)

    private val connections = ConcurrentHashMap<ConnKey, Connection>()
    private val sessions = ConcurrentHashMap<SessionKey, Session>()
    private val lastInvalidateAt = ConcurrentHashMap<ConnKey, Long>()
    private const val INVALIDATE_DEBOUNCE_MS = 2000L
    // IMPORTANT: on ne mutualise PAS les DiskShare.
    // DiskShare/handles SMBJ ne sont pas sûrs à partager entre la lecture vidéo, le serveur HTTP
    // local et l'extraction de sous-titres. Un invalidate()/close() depuis un consommateur pouvait
    // fermer le DiskShare utilisé par un autre, d'où les erreurs "DiskShare has already been closed"
    // observées pendant l'extraction/cast. On garde le pool connexion/session, mais chaque appel
    // getShare() reçoit son propre DiskShare à fermer par le consommateur.

    @Synchronized
    fun getConnection(host: String, port: Int): Connection {
        val key = ConnKey(host, port)
        val existing = connections[key]
        if (existing != null && existing.isConnected) return existing
        val client = SmbClientPool.getClient()
        val conn = client.connect(host, port)
        connections[key] = conn
        return conn
    }

    @Synchronized
    fun getSession(host: String, port: Int, username: String?, password: String?): Session {
        val key = SessionKey(host, port, username, password)
        val existing = sessions[key]
        if (existing != null) {
            val stillValid = try { existing.connection.isConnected } catch (_: Exception) { false }
            if (stillValid) return existing
            sessions.remove(key)
        }
        val conn = getConnection(host, port)
        val authContext = if (!username.isNullOrEmpty()) {
            AuthenticationContext(username, (password ?: "").toCharArray(), "")
        } else {
            AuthenticationContext.anonymous()
        }
        val sess = conn.authenticate(authContext)
        sessions[key] = sess
        return sess
    }

    @Synchronized
    fun getShare(host: String, port: Int, username: String?, password: String?, shareName: String): DiskShare {
        val sess = getSession(host, port, username, password)
        return sess.connectShare(shareName) as DiskShare
    }

    /** A appeler si une ressource semble cassee (erreur IO) pour forcer une reconnexion au prochain appel. */
    @Synchronized
    fun invalidate(host: String, port: Int, username: String?, shareName: String?) {
        val connKey = ConnKey(host, port)
        val now = System.currentTimeMillis()
        val last = lastInvalidateAt[connKey]
        if (last != null && now - last < INVALIDATE_DEBOUNCE_MS) {
            // Sous forte concurrence (plusieurs threads NanoHTTPD sur le même hôte, typique pour
            // un MP4 qui doit lire début ET fin de fichier), un échec transitoire sur UN thread
            // provoquait une fermeture de la connexion PARTAGÉE, cassant du même coup tous les
            // autres threads en train de l'utiliser — qui appelaient alors CHACUN invalidate() à
            // leur tour, créant une tempête de réinvalidations en cascade. Une connexion fraîche
            // vient déjà d'être (re)créée il y a moins de 2s : ne pas la retirer une nouvelle fois.
            android.util.Log.i("SmbSessionPool", "invalidate($host) ignoré (déjà fait il y a ${now - last}ms)")
            return
        }
        lastInvalidateAt[connKey] = now
        // On invalide toutes les entrees pour ce host/username, peu importe le mot de passe stocke
        // (on ne le connait pas forcement a l'appel d'invalidate)
        // Les DiskShare ne sont plus partagés ni stockés : ne jamais fermer ici des shares
        // potentiellement utilisés par un autre flux en cours.
        sessions.keys.filter { it.host == host && it.port == port && it.username == username }
            .forEach { key -> try { sessions[key]?.close() } catch (_: Exception) {}; sessions.remove(key) }
        try { connections[connKey]?.close() } catch (_: Exception) {}
        connections.remove(connKey)
    }

    @Synchronized
    fun closeAll() {
        sessions.values.forEach { try { it.close() } catch (_: Exception) {} }
        connections.values.forEach { try { it.close() } catch (_: Exception) {} }
        sessions.clear()
        connections.clear()
    }
}
