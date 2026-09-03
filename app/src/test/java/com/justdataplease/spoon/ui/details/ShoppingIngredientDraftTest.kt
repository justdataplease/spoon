package com.justdataplease.spoon.ui.details

import com.justdataplease.spoon.data.model.RecipeIngredient
import com.justdataplease.spoon.data.model.RecipeIngredientSection
import com.justdataplease.spoon.ui.model.RecipeDetailUi
import org.junit.Assert.assertEquals
import org.junit.Test

class ShoppingIngredientDraftTest {
    @Test
    fun `ingredient primary label keeps amount unit and name on one compact line`() {
        assertEquals(
            "250 γρ.  φακές",
            RecipeIngredient(title = " φακές ", quantity = " 250 ", unit = " γρ. ").primaryLabel(),
        )
        assertEquals("ελαιόλαδο", RecipeIngredient(title = "ελαιόλαδο").primaryLabel())
    }

    @Test
    fun `recipe ingredients become trimmed shopping drafts and blanks are skipped`() {
        val recipe = RecipeDetailUi(
            recipeId = "42",
            title = "Φακές",
            categoryKey = "legumes",
            rating10 = 9.0,
            prepMinutes = 10,
            stepCount = 2,
            imageUrl = "",
            sourceUrl = "https://example.test/42",
            sourceName = "Πηγή",
            tags = emptyList(),
            isFavorite = false,
            ingredientSections = listOf(
                RecipeIngredientSection(
                    ingredients = listOf(
                        RecipeIngredient(title = " φακές ", quantity = " 250 ", unit = " γρ. ", info = " ψιλές "),
                        RecipeIngredient(title = "   "),
                    ),
                ),
            ),
        )

        val result = recipe.shoppingIngredientDrafts()

        assertEquals(1, result.size)
        assertEquals("42", result.single().recipeId)
        assertEquals("Φακές", result.single().recipeTitle)
        assertEquals("φακές", result.single().title)
        assertEquals("250", result.single().quantity)
        assertEquals("γρ.", result.single().unit)
        assertEquals("ψιλές", result.single().info)
    }
}
