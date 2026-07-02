package fr.retrospare.blazeplayer.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.retrospare.blazeplayer.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    sealed class PaywallState {
        object Loading : PaywallState()
        data class Ready(val isPro: Boolean, val isProPlus: Boolean, val trialDaysLeft: Int) : PaywallState()
        data class Error(val message: String) : PaywallState()
    }

    private val _state = MutableStateFlow<PaywallState>(PaywallState.Loading)
    val state: StateFlow<PaywallState> = _state.asStateFlow()

    val isPro = userRepository.isProFlow

    /**
     * Debug/freemium scaffold only.
     *
     * Do not call RevenueCat/Play Billing here yet: billing is not wired in this branch and the
     * previous implementation could freeze the UI when the Settings button opened the paywall.
     * In debug, UserRepository currently exposes Pro and Pro+ as unlocked by default.
     */
    fun checkProStatus() {
        viewModelScope.launch {
            runCatching {
                val pro = userRepository.isProFlow.first()
                val proPlus = userRepository.isProPlusFlow.first()
                _state.value = PaywallState.Ready(
                    isPro = pro || proPlus,
                    isProPlus = proPlus,
                    trialDaysLeft = 0
                )
            }.onFailure { error ->
                _state.value = PaywallState.Error(error.message ?: "Paywall unavailable")
            }
        }
    }
}
