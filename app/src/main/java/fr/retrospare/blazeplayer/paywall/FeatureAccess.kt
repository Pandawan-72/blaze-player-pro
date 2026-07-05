package fr.retrospare.blazeplayer.paywall

import fr.retrospare.blazeplayer.data.repository.UserRepository
import kotlinx.coroutines.flow.first

/**
 * Point unique de verrouillage freemium.
 *
 * Règles produit :
 * - Gratuit : onglet Local + Cast vidéo locale.
 * - Pro : onglet Réseau (SMB) + onglet Blaze Gallery.
 * - Pro+ : onglet Blaze Audio / mini player, inclut automatiquement Pro.
 *
 * DEBUG_UNLOCK_ALL reste à true pour les builds de développement actuels afin de ne pas bloquer
 * les tests. Passer à false en production quand l'achat sera branché.
 */
object FeatureAccess {
    const val DEBUG_UNLOCK_ALL: Boolean = true

    suspend fun isPro(userRepository: UserRepository): Boolean =
        DEBUG_UNLOCK_ALL || userRepository.isProFlow.first() || userRepository.isProPlusFlow.first()

    suspend fun isProPlus(userRepository: UserRepository): Boolean =
        DEBUG_UNLOCK_ALL || userRepository.isProPlusFlow.first()
}
