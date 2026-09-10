package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.domain.repository.BackendFailure
import com.justdataplease.spoon.domain.repository.BackendFailureKind
import com.justdataplease.spoon.domain.repository.PersonalSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalSyncStatusTest {
    @Test
    fun `offline cold start stops claiming sync activity after its initial wait without inventing acknowledgement`() {
        assertEquals(PersonalSyncState.Syncing(0), status(expired = false))
        assertEquals(PersonalSyncState.Waiting(0), status(expired = true))
        assertEquals(PersonalSyncState.Waiting(4), status(expired = true, pending = 4))
    }

    @Test
    fun `late authoritative snapshots restore normal progress and only acknowledged changes become synced`() {
        assertEquals(PersonalSyncState.Waiting(4), status(expired = true, pending = 4))
        assertEquals(PersonalSyncState.Syncing(4), status(expired = true, pending = 4, ready = true))
        assertEquals(PersonalSyncState.Synced, status(expired = true, pending = 0, ready = true))
    }

    @Test
    fun `new owner starts a new connection window and guest use stays local`() {
        assertEquals(PersonalSyncState.Waiting(0), status(expired = true))
        assertEquals(PersonalSyncState.Syncing(0), status(expired = false))
        assertEquals(PersonalSyncState.LocalOnly, status(canSync = false, expired = true))
    }

    @Test
    fun `optional local recovery failure is visible for guests and cannot be reported as synced`() {
        val guest = status(canSync = false, recovery = true) as PersonalSyncState.Waiting
        assertTrue(guest.needsLocalRecovery)
        val connected = status(ready = true, recovery = true) as PersonalSyncState.Waiting
        assertTrue(connected.needsLocalRecovery)
        assertEquals(PersonalSyncState.LocalOnly, status(canSync = false, recovery = false))
        assertEquals(PersonalSyncState.Synced, status(ready = true, recovery = false))
    }

    @Test
    fun `authentication failures remain backup status after local readiness`() {
        assertEquals(PersonalSyncState.Waiting(2, needsSignIn = true), personalSyncStatus(
            canSynchronize = true, pendingWrites = 2, allServerSnapshotsReady = true,
            initialWaitExpired = false, needsLocalRecovery = false,
            failures = listOf(BackendFailure(BackendFailureKind.AUTHENTICATION, false, "test")),
        ))
    }

    private fun status(canSync: Boolean = true, pending: Int = 0, ready: Boolean = false,
        expired: Boolean = false, recovery: Boolean = false): PersonalSyncState = personalSyncStatus(
        canSynchronize = canSync, pendingWrites = pending, allServerSnapshotsReady = ready,
        initialWaitExpired = expired, needsLocalRecovery = recovery, failures = emptyList(),
    )
}
