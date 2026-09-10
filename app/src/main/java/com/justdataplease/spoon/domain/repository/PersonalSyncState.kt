package com.justdataplease.spoon.domain.repository

/** Personal-data backup status, independent of local feature and catalog availability. */
sealed interface PersonalSyncState {
    /** Changes are durably stored on this device without an active account backup. */
    data object LocalOnly : PersonalSyncState

    data class Syncing(val pendingWrites: Int) : PersonalSyncState

    /** The repository has received server acknowledgement for the current account's changes. */
    data object Synced : PersonalSyncState

    /** Local changes remain available while authentication or remote service access waits. */
    data class Waiting(
        val pendingWrites: Int,
        val needsSignIn: Boolean = false,
        val needsLocalRecovery: Boolean = false,
    ) : PersonalSyncState
}
