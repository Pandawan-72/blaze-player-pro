package fr.retrospare.blazeplayer.billing

import android.app.Activity
import android.util.Log
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offering
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.ReceiveOfferingsCallback
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.models.StoreTransaction
import fr.retrospare.blazeplayer.BuildConfig
import fr.retrospare.blazeplayer.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Tarifs formatés (localisés par Google Play) des deux offres, lus depuis l'offering courant. */
data class LifetimePricing(
    val proPriceFormatted: String?,
    val proPlusPriceFormatted: String?
)

/** Résultat d'une tentative d'achat ou de restauration. */
sealed class PurchaseOutcome {
    object Success : PurchaseOutcome()
    object Cancelled : PurchaseOutcome()
    object NothingToRestore : PurchaseOutcome()
    data class Failure(val message: String) : PurchaseOutcome()
}

/** Résultat interne d'un appel de purchase(), avant interprétation en [PurchaseOutcome]. */
private sealed class RawPurchaseResult {
    data class Completed(val customerInfo: CustomerInfo) : RawPurchaseResult()
    object UserCancelled : RawPurchaseResult()
    data class Errored(val message: String) : RawPurchaseResult()
}

/** Point d'accès unique au SDK RevenueCat.
 *
 * Le stockage local de [UserRepository] est lu immédiatement au démarrage, ce qui conserve les
 * droits achetés sans écran de chargement lorsque l'application est relancée ou mise à jour.
 * En parallèle, [start] installe l'écouteur RevenueCat puis resynchronise silencieusement le
 * CustomerInfo. Tout changement est écrit atomiquement dans DataStore et donc propagé à l'UI.
 */
