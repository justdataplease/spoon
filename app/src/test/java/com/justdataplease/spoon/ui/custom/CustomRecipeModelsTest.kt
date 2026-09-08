package com.justdataplease.spoon.ui.custom

import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.RecipeIngredient
import com.justdataplease.spoon.data.model.RecipeIngredientSection
import com.justdataplease.spoon.data.model.RecipeMethodSection
import com.justdataplease.spoon.ui.model.AvailableCategories
import com.justdataplease.spoon.ui.model.RecipeDetailUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CustomRecipeModelsTest {
    @Test
    fun `valid draft has no validation error`() {
        val draft = CustomRecipeDraftUi(
            title = "Φακές της γιαγιάς",
            categoryKey = "legumes",
            ingredients = listOf(CustomIngredientDraftUi("φακές", "250", "γρ.")),
            steps = listOf("Βράζουμε τις φακές."),
        )
        assertNull(draft.validationMessage())
    }

    @Test
    fun `draft requires title ingredient and step`() {
        val base = CustomRecipeDraftUi(
            title = "",
            categoryKey = "legumes",
            ingredients = emptyList(),
            steps = emptyList(),
        )
        assertEquals("Γράψε έναν τίτλο για τη συνταγή.", base.validationMessage())
        assertEquals(
            "Πρόσθεσε τουλάχιστον ένα υλικό.",
            base.copy(title = "Φακές").validationMessage(),
        )
        assertEquals(
            "Πρόσθεσε τουλάχιστον ένα βήμα εκτέλεσης.",
            base.copy(title = "Φακές", ingredients = listOf(CustomIngredientDraftUi("φακές"))).validationMessage(),
        )
    }

    @Test
    fun `every shared picker category persists as its canonical planner key`() {
        val canonicalKeys = mapOf(
            "legumes" to MealCategory.LEGUMES.key,
            "fish" to MealCategory.FISH.key,
            "meat" to MealCategory.MEAT.key,
            "chicken" to MealCategory.POULTRY.key,
            "pasta" to MealCategory.PASTA_RICE.key,
            "vegetarian" to MealCategory.VEGETABLES.key,
            "dirty" to MealCategory.STREET_FOOD.key,
            "dessert" to MealCategory.DESSERT.key,
            "drinks" to MealCategory.DRINKS.key,
            "other" to MealCategory.OTHER.key,
        )

        assertEquals(canonicalKeys.keys, AvailableCategories.map { it.key }.toSet())
        canonicalKeys.forEach { (uiKey, domainKey) ->
            val draft = validDraft(categoryKey = uiKey)
            assertNull(draft.validationMessage())
            assertEquals(domainKey, draft.toDomainCustomRecipe().category)
        }
    }

    @Test
    fun `unknown and any categories are rejected before persistence`() {
        assertNotNull(validDraft(categoryKey = "unknown").validationMessage())
        assertNotNull(validDraft(categoryKey = MealCategory.ANY.key).validationMessage())
    }

    @Test
    fun `editing roundtrip retains identity fields and selected category`() {
        val details = RecipeDetailUi(
            recipeId = "custom_01234567-89ab-4def-8123-456789abcdef",
            title = "Κοτόπουλο φούρνου",
            categoryKey = "chicken",
            rating10 = 0.0,
            prepMinutes = 15,
            cookMinutes = 50,
            stepCount = 1,
            imageUrl = "data:image/jpeg;base64,YWJj",
            sourceUrl = "",
            sourceName = "Προσωπική συνταγή",
            tags = emptyList(),
            isFavorite = false,
            description = "Οικογενειακή συνταγή",
            servings = "4",
            ingredientSections = listOf(
                RecipeIngredientSection(
                    ingredients = listOf(RecipeIngredient("Κοτόπουλο", "κιλό", "1")),
                ),
            ),
            methodSections = listOf(RecipeMethodSection(steps = listOf("Ψήνουμε."))),
        )

        val edited = details.toCustomRecipeDraftUi().copy(categoryKey = "dirty")
        val persisted = edited.toDomainCustomRecipe()

        assertEquals(details.recipeId, persisted.id)
        assertEquals(MealCategory.STREET_FOOD.key, persisted.category)
        assertEquals(details.title, persisted.title)
        assertEquals(details.imageUrl, persisted.photoDataUri)
        assertEquals("Κοτόπουλο", persisted.ingredientSections.single().ingredients.single().title)
        assertEquals("Ψήνουμε.", persisted.methodSections.single().steps.single())
    }

    private fun validDraft(categoryKey: String) = CustomRecipeDraftUi(
        title = "Συνταγή",
        categoryKey = categoryKey,
        ingredients = listOf(CustomIngredientDraftUi("Υλικό")),
        steps = listOf("Βήμα"),
    )
}
