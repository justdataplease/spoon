package com.justdataplease.spoon.sync

import android.content.Context
import android.content.SharedPreferences

/** Private metadata-only storage. No recipe content, account id, or browsing data is retained. */
internal class CatalogFreshnessStore internal constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    fun read(): CatalogFreshnessStatus {
        val lastCheckedAt = preferences.safeLong(KEY_LAST_CHECKED_AT)
        return CatalogFreshnessStatus(
            lastCheckedAt = lastCheckedAt,
            // Migrate the original success-only store without making a good
            // historical check look like a failed or never-attempted check.
            lastAttemptAt = preferences.safeLong(KEY_LAST_ATTEMPT_AT)
                .takeIf { it > 0L }
                ?: lastCheckedAt,
            catalogUpdatedAt = preferences.safeLong(KEY_CATALOG_UPDATED_AT),
            recipeCount = preferences.safeLong(KEY_RECIPE_COUNT),
            catalogVersion = preferences.safeString(KEY_CATALOG_VERSION),
            catalogHash = preferences.safeString(KEY_CATALOG_HASH),
            lastFailureAt = preferences.safeLong(KEY_LAST_FAILURE_AT),
            consecutiveFailures = preferences.safeLong(KEY_CONSECUTIVE_FAILURES),
            lastFailure = preferences.safeFailure(),
        )
    }

    fun recordSuccess(
        checkedAt: Long,
        catalogUpdatedAt: Long,
        recipeCount: Long,
        catalogVersion: String = "",
        catalogHash: String = "",
    ): Boolean = persist(
        read().afterSuccess(
            checkedAt = checkedAt,
            catalogUpdatedAt = catalogUpdatedAt,
            recipeCount = recipeCount,
            catalogVersion = catalogVersion,
            catalogHash = catalogHash,
        ),
    )

    fun recordFailure(
        attemptedAt: Long,
        failure: CatalogCheckFailure,
    ): Boolean = persist(read().afterFailure(attemptedAt, failure))

    private fun persist(status: CatalogFreshnessStatus): Boolean = preferences.edit()
        .putLong(KEY_LAST_CHECKED_AT, status.lastCheckedAt)
        .putLong(KEY_LAST_ATTEMPT_AT, status.lastAttemptAt)
        .putLong(KEY_CATALOG_UPDATED_AT, status.catalogUpdatedAt)
        .putLong(KEY_RECIPE_COUNT, status.recipeCount)
        .putString(KEY_CATALOG_VERSION, status.catalogVersion)
        .putString(KEY_CATALOG_HASH, status.catalogHash)
        .putLong(KEY_LAST_FAILURE_AT, status.lastFailureAt)
        .putLong(KEY_CONSECUTIVE_FAILURES, status.consecutiveFailures)
        .putString(KEY_LAST_FAILURE, status.lastFailure.name)
        .commit()

    private fun SharedPreferences.safeLong(key: String): Long =
        runCatching { getLong(key, 0L) }.getOrDefault(0L).coerceAtLeast(0L)

    private fun SharedPreferences.safeString(key: String): String =
        runCatching { getString(key, "") }.getOrNull().orEmpty()

    private fun SharedPreferences.safeFailure(): CatalogCheckFailure {
        val stored = runCatching { getString(KEY_LAST_FAILURE, null) }.getOrNull()
        return CatalogCheckFailure.entries.firstOrNull { it.name == stored }
            ?: CatalogCheckFailure.NONE
    }

    companion object {
        private const val PREFERENCES_NAME = "spoon_catalog_freshness"
        private const val KEY_LAST_CHECKED_AT = "lastCheckedAt"
        private const val KEY_LAST_ATTEMPT_AT = "lastAttemptAt"
        private const val KEY_CATALOG_UPDATED_AT = "catalogUpdatedAt"
        private const val KEY_RECIPE_COUNT = "recipeCount"
        private const val KEY_CATALOG_VERSION = "catalogVersion"
        private const val KEY_CATALOG_HASH = "catalogHash"
        private const val KEY_LAST_FAILURE_AT = "lastFailureAt"
        private const val KEY_CONSECUTIVE_FAILURES = "consecutiveFailures"
        private const val KEY_LAST_FAILURE = "lastFailure"
    }
}
