package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.DayMealPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePersonalProjectionTest {
    @Test
    fun `completion creates matching plan and immutable history changes without a read`() {
        val plan = plan(date = "2026-09-04", recipeId = "akis_1", updatedAt = 10L)

        val result = projectMealCompletion(
            plans = listOf(plan),
            storedHistory = emptyList(),
            date = plan.date,
            completed = true,
            nowEpochMillis = 20L,
            newCompletionEventId = "cooked_0123456789abcdef0123456789abcdef",
        )

        assertTrue(result.changedPlan?.completed == true)
        assertEquals(result.changedPlan?.completionEventId, result.historyToCreate?.id)
        assertEquals(result.changedPlan?.recipeId, result.historyToCreate?.recipeId)
        assertEquals(result.changedPlan?.updatedAtEpochMillis, result.historyToCreate?.completedAtEpochMillis)
        assertEquals(listOf(result.historyToCreate), result.storedHistory)
        assertTrue(result.historyIdsToDelete.isEmpty())
    }

    @Test
    fun `undo queues deletion for explicit event even before history listener catches up`() {
        val eventId = "cooked_0123456789abcdef0123456789abcdef"
        val plan = plan(
            date = "2026-09-04",
            recipeId = "akis_1",
            completed = true,
            completionEventId = eventId,
            updatedAt = 20L,
        )

        val result = projectMealCompletion(
            plans = listOf(plan),
            storedHistory = emptyList(),
            date = plan.date,
            completed = false,
            nowEpochMillis = 30L,
            newCompletionEventId = "unused",
        )

        assertFalse(result.changedPlan!!.completed)
        assertEquals("", result.changedPlan.completionEventId)
        assertEquals(setOf(eventId), result.historyIdsToDelete)
        assertTrue(result.storedHistory.isEmpty())
    }

    @Test
    fun `deleting active history also clears current plan while preserving older events`() {
        val active = CookedMeal(
            id = "cooked_0123456789abcdef0123456789abcdef",
            date = "2026-09-04",
            recipeId = "akis_1",
            recipeTitle = "Συνταγή",
            completedAtEpochMillis = 20L,
        )
        val older = active.copy(
            id = "cooked_fedcba9876543210fedcba9876543210",
            completedAtEpochMillis = 5L,
        )
        val plan = plan(
            date = active.date,
            recipeId = active.recipeId,
            completed = true,
            completionEventId = active.id,
            updatedAt = active.completedAtEpochMillis,
        )

        val result = projectHistoryDeletion(listOf(plan), listOf(active, older), active.id, 30L)

        assertFalse(result.changedPlan!!.completed)
        assertEquals(active.id, result.historyIdToDelete)
        assertEquals(listOf(older), result.storedHistory)
    }

    @Test
    fun `legacy synthesized history clears plan but does not delete unknown document`() {
        val plan = plan(
            date = "2026-09-04",
            recipeId = "akis_1",
            completed = true,
            completionEventId = "",
            updatedAt = 20L,
        )

        val result = projectHistoryDeletion(listOf(plan), emptyList(), plan.date, 30L)

        assertFalse(result.changedPlan!!.completed)
        assertNull(result.historyIdToDelete)
    }

    @Test
    fun `already matching completion is a no-op`() {
        val plan = plan(
            date = "2026-09-04",
            recipeId = "akis_1",
            completed = true,
            completionEventId = "cooked_0123456789abcdef0123456789abcdef",
            updatedAt = 20L,
        )

        val result = projectMealCompletion(
            plans = listOf(plan),
            storedHistory = emptyList(),
            date = plan.date,
            completed = true,
            nowEpochMillis = 30L,
            newCompletionEventId = "unused",
        )

        assertNull(result.changedPlan)
        assertEquals(listOf(plan), result.plans)
    }

    private fun plan(
        date: String,
        recipeId: String,
        completed: Boolean = false,
        completionEventId: String = "",
        updatedAt: Long,
    ) = DayMealPlan(
        id = date,
        date = date,
        category = "fish",
        recipeId = recipeId,
        recipeTitle = "Συνταγή",
        completed = completed,
        completionEventId = completionEventId,
        updatedAtEpochMillis = updatedAt,
    )
}
