package com.justdataplease.spoon.ui

import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.domain.repository.AccountState
import com.justdataplease.spoon.domain.repository.BackendState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoonViewModelLifecyclePolicyTest {
    @Test
    fun `owner tracker ignores profile refresh but detects uid change and sign out`() {
        val tracker = AccountOwnerTracker()

        assertFalse(tracker.onAccountState(AccountState.Email("owner-a", "a@example.com", false)))
        assertFalse(tracker.onAccountState(AccountState.Email("owner-a", "a@example.com", true)))
        assertFalse(tracker.onAccountState(AccountState.Anonymous("owner-a")))
        assertTrue(tracker.onAccountState(AccountState.Email("owner-b", "b@example.com", false)))
        assertTrue(tracker.onAccountState(AccountState.Loading))
        assertTrue(tracker.onAccountState(AccountState.Anonymous("guest-c")))
    }

    @Test
    fun `owner becoming known after startup loading invalidates an unowned selection`() {
        val tracker = AccountOwnerTracker()

        assertFalse(tracker.onAccountState(AccountState.Loading))
        assertTrue(tracker.onAccountState(AccountState.Email("owner-a", "a@example.com", false)))
    }

    @Test
    fun `planning readiness requires a ready backend and an active Greek recipe snapshot`() {
        val greek = Recipe(id = "greek", title = "Συνταγή", language = "el-GR")

        assertFalse(isCatalogReadyForPlanning(BackendState.Connecting, listOf(greek)))
        assertFalse(isCatalogReadyForPlanning(BackendState.Cloud, emptyList()))
        assertFalse(
            isCatalogReadyForPlanning(
                BackendState.Cloud,
                listOf(greek.copy(id = "inactive", active = false)),
            ),
        )
        assertFalse(
            isCatalogReadyForPlanning(
                BackendState.Cloud,
                listOf(greek.copy(id = "english", language = "en")),
            ),
        )
        assertTrue(isCatalogReadyForPlanning(BackendState.Cloud, listOf(greek)))
        assertTrue(isCatalogReadyForPlanning(BackendState.Local, listOf(greek)))
    }

    @Test
    fun `timed out startup request is retried exactly once after catalog readiness`() {
        val weekStart = LocalDate.of(2026, 8, 31)
        val gate = CatalogWeekRetryGate()

        gate.onDirectRequest(weekStart)
        assertNull(gate.onCatalogReadiness(false))
        assertEquals(weekStart, gate.onCatalogReadiness(true))
        assertTrue(gate.consumeRetry(weekStart))
        assertFalse(gate.consumeRetry(weekStart))
        assertNull(gate.retryCandidate())

        assertNull(gate.onCatalogReadiness(false))
        assertNull(gate.onCatalogReadiness(true))
    }

    @Test
    fun `successful initial attempt cancels a queued catalog retry`() {
        val weekStart = LocalDate.of(2026, 8, 31)
        val gate = CatalogWeekRetryGate()

        gate.onDirectRequest(weekStart)
        val queuedCandidate = gate.onCatalogReadiness(true)
        gate.onAttemptSucceeded(weekStart)

        assertEquals(weekStart, queuedCandidate)
        assertFalse(gate.consumeRetry(requireNotNull(queuedCandidate)))
        assertNull(gate.retryCandidate())
    }

    @Test
    fun `owner change invalidates old readiness and queues the visible week once`() {
        val weekStart = LocalDate.of(2026, 8, 31)
        val gate = CatalogWeekRetryGate()

        assertNull(gate.onCatalogReadiness(true))
        gate.onAccountOwnerChanged(weekStart)

        assertNull(gate.retryCandidate())
        assertEquals(weekStart, gate.onCatalogReadiness(true))
        assertTrue(gate.consumeRetry(weekStart))
        assertFalse(gate.consumeRetry(weekStart))
    }
}
