package fr.retrospare.blazeplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Point d’entrée dédié à l’icône "Blaze Audio".
 *  Copie la logique robuste de BlazeGalleryLauncherActivity : une tâche dédiée + un flag
 *  persistant permettent de forcer l’onglet Audio même si Blaze Player tourne déjà en arrière-plan. */
class BlazeAudioLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        BlazeStartupWarmup.requestAudioPriority(this)

        getSharedPreferences("launcher_requests", MODE_PRIVATE)
            .edit()
            .putBoolean("pendingOpenBlazeAudio", true)
            .putLong("pendingOpenBlazeAudioAt", System.currentTimeMillis())
            .apply()

        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra("openBlazeAudio", true)
            putExtra("requestedTab", 4)
        })
        finish()
    }
}
