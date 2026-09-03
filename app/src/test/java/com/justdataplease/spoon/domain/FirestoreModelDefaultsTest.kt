package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.FavoriteRecipe
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreModelDefaultsTest {
    @Test
    fun `Firestore-facing models have safe empty constructors`() {
        assertEquals("", Recipe().id)
        assertEquals(0, Recipe().cookMinutes)
        assertEquals(0, Recipe().totalMinutes)
        assertEquals(0, Recipe().preparationCount)
        assertEquals("el", Recipe().language)
        assertTrue(Recipe().active)
        assertEquals("", DayMealPlan().date)
        assertEquals(RecipeFilters(), DayMealPlan().filters)
        assertEquals("", FavoriteRecipe().recipeId)
    }

    @Test
    fun `Firestore document ids have annotated setters`() {
        listOf(Recipe::class.java, DayMealPlan::class.java, FavoriteRecipe::class.java).forEach { type ->
            val setter = type.getMethod("setId", String::class.java)
            assertNotNull(setter.getAnnotation(com.google.firebase.firestore.DocumentId::class.java))
        }
    }

    @Test
    fun `recipe filters match Firestore rule boundaries`() {
        assertTrue(RecipeFilters().isValid())
        assertTrue(RecipeFilters(easeLevel = "easy", maxPrepMinutes = 10_080).isValid())
        assertFalse(RecipeFilters(easeLevel = " ").isValid())
        assertFalse(RecipeFilters(maxPrepMinutes = -1).isValid())
        assertFalse(RecipeFilters(maxPrepMinutes = 10_081).isValid())
    }
}
