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
    fun `registration accepts matching passwords and a trimmed valid email`() {
        assertNull(validateAccountInput(AccountFormMode.SIGN_UP, "  me@example.com  ", "secure-pass", "secure-pass"))
    }

    @Test
    fun `registration requires confirmation before creating an account`() {
        assertEquals(
            "Επιβεβαίωσε τον κωδικό σου.",
            validateAccountInput(AccountFormMode.SIGN_UP, "me@example.com", "secure-pass"),
        )
        assertEquals(
            "Οι κωδικοί δεν ταιριάζουν.",
            validateAccountInput(AccountFormMode.SIGN_UP, "me@example.com", "secure-pass", "another-pass"),
        )
    }

    @Test
    fun `registration never silently trims password confirmation`() {
        assertEquals(
            "Οι κωδικοί δεν ταιριάζουν.",
            validateAccountInput(AccountFormMode.SIGN_UP, "me@example.com", " secure-pass ", "secure-pass"),
        )
        assertNull(validateAccountInput(AccountFormMode.SIGN_UP, "me@example.com", " secure-pass ", " secure-pass "))
    }

    @Test
    fun `registration validates email and password before confirmation`() {
        assertEquals(
            "Γράψε μια έγκυρη ηλεκτρονική διεύθυνση.",
            validateAccountInput(AccountFormMode.SIGN_UP, "invalid", "123", "456"),
        )
        assertEquals(
            "Ο κωδικός πρέπει να έχει τουλάχιστον 6 χαρακτήρες.",
            validateAccountInput(AccountFormMode.SIGN_UP, "me@example.com", "123", "123"),
        )
    }

    @Test
    fun `sign in and password reset do not require registration confirmation`() {
        assertNull(validateAccountInput(AccountFormMode.SIGN_IN, "me@example.com", "secure-pass", "different"))
        assertNull(validateAccountInput(AccountFormMode.RESET, "me@example.com", "", "different"))
    }

    @Test
    fun `password reset only needs valid email`() {
        assertNull(validateAccountInput(AccountFormMode.RESET, "me@example.com"))
    }
}
