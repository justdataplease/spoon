package com.justdataplease.spoon.ui.account

import com.justdataplease.spoon.domain.repository.PersonalSyncState

data class AccountUiState(
    val isSignedIn: Boolean = true,
    val isAnonymous: Boolean = true,
    val email: String = "",
    /** Stable owner identity for personal draft isolation; never shown in the UI. */
    val dataOwnerKey: String = "guest",
    val isEmailVerified: Boolean? = null,
    val syncState: PersonalSyncState = PersonalSyncState.LocalOnly,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
)

internal enum class AccountFormMode { SIGN_IN, SIGN_UP, RESET }

internal fun validateAccountInput(
    mode: AccountFormMode,
    email: String,
    password: String = "",
    passwordConfirmation: String = "",
): String? = when {
    !email.trim().isPlausibleEmail() -> "Γράψε μια έγκυρη ηλεκτρονική διεύθυνση."
    mode == AccountFormMode.RESET -> null
    password.length < 6 -> "Ο κωδικός πρέπει να έχει τουλάχιστον 6 χαρακτήρες."
    mode == AccountFormMode.SIGN_UP && passwordConfirmation.isEmpty() -> "Επιβεβαίωσε τον κωδικό σου."
    mode == AccountFormMode.SIGN_UP && password != passwordConfirmation -> "Οι κωδικοί δεν ταιριάζουν."
    else -> null
}

private fun String.isPlausibleEmail(): Boolean {
    if (length !in 3..254 || any(Char::isWhitespace)) return false
    val at = lastIndexOf('@')
    if (at !in 1 until lastIndex) return false
    val domain = substring(at + 1)
    return '.' in domain && !domain.startsWith('.') && !domain.endsWith('.')
}
