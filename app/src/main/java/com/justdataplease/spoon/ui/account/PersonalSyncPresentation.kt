package com.justdataplease.spoon.ui.account

import com.justdataplease.spoon.domain.repository.PersonalSyncState

internal enum class PersonalSyncIndicator { DEVICE, UPLOADING, SYNCED, WAITING }

internal data class PersonalSyncPresentation(
    val title: String,
    val indicator: PersonalSyncIndicator,
)

/** Only an explicit server-acknowledged state is presented as synced. */
internal fun personalSyncPresentation(state: PersonalSyncState): PersonalSyncPresentation = when (state) {
    PersonalSyncState.LocalOnly -> PersonalSyncPresentation(
        title = "Αποθηκευμένα στη συσκευή",
        indicator = PersonalSyncIndicator.DEVICE,
    )

    is PersonalSyncState.Syncing -> PersonalSyncPresentation(
        title = "Γίνεται συγχρονισμός",
        indicator = PersonalSyncIndicator.UPLOADING,
    )

    PersonalSyncState.Synced -> PersonalSyncPresentation(
        title = "Πλήρως συγχρονισμένα",
        indicator = PersonalSyncIndicator.SYNCED,
    )

    is PersonalSyncState.Waiting -> PersonalSyncPresentation(
        title = when {
            state.needsLocalRecovery -> "Απαιτείται έλεγχος δεδομένων"
            state.needsSignIn -> "Απαιτείται σύνδεση λογαριασμού"
            else -> "Αναμονή συγχρονισμού"
        },
        indicator = PersonalSyncIndicator.WAITING,
    )
}
