package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.DemoRecipeCatalog
import com.justdataplease.spoon.data.model.EaseLevel
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyPlanDefaultsTest {
    @Test
    fun `week always begins on Monday and contains seven dates`() {
        val dates = WeeklyPlanDefaults.dates(LocalDate.of(2026, 9, 3))

        assertEquals(LocalDate.of(2026, 8, 31), dates.first())
        assertEquals(LocalDate.of(2026, 9, 6), dates.last())
        assertEquals(7, dates.size)
    }

    @Test
    fun `weekly category rhythm is deterministic`() {
        val expected = listOf(
            MealCategory.LEGUMES,
            MealCategory.POULTRY,
            MealCategory.VEGETABLES,
            MealCategory.MEAT,
            MealCategory.FISH,
            MealCategory.STREET_FOOD,
            MealCategory.PASTA_RICE,
        )

        assertEquals(
            expected,
            DayOfWeek.entries.map(WeeklyPlanDefaults::categoryFor),
        )
    }

    @Test
    fun `demo catalog supports alternatives for every default category`() {
        val counts = DemoRecipeCatalog.recipes.groupingBy(Recipe::category).eachCount()

        DayOfWeek.entries.map(WeeklyPlanDefaults::categoryFor).forEach { category ->
            assertTrue("Missing alternatives for ${category.key}", (counts[category.key] ?: 0) >= 2)
        }
    }

    @Test
    fun `catalog categories include dessert drinks and other without changing the weekly rhythm`() {
        assertEquals(MealCategory.DESSERT, MealCategory.fromKey("dessert"))
        assertEquals("Γλυκά", MealCategory.DESSERT.greekLabel)
        assertEquals(MealCategory.OTHER, MealCategory.fromKey("other"))
        assertEquals("Άλλο", MealCategory.OTHER.greekLabel)
        assertEquals(MealCategory.DRINKS, MealCategory.fromKey("drinks"))
        assertEquals("Ροφήματα", MealCategory.DRINKS.greekLabel)
        assertTrue(DayOfWeek.entries.map(WeeklyPlanDefaults::categoryFor).none {
            it == MealCategory.DESSERT || it == MealCategory.DRINKS ||
                it == MealCategory.OTHER
        })
    }

    @Test
    fun `demo catalog exposes Greek-facing labels`() {
        assertTrue(DemoRecipeCatalog.recipes.none { "Burger" in it.title })
        assertTrue(DemoRecipeCatalog.recipes.any { "Μπέργκερ" in it.title })
        assertTrue(DemoRecipeCatalog.recipes.all { it.sourceName == "Δείγμα εφαρμογής" })
    }

    @Test
    fun `ease is derived from preparation step count`() {
        assertEquals(EaseLevel.UNKNOWN, Recipe(stepCount = 0).easeLevel)
        assertEquals(EaseLevel.EASY, Recipe(stepCount = 5).easeLevel)
        assertEquals(EaseLevel.MODERATE, Recipe(stepCount = 6).easeLevel)
        assertEquals(EaseLevel.INVOLVED, Recipe(stepCount = 10).easeLevel)
    }

    @Test
    fun `preparation count participates in derived ease`() {
        assertEquals(EaseLevel.INVOLVED, Recipe(stepCount = 4, preparationCount = 3).easeLevel)
        assertEquals(EaseLevel.MODERATE, Recipe(stepCount = 4, preparationCount = 2).easeLevel)
        assertEquals(EaseLevel.EASY, Recipe(stepCount = 4, preparationCount = 1).easeLevel)
    }
}
