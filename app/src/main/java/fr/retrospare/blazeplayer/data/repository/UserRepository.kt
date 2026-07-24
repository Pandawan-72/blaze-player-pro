package fr.retrospare.blazeplayer.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/** État calculé des droits de l'utilisateur.
 *
 * Un essai actif accorde temporairement les mêmes droits qu'un achat Pro+ et inclut donc Pro.
 * Après sa date de fin, seuls les achats persistés continuent à ouvrir les fonctionnalités.
 */
data class SubscriptionAccessState(
    val isProPurchased: Boolean,
    val isProPlusPurchased: Boolean,
    val trialStartMillis: Long,
    val trialEndMillis: Long,
    val evaluatedAtMillis: Long,
    val isTrialActive: Boolean,
    val trialDaysLeft: Int,
    val hasProAccess: Boolean,
    val hasProPlusAccess: Boolean,
    val trialReminderSent: Boolean
)

@Singleton
class UserRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    companion object {
        private val KEY_MINI_PLAYER = booleanPreferencesKey("mini_player_enabled")
        private val KEY_IS_PRO = booleanPreferencesKey("is_pro")
        private val KEY_IS_PRO_PLUS = booleanPreferencesKey("is_pro_plus")
        private val KEY_PLAYER_THEME = stringPreferencesKey("player_theme")
        private val KEY_TRIAL_START_MILLIS = longPreferencesKey("pro_plus_trial_start_millis")
        private val KEY_TRIAL_LAST_SEEN_MILLIS = longPreferencesKey("pro_plus_trial_last_seen_millis")
        private val KEY_TRIAL_REMINDER_SENT = booleanPreferencesKey("pro_plus_trial_reminder_sent")

