package fr.retrospare.blazeplayer.paywall

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import fr.retrospare.blazeplayer.MainActivity
import fr.retrospare.blazeplayer.data.repository.SubscriptionAccessState
import fr.retrospare.blazeplayer.data.repository.UserRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Niveau minimal requis par un écran premium. */
enum class AccessLevel { PRO, PRO_PLUS }

/** Redirection et surveillance communes des écrans secondaires premium. */
object AccessGateUi {
    fun redirectToPaywall(activity: Activity) {
        activity.startActivity(Intent(activity, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(TrialReminderScheduler.EXTRA_OPEN_PAYWALL, true)
        })
        activity.finish()
    }

    /** Contrôle synchrone à appeler avant de construire un écran premium. */
    fun enforceNow(
        activity: Activity,
        userRepository: UserRepository,
        level: AccessLevel
    ): Boolean {
        val allowed = runBlocking {
            when (level) {
                AccessLevel.PRO -> FeatureAccess.isPro(userRepository)
                AccessLevel.PRO_PLUS -> FeatureAccess.isProPlus(userRepository)
            }
        }
        if (!allowed) redirectToPaywall(activity)
        return allowed
    }

    /**
     * Maintient le verrou pendant que l'écran reste ouvert. Le délai coroutine est monotone : un
     * changement de date système ne prolonge pas l'écran. En arrière-plan, repeatOnLifecycle suspend
     * le contrôle ; il est réévalué immédiatement au prochain retour au premier plan.
     */
    fun monitor(
        activity: AppCompatActivity,
        userRepository: UserRepository,
        level: AccessLevel
    ) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive && !activity.isFinishing && !activity.isDestroyed) {
                    val state = userRepository.currentAccessState()
                    if (!state.allows(level)) {
                        redirectToPaywall(activity)
                        return@repeatOnLifecycle
                    }
                    if (!state.reliesOnTrial(level)) return@repeatOnLifecycle
                    val remaining = (state.trialEndMillis - state.evaluatedAtMillis).coerceAtLeast(1L)
                    delay(remaining)
                }
            }
        }
    }

    private fun SubscriptionAccessState.allows(level: AccessLevel): Boolean = when (level) {
        AccessLevel.PRO -> hasProAccess
        AccessLevel.PRO_PLUS -> hasProPlusAccess
    }

    private fun SubscriptionAccessState.reliesOnTrial(level: AccessLevel): Boolean {
        if (!isTrialActive) return false
        return when (level) {
            AccessLevel.PRO -> !isProPurchased && !isProPlusPurchased
            AccessLevel.PRO_PLUS -> !isProPlusPurchased
        }
    }
}
