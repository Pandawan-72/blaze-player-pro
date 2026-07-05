package fr.retrospare.blazeplayer.player

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
class PartyClient(private val connection: PartyConnection) {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun join(nickname: String, onResult: (Boolean) -> Unit) {
        val body = JSONObject().apply {
            put("token", connection.token)
            put("nickname", nickname)
        }
        request("POST", "/join", body) { success, _ -> mainHandler.post { onResult(success) } }
    }

    fun fetchState(onResult: (PartyState?) -> Unit) {
        request("GET", "/state?token=${connection.token}", null) { success, responseBody ->
            val state = if (success && responseBody != null) {
                try { PartyState.fromJson(JSONObject(responseBody)) } catch (_: Exception) { null }
            } else null
            mainHandler.post { onResult(state) }
        }
    }

    fun sendVote(path: String, nickname: String, add: Boolean, onResult: (Boolean) -> Unit) {
        val body = JSONObject().apply {
            put("token", connection.token)
            put("nickname", nickname)
            put("path", path)
            put("action", if (add) "add" else "remove")
        }
        request("POST", "/vote", body) { success, _ -> mainHandler.post { onResult(success) } }
    }

    private fun request(method: String, path: String, body: JSONObject?, callback: (Boolean, String?) -> Unit) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("${connection.baseUrl()}$path")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 3000
                    readTimeout = 3000
                }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }
                callback(code in 200..299, text)
            } catch (e: Exception) {
                android.util.Log.w("PartyClient", "Requête Blaze Party échouée ($method $path)", e)
                callback(false, null)
            } finally {
                conn?.disconnect()
            }
        }.start()
    }
}
