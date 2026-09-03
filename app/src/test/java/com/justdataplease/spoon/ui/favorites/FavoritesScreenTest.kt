package com.justdataplease.spoon.ui.favorites

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesScreenTest {
    @Test
    fun `favorite summary uses singular for one recipe`() {
        assertEquals(
            "1 συνταγή που αξίζει να ξαναφτιάξεις",
            favoriteRecipeSummary(1),
        )
    }

    @Test
    fun `favorite summary uses plural for multiple recipes`() {
        assertEquals(
            "2 συνταγές που αξίζει να ξαναφτιάξεις",
            favoriteRecipeSummary(2),
        )
    }
}
