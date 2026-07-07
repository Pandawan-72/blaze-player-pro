package fr.retrospare.blazeplayer.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client HTTP minimal côté invité pour une session Blaze Party, parlant à [PartyHostServer].
 *
 * Comble le "point d'extension V1" laissé dans MainActivity.handleBlazePartyInvite et
 * AudioPlayerFragment.showBlazePartyJoined : avant ce fichier, host/port/token issus du QR/lien
 * profond n'étaient jamais utilisés pour contacter réellement l'hôte, les votes de l'invité
 * restant coincés dans ses propres SharedPreferences locales.
 *
 * Toutes les requêtes s'exécutent sur un thread séparé ; les callbacks sont systématiquement
 * ramenés sur le thread principal pour pouvoir mettre à jour l'UI sans risque.
 */
class PartyClient(context: Context, private val connection: PartyConnection) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var preferredHost: String? = null

    fun join(nickname: String, onResult: (Boolean) -> Unit) {
        joinAndFetch(nickname) { state -> onResult(state != null) }
    }

    /**
     * Rejoint la session et récupère immédiatement le snapshot de l'hôte renvoyé par /join.
     * Avant, l'app faisait seulement join() puis attendait le polling /state suivant : sur certains
     * appareils, l'écran Blaze Audio s'ouvrait donc avec une file vide et pouvait sembler ne jamais
     * recevoir la liste partagée si la première requête /state échouait.
     */
    fun joinAndFetch(nickname: String, onResult: (PartyState?) -> Unit) {
        val body = JSONObject().apply {
            put("token", connection.token)
            put("nickname", nickname)
        }
        request("POST", "/join", body) { success, responseBody ->
            val state = parseWrappedState(success, responseBody)
            if (state != null) {
                mainHandler.post { onResult(state) }
            } else {
                // Secours très important : sur certaines piles Android/NanoHTTPD, le corps JSON du
                // POST peut ne pas être relu correctement après un scan QR externe. Le GET transporte
                // les mêmes données dans l'URL et permet de distinguer un vrai hôte injoignable d'un
                // simple problème de parsing du corps /join.
                val nick = encoded(nickname)
                request("GET", "/join?token=${encoded(connection.token)}&nickname=$nick", null) { getSuccess, getBody ->
                    mainHandler.post { onResult(parseWrappedState(getSuccess, getBody)) }
                }
            }
        }
    }

    fun fetchState(onResult: (PartyState?) -> Unit) {
        request("GET", "/state?token=${encoded(connection.token)}", null) { success, responseBody ->
            val state = if (success && responseBody != null) {
                try { PartyState.fromJson(JSONObject(responseBody)) } catch (_: Exception) { null }
            } else null
            mainHandler.post { onResult(state) }
        }
    }

    fun sendVote(path: String, nickname: String, add: Boolean, onResult: (Boolean) -> Unit) {
        sendVoteAndFetch(path, nickname, add) { state -> onResult(state != null) }
    }

    /** Envoie un vote et récupère directement l'état renvoyé par /vote. Cela évite un second GET
     *  /state immédiatement après le POST, donc une occasion de moins de tomber sur une socket Wi‑Fi
     *  recyclée alors que le vote a bien été enregistré côté hôte. */
    fun sendVoteAndFetch(path: String, nickname: String, add: Boolean, onResult: (PartyState?) -> Unit) {
        val body = JSONObject().apply {
            put("token", connection.token)
            put("nickname", nickname)
            put("path", path)
            put("action", if (add) "add" else "remove")
        }
        request("POST", "/vote", body) { success, responseBody ->
            val state = parseWrappedState(success, responseBody)
            if (state != null) {
                mainHandler.post { onResult(state) }
            } else {
                val urlPath = "/vote?token=${encoded(connection.token)}" +
                    "&nickname=${encoded(nickname)}" +
                    "&path=${encoded(path)}" +
                    "&action=${if (add) "add" else "remove"}"
                request("GET", urlPath, null) { getSuccess, getBody ->
                    mainHandler.post { onResult(parseWrappedState(getSuccess, getBody)) }
                }
            }
        }
    }

    private fun request(method: String, path: String, body: JSONObject?, callback: (Boolean, String?) -> Unit) {
        Thread {
            val bodyBytes = body?.toString()?.toByteArray(Charsets.UTF_8)
            var lastError: Exception? = null
            val candidates = candidateHosts()
            val routes = candidateNetworksForLan()
            if (candidates.isEmpty()) {
                callback(false, null)
                return@Thread
            }
            for (host in candidates) {
                val url = URL("http://${formatHostForUrl(host)}:${connection.port}$path")
                for (network in routes) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
                            requestMethod = method
                            connectTimeout = 4500
                            readTimeout = 4500
                            useCaches = false
                            instanceFollowRedirects = false
                            setRequestProperty("Accept", "application/json")
                            setRequestProperty("Connection", "close")
                        }
                        if (bodyBytes != null) {
                            conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            conn.setFixedLengthStreamingMode(bodyBytes.size)
                            conn.outputStream.use { it.write(bodyBytes) }
                        }
                        val code = conn.responseCode
                        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                        val text = stream?.bufferedReader()?.use { it.readText() }
                        if (code in 200..299) {
                            preferredHost = host
                            callback(true, text)
                        } else {
                            // Le serveur répond : ce n'est donc pas un problème d'adresse IP candidate
                            // mais un token/endpoint invalide. Inutile d'essayer les autres interfaces.
                            callback(false, text)
                        }
                        return@Thread
                    } catch (e: Exception) {
                        lastError = e
                        val routeName = network?.toString() ?: "default"
                        android.util.Log.w("PartyClient", "Requête Blaze Party échouée sur $host via $routeName ($method $path)", e)
                    } finally {
                        conn?.disconnect()
                    }
                }
            }
            if (lastError != null) {
                android.util.Log.w("PartyClient", "Aucune adresse Blaze Party candidate n'a répondu ($method $path)", lastError)
            }
            callback(false, null)
        }.start()
    }

    private fun parseWrappedState(success: Boolean, responseBody: String?): PartyState? {
        if (!success || responseBody == null) return null
        return try {
            val root = JSONObject(responseBody)
            root.optJSONObject("state")?.let { PartyState.fromJson(it) }
                ?: PartyState.fromJson(root)
        } catch (_: Exception) { null }
    }

    /**
     * Ouvre les connexions HTTP explicitement sur le réseau local (Wi‑Fi/ethernet) quand Android en
     * expose un. C'est crucial sur les téléphones qui gardent la data mobile comme réseau par défaut
     * parce que le Wi‑Fi n'a pas d'Internet : sans ce forçage, les appels vers 192.168.x.x partent sur
     * la mauvaise interface, le QR semble accepté, puis la file partagée ne charge jamais.
     */
    private fun candidateNetworksForLan(): List<Network?> {
        val cm = try { appContext.getSystemService(ConnectivityManager::class.java) } catch (_: Exception) { null }
            ?: return listOf(null)
        val ordered = linkedSetOf<Network?>()
        try {
            cm.allNetworks.forEach { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@forEach
                if ((caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    ordered.add(network)
                }
            }
            val active = cm.activeNetwork
            val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
            if (active != null && activeCaps != null &&
                !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                ordered.add(active)
            }
        } catch (_: Exception) { }
        ordered.add(null) // dernier recours : routage Android par défaut
        return ordered.toList()
    }

    private fun candidateHosts(): List<String> {
        val ordered = mutableListOf<String>()
        preferredHost?.takeIf { it.isNotBlank() }?.let(ordered::add)
        ordered += connection.hosts()
        return ordered.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun encoded(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun formatHostForUrl(value: String): String =
        if (value.contains(':') && !value.startsWith("[")) "[$value]" else value
}
