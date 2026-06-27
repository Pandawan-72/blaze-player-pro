package fr.retrospare.blazeplayer.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    private val gson = Gson()
    private val SHARES_KEY = stringPreferencesKey("network_shares")

    fun getShares(): Flow<List<NetworkShare>> = dataStore.data.map { prefs ->
        val json = prefs[SHARES_KEY] ?: return@map emptyList()
        try {
            val type = object : TypeToken<List<NetworkShare>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    suspend fun saveShare(share: NetworkShare) {
        dataStore.edit { prefs ->
            val current = getSharesList(prefs).toMutableList()
            val idx = current.indexOfFirst { it.id == share.id }
            if (idx >= 0) current[idx] = share else current.add(share)
            // Si ce chemin est défini par défaut, désactiver les autres
            if (share.isDefault) {
                val updated = current.map {
                    if (it.id != share.id) it.copy(isDefault = false) else it
                }
                prefs[SHARES_KEY] = gson.toJson(updated)
            } else {
                prefs[SHARES_KEY] = gson.toJson(current)
            }
        }
    }

    suspend fun deleteShare(id: String) {
        dataStore.edit { prefs ->
            val current = getSharesList(prefs).filter { it.id != id }
            prefs[SHARES_KEY] = gson.toJson(current)
        }
    }

    suspend fun getShareById(id: String): NetworkShare? {
        return getShares().first().find { it.id == id }
    }

    suspend fun getDefaultShare(): NetworkShare? {
        var result: NetworkShare? = null
        dataStore.edit { prefs ->
            result = getSharesList(prefs).firstOrNull { it.isDefault }
                ?: getSharesList(prefs).firstOrNull()
        }
        return result
    }

    fun createShare(
        name: String,
        host: String,
        port: Int? = null,
        shareName: String,
        username: String? = null,
        password: String? = null,
        type: ShareType = ShareType.SMB,
        isDefault: Boolean = false
    ) = NetworkShare(
        id = UUID.randomUUID().toString(),
        name = name,
        host = host,
        port = port,
        shareName = shareName,
        username = username,
        password = password,
        type = type,
        isDefault = isDefault
    )

    private fun getSharesList(prefs: Preferences): List<NetworkShare> {
        val json = prefs[SHARES_KEY] ?: return emptyList()
        return try {
            val type = object : TypeToken<List<NetworkShare>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }
}
