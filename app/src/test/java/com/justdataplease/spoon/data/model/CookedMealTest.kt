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

    @Test
    fun stored_events_survive_reroll_and_multiple_recipes_on_the_same_date() {
        val first = CookedMeal(
            id = "cooked_20260903_3000",
            date = "2026-09-03",
            recipeId = "42",
            recipeTitle = "Φακές",
            completedAtEpochMillis = 3_000,
        )
        val second = CookedMeal(
            id = "cooked_20260903_5000",
            date = "2026-09-03",
            recipeId = "43",
            recipeTitle = "Ψάρι",
            completedAtEpochMillis = 5_000,
        )

        val result = mergeCookedHistory(
            plans = listOf(
                DayMealPlan(
                    date = "2026-09-03",
                    recipeId = "44",
                    recipeTitle = "Νέα πρόταση",
                    completed = false,
                    updatedAtEpochMillis = 7_000,
                ),
            ),
            storedHistory = listOf(first, second),
        )

        assertEquals(listOf(second, first), result)
    }

    @Test
    fun active_completion_matches_recipe_timestamp_and_explicit_event_id() {
        val plan = DayMealPlan(
            date = "2026-09-03",
            recipeId = "42",
            recipeTitle = "Φακές",
            completed = true,
            completionEventId = "cooked_20260903_3000",
            updatedAtEpochMillis = 3_000,
        )
        val matching = plan.toCookedMeal()

        assertEquals(true, matching.matchesActiveCompletion(plan))
        assertEquals(
            false,
            matching.copy(id = "cooked_20260903_3001").matchesActiveCompletion(plan),
        )
        assertEquals(
            false,
            matching.copy(recipeId = "replacement").matchesActiveCompletion(plan),
        )
    }
}
