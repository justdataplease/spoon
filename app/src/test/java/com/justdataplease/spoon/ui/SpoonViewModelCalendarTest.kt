package com.justdataplease.spoon.ui

import com.justdataplease.spoon.ui.model.CalendarMealUi
import com.justdataplease.spoon.ui.model.DayPlanUi
import com.justdataplease.spoon.ui.history.HistoryEntryUi
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoonViewModelCalendarTest {
    private val date = LocalDate.of(2026, 9, 4)

    @Test
    fun `completed calendar date outside selected week toggles to incomplete`() {
        val nextCompleted = !completionStateForDate(
            date = date,
            calendarMeals = listOf(calendarMeal(isCompleted = true)),
            weekPlans = emptyList(),
        )

        assertFalse(nextCompleted)
    }

    @Test
    fun `calendar completion wins when month and week snapshots temporarily differ`() {
        val completed = completionStateForDate(
            date = date,
            calendarMeals = listOf(calendarMeal(isCompleted = false)),
            weekPlans = listOf(
                DayPlanUi(
                    date = date,
                    recipeId = "fish-lemon",
                    recipeTitle = "Ψάρι λεμονάτο",
                    categoryKey = "fish",
                    isCompleted = true,
                ),
            ),
        )

        assertFalse(completed)
    }

    @Test
    fun `week completion is fallback when date is absent from calendar snapshot`() {
        val completed = completionStateForDate(
            date = date,
            calendarMeals = emptyList(),
            weekPlans = listOf(
                DayPlanUi(
                    date = date,
                    recipeId = "fish-lemon",
                    recipeTitle = "Ψάρι λεμονάτο",
                    categoryKey = "fish",
                    isCompleted = true,
                ),
            ),
        )

        assertTrue(completed)
    }

    @Test
    fun `history marks a date completed outside the visible calendar and week`() {
        val completed = completionStateForDate(
            date = date,
            calendarMeals = emptyList(),
            weekPlans = emptyList(),
            historyEntries = listOf(
                HistoryEntryUi(
                    id = date.toString(),
                    date = date,
                    recipeId = "fish-lemon",
                    title = "Recipe",
                    categoryLabel = "Fish",
                    categoryEmoji = "",
                ),
            ),
        )

        assertTrue(completed)
    }

    private fun calendarMeal(isCompleted: Boolean) = CalendarMealUi(
        date = date,
        recipeId = "fish-lemon",
        categoryKey = "fish",
        recipeTitle = "Ψάρι λεμονάτο",
        isCompleted = isCompleted,
    )
}
