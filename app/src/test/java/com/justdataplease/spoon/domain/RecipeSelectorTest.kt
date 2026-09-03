package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.model.EaseLevel
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeSelectorTest {
    private val selector = RecipeSelector()

    private val recipes = listOf(
        Recipe("easy-fish", "Easy fish", MealCategory.FISH.key, 9.2, 20, 4),
        Recipe("slow-fish", "Slow fish", MealCategory.FISH.key, 9.5, 70, 4),
        Recipe("medium-fish", "Medium fish", MealCategory.FISH.key, 8.8, 25, 7),
        Recipe("easy-meat", "Easy meat", MealCategory.MEAT.key, 9.7, 15, 4),
        Recipe("unknown-time", "Unknown time", MealCategory.FISH.key, 9.9, 0, 4),
    )

    @Test
    fun `all filters are applied together`() {
        val candidates = selector.candidates(
            recipes = recipes,
            filters = RecipeFilters(
                category = MealCategory.FISH.key,
                easeLevel = EaseLevel.EASY.key,
                minRating = 9.0,
                maxPrepMinutes = 30,
            ),
        )

        assertEquals(listOf("easy-fish"), candidates.map(Recipe::id))
    }

    @Test
    fun `current choice is always excluded`() {
        val selected = selector.select(
            recipes = listOf(recipes.first()),
            filters = RecipeFilters(category = MealCategory.FISH.key),
            excludingRecipeId = "easy-fish",
            random = Random(7),
        )

        assertNull(selected)
    }

    @Test
    fun `inactive recipes are never candidates`() {
        val candidates = selector.candidates(
            recipes = listOf(
                Recipe(
                    id = "inactive",
                    title = "Inactive",
                    category = MealCategory.FISH.key,
                    active = false,
                ),
                Recipe(
                    id = "active",
                    title = "Active",
                    category = MealCategory.FISH.key,
                ),
            ),
            filters = RecipeFilters(category = MealCategory.FISH.key),
        )

        assertEquals(listOf("active"), candidates.map(Recipe::id))
    }

    @Test
    fun `impossible constraints do not fall back to a looser query`() {
        val selected = selector.select(
            recipes = recipes,
            filters = RecipeFilters(
                category = MealCategory.FISH.key,
                easeLevel = EaseLevel.INVOLVED.key,
                minRating = 10.0,
                maxPrepMinutes = 10,
            ),
        )

        assertNull(selected)
    }

    @Test
    fun `rating threshold is strict at seven`() {
        val candidates = selector.candidates(
            recipes = listOf(
                Recipe("equal", "Equal", MealCategory.FISH.key, 7.0, 20, 4),
                Recipe("above", "Above", MealCategory.FISH.key, 7.1, 20, 4),
            ),
            filters = RecipeFilters(
                category = MealCategory.FISH.key,
                minRating = 7.0,
            ),
        )

        assertEquals(listOf("above"), candidates.map(Recipe::id))
    }

    @Test
    fun `unknown preparation time does not pass a time cap`() {
        val candidates = selector.candidates(
            recipes = recipes,
            filters = RecipeFilters(
                category = MealCategory.FISH.key,
                maxPrepMinutes = 30,
            ),
        )

        assertEquals(listOf("easy-fish", "medium-fish"), candidates.map(Recipe::id))
    }

    @Test
    fun `invalid filter value yields no candidates`() {
        val candidates = selector.candidates(
            recipes = recipes,
            filters = RecipeFilters(
                category = MealCategory.FISH.key,
                minRating = 10.1,
            ),
        )

        assertEquals(emptyList<Recipe>(), candidates)
    }

    @Test
    fun exact_match_uses_the_same_combined_filter_pipeline() {
        val recipe = Recipe(
            id = "fish",
            category = MealCategory.FISH.key,
            rating = 8.5,
            prepMinutes = 25,
            stepCount = 7,
            preparationCount = 2,
        )
        val matching = RecipeFilters(
            category = MealCategory.FISH.key,
            easeLevel = EaseLevel.MODERATE.key,
            minRating = 8.0,
            maxPrepMinutes = 30,
        )

        assertEquals(true, selector.matches(recipe, matching))
        assertEquals(false, selector.matches(recipe, matching.copy(category = MealCategory.MEAT.key)))
        assertEquals(false, selector.matches(recipe, matching.copy(minRating = 9.0)))
        assertEquals(false, selector.matches(recipe, matching.copy(maxPrepMinutes = 20)))
        assertEquals(false, selector.matches(recipe, matching.copy(easeLevel = EaseLevel.EASY.key)))
    }
}
