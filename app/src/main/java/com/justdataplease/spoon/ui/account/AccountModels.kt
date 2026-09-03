package com.justdataplease.spoon.ui.account

data class AccountUiState(
    val isSignedIn: Boolean = true,
    val isAnonymous: Boolean = true,
    val email: String = "",
    val isEmailVerified: Boolean? = null,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
)

internal enum class AccountFormMode { CREATE, SIGN_IN, RESET }

internal fun validateAccountInput(
    mode: AccountFormMode,
    email: String,
    password: String = "",
    confirmation: String = "",
): String? = when {
    !email.trim().isPlausibleEmail() -> "Γράψε μια έγκυρη ηλεκτρονική διεύθυνση."
    mode == AccountFormMode.RESET -> null
    password.length < 6 -> "Ο κωδικός πρέπει να έχει τουλάχιστον 6 χαρακτήρες."
    mode == AccountFormMode.CREATE && password != confirmation -> "Οι δύο κωδικοί δεν ταιριάζουν."
    else -> null
}

private fun String.isPlausibleEmail(): Boolean {
    if (length !in 3..254 || any(Char::isWhitespace)) return false
    val at = lastIndexOf('@')
    if (at !in 1 until lastIndex) return false
    val domain = substring(at + 1)
    return '.' in domain && !domain.startsWith('.') && !domain.endsWith('.')
}
