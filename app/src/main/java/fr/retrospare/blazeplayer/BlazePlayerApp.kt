package fr.retrospare.blazeplayer

import android.app.Application
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import dagger.hilt.android.HiltAndroidApp
import fr.retrospare.blazeplayer.debug.CrashReporter

@HiltAndroidApp
class BlazePlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        configureRevenueCat()
    }

    /** Doit être appelé avant toute utilisation de Purchases.sharedInstance (paywall, RevenueCatManager…).
     * La clé provient de local.properties -> BuildConfig.REVENUECAT_API_KEY (voir build.gradle.kts). */
    private fun configureRevenueCat() {
        Purchases.logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.ERROR
        if (BuildConfig.REVENUECAT_API_KEY.isBlank()) {
            android.util.Log.e(
                "BlazePlayerApp",
                "REVENUECAT_API_KEY manquante : ajoute REVENUECAT_API_KEY=goog_xxx dans local.properties."
            )
            return
        }
        Purchases.configure(
            PurchasesConfiguration.Builder(this, BuildConfig.REVENUECAT_API_KEY).build()
        )
    }
}
