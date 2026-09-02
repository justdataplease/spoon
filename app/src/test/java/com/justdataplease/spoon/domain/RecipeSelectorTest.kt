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
    fun `minimum rating supports an exact ten`() {
        val candidates = selector.candidates(
            recipes = listOf(
                Recipe("perfect", "Perfect", MealCategory.FISH.key, 10.0, 20, 4),
                Recipe("almost", "Almost", MealCategory.FISH.key, 9.9, 20, 4),
            ),
            filters = RecipeFilters(
                category = MealCategory.FISH.key,
                minRating = 10.0,
            ),
        )

        assertEquals(listOf("perfect"), candidates.map(Recipe::id))
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
}
