package com.justdataplease.spoon.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MealPreferenceSettingsStoreTest {
    @Test
    fun `defaults include every recipe`() = runTest {
        val store = newStore()

        assertEquals(MealPreferenceSettings(), store.settings.first())
    }

    @Test
    fun `each preference group persists without replacing the others`() = runTest {
        val store = newStore()

        store.setExcludedCategories(setOf("  Ψάρια  ", "", "Όσπρια"))
        store.setVeganOnly(true)
        store.setExcludedIngredientTerms(setOf("Φυστίκια", "  Γάλα", "φυστικια"))

        assertEquals(
            MealPreferenceSettings(
                excludedCategories = setOf("Ψάρια", "Όσπρια"),
                veganOnly = true,
                excludedIngredientTerms = setOf("Φυστίκια", "Γάλα", "φυστικια"),
            ),
            store.settings.first(),
        )
    }

    @Test
    fun `settings flow reacts to an atomic update`() = runTest {
        val store = newStore()
        val nextValue = async(UnconfinedTestDispatcher(testScheduler)) {
            store.settings.drop(1).first()
        }
        runCurrent()

        store.update { current ->
            current.copy(
                excludedCategories = setOf("Γλυκά"),
                veganOnly = true,
                excludedIngredientTerms = setOf("  Κόκκινο   Κρέας  "),
            )
        }

        assertEquals(
            MealPreferenceSettings(
                excludedCategories = setOf("Γλυκά"),
                veganOnly = true,
                excludedIngredientTerms = setOf("Κόκκινο Κρέας"),
            ),
            nextValue.await(),
        )
    }

    @Test
    fun `clear restores include everything defaults`() = runTest {
        val store = newStore()
        store.update {
            MealPreferenceSettings(
                excludedCategories = setOf("Γλυκά"),
                veganOnly = true,
                excludedIngredientTerms = setOf("Αυγό"),
            )
        }

        store.clear()

        assertEquals(MealPreferenceSettings(), store.settings.first())
    }

    @Test
    fun `ingredient terms preserve Greek spelling while trimming outer whitespace`() =
        runTest {
            val store = newStore()

            store.setExcludedIngredientTerms(setOf("  Γάλα-Καρύδας! ", "ΦΑΚΕΣ"))

            assertEquals(
                setOf("Γάλα-Καρύδας!", "ΦΑΚΕΣ"),
                store.settings.first().excludedIngredientTerms,
            )
        }

    private fun kotlinx.coroutines.test.TestScope.newStore(): MealPreferenceSettingsStore {
        val file = File.createTempFile("meal_preference_settings_", ".preferences_pb")
            .also(File::deleteOnExit)
        return MealPreferenceSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { file },
            ),
        )
    }
}
