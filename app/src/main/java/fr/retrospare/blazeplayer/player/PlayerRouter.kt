package fr.retrospare.blazeplayer.player

import android.content.Context
import android.content.Intent

object PlayerRouter {

    // Formats non supportés nativement par ExoPlayer
    private val VLC_FORMATS = setOf(
        "avi", "xvid", "divx", "rmvb", "rm", "vob", "iso",
        "flv", "3gp", "ogv", "wmv", "asf"
    )

    // Codecs audio nécessitant VLC
    private val VLC_AUDIO = setOf(
        "flac", "ape", "wv", "mka", "dts", "ac3", "wma", "ogg", "opus"
    )

    fun open(context: Context, path: String, name: String) {
        val ext = path.substringAfterLast('.', "").lowercase()
        val useVlc = ext in VLC_FORMATS || ext in VLC_AUDIO

        val intent = if (useVlc) {
            Intent(context, VlcPlayerActivity::class.java)
        } else {
            Intent(context, PlayerActivity::class.java)
        }.apply {
            putExtra("mediaPath", path)
            putExtra("mediaName", name)
        }
        context.startActivity(intent)
    }
}
