package com.justdataplease.spoon.ui.account

import com.justdataplease.spoon.domain.repository.PersonalSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PersonalSyncPresentationTest {
    @Test
    fun `zero pending changes alone never claim server acknowledgement`() {
        val synced = personalSyncPresentation(PersonalSyncState.Synced)
        val unacknowledgedStates = listOf(
            PersonalSyncState.LocalOnly,
            PersonalSyncState.Syncing(0),
            PersonalSyncState.Waiting(0),
            PersonalSyncState.Waiting(0, needsSignIn = true),
        )

        unacknowledgedStates.forEach { state ->
            val status = personalSyncPresentation(state)
            assertFalse("Unexpected sync acknowledgement for $state", status.indicator == PersonalSyncIndicator.SYNCED)
            assertNotEquals(synced.title, status.title)
        }
        assertEquals(PersonalSyncIndicator.SYNCED, synced.indicator)
    }

    @Test
    fun `upload counts share a concise in progress status`() {
        val one = personalSyncPresentation(PersonalSyncState.Syncing(1))
        val several = personalSyncPresentation(PersonalSyncState.Syncing(14))

        assertEquals("Γίνεται συγχρονισμός", one.title)
        assertEquals(one, several)
        assertEquals(PersonalSyncIndicator.UPLOADING, several.indicator)
    }

    @Test
    fun `pending changes ask for sign in only when authentication is required`() {
        val signIn = personalSyncPresentation(PersonalSyncState.Waiting(3, needsSignIn = true))
        val connection = personalSyncPresentation(PersonalSyncState.Waiting(3))

        assertEquals("Απαιτείται σύνδεση λογαριασμού", signIn.title)
        assertEquals("Αναμονή συγχρονισμού", connection.title)
        assertEquals(PersonalSyncIndicator.WAITING, signIn.indicator)
        assertEquals(PersonalSyncIndicator.WAITING, connection.indicator)
    }

    @Test
    fun `local only describes device storage`() {
        val local = personalSyncPresentation(PersonalSyncState.LocalOnly)

        assertEquals(PersonalSyncIndicator.DEVICE, local.indicator)
        assertEquals("Αποθηκευμένα στη συσκευή", local.title)
    }

    @Test
    fun `local transfer recovery takes precedence over signing in`() {
        for (pending in listOf(0, 4)) {
            val state = PersonalSyncState.Waiting(pending, needsSignIn = true, needsLocalRecovery = true)
            val presentation = personalSyncPresentation(state)

            assertEquals(PersonalSyncIndicator.WAITING, presentation.indicator)
            assertEquals("Απαιτείται έλεγχος δεδομένων", presentation.title)
            assertNotEquals(
                personalSyncPresentation(PersonalSyncState.Waiting(pending, needsSignIn = true)).title,
                presentation.title,
            )
        }
    }

    @Test
    fun `invalid negative pending counts do not appear as work or acknowledgement`() {
        val syncing = personalSyncPresentation(PersonalSyncState.Syncing(-1))
        val waiting = personalSyncPresentation(PersonalSyncState.Waiting(-1))

        assertFalse(syncing.title.contains("-1"))
        assertFalse(waiting.title.contains("-1"))
        assertEquals(PersonalSyncIndicator.UPLOADING, syncing.indicator)
        assertEquals(PersonalSyncIndicator.WAITING, waiting.indicator)
    }
}
