package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.domain.repository.BackendFailure
import com.justdataplease.spoon.domain.repository.BackendFailureKind
import com.justdataplease.spoon.domain.repository.PersonalSyncState

internal const val INITIAL_PERSONAL_SYNC_WAIT_MILLIS = 15_000L

/** Local readiness does not depend on this cloud-only status policy. */
internal fun personalSyncStatus(
    canSynchronize: Boolean,
    pendingWrites: Int,
    allServerSnapshotsReady: Boolean,
    initialWaitExpired: Boolean,
    needsLocalRecovery: Boolean,
    failures: Collection<BackendFailure>,
): PersonalSyncState {
    val pending = pendingWrites.coerceAtLeast(0)
    return when {
        needsLocalRecovery -> PersonalSyncState.Waiting(pending, needsLocalRecovery = true)
        !canSynchronize -> PersonalSyncState.LocalOnly
        failures.isNotEmpty() -> PersonalSyncState.Waiting(pending,
            needsSignIn = failures.any { it.kind == BackendFailureKind.AUTHENTICATION })
        initialWaitExpired && !allServerSnapshotsReady -> PersonalSyncState.Waiting(pending)
        pending == 0 && allServerSnapshotsReady -> PersonalSyncState.Synced
        else -> PersonalSyncState.Syncing(pending)
    }
}
