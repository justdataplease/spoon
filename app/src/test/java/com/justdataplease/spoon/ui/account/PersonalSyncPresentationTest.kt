package com.justdataplease.spoon.ui.account

import com.justdataplease.spoon.domain.repository.PersonalSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalSyncPresentationTest {
    @Test
    fun `zero pending changes alone never claim server acknowledgement`() {
        val unacknowledgedStates = listOf(
            PersonalSyncState.LocalOnly,
            PersonalSyncState.Syncing(0),
            PersonalSyncState.Waiting(0),
            PersonalSyncState.Waiting(0, needsSignIn = true),
        )

        unacknowledgedStates.forEach { state ->
            val status = personalSyncPresentation(state)
            assertFalse("Unexpected sync acknowledgement for $state", status.indicator == PersonalSyncIndicator.SYNCED)
            assertFalse(status.title == "Συγχρονισμένα")
        }
        assertEquals(
            PersonalSyncIndicator.SYNCED,
            personalSyncPresentation(PersonalSyncState.Synced).indicator,
        )
    }

    @Test
    fun `upload count remains visible without implying the app is blocked`() {
        val one = personalSyncPresentation(PersonalSyncState.Syncing(1))
        val several = personalSyncPresentation(PersonalSyncState.Syncing(14))

        assertTrue(one.detail.startsWith("Αποστέλλεται 1 αλλαγή."))
        assertTrue(several.detail.startsWith("Αποστέλλονται 14 αλλαγές."))
        assertTrue(several.detail.contains("Μπορείς να συνεχίσεις"))
        assertEquals(PersonalSyncIndicator.UPLOADING, several.indicator)
    }

    @Test
    fun `pending changes ask for sign in only when authentication is required`() {
        val signIn = personalSyncPresentation(PersonalSyncState.Waiting(3, needsSignIn = true))
        val connection = personalSyncPresentation(PersonalSyncState.Waiting(3))

        assertTrue(signIn.detail.contains("3 αλλαγές"))
        assertTrue(signIn.detail.contains("Συνδέσου"))
        assertTrue(signIn.detail.contains("χωρίς σύνδεση"))
        assertFalse(connection.detail.contains("Συνδέσου"))
        assertTrue(connection.detail.contains("υπηρεσία είναι διαθέσιμη"))
        assertTrue(connection.title.contains("Αποθηκευμένα στη συσκευή"))
    }

    @Test
    fun `local only describes device storage and unrestricted local use`() {
        val local = personalSyncPresentation(PersonalSyncState.LocalOnly)

        assertEquals(PersonalSyncIndicator.DEVICE, local.indicator)
        assertEquals("Αποθηκευμένα στη συσκευή", local.title)
        assertTrue(local.detail.contains("Όλες οι λειτουργίες"))
        assertTrue(local.detail.contains("χωρίς σύνδεση σε λογαριασμό"))
    }

    @Test
    fun `invalid negative pending counts do not appear as work or acknowledgement`() {
        val syncing = personalSyncPresentation(PersonalSyncState.Syncing(-1))
        val waiting = personalSyncPresentation(PersonalSyncState.Waiting(-1))

        assertFalse(syncing.detail.contains("-1"))
        assertFalse(waiting.detail.contains("-1"))
        assertEquals(PersonalSyncIndicator.UPLOADING, syncing.indicator)
        assertEquals(PersonalSyncIndicator.WAITING, waiting.indicator)
    }
}
