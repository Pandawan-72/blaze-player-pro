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
    private val shares = ConcurrentHashMap<ShareKey, DiskShare>()

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
        val key = ShareKey(host, port, username, password, shareName)
        val existing = shares[key]
        if (existing != null && existing.isConnected) return existing
        val sess = getSession(host, port, username, password)
        val share = sess.connectShare(shareName) as DiskShare
        shares[key] = share
        return share
    }

    /** A appeler si une ressource semble cassee (erreur IO) pour forcer une reconnexion au prochain appel. */
    @Synchronized
    fun invalidate(host: String, port: Int, username: String?, shareName: String?) {
        // On invalide toutes les entrees pour ce host/username, peu importe le mot de passe stocke
        // (on ne le connait pas forcement a l'appel d'invalidate)
        shares.keys.filter { it.host == host && it.port == port && it.username == username && (shareName == null || it.shareName == shareName) }
            .forEach { key -> try { shares[key]?.close() } catch (_: Exception) {}; shares.remove(key) }
        sessions.keys.filter { it.host == host && it.port == port && it.username == username }
            .forEach { key -> try { sessions[key]?.close() } catch (_: Exception) {}; sessions.remove(key) }
        val connKey = ConnKey(host, port)
        try { connections[connKey]?.close() } catch (_: Exception) {}
        connections.remove(connKey)
    }

    @Synchronized
    fun closeAll() {
        shares.values.forEach { try { it.close() } catch (_: Exception) {} }
        sessions.values.forEach { try { it.close() } catch (_: Exception) {} }
        connections.values.forEach { try { it.close() } catch (_: Exception) {} }
        shares.clear()
        sessions.clear()
        connections.clear()
    }
}
