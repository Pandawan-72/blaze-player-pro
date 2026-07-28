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
    val voters: List<String>,
    val artist: String = "",
    val title: String = "",
    val extension: String = "",
    val bitrate: Long = 0L,
    val isLossless: Boolean = false,
    val durationMs: Long = 0L,
    /** Position dans l’historique de lecture Party côté hôte. 0 = pas encore joué. */
    val playedOrder: Int = 0
)

/** Snapshot complet de la session Party, tel que renvoyé par GET /state côté hôte. */
data class PartyState(
    val tracks: List<PartyTrack>,
    val currentPath: String?,
    val hostNickname: String,
    /** Position de lecture de l'hôte au moment du snapshot. */
    val currentPositionMs: Long = 0L,
    /** Durée de la piste en cours côté hôte au moment du snapshot. */
    val currentDurationMs: Long = 0L,
    /** Indique si la piste courante avance réellement côté hôte. */
    val isPlaying: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("currentPath", currentPath ?: JSONObject.NULL)
        put("hostNickname", hostNickname)
        put("currentPositionMs", currentPositionMs.coerceAtLeast(0L))
        put("currentDurationMs", currentDurationMs.coerceAtLeast(0L))
        put("isPlaying", isPlaying)
        put("tracks", JSONArray().apply {
            tracks.forEach { t ->
                put(JSONObject().apply {
                    put("path", t.path)
                    put("name", t.name)
                    put("votes", t.votes)
                    put("voters", JSONArray(t.voters))
                    if (t.artist.isNotBlank()) put("artist", t.artist)
                    if (t.title.isNotBlank()) put("title", t.title)
                    if (t.extension.isNotBlank()) put("extension", t.extension)
                    if (t.bitrate > 0L) put("bitrate", t.bitrate)
                    if (t.isLossless) put("isLossless", true)
                    if (t.durationMs > 0L) put("durationMs", t.durationMs)
                    if (t.playedOrder > 0) put("playedOrder", t.playedOrder)
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
                    voters = (0 until votersArr.length()).mapNotNull { v -> votersArr.optString(v).takeIf { it.isNotBlank() } },
                    artist = o.optString("artist"),
                    title = o.optString("title"),
                    extension = o.optString("extension"),
                    bitrate = o.optLong("bitrate", 0L),
                    isLossless = o.optBoolean("isLossless", false),
                    durationMs = o.optLong("durationMs", 0L),
                    playedOrder = o.optInt("playedOrder", 0)
                )
            }
            val currentPath = if (json.isNull("currentPath")) null else json.optString("currentPath").takeIf { it.isNotBlank() }
            return PartyState(
                tracks = tracks,
                currentPath = currentPath,
                hostNickname = json.optString("hostNickname"),
                currentPositionMs = json.optLong("currentPositionMs", 0L),
                currentDurationMs = json.optLong("currentDurationMs", 0L),
                isPlaying = json.optBoolean("isPlaying", false)
            )
        }
    }
}

/** Coordonnées réseau d'une session Blaze Party, extraites d'un QR/lien profond. */
data class PartyConnection(
    val host: String,
    val port: Int,
    val token: String,
    val alternateHosts: List<String> = emptyList()
) {
    fun hosts(): List<String> = (listOf(host) + alternateHosts)
        .map { it.trim() }
        .filter { it.isNotBlank() && it != "0.0.0.0" }
        .distinct()

    fun baseUrl(): String = "http://${formatHostForUrl(host)}:$port"
    fun baseUrls(): List<String> = hosts().map { "http://${formatHostForUrl(it)}:$port" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("host", host)
        put("port", port)
        put("token", token)
        put("hosts", JSONArray(hosts()))
    }

    private fun formatHostForUrl(value: String): String =
        if (value.contains(':') && !value.startsWith("[")) "[$value]" else value

    companion object {
        fun fromJson(json: JSONObject): PartyConnection? {
            val host = json.optString("host").takeIf { it.isNotBlank() } ?: return null
            val token = json.optString("token").takeIf { it.isNotBlank() } ?: return null
            val port = json.optInt("port", PartyProtocol.DEFAULT_PORT)
            val alternates = readHosts(json.optJSONArray("hosts"), json.optString("hostsCsv").takeIf { it.isNotBlank() })
                .filterNot { it == host }
            return PartyConnection(host, port, token, alternates)
        }

        private fun readHosts(array: JSONArray?, csv: String?): List<String> {
            val out = linkedSetOf<String>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotBlank() }?.let(out::add)
                }
            }
            csv?.split(',')?.forEach { part ->
                part.trim().takeIf { it.isNotBlank() }?.let(out::add)
            }
            return out.toList()
        }
    }
}

