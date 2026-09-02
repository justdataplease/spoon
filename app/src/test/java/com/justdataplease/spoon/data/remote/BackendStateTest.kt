package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.UnavailableSpoonRepository
import com.justdataplease.spoon.domain.repository.BackendFailure
import com.justdataplease.spoon.domain.repository.BackendFailureKind
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.domain.repository.BackendUnavailableException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendStateTest {
    @Test
    fun `cloud is ready only after every component is ready`() {
        assertSame(
            BackendState.Cloud,
            aggregateCloudState(List(4) { CloudComponentState.Ready }),
        )
        assertSame(
            BackendState.Connecting,
            aggregateCloudState(
                listOf(
                    CloudComponentState.Ready,
                    CloudComponentState.Ready,
                    CloudComponentState.Pending,
                    CloudComponentState.Ready,
                ),
            ),
        )
    }

    @Test
    fun `component failure is surfaced ahead of pending components`() {
        val failure = BackendFailure(
            BackendFailureKind.PERMISSION,
            false,
            "permission",
        )

        val state = aggregateCloudState(
            listOf(CloudComponentState.Pending, CloudComponentState.Failed(failure)),
        )

        assertEquals(BackendState.Error(failure), state)
    }

    @Test
    fun `retry delay is exponentially capped`() {
        assertEquals(1_000L, retryDelayMillis(0))
        assertEquals(8_000L, retryDelayMillis(3))
        assertEquals(30_000L, retryDelayMillis(50))
    }

    @Test
    fun `configured initialization failure never exposes demo data or accepts writes`() = runBlocking {
        val failure = BackendFailure(
            BackendFailureKind.CONFIGURATION,
            false,
            "configuration",
        )
        val repository = UnavailableSpoonRepository(failure)

        assertEquals(BackendState.Error(failure), repository.backendState.value)
        assertTrue(repository.recipes.first().isEmpty())
        assertTrue(repository.mealPlans.first().isEmpty())
        assertTrue(repository.favoriteRecipeIds.first().isEmpty())

        val thrown = runCatching { repository.ensureReady() }.exceptionOrNull()
        assertTrue(thrown is BackendUnavailableException)
        assertFalse((thrown as BackendUnavailableException).failure.isRetryable)
    }
}
