package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.local.AccountTransferStore
import kotlinx.coroutines.CancellationException

internal sealed interface AccountTransferRecoveryOutcome {
    data object NotNeeded : AccountTransferRecoveryOutcome
    data object Recovered : AccountTransferRecoveryOutcome
    data class Failed(val error: Exception, val claimCompleted: Boolean = false) : AccountTransferRecoveryOutcome
}

/** Optional recovery metadata must not make an otherwise healthy local database unusable. */
internal fun recoverPendingAccountTransfer(
    store: AccountTransferStore,
    ownerUid: String,
    email: String?,
    isAnonymous: Boolean,
    claim: (sourceUid: String, destinationUid: String) -> Unit,
): AccountTransferRecoveryOutcome {
    var claimCompleted = false
    return try {
        val pending = store.read()
        if (pending == null || !pending.matchesAuthenticatedAccount(email, isAnonymous)) {
            AccountTransferRecoveryOutcome.NotNeeded
        } else {
            claim(pending.anonymousUid, ownerUid)
            claimCompleted = true
            // Claim is an atomic database move. If clearing the intent fails, the destination
            // is already durable and retrying the same now-empty source is safe and idempotent.
            store.clear()
            AccountTransferRecoveryOutcome.Recovered
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AccountTransferRecoveryOutcome.Failed(error, claimCompleted)
    }
}
