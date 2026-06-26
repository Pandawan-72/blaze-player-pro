package fr.retrospare.blazeplayer.player

import android.content.Context
import android.content.Intent

object PlayerRouter {

    private val AUDIO_FORMATS = setOf(
        "mp3", "flac", "aac", "ogg", "opus", "wav", "m4a", "wma", "ape",
        "dts", "ac3", "mka", "wv", "aiff", "alac"
    )

    private val VLC_FORMATS = setOf(
        "avi", "xvid", "divx", "rmvb", "rm", "vob", "flv", "3gp", "ogv", "wmv", "asf"
    )

    fun open(context: Context, path: String, name: String) {
        // Pour les URI content://, on extrait l'extension depuis le nom du fichier
        val ext = when {
            path.contains("content://") -> name.substringAfterLast('.', "").lowercase()
            else -> path.substringAfterLast('.', "").lowercase()
        }
        val intent = when {
            ext in AUDIO_FORMATS -> Intent(context, AudioPlayerActivity::class.java)
            ext in VLC_FORMATS -> Intent(context, VlcPlayerActivity::class.java)
            else -> Intent(context, PlayerActivity::class.java)
        }.apply {
            putExtra("mediaPath", path)
            putExtra("mediaName", name)
        }
        context.startActivity(intent)
    }
}
