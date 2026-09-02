package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.RecipeFilters
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** A stable weekly rhythm; regenerating the same week always assigns the same categories. */
object WeeklyPlanDefaults {
    private val categoriesByDay = mapOf(
        DayOfWeek.MONDAY to MealCategory.LEGUMES,
        DayOfWeek.TUESDAY to MealCategory.POULTRY,
        DayOfWeek.WEDNESDAY to MealCategory.VEGETABLES,
        DayOfWeek.THURSDAY to MealCategory.MEAT,
        DayOfWeek.FRIDAY to MealCategory.FISH,
        DayOfWeek.SATURDAY to MealCategory.STREET_FOOD,
        DayOfWeek.SUNDAY to MealCategory.PASTA_RICE,
    )

    fun weekStart(containingDate: LocalDate): LocalDate =
        containingDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun dates(containingDate: LocalDate): List<LocalDate> {
        val monday = weekStart(containingDate)
        return (0L..6L).map(monday::plusDays)
    }

    fun categoryFor(dayOfWeek: DayOfWeek): MealCategory =
        checkNotNull(categoriesByDay[dayOfWeek])

    fun filtersFor(date: LocalDate): RecipeFilters =
        RecipeFilters(category = categoryFor(date.dayOfWeek).key)
}

