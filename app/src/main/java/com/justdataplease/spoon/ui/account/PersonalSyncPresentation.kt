package com.justdataplease.spoon.ui.account

import com.justdataplease.spoon.domain.repository.PersonalSyncState

internal enum class PersonalSyncIndicator { DEVICE, UPLOADING, SYNCED, WAITING }

internal data class PersonalSyncPresentation(
    val title: String,
    val detail: String,
    val indicator: PersonalSyncIndicator,
)

/** Only an explicit server-acknowledged state is presented as synced. */
internal fun personalSyncPresentation(state: PersonalSyncState): PersonalSyncPresentation = when (state) {
    PersonalSyncState.LocalOnly -> PersonalSyncPresentation(
        title = "Αποθηκευμένα στη συσκευή",
        detail = "Οι αλλαγές σου αποθηκεύονται εδώ. Όλες οι λειτουργίες είναι διαθέσιμες χωρίς σύνδεση σε λογαριασμό.",
        indicator = PersonalSyncIndicator.DEVICE,
    )

    is PersonalSyncState.Syncing -> PersonalSyncPresentation(
        title = "Γίνεται συγχρονισμός",
        detail = when (val count = state.pendingWrites.coerceAtLeast(0)) {
            0 -> "Γίνεται έλεγχος συγχρονισμού. Τα δεδομένα σου παραμένουν αποθηκευμένα στη συσκευή."
            1 -> "Αποστέλλεται 1 αλλαγή. Μπορείς να συνεχίσεις να χρησιμοποιείς την εφαρμογή."
            else -> "Αποστέλλονται $count αλλαγές. Μπορείς να συνεχίσεις να χρησιμοποιείς την εφαρμογή."
        },
        indicator = PersonalSyncIndicator.UPLOADING,
    )

    PersonalSyncState.Synced -> PersonalSyncPresentation(
        title = "Συγχρονισμένα",
        detail = "Οι αποθηκευμένες αλλαγές σου έχουν συγχρονιστεί με τον λογαριασμό σου.",
        indicator = PersonalSyncIndicator.SYNCED,
    )

    is PersonalSyncState.Waiting -> PersonalSyncPresentation(
        title = "Αποθηκευμένα στη συσκευή · αναμονή συγχρονισμού",
        detail = if (state.needsLocalRecovery) {
            "Η μεταφορά των προηγούμενων δεδομένων χρειάζεται έλεγχο. Οι νέες αλλαγές αποθηκεύονται στη συσκευή και μπορείς να συνεχίσεις κανονικά."
        } else buildString {
            when (val count = state.pendingWrites.coerceAtLeast(0)) {
                0 -> append("Τα δεδομένα σου παραμένουν αποθηκευμένα εδώ. ")
                1 -> append("1 αλλαγή περιμένει για συγχρονισμό. ")
                else -> append("$count αλλαγές περιμένουν για συγχρονισμό. ")
            }
            append(
                if (state.needsSignIn) {
                    "Συνδέσου στον λογαριασμό σου ή δημιούργησε έναν για αντίγραφο ασφαλείας. Μπορείς να συνεχίσεις κανονικά χωρίς σύνδεση."
                } else {
                    "Ο συγχρονισμός θα συνεχιστεί αυτόματα όταν η υπηρεσία είναι διαθέσιμη και υπάρχει σύνδεση στο διαδίκτυο."
                },
            )
        },
        indicator = PersonalSyncIndicator.WAITING,
    )
}
