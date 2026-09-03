package com.justdataplease.spoon.ui

import com.justdataplease.spoon.domain.repository.BackendFailure
import com.justdataplease.spoon.domain.repository.BackendFailureKind
import com.justdataplease.spoon.domain.repository.BackendUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpoonUserMessageTest {
    @Test
    fun `unknown exception message is never exposed to the user`() {
        val rawMessage = "Internal storage driver failed"

        val shownMessage = IllegalStateException(rawMessage).userMessage()

        assertEquals("Κάτι πήγε στραβά. Δοκίμασε ξανά.", shownMessage)
        assertFalse(shownMessage.contains(rawMessage))
    }

    @Test
    fun `classified backend failures keep their Greek user messages`() {
        val expected = mapOf(
            BackendFailureKind.PERMISSION to "Το Firestore απέρριψε την πρόσβαση. Έλεγξε τους κανόνες ασφαλείας.",
            BackendFailureKind.CONFIGURATION to "Η ρύθμιση του Firebase χρειάζεται διόρθωση.",
            BackendFailureKind.AUTHENTICATION to "Η ταυτοποίηση στο Firebase απέτυχε.",
            BackendFailureKind.NETWORK to "Δεν υπάρχει σύνδεση με το Firestore. Θα γίνει νέα προσπάθεια.",
            BackendFailureKind.UNKNOWN to "Η σύνδεση με το Firebase απέτυχε.",
        )

        expected.forEach { (kind, message) ->
            val error = BackendUnavailableException(
                BackendFailure(kind = kind, isRetryable = false, message = "Raw backend detail"),
            )
            assertEquals(message, error.userMessage())
        }
    }
}
