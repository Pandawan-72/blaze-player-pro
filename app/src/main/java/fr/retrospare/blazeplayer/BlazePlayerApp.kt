package fr.retrospare.blazeplayer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import fr.retrospare.blazeplayer.debug.CrashReporter

@HiltAndroidApp
class BlazePlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
