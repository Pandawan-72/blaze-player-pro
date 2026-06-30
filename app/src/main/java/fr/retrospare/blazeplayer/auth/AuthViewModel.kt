package fr.retrospare.blazeplayer.auth

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    object Success : AuthState()
    data class Error(val message: String) : AuthState()
    object LoggedOut : AuthState()
}

@HiltViewModel
class AuthViewModel @Inject constructor() : ViewModel() {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState

    fun login(email: String, password: String) { _authState.value = AuthState.Error("Auth disabled") }
    fun register(email: String, password: String) { _authState.value = AuthState.Error("Auth disabled") }
    fun logout() { _authState.value = AuthState.LoggedOut }
    fun resetPassword(email: String) { _authState.value = AuthState.Error("Auth disabled") }
    fun loginWithGoogle(idToken: String) { _authState.value = AuthState.Error("Auth disabled") }
    fun sendPasswordReset(email: String) { _authState.value = AuthState.Error("Auth disabled") }
}
