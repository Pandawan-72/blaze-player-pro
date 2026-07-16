package fr.retrospare.blazeplayer.cast

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings

/**
 * Ouvre le panneau de partage d'écran fourni par Android / le constructeur.
 *
 * Il n'existe pas d'API publique permettant à une application tierce de démarrer silencieusement
 * le mirroring Google Cast ou d'en imposer la route audio. Le téléphone garde donc la main sur le
 * dialogue de consentement et sur la séparation audio/vidéo lorsqu'elle est prise en charge.
 */
object SystemScreenMirrorLauncher {
    enum class Result { OPENED, UNAVAILABLE }

    fun open(activity: Activity): Result {
        val candidates = listOf(
            Intent(Settings.ACTION_CAST_SETTINGS),
            Intent("android.settings.CAST_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (intent in candidates) {
            try {
                if (intent.resolveActivity(activity.packageManager) != null) {
                    activity.startActivity(intent)
                    return Result.OPENED
                }
            } catch (_: ActivityNotFoundException) {
                // Essaie le panneau suivant.
            } catch (_: SecurityException) {
                // Certains constructeurs exposent l'action mais refusent son lancement direct.
            }
        }
        return Result.UNAVAILABLE
    }
}
