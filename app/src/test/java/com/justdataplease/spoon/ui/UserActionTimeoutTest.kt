package com.justdataplease.spoon.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserActionTimeoutTest {
    @Test
    fun `fast user action returns its result`() = runBlocking {
        val result = withUserActionTimeout(timeoutMillis = 1_000L) { "ready" }

        assertEquals("ready", result)
    }

    @Test
    fun `pending user action becomes a dedicated timeout failure`() = runBlocking {
        val thrown = runCatching {
            withUserActionTimeout(timeoutMillis = 20L) { delay(5_000L) }
        }.exceptionOrNull()

        assertTrue(thrown is UserActionTimeoutException)
        assertEquals(
            "Η ενέργεια άργησε περισσότερο από το αναμενόμενο. Δοκίμασε ξανά.",
            thrown?.userMessage(),
        )
    }
}
