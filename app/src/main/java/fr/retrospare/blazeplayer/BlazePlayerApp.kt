package fr.retrospare.blazeplayer

import android.app.Application
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import fr.retrospare.blazeplayer.billing.RevenueCatManager
import fr.retrospare.blazeplayer.debug.CrashReporter
import fr.retrospare.blazeplayer.ui.HapticFeedbackManager
import javax.inject.Inject

@HiltAndroidApp
class BlazePlayerApp : Application() {

    /** Lazy est important : Hilt peut injecter l'Application avant onCreate(), mais le manager
     * ne doit accéder à Purchases.sharedInstance qu'après Purchases.configure(). */
    @Inject
    lateinit var revenueCatManager: Lazy<RevenueCatManager>

    override fun onCreate() {
        super.onCreate()
        HapticFeedbackManager.initialize(this)
        CrashReporter.install(this)
        if (configureRevenueCat()) {
            // Enregistre immédiatement l'écoute globale et lance une synchronisation silencieuse.
            // Aucun écran n'attend ce réseau : les droits DataStore persistés sont utilisés dès le
            // premier rendu, puis toute confirmation RevenueCat se propage automatiquement.
            revenueCatManager.get().start()
        }
    }

    /** Doit être appelé avant toute utilisation de Purchases.sharedInstance (paywall, manager…).
     * La clé provient de local.properties -> BuildConfig.REVENUECAT_API_KEY. */
    private fun configureRevenueCat(): Boolean {
        Purchases.logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.ERROR
        if (BuildConfig.REVENUECAT_API_KEY.isBlank()) {
            android.util.Log.e(
                "BlazePlayerApp",
                "REVENUECAT_API_KEY manquante : ajoute REVENUECAT_API_KEY=goog_xxx dans local.properties."
            )
            return false
        }
        return runCatching {
            Purchases.configure(
                PurchasesConfiguration.Builder(this, BuildConfig.REVENUECAT_API_KEY).build()
            )
            true
        }.onFailure {
            android.util.Log.e("BlazePlayerApp", "Échec de configuration RevenueCat", it)
        }.getOrDefault(false)
    }
}
