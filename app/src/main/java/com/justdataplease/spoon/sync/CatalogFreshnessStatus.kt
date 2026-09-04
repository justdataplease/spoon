package com.justdataplease.spoon.sync

import java.util.concurrent.TimeUnit

enum class CatalogCheckState {
    NEVER_CHECKED,
    CURRENT,
    STALE,
    FAILED,
}

enum class CatalogCheckFailure {
    NONE,
    STATUS_MISSING,
    STATUS_MALFORMED,
    TRANSIENT_ERROR,
    PERMANENT_ERROR,
}

data class CatalogCheckpoint(
    val catalogUpdatedAt: Long,
    val recipeCount: Long,
    val catalogVersion: String,
    val catalogHash: String,
)

sealed interface CatalogCheckpointValidation {
    data class Valid(val checkpoint: CatalogCheckpoint) : CatalogCheckpointValidation
    data object Missing : CatalogCheckpointValidation
    data class Malformed(val reason: String) : CatalogCheckpointValidation
}

/** Non-personal catalog check state persisted in private app storage. */
data class CatalogFreshnessStatus(
    /** Last successful check. Failed attempts never advance the 90-day clock. */
    val lastCheckedAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val catalogUpdatedAt: Long = 0L,
    val recipeCount: Long = 0L,
    val catalogVersion: String = "",
    val catalogHash: String = "",
    val lastFailureAt: Long = 0L,
    val consecutiveFailures: Long = 0L,
    val lastFailure: CatalogCheckFailure = CatalogCheckFailure.NONE,
) {
    val lastAttemptFailed: Boolean
        get() = consecutiveFailures > 0L &&
            lastFailure != CatalogCheckFailure.NONE &&
            lastFailureAt > 0L &&
            lastFailureAt >= lastCheckedAt

    fun checkStateAt(nowEpochMillis: Long): CatalogCheckState = when {
        lastAttemptFailed -> CatalogCheckState.FAILED
        lastCheckedAt <= 0L -> CatalogCheckState.NEVER_CHECKED
        CatalogFreshnessPolicy.isDue(lastCheckedAt, nowEpochMillis) -> CatalogCheckState.STALE
        else -> CatalogCheckState.CURRENT
    }

    fun delayUntilNextCheck(nowEpochMillis: Long): Long =
        if (lastAttemptFailed) {
            0L
        } else {
            CatalogFreshnessPolicy.delayUntilNextCheck(lastCheckedAt, nowEpochMillis)
        }

    fun afterSuccess(
        checkedAt: Long,
        catalogUpdatedAt: Long,
        recipeCount: Long,
        catalogVersion: String = "",
        catalogHash: String = "",
    ): CatalogFreshnessStatus {
        require(checkedAt > 0L)
        require(catalogUpdatedAt >= 0L)
        require(recipeCount >= 0L)
        require(
            (catalogVersion.isEmpty() && catalogHash.isEmpty()) ||
                catalogVersion == "sha256:$catalogHash",
        )
        return copy(
            lastCheckedAt = checkedAt,
            lastAttemptAt = checkedAt,
            catalogUpdatedAt = catalogUpdatedAt,
            recipeCount = recipeCount,
            catalogVersion = catalogVersion,
            catalogHash = catalogHash,
            lastFailureAt = 0L,
            consecutiveFailures = 0L,
            lastFailure = CatalogCheckFailure.NONE,
        )
    }

    fun afterFailure(
        attemptedAt: Long,
        failure: CatalogCheckFailure,
    ): CatalogFreshnessStatus {
        require(attemptedAt > 0L)
        require(failure != CatalogCheckFailure.NONE)
        val failures = if (consecutiveFailures == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            consecutiveFailures + 1L
        }
        return copy(
            lastAttemptAt = attemptedAt,
            lastFailureAt = attemptedAt,
            consecutiveFailures = failures,
            lastFailure = failure,
        )
    }

    val hasCatalogMetadata: Boolean
        get() = catalogUpdatedAt > 0L || recipeCount > 0L || catalogVersion.isNotEmpty()
}

/** Pure timing, checkpoint-validation, and retry decisions shared with JVM tests. */
object CatalogFreshnessPolicy {
    const val CHECK_INTERVAL_DAYS = 90L
    const val MAX_RECIPE_COUNT = 1_000_000L
    val checkIntervalMillis: Long = TimeUnit.DAYS.toMillis(CHECK_INTERVAL_DAYS)

    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val retryableFirestoreCodes = setOf(
        "ABORTED",
        "CANCELLED",
        "DEADLINE_EXCEEDED",
        "INTERNAL",
        "RESOURCE_EXHAUSTED",
        "UNAVAILABLE",
        "UNKNOWN",
    )

    fun isDue(lastCheckedAt: Long, nowEpochMillis: Long): Boolean =
        delayUntilNextCheck(lastCheckedAt, nowEpochMillis) == 0L

    fun delayUntilNextCheck(lastCheckedAt: Long, nowEpochMillis: Long): Long {
        if (lastCheckedAt <= 0L) return 0L
        // A wall-clock correction backwards must not trigger a burst of checks.
        if (nowEpochMillis <= lastCheckedAt) return checkIntervalMillis
        val elapsed = nowEpochMillis - lastCheckedAt
        return (checkIntervalMillis - elapsed).coerceAtLeast(0L)
    }

    fun shouldRetryFirestoreCode(codeName: String): Boolean = codeName in retryableFirestoreCodes

    fun validateCheckpoint(
        exists: Boolean,
        language: String?,
        recipeCount: Long?,
        lastImportedAt: Long?,
        catalogVersion: String?,
        catalogHash: String?,
    ): CatalogCheckpointValidation {
        if (!exists) return CatalogCheckpointValidation.Missing
        if (language != "el") {
            return CatalogCheckpointValidation.Malformed("language must be el")
        }
        if (recipeCount == null || recipeCount !in 1L..MAX_RECIPE_COUNT) {
            return CatalogCheckpointValidation.Malformed("recipeCount must be a positive integer")
        }
        if (lastImportedAt == null || lastImportedAt <= 0L) {
            return CatalogCheckpointValidation.Malformed("lastImportedAt must be a server timestamp")
        }
        if (catalogHash == null || !sha256Pattern.matches(catalogHash)) {
            return CatalogCheckpointValidation.Malformed("catalogHash must be lowercase SHA-256")
        }
        if (catalogVersion != "sha256:$catalogHash") {
            return CatalogCheckpointValidation.Malformed(
                "catalogVersion must match catalogHash",
            )
        }
        return CatalogCheckpointValidation.Valid(
            CatalogCheckpoint(
                catalogUpdatedAt = lastImportedAt,
                recipeCount = recipeCount,
                catalogVersion = catalogVersion,
                catalogHash = catalogHash,
            ),
        )
    }
}
