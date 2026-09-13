package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.FavoriteRecipe
import com.justdataplease.spoon.data.model.RecipeIngredient
import com.justdataplease.spoon.data.model.RecipeIngredientSection
import com.justdataplease.spoon.data.model.RecipeMethodSection
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UserContentFirestoreSerializationTest {
    @Test
    fun `favorite contains only fields accepted by offline upsert rules`() {
        val document = FavoriteRecipe(
            recipeId = "akis_1",
            addedAtEpochMillis = 2_000,
        ).toFirestoreDocument()

        assertEquals(setOf("recipeId", "addedAtEpochMillis", "includedInWeeklyPlan", "weeklyPlanUpdatedAtEpochMillis"), document.keys)
        assertEquals(true, document["includedInWeeklyPlan"])
        assertEquals(0L, document["weeklyPlanUpdatedAtEpochMillis"])
        assertFalse(document.containsKey("id"))
    }

    @Test
    fun `duplicate add preserves cloud exclusion while explicit choice preserves server membership`() {
        val server = FavoriteRecipe("recipe", "recipe", 10, false, 20)
        val added = FavoriteRecipe("recipe", "recipe", 30)
        assertEquals(server, added.withServerMembership(server))
        val changed = added.copy(includedInWeeklyPlan = true, weeklyPlanUpdatedAtEpochMillis = 40)
        assertEquals(changed.copy(addedAtEpochMillis = 10), changed.withServerMembership(server))
        assertEquals(mapOf("includedInWeeklyPlan" to true, "weeklyPlanUpdatedAtEpochMillis" to 40L),
            changed.weeklyPlanFirestoreFields())
    }

    @Test
    fun shopping_item_contains_only_rules_approved_fields() {
        val document = ShoppingListItem(
            id = "shopping_1",
            name = "Φακές",
            quantity = "500",
            unit = "γρ.",
            info = "ψιλές",
            recipeId = "42",
            recipeTitle = "Φακές σούπα",
            sectionTitle = "Για τη σούπα",
            checked = true,
            createdAtEpochMillis = 1_000,
            updatedAtEpochMillis = 2_000,
        ).toFirestoreDocument()

        assertEquals(
            setOf(
                "name",
                "quantity",
                "unit",
                "info",
                "recipeId",
                "recipeTitle",
                "sectionTitle",
                "checked",
                "createdAtEpochMillis",
                "updatedAtEpochMillis",
            ),
            document.keys,
        )
        assertFalse(document.containsKey("id"))
        assertEquals("Φακές", document["name"])
    }

    @Test
    fun recipe_note_uses_recipe_identity_without_serializing_document_id() {
        val document = RecipeNote(
            id = "42",
            recipeId = "42",
            text = "Λίγο περισσότερο λεμόνι.",
            updatedAtEpochMillis = 2_000,
        ).toFirestoreDocument()

        assertEquals(setOf("recipeId", "text", "updatedAtEpochMillis"), document.keys)
        assertFalse(document.containsKey("id"))
    }

    @Test
    fun custom_recipe_serializes_manual_fields_and_compact_nested_maps() {
        val document = sampleCustomRecipe().toFirestoreDocument()

        assertEquals(
            setOf(
                "title",
                "description",
                "category",
                "prepMinutes",
                "cookMinutes",
                "servings",
                "ingredientSections",
                "methodSections",
                "photoDataUri",
                "active",
                "createdAtEpochMillis",
                "updatedAtEpochMillis",
            ),
            document.keys,
        )
        assertFalse(document.containsKey("id"))
        assertEquals("vegetables", document["category"])

        val ingredientSections = document["ingredientSections"] as List<*>
        val section = ingredientSections.single() as Map<*, *>
        val ingredient = (section["ingredients"] as List<*>).single() as Map<*, *>
        assertEquals(setOf("title", "unit", "quantity", "info"), ingredient.keys)
        assertFalse(ingredient.containsKey("internalLink"))

        val methodSections = document["methodSections"] as List<*>
        val method = methodSections.single() as Map<*, *>
        assertEquals(setOf("title", "steps"), method.keys)
    }

    @Test
    fun history_serializer_contains_exact_snapshot_fields() {
        val document = DayMealPlan(
            date = "2026-09-03",
            recipeId = "42",
            recipeTitle = "Φακές σούπα",
        ).toCookedMealDocument(completedAtEpochMillis = 3_000)

        assertEquals(
            setOf("date", "recipeId", "recipeTitle", "completedAtEpochMillis"),
            document.keys,
        )
        assertEquals("2026-09-03", document["date"])
        assertEquals(3_000L, document["completedAtEpochMillis"])
    }

    private fun sampleCustomRecipe() = CustomRecipe(
        id = "custom_01234567-89ab-4def-8123-456789abcdef",
        title = "Η πίτα μου",
        description = "Οικογενειακή συνταγή",
        category = "vegetables",
        prepMinutes = 20,
        cookMinutes = 45,
        servings = "6",
        ingredientSections = listOf(
            RecipeIngredientSection(
                title = "Υλικά",
                ingredients = listOf(
                    RecipeIngredient(
                        title = "Σπανάκι",
                        unit = "γρ.",
                        quantity = "500",
                        info = "καθαρισμένο",
                        internalLink = "must-not-be-written",
                    ),
                ),
            ),
        ),
        methodSections = listOf(
            RecipeMethodSection(title = "Εκτέλεση", steps = listOf("Ψήνουμε.")),
        ),
        photoDataUri = "data:image/jpeg;base64,YWJj",
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 2_000,
    )
}
