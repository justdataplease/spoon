package com.justdataplease.spoon.ui.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class UiEaseMappingTest {
    @Test
    fun `all recipe projections use preparation and step workload boundaries`() {
        val cases = listOf(
            Workload(preparations = 0, steps = 0, expected = EaseUi.UNKNOWN),
            Workload(preparations = 0, steps = 1, expected = EaseUi.EASY),
            Workload(preparations = 1, steps = 5, expected = EaseUi.EASY),
            Workload(preparations = 2, steps = 4, expected = EaseUi.MEDIUM),
            Workload(preparations = 1, steps = 6, expected = EaseUi.MEDIUM),
            Workload(preparations = 3, steps = 3, expected = EaseUi.HARD),
            Workload(preparations = 0, steps = 10, expected = EaseUi.HARD),
        )

        cases.forEach { workload ->
            val day = DayPlanUi(
                date = LocalDate.of(2026, 9, 3),
                categoryKey = "fish",
                stepCount = workload.steps,
                preparationCount = workload.preparations,
            )
            val favorite = FavoriteUi(
                recipeId = "recipe",
                title = "Συνταγή",
                categoryKey = "fish",
                rating10 = 8.0,
                prepMinutes = 20,
                totalMinutes = 30,
                stepCount = workload.steps,
                preparationCount = workload.preparations,
                sourceUrl = "https://example.com/recipe",
            )
            val detail = RecipeDetailUi(
                recipeId = "recipe",
                title = "Συνταγή",
                categoryKey = "fish",
                rating10 = 8.0,
                prepMinutes = 20,
                stepCount = workload.steps,
                preparationCount = workload.preparations,
                imageUrl = "",
                sourceUrl = "https://example.com/recipe",
                sourceName = "Πηγή",
                tags = emptyList(),
                isFavorite = false,
            )

            val context = "preparations=${workload.preparations}, steps=${workload.steps}"
            assertEquals(context, workload.expected, day.ease)
            assertEquals(context, workload.expected, favorite.ease)
            assertEquals(context, workload.expected, detail.ease)
        }
    }

    @Test
    fun `ease labels explain both workload dimensions`() {
        assertEquals("Άγνωστη", EaseUi.UNKNOWN.greekLabel)
        assertEquals("Ευκολάκι", EaseUi.EASY.greekLabel)
        assertEquals("έως 1 παρασκευή και 1–5 βήματα", EaseUi.EASY.detail)
        assertEquals("2 παρασκευές ή 6–9 βήματα", EaseUi.MEDIUM.detail)
        assertEquals("3+ παρασκευές ή 10+ βήματα", EaseUi.HARD.detail)
    }

    @Test
    fun `unknown ease is display only and not a selectable filter`() {
        assertEquals(
            listOf(EaseUi.ANY, EaseUi.EASY, EaseUi.MEDIUM, EaseUi.HARD),
            SelectableEaseOptions,
        )
    }

    @Test
    fun `step count is never presented as preparation count`() {
        val day = DayPlanUi(
            date = LocalDate.of(2026, 9, 3),
            categoryKey = "fish",
            stepCount = 7,
            preparationCount = 0,
        )
        val favorite = FavoriteUi(
            recipeId = "recipe",
            title = "Συνταγή",
            categoryKey = "fish",
            rating10 = 8.0,
            prepMinutes = 20,
            totalMinutes = 30,
            stepCount = 7,
            preparationCount = 0,
            sourceUrl = "https://example.com/recipe",
        )
        val detail = RecipeDetailUi(
            recipeId = "recipe",
            title = "Συνταγή",
            categoryKey = "fish",
            rating10 = 8.0,
            prepMinutes = 20,
            stepCount = 7,
            preparationCount = 0,
            imageUrl = "",
            sourceUrl = "https://example.com/recipe",
            sourceName = "Πηγή",
            tags = emptyList(),
            isFavorite = false,
        )

        assertEquals(0, day.displayPreparationCount)
        assertEquals(0, favorite.displayPreparationCount)
        assertEquals(0, detail.displayPreparationCount)
        assertEquals(EaseUi.MEDIUM, detail.ease)
    }

    @Test
    fun `unknown category labels are Greek in every recipe projection`() {
        val day = DayPlanUi(
            date = LocalDate.of(2026, 9, 3),
            categoryKey = "legacy_key",
        )
        val favorite = FavoriteUi(
            recipeId = "recipe",
            title = "Συνταγή",
            categoryKey = "legacy_key",
            rating10 = 8.0,
            prepMinutes = 20,
            totalMinutes = 30,
            stepCount = 4,
            sourceUrl = "https://example.com/recipe",
        )
        val detail = RecipeDetailUi(
            recipeId = "recipe",
            title = "Συνταγή",
            categoryKey = "legacy_key",
            rating10 = 8.0,
            prepMinutes = 20,
            stepCount = 4,
            imageUrl = "",
            sourceUrl = "https://example.com/recipe",
            sourceName = "Πηγή",
            tags = emptyList(),
            isFavorite = false,
        )

        assertEquals("Άλλο", day.category.label)
        assertEquals("Άλλο", favorite.category.label)
        assertEquals("Άλλο", detail.category.label)
        assertEquals(
            "Χωρίς κατηγορία",
            day.copy(categoryKey = "").category.label,
        )
    }

    @Test
    fun `authoritative dessert drinks and other categories are selectable in Greek`() {
        assertEquals(
            CategoryUi("dessert", "Γλυκά", "🍰"),
            AvailableCategories.single { it.key == "dessert" },
        )
        assertEquals(
            CategoryUi("drinks", "Ροφήματα", "🥤"),
            AvailableCategories.single { it.key == "drinks" },
        )
        assertEquals(
            CategoryUi("other", "Άλλο", "🍽️"),
            AvailableCategories.single { it.key == "other" },
        )
        assertEquals("Γλυκά", DayPlanUi(
            date = LocalDate.of(2026, 9, 3),
            categoryKey = "dessert",
        ).category.label)
        assertEquals("Ροφήματα", DayPlanUi(
            date = LocalDate.of(2026, 9, 3),
            categoryKey = "drinks",
        ).category.label)
        assertEquals("Άλλο", DayPlanUi(
            date = LocalDate.of(2026, 9, 3),
            categoryKey = "other",
        ).category.label)
    }

    private data class Workload(
        val preparations: Int,
        val steps: Int,
        val expected: EaseUi,
    )
}
