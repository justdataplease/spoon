package com.justdataplease.spoon.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountModelsTest {
    @Test
    fun `sign in validates email and password`() {
        assertEquals(
            "Γράψε μια έγκυρη ηλεκτρονική διεύθυνση.",
            validateAccountInput(
                AccountFormMode.SIGN_IN,
                "λάθος",
                "Dokimi-Password-8472!",
            ),
        )
        assertEquals(
            "Ο κωδικός πρέπει να έχει τουλάχιστον 6 χαρακτήρες.",
            validateAccountInput(AccountFormMode.SIGN_IN, "me@example.com", "123"),
        )
        assertNull(
            validateAccountInput(
                AccountFormMode.SIGN_IN,
                "me@example.com",
                "Dokimi-Password-8472!",
            ),
        )
    }

    @Test
    fun `password reset only needs valid email`() {
        assertNull(validateAccountInput(AccountFormMode.RESET, "me@example.com"))
    }
}
