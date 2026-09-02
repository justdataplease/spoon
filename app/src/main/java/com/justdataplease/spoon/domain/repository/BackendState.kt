package com.justdataplease.spoon.domain.repository

/** Runtime storage state exposed by every repository implementation. */
sealed interface BackendState {
    data object Local : BackendState
    data object Connecting : BackendState
    data object Cloud : BackendState
    data class Error(val failure: BackendFailure) : BackendState
}

data class BackendFailure(
    val kind: BackendFailureKind,
    val isRetryable: Boolean,
    val message: String,
)

enum class BackendFailureKind {
    AUTHENTICATION,
    PERMISSION,
    NETWORK,
    CONFIGURATION,
    UNKNOWN,
}

class BackendUnavailableException(
    val failure: BackendFailure,
) : IllegalStateException(failure.message)
