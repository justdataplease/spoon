package com.justdataplease.spoon.data.model

import com.google.firebase.firestore.DocumentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomRecipeTest {
    @Test
    fun custom_recipe_becomes_active_Greek_recipe_with_Greek_category() {
        val custom = validCustomRecipe(
            ingredientCount = 20,
            methodSections = listOf(
                RecipeMethodSection(
                    title = "Εκτέλεση",
                    steps = listOf("Ανακατεύουμε.", "Ψήνουμε."),
                ),
            ),
        )

        val recipe = custom.toRecipe()

        assertEquals("el", recipe.language)
        assertTrue(recipe.active)
        assertEquals("Λαχανικά", recipe.categoryLabel)
        assertEquals(1, recipe.preparationCount)
        assertEquals(2, recipe.stepCount)
        assertEquals(EaseLevel.EASY, recipe.easeLevel)
        assertEquals(custom.photoDataUri, recipe.imageUrl)
        assertEquals(listOf(custom.photoDataUri), recipe.imageUrls)
    }

    @Test
    fun generated_custom_ids_are_safe_UUID_document_ids() {
        repeat(10) {
            val id = newCustomRecipeId()
            assertTrue(id.isCustomRecipeId())
            assertTrue(id.length <= 128)
            assertTrue(id.matches(Regex("[A-Za-z0-9_-]+")))
        }
    }

    @Test
    fun custom_validation_rejects_non_JPEG_or_malformed_photo_data() {
        assertThrows(IllegalArgumentException::class.java) {
            validCustomRecipe(photoDataUri = "data:image/png;base64,YWJj").requireValid()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validCustomRecipe(photoDataUri = "data:image/jpeg;base64,όχι").requireValid()
        }
    }

    @Test
    fun owner_models_retain_Firestore_empty_constructors_and_ids() {
        assertEquals("", ShoppingListItem().name)
        assertEquals("", RecipeNote().text)
        assertEquals("", CookedMeal().date)
        assertEquals("any", CustomRecipe().category)

        listOf(
            ShoppingListItem::class.java,
            RecipeNote::class.java,
            CookedMeal::class.java,
            CustomRecipe::class.java,
        ).forEach { type ->
            assertNotNull(
                type.getMethod("setId", String::class.java).getAnnotation(DocumentId::class.java),
            )
        }
    }

    private fun validCustomRecipe(
        ingredientCount: Int = 1,
        methodSections: List<RecipeMethodSection> = listOf(
            RecipeMethodSection(title = "Εκτέλεση", steps = listOf("Ψήνουμε.")),
        ),
        photoDataUri: String = "data:image/jpeg;base64,YWJj",
    ) = CustomRecipe(
        id = "custom_01234567-89ab-4def-8123-456789abcdef",
        title = "Η πίτα μου",
        category = "vegetables",
        ingredientSections = listOf(
            RecipeIngredientSection(
                title = "Υλικά",
                ingredients = List(ingredientCount) { index ->
                    RecipeIngredient(title = "Υλικό $index")
                },
            ),
        ),
        methodSections = methodSections,
        photoDataUri = photoDataUri,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 2_000,
    )
}
