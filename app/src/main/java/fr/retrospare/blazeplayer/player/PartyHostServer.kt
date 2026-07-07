package fr.retrospare.blazeplayer.player

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Serveur HTTP local embarqué côté hôte d'une session Blaze Party.
 *
 * Avant ce fichier, le QR/lien profond Blaze Party ne faisait que positionner des indicateurs
 * locaux (isHost/isConnected) dans [BlazePartyVoteManager] : aucune donnée n'était jamais
 * réellement échangée entre l'hôte et les invités, chacun ayant ses propres SharedPreferences
 * isolées. Ce serveur comble ce manque en exposant, sur le réseau Wi-Fi local, l'état courant de
 * la file d'attente (fourni par [stateProvider], qui lit [BlazePartyVoteManager]/la playlist Party
 * de l'hôte) et en recevant les votes des invités via [onVoteReceived].
 *
 * Basé sur le même moteur (NanoHTTPD) que [fr.retrospare.blazeplayer.cast.LocalStreamServer],
 * déjà utilisé et éprouvé dans le projet pour le Chromecast, mais sur un port dédié distinct pour
 * ne jamais entrer en conflit avec le relai de flux média.
 *
 * Routes :
 *  - GET  /state?token=... -> JSON de l'état courant (file + votes + morceau en cours)
 *  - POST /join   { token, nickname }               -> signale l'arrivée d'un invité
 *  - POST /vote   { token, nickname, path, action }  -> action = "add" | "remove"
 */
class PartyHostServer(
    private val token: String,
    private val stateProvider: () -> PartyState,
    private val onVoteReceived: (path: String, nickname: String, add: Boolean) -> Unit,
    private val onGuestJoined: (nickname: String) -> Unit,
    port: Int = PartyProtocol.DEFAULT_PORT
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "PartyHostServer"
    }

    private fun cors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        // Force la fermeture HTTP après chaque requête. Les clients Android/HttpURLConnection
        // réutilisent parfois une socket LAN devenue invalide après un changement Wi-Fi, une veille
        // courte ou un recyclage NanoHTTPD ; en Blaze Party cela se traduisait par plusieurs /state
        // en échec puis un faux "connexion à l'hôte perdue". Une socket fraîche à chaque poll est
        // plus fiable pour ce petit protocole local.
        response.addHeader("Connection", "close")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        cors(newFixedLengthResponse(status, "application/json", body.toString()))

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.OPTIONS ->
                    cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
                session.method == Method.GET && session.uri == "/ping" -> handlePing(session)
                session.method == Method.GET && session.uri == "/state" -> handleState(session)
                (session.method == Method.POST || session.method == Method.GET) && session.uri == "/join" -> handleJoin(session)
                (session.method == Method.POST || session.method == Method.GET) && session.uri == "/vote" -> handleVote(session)
                else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "not_found"))
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Erreur de traitement d'une requête Blaze Party", e)
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "internal_error"))
        }
    }

    /** Lit le corps brut d'une requête POST (NanoHTTPD le place dans la clé "postData"). */
    private fun readBody(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        try { session.parseBody(files) } catch (_: Exception) {}
        val raw = files["postData"].orEmpty()
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun handlePing(session: IHTTPSession): Response {
        val requestToken = session.parameters["token"]?.firstOrNull()
        if (requestToken != token) {
            return json(Response.Status.FORBIDDEN, JSONObject().put("error", "invalid_token"))
        }
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun handleState(session: IHTTPSession): Response {
        val requestToken = session.parameters["token"]?.firstOrNull()
        if (requestToken != token) {
            return json(Response.Status.FORBIDDEN, JSONObject().put("error", "invalid_token"))
        }
        return json(Response.Status.OK, stateProvider().toJson())
    }

    private fun requestValue(session: IHTTPSession, body: JSONObject, key: String): String =
        body.optString(key).takeIf { it.isNotBlank() }
            ?: session.parameters[key]?.firstOrNull().orEmpty()

    private fun handleJoin(session: IHTTPSession): Response {
        val body = if (session.method == Method.POST) readBody(session) else JSONObject()
        if (requestValue(session, body, "token") != token) {
            return json(Response.Status.FORBIDDEN, JSONObject().put("error", "invalid_token"))
        }
        val nickname = requestValue(session, body, "nickname").ifBlank { "Invité" }
        onGuestJoined(nickname)
        return json(Response.Status.OK, JSONObject().put("ok", true).put("state", stateProvider().toJson()))
    }

    private fun handleVote(session: IHTTPSession): Response {
        val body = if (session.method == Method.POST) readBody(session) else JSONObject()
        if (requestValue(session, body, "token") != token) {
            return json(Response.Status.FORBIDDEN, JSONObject().put("error", "invalid_token"))
        }
        val path = requestValue(session, body, "path").takeIf { it.isNotBlank() }
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing_path"))
        val nickname = requestValue(session, body, "nickname").ifBlank { "Invité" }
        val add = requestValue(session, body, "action").ifBlank { "add" } != "remove"
        onVoteReceived(path, nickname, add)
        return json(Response.Status.OK, JSONObject().put("ok", true).put("state", stateProvider().toJson()))
    }
}
