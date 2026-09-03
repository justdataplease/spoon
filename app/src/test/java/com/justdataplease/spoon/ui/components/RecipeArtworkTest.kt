package com.justdataplease.spoon.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeArtworkTest {
    @Test
    fun `accepts https and bounded jpeg data url`() {
        val dataUrl = "data:image/jpeg;base64,AAAAAAAAAAAA"
        assertEquals("https://example.test/food.jpg", normalizeRecipeImageSource("https://example.test/food.jpg"))
        assertEquals(dataUrl, normalizeRecipeImageSource(dataUrl))
    }

    @Test
    fun `rejects malformed and oversized image data`() {
        assertNull(normalizeRecipeImageSource("data:image/svg+xml;base64,AAAAAAAAAAAA"))
        assertNull(normalizeRecipeImageSource("data:image/jpeg;base64,ΑΑΑΑΑΑΑΑΑΑΑΑ"))
        assertNull(normalizeRecipeImageSource("data:image/jpeg;base64," + "A".repeat(700_000)))
    }
}
