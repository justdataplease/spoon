package com.justdataplease.spoon.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CookedMealTest {
    @Test
    fun legacy_completed_plans_appear_without_a_stored_history_document() {
        val result = mergeCookedHistory(
            plans = listOf(
                DayMealPlan(
                    date = "2026-09-03",
                    recipeId = "42",
                    recipeTitle = "Φακές",
                    completed = true,
                    updatedAtEpochMillis = 3_000,
                ),
                DayMealPlan(
                    date = "2026-09-04",
                    recipeId = "43",
                    recipeTitle = "Ψάρι",
                    completed = false,
                    updatedAtEpochMillis = 4_000,
                ),
            ),
            storedHistory = emptyList(),
        )

        assertEquals(listOf("2026-09-03"), result.map(CookedMeal::date))
        assertEquals("Φακές", result.single().recipeTitle)
    }

    @Test
    fun stored_completion_timestamp_is_preserved_when_snapshot_matches_plan() {
        val result = mergeCookedHistory(
            plans = listOf(
                DayMealPlan(
                    date = "2026-09-03",
                    recipeId = "42",
                    recipeTitle = "Φακές",
                    completed = true,
                    updatedAtEpochMillis = 3_000,
                ),
            ),
            storedHistory = listOf(
                CookedMeal(
                    date = "2026-09-03",
                    recipeId = "42",
                    recipeTitle = "Φακές",
                    completedAtEpochMillis = 3_000,
                ),
            ),
        )

        assertEquals(3_000L, result.single().completedAtEpochMillis)
    }
}
