package com.justdataplease.spoon.ui

import com.justdataplease.spoon.data.model.Recipe
import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreRecipeWindowTest {
    @Test
    fun `retained Explore recipes stay bounded to the newest pages`() {
        val existing = (1..240).map(::recipe)
        val incoming = (241..264).map(::recipe)

        val retained = retainExploreWindow(existing, incoming)

        assertEquals(240, retained.size)
        assertEquals("25", retained.first().id)
        assertEquals("264", retained.last().id)
    }

    @Test
    fun `overlapping page ids are not retained twice`() {
        val retained = retainExploreWindow(
            existing = (1..24).map(::recipe),
            incoming = (24..47).map(::recipe),
        )

        assertEquals(47, retained.size)
        assertEquals(47, retained.map(Recipe::id).distinct().size)
    }

    private fun recipe(id: Int) = Recipe(id = id.toString(), title = "Συνταγή $id")
}
