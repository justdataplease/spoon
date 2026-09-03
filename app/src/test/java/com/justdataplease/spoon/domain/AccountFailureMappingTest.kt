package com.justdataplease.spoon.domain

import com.justdataplease.spoon.domain.repository.AccountFailureKind
import com.justdataplease.spoon.domain.repository.accountFailureForFirebaseCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountFailureMappingTest {
    @Test
    fun maps_actionable_Firebase_codes_to_stable_kinds() {
        assertEquals(
            AccountFailureKind.INVALID_EMAIL,
            accountFailureForFirebaseCode("ERROR_INVALID_EMAIL").kind,
        )
        assertEquals(
            AccountFailureKind.WEAK_PASSWORD,
            accountFailureForFirebaseCode("ERROR_WEAK_PASSWORD").kind,
        )
        assertEquals(
            AccountFailureKind.EMAIL_IN_USE,
            accountFailureForFirebaseCode("ERROR_CREDENTIAL_ALREADY_IN_USE").kind,
        )
        assertEquals(
            AccountFailureKind.WRONG_CREDENTIALS,
            accountFailureForFirebaseCode("ERROR_INVALID_CREDENTIAL").kind,
        )
        assertEquals(
            AccountFailureKind.NETWORK,
            accountFailureForFirebaseCode("ERROR_NETWORK_REQUEST_FAILED").kind,
        )
        assertEquals(
            AccountFailureKind.TOO_MANY_REQUESTS,
            accountFailureForFirebaseCode("ERROR_TOO_MANY_REQUESTS").kind,
        )
    }

    @Test
    fun mapped_messages_are_Greek_safe_and_never_echo_credentials() {
        val messages = listOf(
            "ERROR_INVALID_EMAIL",
            "ERROR_WEAK_PASSWORD",
            "ERROR_EMAIL_ALREADY_IN_USE",
            "ERROR_WRONG_PASSWORD",
            "ERROR_USER_DISABLED",
            "ERROR_NETWORK_REQUEST_FAILED",
            "ERROR_TOO_MANY_REQUESTS",
            "ERROR_REQUIRES_RECENT_LOGIN",
            null,
        ).map { accountFailureForFirebaseCode(it).greekMessage }

        messages.forEach { message ->
            assertFalse(message.isBlank())
            assertFalse(message.contains("secret-password"))
            assertFalse(message.contains("someone@example.com"))
        }
    }
}
