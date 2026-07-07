package fr.retrospare.blazeplayer.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object BlazePartyVoteManager {
    private const val PREFS = "blaze_party_votes"
    private const val KEY_VOTES = "votes_by_path"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_IS_HOST = "is_host"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_SESSION_PAYLOAD = "session_payload"
    private const val KEY_ACTIVE = "active"
    private const val KEY_CONNECTION = "connection"
    private const val KEY_HOST_TOKEN = "host_token"
    private const val KEY_PLAYED_ORDER = "played_order"

    fun getNickname(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NICKNAME, "")
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(fr.retrospare.blazeplayer.R.string.blaze_party_default_host)

    fun saveNickname(context: Context, nickname: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NICKNAME, nickname.trim().ifBlank { context.getString(fr.retrospare.blazeplayer.R.string.blaze_party_default_guest) })
            .apply()
    }

    fun isHost(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_IS_HOST, true)

    fun setHost(context: Context, host: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_HOST, host)
            // Côté hôte, KEY_CONNECTED représente la présence d'un vrai client distant,
            // pas simplement le fait d'avoir ouvert/hosté Blaze Party. Sans client,
            // le démarrage doit rester sur la file locale.
            .putBoolean(KEY_CONNECTED, !host)
            .putBoolean(KEY_ACTIVE, true)
            .apply()
    }

    fun isConnected(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONNECTED, false)

    /** Marque qu'un client distant réel est connecté, sans toucher au rôle host/guest — contrairement
     *  à [setHost] qui réinitialise volontairement KEY_CONNECTED selon le rôle. À utiliser côté hôte
     *  quand [PartyHostServer] reçoit effectivement un /join, seul moment où "connecté" est vrai. */
    fun markGuestConnected(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONNECTED, true)
            .putBoolean(KEY_ACTIVE, true)
            .apply()
    }

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)

    fun saveSessionPayload(context: Context, payload: String) {
        val isHost = isHost(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSION_PAYLOAD, payload)
            // En mode hôte, générer/afficher le QR ne veut pas dire qu'un client
            // est connecté. En mode invité, sauvegarder le payload signifie bien
            // que l'utilisateur rejoint une session distante.
            .putBoolean(KEY_CONNECTED, !isHost)
            .putBoolean(KEY_ACTIVE, true)
            .apply()
    }

    fun getSessionPayload(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SESSION_PAYLOAD, null)

    /** Coordonnées réseau (IP/port/jeton) de l'hôte à contacter, côté invité. Persistées à part
     *  du payload brut du QR pour rester utilisables quel que soit le point d'entrée (scan QR
     *  interne à l'app ou lien profond intercepté par MainActivity). */
    fun saveConnection(context: Context, connection: PartyConnection) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONNECTION, connection.toJson().toString())
            .apply()
    }

    fun getConnection(context: Context): PartyConnection? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONNECTION, null)
            ?: return null
        return try { PartyConnection.fromJson(org.json.JSONObject(raw)) } catch (_: Exception) { null }
    }

    /** Jeton de session généré par l'hôte au moment de la création de la party, utilisé pour
     *  (re)démarrer [PartyHostServer] avec le même jeton que celui déjà distribué via le QR. */
    fun saveHostToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST_TOKEN, token)
            .apply()
    }

    fun getHostToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST_TOKEN, null)


    /** Historique léger des morceaux déjà joués pendant la session Party. Il est persisté côté
     *  hôte pour que le service NanoHTTPD puisse exposer le même ordre que l'écran hôte : une
     *  piste jouée retombe tout en bas de la file partagée au lieu de remonter parmi les titres à
     *  zéro vote. */
    fun markPlayed(context: Context, path: String) {
        val clean = path.trim()
        if (clean.isBlank()) return
        val current = playedOrder(context).toMutableList()
        current.remove(clean)
        current.add(clean)
        writePlayedOrder(context, current)
    }

    fun clearPlayedOrder(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PLAYED_ORDER)
            .apply()
    }

    fun playedRank(context: Context, path: String): Int =
        playedOrder(context).indexOf(path).let { if (it >= 0) it + 1 else 0 }

    fun playedOrder(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PLAYED_ORDER, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { value -> value.isNotBlank() } }
        } catch (_: Exception) { emptyList() }
    }

    private fun writePlayedOrder(context: Context, paths: List<String>) {
        val arr = JSONArray()
        paths.distinct().forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLAYED_ORDER, arr.toString())
            .apply()
    }

    fun disconnect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONNECTED, false)
            .putBoolean(KEY_ACTIVE, false)
            .remove(KEY_CONNECTION)
            .remove(KEY_HOST_TOKEN)
            .apply()
    }

    fun votersFor(context: Context, path: String): List<String> = readAll(context)[path].orEmpty().distinct()

    fun voteCount(context: Context, path: String): Int = votersFor(context, path).size

    fun addVote(context: Context, path: String, nickname: String = getNickname(context)) {
        val all = readAll(context).toMutableMap()
        val list = all[path].orEmpty().toMutableList()
        val clean = nickname.trim().ifBlank { context.getString(fr.retrospare.blazeplayer.R.string.blaze_party_default_guest) }
        if (!list.any { it.equals(clean, ignoreCase = true) }) list.add(clean)
        all[path] = list
        writeAll(context, all)
    }

    fun hasVoted(context: Context, path: String, nickname: String = getNickname(context)): Boolean {
        val clean = nickname.trim().ifBlank { context.getString(fr.retrospare.blazeplayer.R.string.blaze_party_default_guest) }
        return votersFor(context, path).any { it.equals(clean, ignoreCase = true) }
    }

    fun removeVote(context: Context, path: String, nickname: String = getNickname(context)) {
        val clean = nickname.trim().ifBlank { context.getString(fr.retrospare.blazeplayer.R.string.blaze_party_default_guest) }
        val all = readAll(context).toMutableMap()
        val filtered = all[path].orEmpty().filterNot { it.equals(clean, ignoreCase = true) }
        if (filtered.isEmpty()) all.remove(path) else all[path] = filtered
        writeAll(context, all)
    }

    fun clearVotesForTrack(context: Context, path: String) {
        val all = readAll(context).toMutableMap()
        if (all.remove(path) != null) writeAll(context, all)
    }

    fun clearVotesForMissingTracks(context: Context, validPaths: Set<String>) {
        val filtered = readAll(context).filterKeys { it in validPaths }
        writeAll(context, filtered)
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_VOTES)
            .remove(KEY_PLAYED_ORDER)
            .apply()
    }

    private fun readAll(context: Context): Map<String, List<String>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOTES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key ->
                val arr = obj.optJSONArray(key) ?: JSONArray()
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }
        } catch (_: Exception) { emptyMap() }
    }

    private fun writeAll(context: Context, data: Map<String, List<String>>) {
        val obj = JSONObject()
        data.forEach { (path, voters) ->
            val arr = JSONArray()
            voters.distinct().forEach { arr.put(it) }
            obj.put(path, arr)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VOTES, obj.toString())
            .apply()
    }
}
