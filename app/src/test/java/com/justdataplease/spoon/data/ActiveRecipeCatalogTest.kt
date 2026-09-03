package com.justdataplease.spoon.data

import com.justdataplease.spoon.data.model.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRecipeCatalogTest {
    @Test
    fun `only active Greek remote recipes remain eligible`() {
        val recipes = eligibleRemoteRecipes(
            listOf(
                Recipe(id = "active-greek"),
                Recipe(id = "inactive-greek", active = false),
                Recipe(id = "active-english", language = "en"),
            ),
        )

        assertEquals(listOf("active-greek"), recipes.map(Recipe::id))
    }

    @Test
    fun `an inactive-only remote catalog stays empty`() {
        val recipes = eligibleRemoteRecipes(
            listOf(Recipe(id = "inactive", active = false)),
        )

        assertTrue(recipes.isEmpty())
    }

    @Test
    fun `a non-Greek-only remote catalog stays empty`() {
        val recipes = eligibleRemoteRecipes(
            listOf(Recipe(id = "english", language = "en")),
        )

        assertTrue(recipes.isEmpty())
    }

    @Test
    fun `legacy Akis Snack is removed from street food without losing other tags`() {
        val recipe = Recipe(
            id = "2009",
            category = "street_food",
            categoryLabel = "Βρώμικο",
            sourceKey = "akis",
            mealTypeLabels = listOf("Σνακ"),
            tags = listOf("Βρώμικο", "Σοκολάτα"),
        )

        val normalized = eligibleRemoteRecipes(listOf(recipe)).single()

        assertEquals("other", normalized.category)
        assertEquals("Άλλο", normalized.categoryLabel)
        assertEquals(listOf("Σοκολάτα"), normalized.tags)
    }

    @Test
    fun `Akis Sandwich and Finger food remain street food`() {
        listOf("Σάντουιτς", "Finger food").forEach { facet ->
            val recipe = Recipe(
                id = facet,
                category = "street_food",
                sourceKey = "akis",
                mealTypeLabels = listOf(facet),
            )

            assertEquals("street_food", eligibleRemoteRecipes(listOf(recipe)).single().category)
        }
    }

    @Test
    fun `legacy Akis domain is normalized but another provider is untouched`() {
        val legacyAkis = Recipe(
            id = "legacy",
            category = "street_food",
            source = "akispetretzikis.com",
            mealTypeLabels = listOf("Σνακ"),
        )
        val argiro = legacyAkis.copy(id = "argiro_1", source = "argiro.gr")
        val normalized = eligibleRemoteRecipes(listOf(legacyAkis, argiro)).associateBy(Recipe::id)

        assertEquals("other", normalized.getValue("legacy").category)
        assertEquals("street_food", normalized.getValue("argiro_1").category)
    }
}
