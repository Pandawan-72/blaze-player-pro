package fr.retrospare.blazeplayer.paywall

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.retrospare.blazeplayer.billing.PurchaseOutcome
import fr.retrospare.blazeplayer.billing.RevenueCatManager
import fr.retrospare.blazeplayer.data.repository.UserRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val revenueCatManager: RevenueCatManager
) : ViewModel() {

    sealed class PaywallState {
        object Loading : PaywallState()
        data class Ready(
            val isPro: Boolean,
            val isProPlus: Boolean,
            val isProPurchased: Boolean,
            val isProPlusPurchased: Boolean,
            val isTrialActive: Boolean,
            val trialDaysLeft: Int,
            val proPriceFormatted: String?,
            val proPlusPriceFormatted: String?,
            val isPurchaseInProgress: Boolean
        ) : PaywallState()
        data class Error(val message: String) : PaywallState()
    }

    sealed class PaywallEvent {
        object ProPurchaseSuccess : PaywallEvent()
        object ProPlusPurchaseSuccess : PaywallEvent()
        object PurchaseCancelled : PaywallEvent()
        data class PurchaseError(val message: String) : PaywallEvent()
        object RestoreSuccess : PaywallEvent()
        object RestoreNothingFound : PaywallEvent()
        data class RestoreError(val message: String) : PaywallEvent()
    }

    private val _state = MutableStateFlow<PaywallState>(PaywallState.Loading)
    val state: StateFlow<PaywallState> = _state.asStateFlow()

    private val _events = Channel<PaywallEvent>(Channel.BUFFERED)
    val events: Flow<PaywallEvent> = _events.receiveAsFlow()

    private var cachedProPrice: String? = null
    private var cachedProPlusPrice: String? = null

    fun checkProStatus() {
        viewModelScope.launch {
            revenueCatManager.syncCustomerInfo()
            if (cachedProPrice == null && cachedProPlusPrice == null) {
                val pricing = revenueCatManager.fetchPricing()
                cachedProPrice = pricing.proPriceFormatted
                cachedProPlusPrice = pricing.proPlusPriceFormatted
            }
            refreshState(purchaseInProgress = false)
        }
    }

    fun purchasePro(activity: Activity) = launchPurchase {
        when (val outcome = revenueCatManager.purchasePro(activity)) {
            PurchaseOutcome.Success -> _events.send(PaywallEvent.ProPurchaseSuccess)
            PurchaseOutcome.Cancelled -> _events.send(PaywallEvent.PurchaseCancelled)
            PurchaseOutcome.NothingToRestore -> Unit
            is PurchaseOutcome.Failure -> _events.send(PaywallEvent.PurchaseError(outcome.message))
        }
    }

    fun purchaseProPlus(activity: Activity) = launchPurchase {
        when (val outcome = revenueCatManager.purchaseProPlus(activity)) {
            PurchaseOutcome.Success -> _events.send(PaywallEvent.ProPlusPurchaseSuccess)
            PurchaseOutcome.Cancelled -> _events.send(PaywallEvent.PurchaseCancelled)
            PurchaseOutcome.NothingToRestore -> Unit
            is PurchaseOutcome.Failure -> _events.send(PaywallEvent.PurchaseError(outcome.message))
        }
    }

    fun restorePurchases() = launchPurchase {
        when (val outcome = revenueCatManager.restorePurchases()) {
            PurchaseOutcome.Success -> _events.send(PaywallEvent.RestoreSuccess)
            PurchaseOutcome.NothingToRestore -> _events.send(PaywallEvent.RestoreNothingFound)
            PurchaseOutcome.Cancelled -> Unit
            is PurchaseOutcome.Failure -> _events.send(PaywallEvent.RestoreError(outcome.message))
        }
    }

    private fun launchPurchase(block: suspend () -> Unit) {
        viewModelScope.launch {
            refreshState(purchaseInProgress = true)
            runCatching { block() }
            refreshState(purchaseInProgress = false)
        }
    }

    private suspend fun refreshState(purchaseInProgress: Boolean) {
        runCatching { userRepository.ensureTrialStarted() }
            .onSuccess { access ->
                _state.value = PaywallState.Ready(
                    isPro = access.hasProAccess,
                    isProPlus = access.hasProPlusAccess,
                    isProPurchased = access.isProPurchased,
                    isProPlusPurchased = access.isProPlusPurchased,
                    isTrialActive = access.isTrialActive,
                    trialDaysLeft = access.trialDaysLeft,
                    proPriceFormatted = cachedProPrice,
                    proPlusPriceFormatted = cachedProPlusPrice,
                    isPurchaseInProgress = purchaseInProgress
                )
            }
            .onFailure { error ->
                _state.value = PaywallState.Error(error.message ?: "Paywall unavailable")
            }
    }
}
