package fr.retrospare.blazeplayer.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
        data class Ready(
            val isPro: Boolean,
            val isProPlus: Boolean,
            val isProPurchased: Boolean,
            val isProPlusPurchased: Boolean,
            val isTrialActive: Boolean,
            val trialDaysLeft: Int
        ) : PaywallState()
        data class Error(val message: String) : PaywallState()
    }

    private val _state = MutableStateFlow<PaywallState>(PaywallState.Loading)
    val state: StateFlow<PaywallState> = _state.asStateFlow()

    fun checkProStatus() {
        viewModelScope.launch {
            runCatching { userRepository.ensureTrialStarted() }
                .onSuccess { access ->
                    _state.value = PaywallState.Ready(
                        isPro = access.hasProAccess,
                        isProPlus = access.hasProPlusAccess,
                        isProPurchased = access.isProPurchased,
                        isProPlusPurchased = access.isProPlusPurchased,
                        isTrialActive = access.isTrialActive,
                        trialDaysLeft = access.trialDaysLeft
                    )
                }
                .onFailure { error ->
                    _state.value = PaywallState.Error(error.message ?: "Paywall unavailable")
                }
        }
    }
}
