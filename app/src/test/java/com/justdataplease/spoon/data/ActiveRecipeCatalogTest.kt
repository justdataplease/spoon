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
}