/**
 * Comble le "point d'extension V1" laissé dans AudioPlayerFragment/MainActivity : construit et
 * interprète le payload du QR/lien profond Blaze Party (adresse IP, port, jeton de session), pour
 * que l'invité puisse réellement se connecter au [PartyHostServer] de l'hôte plutôt que de se
 * contenter de positionner des indicateurs locaux.
 *
 * Formats supportés :
 *  - QR principal       : Android Intent URI forcée vers Blaze Player :
 *    "intent://join?host=<ip>&port=<port>&token=<token>#Intent;scheme=blazeparty;package=...;end"
 *  - lien profond direct: "blazeparty://join?host=<ip>&port=<port>&token=<token>"
 *  - compact historique : "bp://<ip>[:<port>]/<token>"
 *  - fallback navigateur: fiche Google Play de Blaze Player si l’application n’est pas installée.
 */
object PartyProtocol {
    const val DEFAULT_PORT = 57931
    private const val ANDROID_PACKAGE = "fr.retrospare.blazeplayer"
    private const val PLAY_STORE_FALLBACK =
        "https://play.google.com/store/apps/details?id=fr.retrospare.blazeplayer"

    /**
     * Payload QR principal : Android Intent URI ciblée sur le package Blaze Player.
     *
     * Le QR transporte aussi, si possible, plusieurs IP LAN candidates. Certains appareils Android
     * annoncent comme "réseau actif" une interface mobile/VPN alors que Blaze Party doit utiliser
     * l'adresse Wi‑Fi/ethernet locale. Le client essaie ces adresses dans l'ordre jusqu'à trouver
     * le serveur NanoHTTPD de l'hôte.
     */
    fun buildPayload(
        ip: String,
        token: String,
        port: Int = DEFAULT_PORT,
        alternateHosts: List<String> = emptyList()
    ): String = buildIntentPayload(ip, token, port, alternateHosts)

    fun buildIntentPayload(
        ip: String,
        token: String,
        port: Int = DEFAULT_PORT,
        alternateHosts: List<String> = emptyList()
    ): String {
        val hosts = normalizeHosts(ip, alternateHosts)
        val fallback = Uri.encode(PLAY_STORE_FALLBACK)
        return appendJoinParams(Uri.Builder().scheme("intent").authority("join"), ip, token, port, hosts)
            .encodedFragment(
                "Intent;" +
                    "scheme=blazeparty;" +
                    "package=$ANDROID_PACKAGE;" +
                    "action=android.intent.action.VIEW;" +
                    "category=android.intent.category.BROWSABLE;" +
                    "S.browser_fallback_url=$fallback;" +
                    "end"
            )
            .build()
            .toString()
    }

    fun buildLongPayload(
        ip: String,
        token: String,
        port: Int = DEFAULT_PORT,
        alternateHosts: List<String> = emptyList()
    ): String = appendJoinParams(Uri.Builder().scheme("blazeparty").authority("join"), ip, token, port, normalizeHosts(ip, alternateHosts))
        .build()
        .toString()

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
                "blazeparty", "intent" -> parseJoinQuery(uri)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun appendJoinParams(
        builder: Uri.Builder,
        ip: String,
        token: String,
        port: Int,
        hosts: List<String>
    ): Uri.Builder = builder
        .appendQueryParameter("host", ip)
        .appendQueryParameter("port", port.toString())
        .appendQueryParameter("token", token)
        .also { b ->
            if (hosts.size > 1) b.appendQueryParameter("hosts", hosts.joinToString(","))
        }

    private fun parseJoinQuery(uri: Uri): PartyConnection? {
        if (uri.host != "join") return null
        val host = uri.getQueryParameter("host")?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: DEFAULT_PORT
        val token = uri.getQueryParameter("token")?.takeIf { it.isNotBlank() } ?: return null
        val hosts = uri.getQueryParameter("hosts")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return PartyConnection(host, port, token, normalizeHosts(host, hosts).filterNot { it == host })
    }

    private fun normalizeHosts(primary: String, alternates: List<String>): List<String> =
        (listOf(primary) + alternates)
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "0.0.0.0" }
            .distinct()
}
