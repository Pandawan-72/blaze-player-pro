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

    fun disconnect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONNECTED, false)
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
