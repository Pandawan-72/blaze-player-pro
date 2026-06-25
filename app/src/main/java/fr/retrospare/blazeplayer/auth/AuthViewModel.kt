package fr.retrospare.blazeplayer.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.retrospare.blazeplayer.data.model.User
import fr.retrospare.blazeplayer.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    sealed class AuthState {
        object Idle : AuthState()
        object Loading : AuthState()
        data class Success(val user: FirebaseUser) : AuthState()
        data class Error(val message: String) : AuthState()
        object PasswordResetSent : AuthState()
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val currentUser get() = authRepository.currentUser

    fun loginWithEmail(email: String, password: String) {
        if (!validateEmail(email)) {
            _authState.value = AuthState.Error("Email invalide")
            return
        }
        if (password.length < 6) {
            _authState.value = AuthState.Error("Mot de passe trop court (6 caractères min.)")
            return
        }
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            authRepository.login(email, password).fold(
                onSuccess = { _authState.value = AuthState.Success(it) },
                onFailure = { _authState.value = AuthState.Error(mapFirebaseError(it.message)) }
            )
        }
    }

    fun registerWithEmail(email: String, password: String, confirmPassword: String) {
        if (!validateEmail(email)) {
            _authState.value = AuthState.Error("Email invalide")
            return
        }
        if (password.length < 6) {
            _authState.value = AuthState.Error("Mot de passe trop court (6 caractères min.)")
            return
        }
        if (password != confirmPassword) {
            _authState.value = AuthState.Error("Les mots de passe ne correspondent pas")
            return
        }
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            authRepository.register(email, password).fold(
                onSuccess = { user ->
                    val newUser = User(
                        uid = user.uid,
                        email = user.email ?: email,
                        displayName = user.displayName ?: "",
                        isPro = false,
                        trialStartDate = System.currentTimeMillis()
                    )
                    authRepository.saveUserProfile(newUser)
                    _authState.value = AuthState.Success(user)
                },
                onFailure = { _authState.value = AuthState.Error(mapFirebaseError(it.message)) }
            )
        }
    }

    fun loginWithGoogle(idToken: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            authRepository.loginWithGoogle(idToken).fold(
                onSuccess = { user ->
                    // Create Firestore profile for new Google users
                    if (!authRepository.userExists(user.uid)) {
                        val newUser = User(
                            uid = user.uid,
                            email = user.email ?: "",
                            displayName = user.displayName ?: "",
                            isPro = false,
                            trialStartDate = System.currentTimeMillis()
                        )
                        authRepository.saveUserProfile(newUser)
                    }
                    _authState.value = AuthState.Success(user)
                },
                onFailure = { _authState.value = AuthState.Error(mapFirebaseError(it.message)) }
            )
        }
    }

    fun sendPasswordReset(email: String) {
        if (!validateEmail(email)) {
            _authState.value = AuthState.Error("Email invalide")
            return
        }
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            authRepository.sendPasswordReset(email).fold(
                onSuccess = { _authState.value = AuthState.PasswordResetSent },
                onFailure = { _authState.value = AuthState.Error(mapFirebaseError(it.message)) }
            )
        }
    }

    fun signOut() = authRepository.signOut()

    fun resetState() { _authState.value = AuthState.Idle }

    private fun validateEmail(email: String) =
        email.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()

    private fun mapFirebaseError(message: String?): String = when {
        message == null -> "Une erreur est survenue"
        message.contains("INVALID_EMAIL") || message.contains("badly formatted") ->
            "Adresse email invalide"
        message.contains("EMAIL_EXISTS") || message.contains("already in use") ->
            "Cette adresse email est déjà utilisée"
        message.contains("WRONG_PASSWORD") || message.contains("password is invalid") ->
            "Mot de passe incorrect"
        message.contains("USER_NOT_FOUND") || message.contains("no user record") ->
            "Aucun compte trouvé avec cet email"
        message.contains("WEAK_PASSWORD") ->
            "Mot de passe trop faible"
        message.contains("NETWORK_ERROR") || message.contains("network") ->
            "Erreur réseau, vérifiez votre connexion"
        else -> "Erreur : $message"
    }
}