        const val TRIAL_DURATION_DAYS: Int = 15
        const val TRIAL_REMINDER_DAYS_BEFORE_END: Int = 2
        const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L
        const val TRIAL_DURATION_MILLIS: Long = TRIAL_DURATION_DAYS * DAY_MILLIS
        const val TRIAL_REMINDER_BEFORE_END_MILLIS: Long =
            TRIAL_REMINDER_DAYS_BEFORE_END * DAY_MILLIS
        private const val CLOCK_CHECKPOINT_INTERVAL_MILLIS: Long = 60_000L
    }

    val miniPlayerEnabledFlow: Flow<Boolean> = dataStore.data.map { it[KEY_MINI_PLAYER] ?: false }

    suspend fun setMiniPlayerEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_MINI_PLAYER] = enabled }
    }

    /** Droits effectifs, essai inclus. */
    val accessStateFlow: Flow<SubscriptionAccessState> = dataStore.data.map { preferences ->
        buildAccessState(preferences, System.currentTimeMillis())
    }

    val isProFlow: Flow<Boolean> = accessStateFlow.map { it.hasProAccess }
    val isProPlusFlow: Flow<Boolean> = accessStateFlow.map { it.hasProPlusAccess }

    /** Valeurs d'achat brutes, sans l'essai. Elles sont destinées au futur raccordement
     * RevenueCat / Google Play Billing. */
    val isProPurchasedFlow: Flow<Boolean> = dataStore.data.map { it[KEY_IS_PRO] ?: false }
    val isProPlusPurchasedFlow: Flow<Boolean> = dataStore.data.map { it[KEY_IS_PRO_PLUS] ?: false }

    /** Démarre l'essai une seule fois, au premier lancement suivant l'installation.
     * La clé n'est jamais supprimée à l'expiration : une réouverture de l'application ne peut donc
     * pas réinitialiser les quinze jours. */
    suspend fun ensureTrialStarted(nowMillis: Long = System.currentTimeMillis()): SubscriptionAccessState {
        var snapshot = dataStore.data.first()
        val startMissing = (snapshot[KEY_TRIAL_START_MILLIS] ?: 0L) <= 0L
        val lastSeen = snapshot[KEY_TRIAL_LAST_SEEN_MILLIS] ?: 0L
        val checkpointDue = nowMillis - lastSeen >= CLOCK_CHECKPOINT_INTERVAL_MILLIS
        if (startMissing || checkpointDue) {
            dataStore.edit { preferences ->
                if ((preferences[KEY_TRIAL_START_MILLIS] ?: 0L) <= 0L) {
                    preferences[KEY_TRIAL_START_MILLIS] = nowMillis
                    preferences[KEY_TRIAL_REMINDER_SENT] = false
                }
                val persistedLastSeen = preferences[KEY_TRIAL_LAST_SEEN_MILLIS] ?: 0L
                if (nowMillis > persistedLastSeen) preferences[KEY_TRIAL_LAST_SEEN_MILLIS] = nowMillis
            }
            snapshot = dataStore.data.first()
        }
        return buildAccessState(snapshot, nowMillis)
    }

    /** Renvoie l'état courant et mémorise périodiquement l'horloge la plus avancée observée.
     * Le calcul reste précis à la milliseconde ; seul le checkpoint anti-retour d'horloge est limité
     * à une écriture par minute afin de ne pas bloquer l'interface lors de chaque contrôle d'accès. */
    suspend fun currentAccessState(nowMillis: Long = System.currentTimeMillis()): SubscriptionAccessState {
        var snapshot = dataStore.data.first()
        val lastSeen = snapshot[KEY_TRIAL_LAST_SEEN_MILLIS] ?: 0L
        val effectiveNow = maxOf(nowMillis, lastSeen)
        if (effectiveNow - lastSeen >= CLOCK_CHECKPOINT_INTERVAL_MILLIS) {
            dataStore.edit { preferences ->
                val persistedLastSeen = preferences[KEY_TRIAL_LAST_SEEN_MILLIS] ?: 0L
                if (effectiveNow > persistedLastSeen) preferences[KEY_TRIAL_LAST_SEEN_MILLIS] = effectiveNow
            }
            snapshot = dataStore.data.first()
        }
        return buildAccessState(snapshot, effectiveNow)
    }

    suspend fun markTrialReminderSent() {
        dataStore.edit { it[KEY_TRIAL_REMINDER_SENT] = true }
    }

    /** Persiste les deux achats dans une seule transaction DataStore.
     * Une seule émission est ainsi envoyée aux écrans : aucun instant intermédiaire ne peut
     * afficher Pro+ actif avec Pro encore verrouillé (ou l'inverse). */
    suspend fun setPurchasedAccess(isPro: Boolean, isProPlus: Boolean) {
        val normalizedProPlus = isProPlus
        val normalizedPro = isPro || normalizedProPlus
        dataStore.edit { preferences ->
            preferences[KEY_IS_PRO] = normalizedPro
            preferences[KEY_IS_PRO_PLUS] = normalizedProPlus
        }
    }

    suspend fun setProStatus(isPro: Boolean) {
        dataStore.edit { preferences ->
            val proPlus = preferences[KEY_IS_PRO_PLUS] ?: false
            preferences[KEY_IS_PRO] = isPro || proPlus
        }
    }

    suspend fun setProPlusStatus(isProPlus: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_IS_PRO_PLUS] = isProPlus
            if (isProPlus) preferences[KEY_IS_PRO] = true
        }
    }

    suspend fun setPlayerTheme(theme: String) {
        dataStore.edit { it[KEY_PLAYER_THEME] = theme }
    }

    private fun buildAccessState(preferences: Preferences, nowMillis: Long): SubscriptionAccessState {
        val proPurchased = preferences[KEY_IS_PRO] ?: false
        val proPlusPurchased = preferences[KEY_IS_PRO_PLUS] ?: false
        val trialStart = preferences[KEY_TRIAL_START_MILLIS] ?: 0L
        val trialEnd = if (trialStart > 0L) trialStart + TRIAL_DURATION_MILLIS else 0L
        val lastSeen = preferences[KEY_TRIAL_LAST_SEEN_MILLIS] ?: 0L
        val effectiveNow = maxOf(nowMillis, lastSeen)
        // Dès qu'un achat permanent est actif, l'essai Pro+ local s'arrête.
        // Un achat Pro ne doit jamais continuer à bénéficier temporairement des droits Pro+
        // ni afficher le badge Pro+ simplement parce que l'essai avait été démarré auparavant.
        val trialActive = !proPurchased && !proPlusPurchased &&
            trialStart > 0L && effectiveNow < trialEnd
        val remainingMillis = (trialEnd - effectiveNow).coerceAtLeast(0L)
        val daysLeft = if (trialActive) {
            ceil(remainingMillis.toDouble() / DAY_MILLIS.toDouble()).toInt().coerceAtLeast(1)
        } else {
            0
        }
        val proPlusAccess = proPlusPurchased || trialActive
        val proAccess = proPurchased || proPlusAccess
        return SubscriptionAccessState(
            isProPurchased = proPurchased,
            isProPlusPurchased = proPlusPurchased,
            trialStartMillis = trialStart,
            trialEndMillis = trialEnd,
            evaluatedAtMillis = effectiveNow,
            isTrialActive = trialActive,
            trialDaysLeft = daysLeft,
            hasProAccess = proAccess,
            hasProPlusAccess = proPlusAccess,
            trialReminderSent = preferences[KEY_TRIAL_REMINDER_SENT] ?: false
        )
    }
}
