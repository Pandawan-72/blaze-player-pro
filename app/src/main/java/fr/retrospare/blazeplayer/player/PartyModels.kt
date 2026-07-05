package fr.retrospare.blazeplayer.player

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Un morceau de la file Blaze Party tel que vu par le réseau : chemin, nom affichable,
 * nombre de votes et pseudos des votants. Utilisé pour sérialiser/désérialiser l'état
 * échangé entre [PartyHostServer] (hôte) et [PartyClient] (invité).
 */
data class PartyTrack(
    val path: String,
    val name: String,
    val votes: Int,
    val voters: List<String>
)

/** Snapshot complet de la session Party, tel que renvoyé par GET /state côté hôte. */
data class PartyState(
    val tracks: List<PartyTrack>,
    val currentPath: String?,
    val hostNickname: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("currentPath", currentPath ?: JSONObject.NULL)
        put("hostNickname", hostNickname)
        put("tracks", JSONArray().apply {
            tracks.forEach { t ->
                put(JSONObject().apply {
                    put("path", t.path)
                    put("name", t.name)
                    put("votes", t.votes)
                    put("voters", JSONArray(t.voters))
                })
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): PartyState {
            val tracksArr = json.optJSONArray("tracks") ?: JSONArray()
            val tracks = (0 until tracksArr.length()).mapNotNull { i ->
                val o = tracksArr.optJSONObject(i) ?: return@mapNotNull null
                val votersArr = o.optJSONArray("voters") ?: JSONArray()
                PartyTrack(
                    path = o.optString("path"),
                    name = o.optString("name"),
                    votes = o.optInt("votes", 0),
                    voters = (0 until votersArr.length()).mapNotNull { v -> votersArr.optString(v).takeIf { it.isNotBlank() } }
                )
            }
            val currentPath = if (json.isNull("currentPath")) null else json.optString("currentPath").takeIf { it.isNotBlank() }
            return PartyState(tracks, currentPath, json.optString("hostNickname"))
        }
    }
}

/** Coordonnées réseau d'une session Blaze Party, extraites d'un QR/lien profond. */
data class PartyConnection(val host: String, val port: Int, val token: String) {
    fun baseUrl(): String = "http://$host:$port"

    fun toJson(): JSONObject = JSONObject().apply {
        put("host", host)
        put("port", port)
        put("token", token)
    }

    companion object {
        fun fromJson(json: JSONObject): PartyConnection? {
            val host = json.optString("host").takeIf { it.isNotBlank() } ?: return null
            val token = json.optString("token").takeIf { it.isNotBlank() } ?: return null
            val port = json.optInt("port", PartyProtocol.DEFAULT_PORT)
            return PartyConnection(host, port, token)
        }
    }
}

/**
 * Comble le "point d'extension V1" laissé dans AudioPlayerFragment/MainActivity : construit et
 * interprète le payload du QR/lien profond Blaze Party (adresse IP, port, jeton de session), pour
 * que l'invité puisse réellement se connecter au [PartyHostServer] de l'hôte plutôt que de se
 * contenter de positionner des indicateurs locaux.
 *
 * Deux formats sont supportés (les deux existaient déjà dans le code, seul le port par défaut
 * "57931" était en dur côté MainActivity) :
 *  - compact : "bp://<ip>[:<port>]/<token>" (celui généré par le QR)
 *  - long    : "blazeparty://join?host=<ip>&port=<port>&token=<token>"
 */
object PartyProtocol {
    const val DEFAULT_PORT = 57931

    fun buildPayload(ip: String, token: String, port: Int = DEFAULT_PORT): String = "bp://$ip:$port/$token"

    fun parse(payload: String?): PartyConnection? {
        if (payload.isNullOrBlank()) return null
        val uri = try { Uri.parse(payload.trim()) } catch (_: Exception) { return null }
        return try {
            when (uri.scheme) {
                "bp" -> {
                    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
                    val port = if (uri.port != -1) uri.port else DEFAULT_PORT
                    val token = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
                    PartyConnection(host, port, token)
                }
                "blazeparty" -> {
                    if (uri.host != "join") return null
                    val host = uri.getQueryParameter("host")?.takeIf { it.isNotBlank() } ?: return null
                    val port = uri.getQueryParameter("port")?.toIntOrNull() ?: DEFAULT_PORT
                    val token = uri.getQueryParameter("token")?.takeIf { it.isNotBlank() } ?: return null
                    PartyConnection(host, port, token)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
