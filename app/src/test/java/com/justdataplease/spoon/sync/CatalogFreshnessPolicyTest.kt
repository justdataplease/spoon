package com.justdataplease.spoon.sync

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogFreshnessPolicyTest {
    private val interval = TimeUnit.DAYS.toMillis(90L)
    private val catalogHash = "a".repeat(64)

    @Test
    fun `an install with no previous check is due immediately`() {
        assertTrue(CatalogFreshnessPolicy.isDue(0L, 1_000L))
        assertEquals(0L, CatalogFreshnessPolicy.delayUntilNextCheck(0L, 1_000L))
        assertEquals(
            CatalogCheckState.NEVER_CHECKED,
            CatalogFreshnessStatus().checkStateAt(1_000L),
        )
    }

    @Test
    fun `successful check becomes stale exactly at ninety days`() {
        val checkedAt = 1_000L
        val status = CatalogFreshnessStatus().afterSuccess(
            checkedAt = checkedAt,
            catalogUpdatedAt = 900L,
            recipeCount = 5_000L,
        )

        assertFalse(CatalogFreshnessPolicy.isDue(checkedAt, checkedAt + interval - 1L))
        assertEquals(
            1L,
            CatalogFreshnessPolicy.delayUntilNextCheck(checkedAt, checkedAt + interval - 1L),
        )
        assertEquals(CatalogCheckState.CURRENT, status.checkStateAt(checkedAt + interval - 1L))
        assertEquals(CatalogCheckState.STALE, status.checkStateAt(checkedAt + interval))
    }

    @Test
    fun `backwards clock correction waits a full interval`() {
        val checkedAt = 50_000L

        assertEquals(
            interval,
            CatalogFreshnessPolicy.delayUntilNextCheck(checkedAt, checkedAt - 10_000L),
        )
    }

    @Test
    fun `failed attempt is explicit and preserves last known good metadata`() {
        val successful = CatalogFreshnessStatus().afterSuccess(
            checkedAt = 10_000L,
            catalogUpdatedAt = 9_000L,
            recipeCount = 2_000L,
        )
        val failed = successful.afterFailure(
            attemptedAt = 11_000L,
            failure = CatalogCheckFailure.STATUS_MISSING,
        )

        assertEquals(CatalogCheckState.FAILED, failed.checkStateAt(11_000L))
        assertTrue(failed.lastAttemptFailed)
        assertEquals(10_000L, failed.lastCheckedAt)
        assertEquals(9_000L, failed.catalogUpdatedAt)
        assertEquals(2_000L, failed.recipeCount)
        assertEquals(1L, failed.consecutiveFailures)
        assertEquals(CatalogCheckFailure.STATUS_MISSING, failed.lastFailure)
        assertEquals(0L, failed.delayUntilNextCheck(11_000L))
        assertTrue(failed.hasCatalogMetadata)
    }

    @Test
    fun `success clears a prior failure state`() {
        val recovered = CatalogFreshnessStatus()
            .afterFailure(1_000L, CatalogCheckFailure.TRANSIENT_ERROR)
            .afterFailure(2_000L, CatalogCheckFailure.TRANSIENT_ERROR)
            .afterSuccess(
                checkedAt = 3_000L,
                catalogUpdatedAt = 2_500L,
                recipeCount = 4_000L,
            )

        assertEquals(CatalogCheckState.CURRENT, recovered.checkStateAt(3_000L))
        assertFalse(recovered.lastAttemptFailed)
        assertEquals(0L, recovered.lastFailureAt)
        assertEquals(0L, recovered.consecutiveFailures)
        assertEquals(CatalogCheckFailure.NONE, recovered.lastFailure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `success rejects inconsistent catalog identity`() {
        CatalogFreshnessStatus().afterSuccess(
            checkedAt = 3_000L,
            catalogUpdatedAt = 2_500L,
            recipeCount = 4_000L,
            catalogVersion = "sha256:${"b".repeat(64)}",
            catalogHash = catalogHash,
        )
    }

    @Test
    fun `only transient Firestore codes are retried`() {
        assertTrue(CatalogFreshnessPolicy.shouldRetryFirestoreCode("UNAVAILABLE"))
        assertTrue(CatalogFreshnessPolicy.shouldRetryFirestoreCode("DEADLINE_EXCEEDED"))
        assertFalse(CatalogFreshnessPolicy.shouldRetryFirestoreCode("PERMISSION_DENIED"))
        assertFalse(CatalogFreshnessPolicy.shouldRetryFirestoreCode("INVALID_ARGUMENT"))
    }

    @Test
    fun `complete importer checkpoint is accepted`() {
        val validation = CatalogFreshnessPolicy.validateCheckpoint(
            exists = true,
            language = "el",
            recipeCount = 5_365L,
            lastImportedAt = 123_456L,
            catalogVersion = "sha256:$catalogHash",
            catalogHash = catalogHash,
        )

        assertEquals(
            CatalogCheckpointValidation.Valid(
                CatalogCheckpoint(
                    catalogUpdatedAt = 123_456L,
                    recipeCount = 5_365L,
                    catalogVersion = "sha256:$catalogHash",
                    catalogHash = catalogHash,
                ),
            ),
            validation,
        )
    }

    @Test
    fun `missing checkpoint is distinct from malformed checkpoint`() {
        assertSame(
            CatalogCheckpointValidation.Missing,
            CatalogFreshnessPolicy.validateCheckpoint(
                exists = false,
                language = null,
                recipeCount = null,
                lastImportedAt = null,
                catalogVersion = null,
                catalogHash = null,
            ),
        )
    }

    @Test
    fun `incomplete or inconsistent checkpoints are rejected`() {
        val malformed = listOf(
            CatalogFreshnessPolicy.validateCheckpoint(
                true,
                "en",
                5_365L,
                123_456L,
                "sha256:$catalogHash",
                catalogHash,
            ),
            CatalogFreshnessPolicy.validateCheckpoint(
                true,
                "el",
                0L,
                123_456L,
                "sha256:$catalogHash",
                catalogHash,
            ),
            CatalogFreshnessPolicy.validateCheckpoint(
                true,
                "el",
                5_365L,
                null,
                "sha256:$catalogHash",
                catalogHash,
            ),
            CatalogFreshnessPolicy.validateCheckpoint(
                true,
                "el",
                5_365L,
                123_456L,
                "sha256:${"b".repeat(64)}",
                catalogHash,
            ),
            CatalogFreshnessPolicy.validateCheckpoint(
                true,
                "el",
                5_365L,
                123_456L,
                "sha256:${catalogHash.uppercase()}",
                catalogHash.uppercase(),
            ),
        )

        assertTrue(malformed.all { it is CatalogCheckpointValidation.Malformed })
    }
}
