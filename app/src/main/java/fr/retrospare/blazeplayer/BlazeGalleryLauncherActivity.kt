package fr.retrospare.blazeplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Point d'entrée dédié à l'icône "Blaze Gallery".
 *
 * Un activity-alias ne retransmet pas toujours une intention distinguable à MainActivity
 * lorsque celle-ci existe déjà dans la tâche. Cette activité trampoline ajoute donc un extra
 * explicite et ramène MainActivity au premier plan, afin de forcer l'onglet Blaze Gallery même
 * si Blaze Player était déjà ouvert en arrière-plan sur l'onglet Local.
 */
class BlazeGalleryLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        BlazeStartupWarmup.requestGalleryPriority(this)
        // Marque la demande dans un stockage persistant : quand Blaze Player est déjà
        // en tâche de fond, certains launchers/ROM peuvent simplement ramener la tâche
        // existante au premier plan et perdre/ignorer l'extra de l'intent. MainActivity
        // et HomeFragment consomment ce flag au retour au premier plan.
        getSharedPreferences("launcher_requests", MODE_PRIVATE)
            .edit()
            .putBoolean("pendingOpenBlazeGallery", true)
            .putLong("pendingOpenBlazeGalleryAt", System.currentTimeMillis())
            .apply()

        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            // FLAG_ACTIVITY_NEW_TASK est requis : cette activité vit maintenant dans sa propre
            // tâche (taskAffinity dédiée dans le manifest, pour garantir que onCreate() ci-dessus
            // s'exécute à chaque appui sur l'icône même quand Blaze Player tourne déjà en arrière-
            // plan). Sans ce flag, Android ne rejoindrait pas correctement la tâche de MainActivity
            // depuis cette tâche distincte.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra("openBlazeGallery", true)
            putExtra("requestedTab", 3)
        })
        finish()
    }
}
