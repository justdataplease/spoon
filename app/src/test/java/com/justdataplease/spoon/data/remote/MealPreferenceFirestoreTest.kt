package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.model.MealPreferenceDocument
import com.justdataplease.spoon.data.preferences.MAX_MEAL_PREFERENCE_EPOCH_MILLIS
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPreferenceFirestoreTest {
    @Test
    fun `source exclusions roundtrip with strict validation and legacy default`() {
        val keys = com.justdataplease.spoon.data.preferences.AllowedRecipePublisherKeys
        val settings = MealPreferenceSettings(excludedSourceKeys = keys, updatedAtEpochMillis = 10L)
        assertEquals(keys.sorted(), settings.toFirestoreDocument()["excludedSourceKeys"])
        assertEquals(settings, settings.normalizedForSync(10L))
        assertEquals(settings, MealPreferenceDocument(excludedCategories = settings.excludedCategories.toList(),
            excludedSourceKeys = keys.toList(), updatedAtEpochMillis = 10L).toSettingsOrNull())
        assertEquals(emptySet<String>(), MealPreferenceDocument(updatedAtEpochMillis = 10L).toSettingsOrNull()?.excludedSourceKeys)
        for (invalid in listOf(listOf("akis", "akis"), listOf("AKIS"), listOf("unknown"), listOf("personal"))) {
            assertNull(MealPreferenceDocument(excludedSourceKeys = invalid, updatedAtEpochMillis = 10L).toSettingsOrNull())
        }
        assertEquals(setOf("akis", "cookpad"), MealPreferenceSettings(excludedSourceKeys = setOf(" AKIS ", "cookpad", "bad"))
            .normalizedForSync(10L).excludedSourceKeys)
    }

    @Test
    fun `all sources enabled keeps the legacy document shape and normalizes missing exclusions`() {
        val settings = MealPreferenceSettings(updatedAtEpochMillis = 10L)
        val document = settings.toFirestoreDocument()

        assertEquals(
            setOf(
                "excludedCategories", "veganOnly", "excludedIngredientTerms",
                "updatedAtEpochMillis", "weekdayCategories", "dessertWeekdayCategories",
                "sideWeekdayCategories", "favoritesOnly",
            ),
            document.keys,
        )
        assertEquals(settings, settings.normalizedForSync(10L))
        assertEquals(
            emptySet<String>(),
            MealPreferenceDocument(updatedAtEpochMillis = 10L).toSettingsOrNull()?.excludedSourceKeys,
        )
    }

    @Test
    fun `source exclusions sanitized to empty also omit the optional field`() {
        val settings = MealPreferenceSettings(
            excludedSourceKeys = setOf(" ", "unknown", "personal"),
            updatedAtEpochMillis = 10L,
        )

        assertFalse(settings.toFirestoreDocument().containsKey("excludedSourceKeys"))
        assertEquals(emptySet<String>(), settings.normalizedForSync(20L).excludedSourceKeys)
    }

    @Test
    fun `enabling every source emits a replacement document that removes prior exclusions`() {
        val prior = MealPreferenceSettings(
            excludedSourceKeys = setOf("akis", "cookpad"),
            excludedIngredientTerms = setOf("γάλα"),
            updatedAtEpochMillis = 10L,
        )
        val cleared = prior.copy(excludedSourceKeys = emptySet()).normalizedForSync(20L)
        val replacement = cleared.toFirestoreDocument()

        assertEquals(listOf("akis", "cookpad"), prior.toFirestoreDocument()["excludedSourceKeys"])
        // FirestoreSpoonRepository uses full set() without merge: this complete replacement
        // removes the absent field instead of retaining the remote document's previous list.
        assertEquals(prior.toFirestoreDocument().keys - "excludedSourceKeys", replacement.keys)
        assertFalse(replacement.containsKey("excludedSourceKeys"))
        assertEquals(listOf("γάλα"), replacement["excludedIngredientTerms"])
        assertEquals(20L, replacement["updatedAtEpochMillis"])
        assertEquals(emptySet<String>(), cleared.excludedSourceKeys)
    }

    @Test
    fun `weekday and favorites settings survive cloud serialization with legacy defaults`() {
        val settings = MealPreferenceSettings(
            weekdayCategories = mapOf("MONDAY" to "meat", "SUNDAY" to "any"),
            sideWeekdayCategories = mapOf("MONDAY" to "vegetables"),
            dessertWeekdayCategories = mapOf("SUNDAY" to "other"),
            favoritesOnly = true,
            updatedAtEpochMillis = 123L,
        )
        val fields = settings.toFirestoreDocument()
        assertEquals(settings.weekdayCategories, fields["weekdayCategories"])
        assertEquals(settings.sideWeekdayCategories, fields["sideWeekdayCategories"])
        assertEquals(settings.dessertWeekdayCategories, fields["dessertWeekdayCategories"])
        assertEquals(settings, settings.normalizedForSync(123L))
        assertEquals(true, fields["favoritesOnly"])
        assertEquals(settings, MealPreferenceDocument(
            excludedCategories = settings.excludedCategories.toList(),
            weekdayCategories = settings.weekdayCategories,
            sideWeekdayCategories = settings.sideWeekdayCategories,
            dessertWeekdayCategories = settings.dessertWeekdayCategories,
            favoritesOnly = true,
            updatedAtEpochMillis = 123L,
        ).toSettingsOrNull())
        assertEquals(
            MealPreferenceSettings(
                excludedCategories = emptySet(),
                updatedAtEpochMillis = 1L,
            ),
            MealPreferenceDocument(updatedAtEpochMillis = 1L).toSettingsOrNull(),
        )
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
                "sideWeekdayCategories",
                "dessertWeekdayCategories",
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
            valid.copy(sideWeekdayCategories = mapOf("FUNDAY" to "meat")),
            valid.copy(dessertWeekdayCategories = mapOf("MONDAY" to "invalid")),
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
