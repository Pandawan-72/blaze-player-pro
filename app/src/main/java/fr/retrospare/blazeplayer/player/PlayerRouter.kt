package fr.retrospare.blazeplayer.player

import android.content.Context
import android.content.Intent
import fr.retrospare.blazeplayer.MainActivity

/**
 * Point d'entrée unique pour ouvrir un média depuis n'importe quel écran de navigation
 * (BrowserFragment, SearchFragment, HomeFragment...).
 *
 * Important (Media3) : la lecture audio passe TOUJOURS par [MainActivity] -> l'onglet audio
 * (AudioPlayerFragment), qui est l'unique UI connectée à [BlazePlayerService] via un
 * [androidx.media3.session.MediaController]. Il ne doit pas exister de second chemin de lecture
 * audio qui instancierait son propre ExoPlayer en dehors de la session partagée : cela casserait
 * la notification, les contrôles lockscreen/Bluetooth et la lecture en arrière-plan pour ce chemin.
 */
object PlayerRouter {

    private val AUDIO_FORMATS = setOf(
        "mp3", "flac", "aac", "ogg", "opus", "wav", "m4a", "wma", "ape",
        "dts", "ac3", "mka", "wv", "aiff", "alac"
    )

    fun open(context: Context, path: String, name: String) {
        // Pour les URI content://, on extrait l'extension depuis le nom du fichier
        val ext = when {
            path.contains("content://") -> name.substringAfterLast('.', "").lowercase()
            else -> path.substringAfterLast('.', "").lowercase()
        }

        if (ext in AUDIO_FORMATS) {
            openAudio(context, path, name)
            return
        }

        // Tout le reste (y compris les formats nécessitant le décodeur FFmpeg étendu) passe par
        // PlayerActivity / ExoPlayer, qui gère la sélection de renderer en interne.
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("mediaPath", path)
            putExtra("mediaName", name)
        }
        context.startActivity(intent)
    }

    private fun openAudio(context: Context, path: String, name: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openAudioPath", path)
            putExtra("openAudioName", name)
        }
        context.startActivity(intent)
    }
}
