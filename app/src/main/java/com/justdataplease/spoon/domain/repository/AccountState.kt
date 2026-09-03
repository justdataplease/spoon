package com.justdataplease.spoon.domain.repository

/** Authentication identity exposed without leaking Firebase types into the UI/domain. */
sealed interface AccountState {
    data object Loading : AccountState
    data class Anonymous(val uid: String) : AccountState
    data class Email(
        val uid: String,
        val email: String,
        val emailVerified: Boolean,
    ) : AccountState
    data object Unavailable : AccountState
}

enum class AccountFailureKind {
    INVALID_EMAIL,
    WEAK_PASSWORD,
    EMAIL_IN_USE,
    WRONG_CREDENTIALS,
    USER_DISABLED,
    NETWORK,
    TOO_MANY_REQUESTS,
    REQUIRES_RECENT_LOGIN,
    UNAVAILABLE,
    UNKNOWN,
}

data class AccountFailure(
    val kind: AccountFailureKind,
    val greekMessage: String,
)

class AccountOperationException(
    val failure: AccountFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.greekMessage, cause)

/** Stable, non-sensitive mapping suitable for UI messages and unit tests. */
internal fun accountFailureForFirebaseCode(code: String?): AccountFailure = when (code) {
    "ERROR_INVALID_EMAIL",
    "ERROR_MISSING_EMAIL",
    -> AccountFailure(AccountFailureKind.INVALID_EMAIL, "Το email δεν είναι έγκυρο.")

    "ERROR_WEAK_PASSWORD" ->
        AccountFailure(
            AccountFailureKind.WEAK_PASSWORD,
            "Ο κωδικός πρέπει να έχει τουλάχιστον 6 χαρακτήρες.",
        )

    "ERROR_EMAIL_ALREADY_IN_USE",
    "ERROR_CREDENTIAL_ALREADY_IN_USE",
    -> AccountFailure(
        AccountFailureKind.EMAIL_IN_USE,
        "Υπάρχει ήδη λογαριασμός με αυτό το email. Δοκίμασε σύνδεση.",
    )

    "ERROR_INVALID_CREDENTIAL",
    "ERROR_WRONG_PASSWORD",
    "ERROR_USER_NOT_FOUND",
    -> AccountFailure(
        AccountFailureKind.WRONG_CREDENTIALS,
        "Το email ή ο κωδικός δεν είναι σωστός.",
    )

    "ERROR_USER_DISABLED" ->
        AccountFailure(AccountFailureKind.USER_DISABLED, "Αυτός ο λογαριασμός έχει απενεργοποιηθεί.")

    "ERROR_NETWORK_REQUEST_FAILED",
    "ERROR_WEB_NETWORK_REQUEST_FAILED",
    -> AccountFailure(
        AccountFailureKind.NETWORK,
        "Δεν υπάρχει σύνδεση. Έλεγξε το δίκτυο και δοκίμασε ξανά.",
    )

    "ERROR_TOO_MANY_REQUESTS" ->
        AccountFailure(
            AccountFailureKind.TOO_MANY_REQUESTS,
            "Έγιναν πολλές προσπάθειες. Περίμενε λίγο και δοκίμασε ξανά.",
        )

    "ERROR_REQUIRES_RECENT_LOGIN" ->
        AccountFailure(
            AccountFailureKind.REQUIRES_RECENT_LOGIN,
            "Χρειάζεται να συνδεθείς ξανά πριν συνεχίσεις.",
        )

    else -> AccountFailure(
        AccountFailureKind.UNKNOWN,
        "Δεν ολοκληρώθηκε η ενέργεια λογαριασμού. Δοκίμασε ξανά.",
    )
}

internal fun unavailableAccountFailure() = AccountFailure(
    AccountFailureKind.UNAVAILABLE,
    "Οι λογαριασμοί δεν είναι διαθέσιμοι σε αυτή την έκδοση.",
)
