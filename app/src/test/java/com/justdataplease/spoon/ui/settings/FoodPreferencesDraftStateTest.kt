package com.justdataplease.spoon.ui.settings

import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodPreferencesDraftStateTest {
    @Test
    fun `rotation restoration preserves all unsaved preference choices`() {
        val original = FoodPreferencesDraftState("account:a", MealPreferenceSettings())
        val edited = MealPreferenceSettings(
            veganOnly = true,
            favoritesOnly = true,
            excludedIngredientTerms = setOf("Αυγό"),
            excludedSourceKeys = setOf("cookpad"),
            weekdayCategories = mapOf("MONDAY" to "vegetables"),
            sideWeekdayCategories = mapOf("TUESDAY" to "pasta_rice"),
            dessertWeekdayCategories = mapOf("WEDNESDAY" to "desserts"),
        )
        original.draft.value = edited

        val restored = FoodPreferencesDraftState.restore(original.encode(), "account:a")!!

        assertEquals(edited, restored.draft.value)
    }

    @Test
    fun `remote changes update untouched fields without erasing local edits`() {
        val baseline = MealPreferenceSettings(updatedAtEpochMillis = 1)
        val editor = FoodPreferencesDraftState("account:a", baseline)
        editor.draft.value = baseline.copy(veganOnly = true)

        editor.receiveSettings(baseline.copy(favoritesOnly = true, updatedAtEpochMillis = 2))

        assertTrue(editor.draft.value.veganOnly)
        assertTrue(editor.draft.value.favoritesOnly)
        assertEquals(2L, editor.draft.value.updatedAtEpochMillis)
    }

    @Test
    fun `acknowledging an older save preserves a newer edit after restoration`() {
        val baseline = MealPreferenceSettings(updatedAtEpochMillis = 1)
        val editor = FoodPreferencesDraftState("account:a", baseline)
        editor.draft.value = baseline.copy(veganOnly = true, favoritesOnly = true)
        val restored = FoodPreferencesDraftState.restore(editor.encode(), "account:a")!!

        restored.receiveSettings(baseline.copy(veganOnly = true, updatedAtEpochMillis = 2))

        assertTrue(restored.draft.value.veganOnly)
        assertTrue(restored.draft.value.favoritesOnly)
    }

    @Test
    fun `clean settings follow a background update`() {
        val baseline = MealPreferenceSettings(veganOnly = true)
        val editor = FoodPreferencesDraftState("guest", baseline)
        editor.receiveSettings(baseline.copy(veganOnly = false))
        assertFalse(editor.draft.value.veganOnly)
    }

    @Test
    fun `preference drafts reject a different account or malformed saved state`() {
        val editor = FoodPreferencesDraftState("account:a", MealPreferenceSettings(veganOnly = true))
        assertNull(FoodPreferencesDraftState.restore(editor.encode(), "account:b"))
        assertNull(FoodPreferencesDraftState.restore(editor.encode(), "guest"))
        assertNull(FoodPreferencesDraftState.restore("broken", "account:a"))
    }
}
