package fr.retrospare.blazeplayer.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.retrospare.blazeplayer.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    sealed class PaywallState {
        object Loading : PaywallState()
        data class Ready(val isPro: Boolean, val trialDaysLeft: Int) : PaywallState()
        data class Error(val message: String) : PaywallState()
    }

    private val _state = MutableStateFlow<PaywallState>(PaywallState.Loading)
    val state: StateFlow<PaywallState> = _state.asStateFlow()

    val isPro = userRepository.isProFlow

    fun checkProStatus() {
        Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                val hasPro = customerInfo.entitlements["pro"]?.isActive == true
                viewModelScope.launch {
                    userRepository.setProStatus(hasPro)
                    _state.value = PaywallState.Ready(isPro = hasPro, trialDaysLeft = 0)
                }
            }
            override fun onError(error: PurchasesError) {
                _state.value = PaywallState.Error(error.message)
            }
        })
    }
}
