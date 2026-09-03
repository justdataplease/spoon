package com.justdataplease.spoon.ui.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomRecipeModelsTest {
    @Test
    fun `valid draft has no validation error`() {
        val draft = CustomRecipeDraftUi(
            title = "Φακές της γιαγιάς",
            categoryKey = "legumes",
            ingredients = listOf(CustomIngredientDraftUi("φακές", "250", "γρ.")),
            steps = listOf("Βράζουμε τις φακές."),
        )
        assertNull(draft.validationMessage())
    }

    @Test
    fun `draft requires title ingredient and step`() {
        val base = CustomRecipeDraftUi(
            title = "",
            categoryKey = "legumes",
            ingredients = emptyList(),
            steps = emptyList(),
        )
        assertEquals("Γράψε έναν τίτλο για τη συνταγή.", base.validationMessage())
        assertEquals(
            "Πρόσθεσε τουλάχιστον ένα υλικό.",
            base.copy(title = "Φακές").validationMessage(),
        )
        assertEquals(
            "Πρόσθεσε τουλάχιστον ένα βήμα εκτέλεσης.",
            base.copy(title = "Φακές", ingredients = listOf(CustomIngredientDraftUi("φακές"))).validationMessage(),
        )
    }
}
