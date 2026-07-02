package fr.retrospare.blazeplayer.ui

import android.widget.TextView
import androidx.core.content.ContextCompat
import fr.retrospare.blazeplayer.R

object BadgeStyle {
    fun applyTechnicalBadge(view: TextView?) {
        view ?: return
        view.setBackgroundResource(R.drawable.bg_badge_gray)
        view.setTextColor(ContextCompat.getColor(view.context, R.color.on_surface_variant))
    }

    /**
     * Badges conteneur : fond noir opaque commun pour ne plus avoir de pastilles
     * transparentes / bariolées, et couleur portée uniquement par le texte.
     */
    fun applyContainerBadge(view: TextView?, rawExtension: String?) {
        view ?: return
        val ext = rawExtension.orEmpty().trim().removePrefix(".").uppercase()
        if (ext.isEmpty()) return
        view.text = ext
        view.setBackgroundResource(R.drawable.bg_badge_black)
        view.setTextColor(ContextCompat.getColor(view.context, colorForContainer(ext)))
    }

    private fun colorForContainer(ext: String): Int = when (ext) {
        // Vidéo demandée
        "MKV" -> R.color.badge_mkv
        "MP4", "M4V" -> R.color.badge_mp4
        "AVI" -> R.color.badge_avi
        "MOV", "QT" -> R.color.badge_mov

        // Audio demandé
        "MP3" -> R.color.badge_mp3
        "FLAC" -> R.color.badge_flac

        // Autres formats vidéo pris en charge
        "WEBM" -> R.color.badge_webm
        "TS", "M2TS", "MTS" -> R.color.badge_ts
        "WMV", "ASF" -> R.color.badge_wmv
        "FLV" -> R.color.badge_flv
        "3GP", "3G2" -> R.color.badge_3gp

        // Autres formats audio pris en charge
        "AAC", "M4A" -> R.color.badge_aac
        "OGG", "OGA" -> R.color.badge_ogg
        "OPUS" -> R.color.badge_opus
        "WAV" -> R.color.badge_wav
        "WMA" -> R.color.badge_wma
        "APE" -> R.color.badge_ape
        "AC3", "EAC3" -> R.color.badge_ac3
        "DTS" -> R.color.badge_dts
        "MKA" -> R.color.badge_mka

        else -> R.color.badge_other
    }
}
