package fr.retrospare.blazeplayer

import android.app.Application
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BlazePlayerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        configureRevenueCat()
    }

    private fun configureRevenueCat() {
        Purchases.logLevel = LogLevel.DEBUG
        Purchases.configure(
            PurchasesConfiguration.Builder(
                context = this,
                apiKey = "REPLACE_WITH_YOUR_REVENUECAT_API_KEY"
            ).build()
        )
    }
}
