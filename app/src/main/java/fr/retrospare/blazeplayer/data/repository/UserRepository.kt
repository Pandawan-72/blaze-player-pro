package fr.retrospare.blazeplayer.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {

    companion object {
        private val KEY_IS_PRO = booleanPreferencesKey("is_pro")
        private val KEY_SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        private val KEY_PLAYER_THEME = stringPreferencesKey("player_theme")
    }

    val isProFlow: Flow<Boolean> = dataStore.data.map { true } // TODO: RevenueCat en production

    val subtitleLanguageFlow: Flow<String> = dataStore.data.map {
        it[KEY_SUBTITLE_LANGUAGE] ?: "auto"
    }

    suspend fun setProStatus(isPro: Boolean) {
        dataStore.edit { it[KEY_IS_PRO] = isPro }
    }

    suspend fun setSubtitleLanguage(language: String) {
        dataStore.edit { it[KEY_SUBTITLE_LANGUAGE] = language }
    }

    suspend fun setPlayerTheme(theme: String) {
        dataStore.edit { it[KEY_PLAYER_THEME] = theme }
    }
}
