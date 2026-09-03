package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.RecipeFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPlanFirestoreSerializationTest {
    @Test
    fun `meal plan document contains exactly the rules-approved fields`() {
        val document = samplePlan().toFirestoreDocument()

        assertEquals(
            setOf(
                "date",
                "category",
                "recipeId",
                "recipeTitle",
                "filters",
                "completed",
                "updatedAtEpochMillis",
            ),
            document.keys,
        )
        assertFalse("Document id must remain in the Firestore path", document.containsKey("id"))
    }

    @Test
    fun `nested filters contain exact values without computed valid property`() {
        val document = samplePlan().toFirestoreDocument()
        val filters = document.getValue("filters") as Map<*, *>

        assertEquals(
            setOf("category", "easeLevel", "minRating", "maxPrepMinutes"),
            filters.keys,
        )
        assertEquals("fish", filters["category"])
        assertEquals("moderate", filters["easeLevel"])
        assertEquals(7.5, filters["minRating"])
        assertEquals(45, filters["maxPrepMinutes"])
        assertFalse(filters.containsKey("valid"))
    }

    @Test
    fun `meal plan values are copied without mapper-generated properties`() {
        val document = samplePlan().toFirestoreDocument()

        assertEquals("2026-09-03", document["date"])
        assertEquals("fish", document["category"])
        assertEquals("recipe-42", document["recipeId"])
        assertEquals("Ψάρι φούρνου", document["recipeTitle"])
        assertTrue(document["completed"] as Boolean)
        assertEquals(1_777_777_777_777L, document["updatedAtEpochMillis"])
    }

    private fun samplePlan() = DayMealPlan(
        id = "must-not-be-serialized",
        date = "2026-09-03",
        category = "fish",
        recipeId = "recipe-42",
        recipeTitle = "Ψάρι φούρνου",
        filters = RecipeFilters(
            category = "fish",
            easeLevel = "moderate",
            minRating = 7.5,
            maxPrepMinutes = 45,
        ),
        completed = true,
        updatedAtEpochMillis = 1_777_777_777_777L,
    )
}
