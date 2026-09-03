package com.justdataplease.spoon.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountModelsTest {
    @Test
    fun `create validates email password and confirmation`() {
        assertEquals(
            "Γράψε μια έγκυρη ηλεκτρονική διεύθυνση.",
            validateAccountInput(
                AccountFormMode.CREATE,
                "λάθος",
                "Dokimi-Password-8472!",
                "Dokimi-Password-8472!",
            ),
        )
        assertEquals(
            "Ο κωδικός πρέπει να έχει τουλάχιστον 6 χαρακτήρες.",
            validateAccountInput(AccountFormMode.CREATE, "me@example.com", "123", "123"),
        )
        assertEquals(
            "Οι δύο κωδικοί δεν ταιριάζουν.",
            validateAccountInput(
                AccountFormMode.CREATE,
                "me@example.com",
                "Dokimi-Password-8472!",
                "Different-Test-Password-3916!",
            ),
        )
        assertNull(
            validateAccountInput(
                AccountFormMode.CREATE,
                "me@example.com",
                "Dokimi-Password-8472!",
                "Dokimi-Password-8472!",
            ),
        )
    }

    @Test
    fun `password reset only needs valid email`() {
        assertNull(validateAccountInput(AccountFormMode.RESET, "me@example.com"))
    }
}
