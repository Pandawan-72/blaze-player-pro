package fr.retrospare.blazeplayer.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor() {
    val currentUser: Any? get() = null
    val authStateFlow: Flow<Any?> = flowOf(null)
    val isLoggedIn: Boolean get() = false
    suspend fun login(email: String, password: String): Result<Any> = Result.failure(Exception("Auth disabled"))
    suspend fun register(email: String, password: String): Result<Any> = Result.failure(Exception("Auth disabled"))
    suspend fun logout() {}
    suspend fun resetPassword(email: String): Result<Unit> = Result.failure(Exception("Auth disabled"))
    suspend fun loginWithGoogle(idToken: String): Result<Any> = Result.failure(Exception("Auth disabled"))
    suspend fun saveUserProfile(uid: String, email: String, name: String) {}
}
