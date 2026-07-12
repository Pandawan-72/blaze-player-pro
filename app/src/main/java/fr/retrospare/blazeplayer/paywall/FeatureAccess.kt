package fr.retrospare.blazeplayer.paywall

import fr.retrospare.blazeplayer.data.repository.UserRepository

/** Point unique de verrouillage freemium.
 *
 * Matrice produit :
 * - Free : Blaze Video local et Cast de vidéos locales.
 * - Pro : Free + réseau local SMB/UPnP + Blaze Gallery.
 * - Pro+ : Pro + lecteur premium Blaze Audio.
 * - Essai : droits Pro+ complets pendant exactement quinze jours.
 */
object FeatureAccess {
    suspend fun isPro(userRepository: UserRepository): Boolean =
        userRepository.ensureTrialStarted().hasProAccess

    suspend fun isProPlus(userRepository: UserRepository): Boolean =
        userRepository.ensureTrialStarted().hasProPlusAccess

    fun isNetworkMediaPath(path: String): Boolean =
        path.startsWith("smb://", ignoreCase = true) ||
            path.startsWith("ftp://", ignoreCase = true) ||
            path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true)
}
