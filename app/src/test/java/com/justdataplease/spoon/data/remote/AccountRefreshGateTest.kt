package com.justdataplease.spoon.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRefreshGateTest {
    @Test fun `bursts are suppressed until the interval boundary`() {
        val gate = AccountRefreshGate()
        assertTrue(gate.shouldRefresh("owner", 100))
        for (time in listOf(100L, 101L, 5_000L, 15_099L)) assertFalse(gate.shouldRefresh("owner", time))
        assertTrue(gate.shouldRefresh("owner", 15_100))
        assertFalse(gate.shouldRefresh("owner", 15_101))
    }

    @Test fun `a changed owner or reset clock can refresh immediately`() {
        val gate = AccountRefreshGate()
        assertTrue(gate.shouldRefresh("first", 100))
        assertTrue(gate.shouldRefresh("second", 101))
        assertFalse(gate.shouldRefresh("second", 102))
        assertTrue(gate.shouldRefresh("second", 1))
    }
}
