package fr.retrospare.blazeplayer.data.model

data class User(
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val isPro: Boolean = false,
    val trialStartDate: Long = 0L,
    val proActivationDate: Long = 0L
)
