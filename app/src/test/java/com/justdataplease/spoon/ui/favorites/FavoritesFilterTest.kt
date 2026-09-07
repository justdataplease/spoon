package com.justdataplease.spoon.ui.favorites

import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.domain.ExploreRecipeFilter
import com.justdataplease.spoon.ui.explore.ExploreFiltersUi
import com.justdataplease.spoon.ui.model.EaseUi
import com.justdataplease.spoon.ui.toDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesFilterTest {
    private val favorite = Recipe(
        id = "favorite", title = "Κοτόπουλο με αυγά", category = "poultry",
        rating = 9.0, prepMinutes = 20, preparationCount = 1, stepCount = 4,
        sourceKey = "akis", dietLabels = listOf("Χωρίς γλουτένη"),
        mealTypeLabels = listOf("Κυρίως"), occasionLabels = listOf("Καθημερινό"),
        methodLabels = listOf("Φούρνος"), cuisineLabels = listOf("Ελληνική"),
        ingredientLabels = listOf("Αυγό"), quickRecipe = true,
    )
    private val filters = ExploreFiltersUi(
        categoryKey = "chicken", ease = EaseUi.EASY, minRating10 = 8, maxPrepMinutes = 30,
        sourceKeys = setOf("akis"), diet = "Χωρίς γλουτένη", mealType = "Κυρίως",
        occasion = "Καθημερινό", method = "Φούρνος", cuisine = "Ελληνική",
        ingredient = "Αυγά", quickOnly = true,
    )

    @Test
    fun `favorites use all search facets together and accent insensitive Greek queries`() {
        val candidates = listOf(
            favorite, favorite.copy(id = "wrong-source", sourceKey = "argiro"),
            favorite.copy(id = "slow", prepMinutes = 60),
            favorite.copy(id = "wrong-category", category = "meat"),
            favorite.copy(id = "wrong-ingredient", ingredientLabels = listOf("Ρύζι")),
            favorite.copy(id = "wrong-diet", dietLabels = emptyList()),
            favorite.copy(id = "wrong-meal", mealTypeLabels = emptyList()),
            favorite.copy(id = "wrong-occasion", occasionLabels = emptyList()),
            favorite.copy(id = "wrong-method", methodLabels = emptyList()),
            favorite.copy(id = "wrong-cuisine", cuisineLabels = emptyList()),
            favorite.copy(id = "not-quick", quickRecipe = false),
            favorite.copy(id = "low-rating", rating = 8.0),
            favorite.copy(id = "hard", preparationCount = 3, stepCount = 12),
        )
        assertEquals(listOf(favorite), ExploreRecipeFilter.filter(candidates, filters.toDomain("κοτοπουλο")))
    }

    @Test
    fun `clearing search restores favorites and global food preferences still apply`() {
        assertTrue(ExploreRecipeFilter.filter(listOf(favorite), filters.toDomain("ανυπαρκτο")).isEmpty())
        assertEquals(listOf(favorite), ExploreRecipeFilter.filter(listOf(favorite), ExploreFiltersUi().toDomain("")))
        assertTrue(ExploreRecipeFilter.filter(listOf(favorite), filters.toDomain(""),
            MealPreferenceSettings(excludedIngredientTerms = setOf("Αυγό"))).isEmpty())
    }
}
