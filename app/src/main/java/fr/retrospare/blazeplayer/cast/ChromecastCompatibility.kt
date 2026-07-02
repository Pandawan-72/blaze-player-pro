package fr.retrospare.blazeplayer.cast

import fr.retrospare.blazeplayer.player.VideoTechnicalInfo

/**
 * Estimation (heuristique, pas d'API officielle exposant les capacités exactes d'un appareil
 * Cast) de la compatibilité entre une vidéo et le Chromecast actuellement connecté.
 *
 * Modèles 4K/HEVC/VP9 : "Chromecast Ultra" et "Chromecast with Google TV". Tous les autres
 * modèles nommés juste "Chromecast" (1ère, 2ème, 3ème génération) sont limités à 1080p, H.264
 * et VP8 uniquement — aucun d'entre eux ne décode HEVC/VP9/AV1/Dolby Vision, quelle que soit la
 * résolution.
 */
object ChromecastCompatibility {

    private val UNSUPPORTED_CODECS_ON_STANDARD_CHROMECAST = setOf(
        "H.265", "VP9", "AV1", "Dolby Vision", "H.266"
    )

    fun isLikely4KCapable(modelName: String?): Boolean {
        if (modelName == null) return true // inconnu -> on ne bloque pas inutilement
        return modelName.contains("Ultra", ignoreCase = true) ||
            modelName.contains("Google TV", ignoreCase = true)
    }

    /** Retourne un message d'incompatibilité si la vidéo est probablement injouable sur ce
     *  modèle de Chromecast, ou null si tout va bien (ou si on ne peut pas se prononcer). */
    fun incompatibilityReason(info: VideoTechnicalInfo, modelName: String?): String? {
        if (isLikely4KCapable(modelName)) return null
        return when {
            info.height > 1080 -> "résolution ${info.resolutionLabel} (4K), non supportée par ce modèle de Chromecast"
            info.videoCodec in UNSUPPORTED_CODECS_ON_STANDARD_CHROMECAST ->
                "codec vidéo ${info.videoCodec}, non supporté par ce modèle de Chromecast"
            else -> null
        }
    }
}
