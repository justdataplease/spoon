package com.justdataplease.spoon.data

import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.remote.FirestoreSpoonRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeDetailsLookupTest {
    @Test
    fun `safe recipe ids accept catalog and demo formats`() {
        assertEquals("9959", requireSafeRecipeDocumentId("9959"))
        assertEquals(
            "demo-fish_lemon-1",
            requireSafeRecipeDocumentId("demo-fish_lemon-1"),
        )
    }

    @Test
    fun `unsafe recipe ids are rejected before document lookup`() {
        listOf("", " ", "../9959", "9959/steps", ".", "συνταγή", "a".repeat(129))
            .forEach { recipeId ->
                val failure = runCatching { requireSafeRecipeDocumentId(recipeId) }.exceptionOrNull()
                assertTrue("Expected rejection for '$recipeId'", failure is IllegalArgumentException)
            }
    }

    @Test
    fun `eligible details normalize a blank decoded id`() {
        val decoded = Recipe(id = "", title = "Δοκιμή", active = true, language = "el")

        val result = eligibleRecipeDetails(decoded, documentId = "9959", requestedId = "9959")

        assertEquals("9959", result?.id)
        assertEquals("Δοκιμή", result?.title)
    }

    @Test
    fun `details fail closed for identity publication and language mismatches`() {
        val valid = Recipe(id = "9959", active = true, language = "el")

        assertSame(valid, eligibleRecipeDetails(valid, "9959", "9959"))
        assertNull(eligibleRecipeDetails(valid.copy(id = "1"), "9959", "9959"))
        assertNull(eligibleRecipeDetails(valid, "1", "9959"))
        assertNull(eligibleRecipeDetails(valid.copy(active = false), "9959", "9959"))
        assertNull(eligibleRecipeDetails(valid.copy(language = "en"), "9959", "9959"))
    }

    @Test
    fun `eligible Akis details use the current fail closed street food rule`() {
        val legacy = Recipe(
            id = "2009",
            category = "street_food",
            categoryLabel = "Βρώμικο",
            sourceKey = "akis",
            mealTypeLabels = listOf("Σνακ"),
            tags = listOf("Βρώμικο", "Σοκολάτα"),
        )

        val result = eligibleRecipeDetails(legacy, "2009", "2009")

        assertEquals("other", result?.category)
        assertEquals("Άλλο", result?.categoryLabel)
        assertEquals(listOf("Σοκολάτα"), result?.tags)
    }

    @Test
    fun `details collection contract matches importer`() {
        assertEquals(
            "spoon_recipe_details",
            FirestoreSpoonRepository.RECIPE_DETAILS_COLLECTION,
        )
    }
}
