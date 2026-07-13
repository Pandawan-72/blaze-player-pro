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
 * `Purchases.configure()` est appelé une seule fois dans [fr.retrospare.blazeplayer.BlazePlayerApp].
 * Cette classe se contente ensuite d'écouter les mises à jour de [CustomerInfo] et de les
 * répercuter sur [UserRepository], qui reste la seule source de vérité consultée par
 * [fr.retrospare.blazeplayer.paywall.FeatureAccess] dans le reste de l'application.
 */
@Singleton
class RevenueCatManager @Inject constructor(
    private val userRepository: UserRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Répercute automatiquement tout changement d'entitlement (achat, restauration,
        // remboursement détecté côté serveur RevenueCat) sur le stockage local.
        Purchases.sharedInstance.updatedCustomerInfoListener =
            UpdatedCustomerInfoListener { customerInfo -> scope.launch { applyCustomerInfo(customerInfo) } }
    }

    /** À appeler à l'ouverture du paywall pour être certain d'avoir l'état le plus récent
     * (par exemple après un achat effectué depuis un autre appareil). */
    suspend fun syncCustomerInfo() {
        val customerInfo = awaitCustomerInfo() ?: return
        applyCustomerInfo(customerInfo)
    }

    suspend fun fetchPricing(): LifetimePricing {
        val offering = awaitCurrentOffering()
        return LifetimePricing(
            proPriceFormatted = offering?.getPackage(RevenueCatIds.PACKAGE_PRO_LIFETIME)
                ?.product?.price?.formatted,
            proPlusPriceFormatted = offering?.getPackage(RevenueCatIds.PACKAGE_PRO_PLUS_LIFETIME)
                ?.product?.price?.formatted
        )
    }

    suspend fun purchasePro(activity: Activity): PurchaseOutcome =
        purchaseByPackageId(activity, RevenueCatIds.PACKAGE_PRO_LIFETIME)

    suspend fun purchaseProPlus(activity: Activity): PurchaseOutcome =
        purchaseByPackageId(activity, RevenueCatIds.PACKAGE_PRO_PLUS_LIFETIME)

    suspend fun restorePurchases(): PurchaseOutcome {
        val hadAnyEntitlementBefore = awaitCustomerInfo()?.entitlements?.active?.isNotEmpty() == true
        val restored = awaitRestore()
        return when (restored) {
            null -> PurchaseOutcome.Failure("La restauration des achats a échoué. Vérifie ta connexion.")
            else -> {
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
                applyCustomerInfo(result.customerInfo)
                PurchaseOutcome.Success
            }
            RawPurchaseResult.UserCancelled -> PurchaseOutcome.Cancelled
            is RawPurchaseResult.Errored -> PurchaseOutcome.Failure(result.message)
        }
    }

    private suspend fun applyCustomerInfo(customerInfo: CustomerInfo) {
        val proActive = customerInfo.entitlements[RevenueCatIds.ENTITLEMENT_PRO]?.isActive == true
        val proPlusActive = customerInfo.entitlements[RevenueCatIds.ENTITLEMENT_PRO_PLUS]?.isActive == true
        userRepository.setProStatus(proActive)
        userRepository.setProPlusStatus(proPlusActive)
    }

    private suspend fun awaitCurrentOffering(): Offering? =
        suspendCancellableCoroutine { continuation ->
            Purchases.sharedInstance.getOfferings(object : ReceiveOfferingsCallback {
                override fun onReceived(offerings: Offerings) {
                    continuation.resume(offerings.current ?: offerings[RevenueCatIds.OFFERING_DEFAULT])
                }

                override fun onError(error: PurchasesError) {
                    Log.w(TAG, "Impossible de récupérer les offres RevenueCat : ${error.message}")
                    continuation.resume(null)
                }
            })
        }

    private suspend fun awaitCustomerInfo(): CustomerInfo? =
        suspendCancellableCoroutine { continuation ->
            Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) {
                    continuation.resume(customerInfo)
                }

                override fun onError(error: PurchasesError) {
                    Log.w(TAG, "Impossible de récupérer le CustomerInfo RevenueCat : ${error.message}")
                    continuation.resume(null)
                }
            })
        }

    private suspend fun awaitRestore(): CustomerInfo? =
        suspendCancellableCoroutine { continuation ->
            Purchases.sharedInstance.restorePurchases(object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) {
                    continuation.resume(customerInfo)
                }

                override fun onError(error: PurchasesError) {
                    Log.w(TAG, "Échec de restauration RevenueCat : ${error.message}")
                    continuation.resume(null)
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
                continuation.resume(RawPurchaseResult.Completed(customerInfo))
            }

            override fun onError(error: PurchasesError, userCancelled: Boolean) {
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