@Singleton
class RevenueCatManager @Inject constructor(
    private val userRepository: UserRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    /** Démarre une seule fois l'écoute globale et la synchronisation silencieuse de lancement.
     * Cette méthode doit être appelée après Purchases.configure(), depuis BlazePlayerApp. */
    fun start() {
        if (BuildConfig.REVENUECAT_API_KEY.isBlank()) {
            Log.e(TAG, "RevenueCat non démarré : REVENUECAT_API_KEY est absente.")
            return
        }
        if (started) return
        synchronized(this) {
            if (started) return
            val purchases = runCatching { Purchases.sharedInstance }
                .onFailure { Log.e(TAG, "RevenueCat n'est pas encore configuré.", it) }
                .getOrNull() ?: return

            purchases.updatedCustomerInfoListener = UpdatedCustomerInfoListener { customerInfo ->
                scope.launch { applyCustomerInfo(customerInfo) }
            }
            started = true
        }

        // Ne bloque jamais le premier frame : le cache local ouvre immédiatement les fonctions
        // déjà achetées, puis RevenueCat confirme/corrige l'état en arrière-plan.
        scope.launch { syncCustomerInfo() }
    }

    private fun ensureStarted(): Boolean {
        if (!started) start()
        return started
    }

    /** Synchronise silencieusement l'état RevenueCat avec le cache local.
     * getCustomerInfo() utilise normalement le cache SDK et le rafraîchit s'il est ancien. */
    suspend fun syncCustomerInfo(): Boolean {
        if (!ensureStarted()) return false
        val customerInfo = awaitCustomerInfo() ?: return false
        applyCustomerInfo(customerInfo)
        return true
    }

    suspend fun fetchPricing(): LifetimePricing {
        if (!ensureStarted()) return LifetimePricing(null, null)
        val offering = awaitCurrentOffering()
        return LifetimePricing(
            proPriceFormatted = offering?.getPackage(RevenueCatIds.PACKAGE_PRO_LIFETIME)
                ?.product?.price?.formatted,
            proPlusPriceFormatted = offering?.getPackage(RevenueCatIds.PACKAGE_PRO_PLUS_LIFETIME)
                ?.product?.price?.formatted
        )
    }

    suspend fun purchasePro(activity: Activity): PurchaseOutcome {
        if (!ensureStarted()) return PurchaseOutcome.Failure("RevenueCat n'est pas configuré.")
        return purchaseByPackageId(activity, RevenueCatIds.PACKAGE_PRO_LIFETIME)
    }

    suspend fun purchaseProPlus(activity: Activity): PurchaseOutcome {
        if (!ensureStarted()) return PurchaseOutcome.Failure("RevenueCat n'est pas configuré.")
        return purchaseByPackageId(activity, RevenueCatIds.PACKAGE_PRO_PLUS_LIFETIME)
    }

    suspend fun restorePurchases(): PurchaseOutcome {
        if (!ensureStarted()) return PurchaseOutcome.Failure("RevenueCat n'est pas configuré.")
        val hadAnyEntitlementBefore = awaitCustomerInfo()?.entitlements?.active?.isNotEmpty() == true
        val restored = awaitRestore()
        return when (restored) {
            null -> PurchaseOutcome.Failure("La restauration des achats a échoué. Vérifie ta connexion.")
            else -> {
                // L'écriture DataStore est terminée avant le retour Success : le contenu est donc
                // déjà débloqué lorsque le ViewModel reçoit le résultat.
                applyCustomerInfo(restored)
                val hasAnyEntitlementAfter = restored.entitlements.active.isNotEmpty()
                if (hasAnyEntitlementAfter || hadAnyEntitlementBefore) {
                    PurchaseOutcome.Success
                } else {
                    PurchaseOutcome.NothingToRestore
                }
            }
        }
    }

    private suspend fun purchaseByPackageId(activity: Activity, packageId: String): PurchaseOutcome {
        val offering = awaitCurrentOffering()
            ?: return PurchaseOutcome.Failure("Offres indisponibles pour le moment.")
        val packageToBuy = offering.getPackage(packageId)
            ?: return PurchaseOutcome.Failure("Produit introuvable dans l'offre RevenueCat ($packageId).")

        return when (val result = awaitPurchase(activity, packageToBuy)) {
            is RawPurchaseResult.Completed -> {
                // Le CustomerInfo retourné par l'achat est la réponse la plus récente. On le
                // persiste avant de signaler le succès afin que Pro/Pro+ soit instantané partout.
                applyCustomerInfo(result.customerInfo)
                PurchaseOutcome.Success
            }
            RawPurchaseResult.UserCancelled -> PurchaseOutcome.Cancelled
            is RawPurchaseResult.Errored -> PurchaseOutcome.Failure(result.message)
        }
    }

    private suspend fun applyCustomerInfo(customerInfo: CustomerInfo) {
        val proPlusActive =
            customerInfo.entitlements[RevenueCatIds.ENTITLEMENT_PRO_PLUS]?.isActive == true
        // Pro+ inclut toujours Pro, même si le dashboard RevenueCat n'a pas encore rattaché le
        // produit Pro+ aux deux entitlements. Cela évite un état transitoire incohérent.
        val proActive = proPlusActive ||
            customerInfo.entitlements[RevenueCatIds.ENTITLEMENT_PRO]?.isActive == true
        userRepository.setPurchasedAccess(
            isPro = proActive,
            isProPlus = proPlusActive
        )
    }

    private suspend fun awaitCurrentOffering(): Offering? =
        suspendCancellableCoroutine { continuation ->
            Purchases.sharedInstance.getOfferings(object : ReceiveOfferingsCallback {
                override fun onReceived(offerings: Offerings) {
                    if (continuation.isActive) {
                        continuation.resume(offerings.current ?: offerings[RevenueCatIds.OFFERING_DEFAULT])
                    }
                }

                override fun onError(error: PurchasesError) {
                    Log.w(TAG, "Impossible de récupérer les offres RevenueCat : ${error.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }

    private suspend fun awaitCustomerInfo(): CustomerInfo? =
        suspendCancellableCoroutine { continuation ->
            Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) {
                    if (continuation.isActive) continuation.resume(customerInfo)
                }

                override fun onError(error: PurchasesError) {
                    Log.w(TAG, "Impossible de récupérer le CustomerInfo RevenueCat : ${error.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }

    private suspend fun awaitRestore(): CustomerInfo? =
        suspendCancellableCoroutine { continuation ->
            Purchases.sharedInstance.restorePurchases(object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) {
                    if (continuation.isActive) continuation.resume(customerInfo)
                }

                override fun onError(error: PurchasesError) {
                    Log.w(TAG, "Échec de restauration RevenueCat : ${error.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }

    private suspend fun awaitPurchase(
        activity: Activity,
        packageToBuy: com.revenuecat.purchases.Package
    ): RawPurchaseResult = suspendCancellableCoroutine { continuation ->
        val params = PurchaseParams.Builder(activity, packageToBuy).build()
        Purchases.sharedInstance.purchase(params, object : PurchaseCallback {
            override fun onCompleted(storeTransaction: StoreTransaction, customerInfo: CustomerInfo) {
                if (continuation.isActive) {
                    continuation.resume(RawPurchaseResult.Completed(customerInfo))
                }
            }

            override fun onError(error: PurchasesError, userCancelled: Boolean) {
                if (!continuation.isActive) return
                if (userCancelled) {
                    continuation.resume(RawPurchaseResult.UserCancelled)
                } else {
                    continuation.resume(RawPurchaseResult.Errored(error.message))
                }
            }
        })
    }

    companion object {
        private const val TAG = "RevenueCatManager"
    }
}
