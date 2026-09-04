package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeIngredient
import com.justdataplease.spoon.data.model.RecipeIngredientSection
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPreferenceFilterTest {
    private val veganRecipe = Recipe(
        id = "vegan",
        category = MealCategory.VEGETABLES.key,
        dietLabels = listOf("Αυστηρά χορτοφαγική (Vegan)"),
        ingredientSections = listOf(
            RecipeIngredientSection(
                ingredients = listOf(
                    RecipeIngredient(title = "Γάλα καρύδας", info = "χωρίς ζάχαρη"),
                ),
            ),
        ),
    )

    @Test
    fun `empty preferences include everything`() {
        assertTrue(veganRecipe.matchesMealPreferences(MealPreferenceSettings()))
    }

    @Test
    fun `vegan aliases are accepted and excluded categories still win`() {
        assertTrue(veganRecipe.matchesMealPreferences(MealPreferenceSettings(veganOnly = true)))
        assertFalse(
            veganRecipe.matchesMealPreferences(
                MealPreferenceSettings(
                    excludedCategories = setOf(MealCategory.VEGETABLES.key),
                    veganOnly = true,
                ),
            ),
        )
    }

    @Test
    fun `ingredient phrase matching ignores Greek accents case punctuation and final sigma`() {
        assertFalse(
            veganRecipe.matchesMealPreferences(
                MealPreferenceSettings(excludedIngredientTerms = setOf("ΓΑΛΑ-ΚΑΡΥΔΑΣ")),
            ),
        )
        assertTrue(
            veganRecipe.matchesMealPreferences(
                MealPreferenceSettings(excludedIngredientTerms = setOf("γάλα αμυγδάλου")),
            ),
        )
    }

    @Test
    fun `untagged recipe is not assumed vegan`() {
        assertFalse(
            veganRecipe.copy(dietLabels = emptyList())
                .matchesMealPreferences(MealPreferenceSettings(veganOnly = true)),
        )
    }
}
