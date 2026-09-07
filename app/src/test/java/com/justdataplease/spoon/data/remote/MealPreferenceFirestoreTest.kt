package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.model.MealPreferenceDocument
import com.justdataplease.spoon.data.preferences.MAX_MEAL_PREFERENCE_EPOCH_MILLIS
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPreferenceFirestoreTest {
    @Test
    fun `weekday and favorites settings survive cloud serialization with legacy defaults`() {
        val settings = MealPreferenceSettings(
            weekdayCategories = mapOf("MONDAY" to "meat", "SUNDAY" to "any"),
            favoritesOnly = true,
            updatedAtEpochMillis = 123L,
        )
        val fields = settings.toFirestoreDocument()
        assertEquals(settings.weekdayCategories, fields["weekdayCategories"])
        assertEquals(true, fields["favoritesOnly"])
        assertEquals(settings, MealPreferenceDocument(
            weekdayCategories = settings.weekdayCategories,
            favoritesOnly = true,
            updatedAtEpochMillis = 123L,
        ).toSettingsOrNull())
        assertEquals(MealPreferenceSettings(updatedAtEpochMillis = 1L), MealPreferenceDocument(updatedAtEpochMillis = 1L).toSettingsOrNull())
        assertNull(MealPreferenceDocument(weekdayCategories = mapOf("FUNDAY" to "meat"), updatedAtEpochMillis = 1L).toSettingsOrNull())
        assertNull(MealPreferenceDocument(weekdayCategories = mapOf("MONDAY" to "invalid"), updatedAtEpochMillis = 1L).toSettingsOrNull())
    }

    @Test
    fun `serializer emits exact deterministic and sanitized Firestore fields`() {
        val document = MealPreferenceSettings(
            excludedCategories = setOf("meat", " fish ", "any", "unknown"),
            veganOnly = true,
            excludedIngredientTerms = setOf(
                "  Γάλα   καρύδας  ",
                "Φιστίκια",
                "x",
                "α".repeat(MAX_EXCLUDED_INGREDIENT_TERM_LENGTH + 1),
            ),
            updatedAtEpochMillis = 123L,
        ).toFirestoreDocument()

        assertEquals(
            setOf(
                "excludedCategories",
                "veganOnly",
                "excludedIngredientTerms",
                "updatedAtEpochMillis",
                "weekdayCategories",
                "favoritesOnly",
            ),
            document.keys,
        )
        assertEquals(listOf("fish", "meat"), document["excludedCategories"])
        assertEquals(listOf("Γάλα καρύδας", "Φιστίκια"), document["excludedIngredientTerms"])
        assertEquals(true, document["veganOnly"])
        assertEquals(123L, document["updatedAtEpochMillis"])
    }

    @Test
    fun `serializer caps ingredient exclusions after stable sorting`() {
        val document = MealPreferenceSettings(
            excludedIngredientTerms = (0 until 45)
                .mapTo(mutableSetOf()) { index ->
                    "υλικό " + index.toString().padStart(2, '0')
                },
            updatedAtEpochMillis = 1L,
        ).toFirestoreDocument()

        @Suppress("UNCHECKED_CAST")
        val ingredients = document.getValue("excludedIngredientTerms") as List<String>
        assertEquals(MAX_EXCLUDED_INGREDIENT_TERMS, ingredients.size)
        assertEquals(ingredients.sorted(), ingredients)
        assertEquals("υλικό 00", ingredients.first())
        assertEquals("υλικό 39", ingredients.last())
    }

    @Test
    fun `valid Firestore document converts to normalized settings`() {
        val settings = MealPreferenceDocument(
            id = MEAL_PREFERENCE_DOCUMENT_ID,
            excludedCategories = listOf("fish", "dessert"),
            veganOnly = true,
            excludedIngredientTerms = listOf("  γάλα   καρύδας ", "φιστίκια"),
            updatedAtEpochMillis = 321L,
        ).toSettingsOrNull()

        assertEquals(
            MealPreferenceSettings(
                excludedCategories = setOf("fish", "dessert"),
                veganOnly = true,
                excludedIngredientTerms = setOf("γάλα καρύδας", "φιστίκια"),
                updatedAtEpochMillis = 321L,
            ),
            settings,
        )
    }

    @Test
    fun `invalid Firestore documents are rejected without partial settings`() {
        val valid = MealPreferenceDocument(
            id = MEAL_PREFERENCE_DOCUMENT_ID,
            excludedCategories = listOf("fish"),
            excludedIngredientTerms = listOf("γάλα"),
            updatedAtEpochMillis = 1L,
        )
        val invalidDocuments = listOf(
            valid.copy(id = "other"),
            valid.copy(updatedAtEpochMillis = 0L),
            valid.copy(updatedAtEpochMillis = MAX_MEAL_PREFERENCE_EPOCH_MILLIS + 1L),
            valid.copy(excludedCategories = listOf("fish", "fish")),
            valid.copy(excludedCategories = listOf("unknown")),
            valid.copy(excludedIngredientTerms = listOf("x")),
            valid.copy(
                excludedIngredientTerms =
                    listOf("α".repeat(MAX_EXCLUDED_INGREDIENT_TERM_LENGTH + 1)),
            ),
            valid.copy(excludedIngredientTerms = listOf("γάλα", "  γάλα  ")),
            valid.copy(
                excludedIngredientTerms =
                    List(MAX_EXCLUDED_INGREDIENT_TERMS + 1) { index -> "υλικό " + index },
            ),
        )

        invalidDocuments.forEach { document ->
            assertNull(document.toSettingsOrNull())
        }
    }

    @Test
    fun `normalization uses supplied timestamp and the Firestore validation contract`() {
        val normalized = MealPreferenceSettings(
            excludedCategories = setOf("fish", "any", "invalid"),
            veganOnly = true,
            excludedIngredientTerms = setOf("  γάλα   καρύδας  ", "x"),
            updatedAtEpochMillis = 5L,
        ).normalizedForSync(timestamp = 88L)

        assertEquals(setOf("fish"), normalized.excludedCategories)
        assertTrue(normalized.veganOnly)
        assertEquals(setOf("γάλα καρύδας"), normalized.excludedIngredientTerms)
        assertEquals(88L, normalized.updatedAtEpochMillis)
    }

    @Test
    fun `next timestamp remains monotonic when the device clock stalls or moves back`() {
        assertEquals(101L, nextPreferenceTimestamp(now = 50L, current = 100L))
        assertEquals(101L, nextPreferenceTimestamp(now = 101L, current = 100L))
        assertEquals(150L, nextPreferenceTimestamp(now = 150L, current = 100L))
        assertEquals(1L, nextPreferenceTimestamp(now = 0L, current = 0L))
        assertEquals(
            MAX_MEAL_PREFERENCE_EPOCH_MILLIS,
            nextPreferenceTimestamp(now = Long.MAX_VALUE, current = Long.MAX_VALUE),
        )
    }
}
