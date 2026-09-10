package com.justdataplease.spoon.ui.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeShareLinkTest {
    @Test
    fun `catalog providers and future safe identities round trip`() {
        listOf("9959", "argiro_15369", "gastronomos_100134", "new-provider_42", "demo-fish-lemon-parcel", "a".repeat(128))
            .forEach { recipeId ->
                val link = RecipeShareLink.create(recipeId)

                assertEquals("https://justdataplease.github.io/spoon/recipe/$recipeId", link)
                assertEquals(recipeId, RecipeShareLink.parse(link))
                assertEquals(recipeId, RecipeShareLink.parse("$link/"))
                assertEquals(recipeId, RecipeShareLink.parse("spoon://recipe/$recipeId"))
                assertEquals(recipeId, RecipeShareLink.parse("spoon://recipe/$recipeId/"))
            }
    }

    @Test
    fun `scheme and authority are case insensitive while recipe identity is preserved`() {
        assertEquals(
            "Recipe_42",
            RecipeShareLink.parse("HTTPS://JUSTDATAPLEASE.GITHUB.IO/spoon/recipe/Recipe_42"),
        )
        assertEquals("Recipe_42", RecipeShareLink.parse("SPOON://RECIPE/Recipe_42"))
    }

    @Test
    fun `personal recipes cannot be shared or received`() {
        listOf("custom_01234567-89ab-4def-8123-456789abcdef", "custom_123")
            .forEach { recipeId ->
                assertNull(RecipeShareLink.create(recipeId))
                assertNull(RecipeShareLink.shareText(recipeId, "Ιδιωτική συνταγή", "Τι θα φάμε;"))
                assertNull(RecipeShareLink.parse("https://justdataplease.github.io/spoon/recipe/$recipeId"))
                assertNull(RecipeShareLink.parse("spoon://recipe/$recipeId"))
            }
    }

    @Test
    fun `unsafe ids cannot create links`() {
        listOf("", " ", ".", "..", "../9959", "9959/steps", "9959?x", "9959#x", "συνταγή", "a".repeat(129))
            .forEach { recipeId ->
                assertNull("Expected rejection for '$recipeId'", RecipeShareLink.create(recipeId))
            }
    }

    @Test
    fun `wrong or ambiguous authorities and schemes are rejected`() {
        listOf(
            "http://justdataplease.github.io/spoon/recipe/9959",
            "https://example.com/spoon/recipe/9959",
            "https://justdataplease.github.io.evil.test/spoon/recipe/9959",
            "https://justdataplease.github.io@evil.test/spoon/recipe/9959",
            "https://evil.test@justdataplease.github.io/spoon/recipe/9959",
            "https://justdataplease.github.io:443/spoon/recipe/9959",
            "https://justdataplease.github.io:/spoon/recipe/9959",
            "https://justdataplease.github.io./spoon/recipe/9959",
            "https://justdataplease%2egithub.io/spoon/recipe/9959",
            "//justdataplease.github.io/spoon/recipe/9959",
            "spoon:recipe/9959",
            "spoon:///recipe/9959",
            "spoon://user@recipe/9959",
            "spoon://recipe:80/9959",
            "spoon://recipe.evil.test/9959",
            "file://recipe/9959",
        ).forEach { link ->
            assertNull("Expected rejection for '$link'", RecipeShareLink.parse(link))
        }
    }

    @Test
    fun `unexpected paths payloads and encoded identities are rejected`() {
        listOf(
            "https://justdataplease.github.io/recipe/9959",
            "https://justdataplease.github.io/spoon/Recipe/9959",
            "https://justdataplease.github.io/spoon/recipe/",
            "https://justdataplease.github.io/spoon/recipe//9959",
            "https://justdataplease.github.io/spoon/recipe/9959//",
            "https://justdataplease.github.io/spoon/recipe/9959/steps",
            "https://justdataplease.github.io/spoon/recipe/../9959",
            "https://justdataplease.github.io/spoon/recipe/9959?owner=someone",
            "https://justdataplease.github.io/spoon/recipe/9959?",
            "https://justdataplease.github.io/spoon/recipe/9959#details",
            "https://justdataplease.github.io/spoon/recipe/9959#",
            "https://justdataplease.github.io/spoon/recipe/99%2F59",
            "https://justdataplease.github.io/spoon/recipe/%39%39%35%39",
            "https://justdataplease.github.io/spoon/recipe/%252F",
            "https://justdataplease.github.io/spoon/recipe/9959%00",
            "https://justdataplease.github.io/spoon/recipe/9959\\steps",
            "spoon://recipe/9959?owner=someone",
            "spoon://recipe/9959#details",
            "spoon://recipe/%39%39%35%39",
            "spoon://recipe/9959/steps",
        ).forEach { link ->
            assertNull("Expected rejection for '$link'", RecipeShareLink.parse(link))
        }
    }

    @Test
    fun `missing malformed and overlong input is ignored`() {
        listOf(null, "", " ", "not a link", "https://[", "spoon://recipe/%", "spoon://recipe/${"a".repeat(129)}", "a".repeat(513))
            .forEach { link -> assertNull(RecipeShareLink.parse(link)) }
        assertNull(RecipeShareLink.parse(" spoon://recipe/9959"))
        assertNull(RecipeShareLink.parse("spoon://recipe/9959\n"))
    }

    @Test
    fun `share message includes normalized title and a clickable HTTPS link`() {
        assertEquals(
            "Δες τη συνταγή «Φακές με λεμόνι» στο «Τι θα φάμε;»:\nhttps://justdataplease.github.io/spoon/recipe/9959",
            RecipeShareLink.shareText("9959", "  Φακές\nμε  λεμόνι  ", "Τι θα φάμε;"),
        )
        assertEquals(
            "Δες αυτή τη συνταγή στο «Τι θα φάμε;»:\nhttps://justdataplease.github.io/spoon/recipe/9959",
            RecipeShareLink.shareText("9959", " \n ", "Τι θα φάμε;"),
        )
    }
}
