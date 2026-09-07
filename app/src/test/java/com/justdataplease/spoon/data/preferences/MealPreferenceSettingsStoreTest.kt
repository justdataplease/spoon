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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `switching owners never exposes the previous owners cached settings`() = runTest {
        val store = newStore()
        val ownerASettings = MealPreferenceSettings(
            excludedCategories = setOf("fish"),
            veganOnly = true,
            excludedIngredientTerms = setOf("φιστίκια"),
            updatedAtEpochMillis = 100L,
        )

        store.readForOwner("owner-a")
        store.replaceForOwner("owner-a", ownerASettings)
        assertEquals(ownerASettings, store.readForOwner("owner-a"))

        assertEquals(MealPreferenceSettings(), store.readForOwner("owner-b"))
        assertEquals(MealPreferenceSettings(), store.settings.first())
        assertEquals(MealPreferenceSettings(), store.readForOwner("owner-a"))
    }

    @Test
    fun `remote replacement for a different active owner is ignored`() = runTest {
        val store = newStore()
        val ownerASettings = MealPreferenceSettings(
            excludedCategories = setOf("fish"),
            updatedAtEpochMillis = 100L,
        )
        store.readForOwner("owner-a")
        store.replaceForOwner("owner-a", ownerASettings)

        store.replaceForOwner(
            "owner-b",
            MealPreferenceSettings(
                veganOnly = true,
                updatedAtEpochMillis = 200L,
            ),
        )

        assertEquals(ownerASettings, store.settings.first())
        assertEquals(ownerASettings, store.readForOwner("owner-a"))
    }

    @Test
    fun `first authenticated edit initializes an empty local owner slot durably`() = runTest {
        val store = newStore()
        val firstEdit = MealPreferenceSettings(
            veganOnly = true,
            updatedAtEpochMillis = 100L,
        )

        assertTrue(store.replaceForOwner("owner-a", firstEdit))
        assertEquals(firstEdit, store.readForOwner("owner-a"))
        assertNull(store.readPendingForNextOwner())
    }

    @Test
    fun `stale remote replacement cannot roll back the active owners cache`() = runTest {
        val store = newStore()
        val current = MealPreferenceSettings(
            excludedCategories = setOf("dessert"),
            veganOnly = true,
            updatedAtEpochMillis = 200L,
        )
        store.readForOwner("owner-a")
        store.replaceForOwner("owner-a", current)

        store.replaceForOwner(
            "owner-a",
            MealPreferenceSettings(
                excludedIngredientTerms = setOf("γάλα"),
                updatedAtEpochMillis = 199L,
            ),
        )

        assertEquals(current, store.settings.first())
    }

    @Test
    fun `first owner claims legacy selections once at revision zero`() = runTest {
        val store = newStore()
        val legacy = MealPreferenceSettings(
            excludedCategories = setOf("dessert"),
            veganOnly = true,
            excludedIngredientTerms = setOf("  Γάλα   καρύδας  "),
        )
        store.update { legacy }
        val migrated = store.readForOwner("owner-a")

        assertEquals(setOf("dessert"), migrated.excludedCategories)
        assertTrue(migrated.veganOnly)
        assertEquals(setOf("Γάλα καρύδας"), migrated.excludedIngredientTerms)
        assertEquals(0L, migrated.updatedAtEpochMillis)
        assertEquals(migrated, store.settings.first())

        assertEquals(MealPreferenceSettings(), store.readForOwner("owner-b"))
    }

    @Test
    fun `empty legacy defaults are claimed without creating a phantom update`() = runTest {
        val store = newStore()

        val claimed = store.readForOwner("owner-a")

        assertEquals(MealPreferenceSettings(), claimed)
        assertEquals(0L, store.settings.first().updatedAtEpochMillis)
    }

    @Test
    fun `pending edit stays isolated from previous owner and is claimed by next owner`() = runTest {
        val store = newStore()
        val previousOwner = MealPreferenceSettings(
            excludedCategories = setOf("fish"),
            updatedAtEpochMillis = 100L,
        )
        val pending = MealPreferenceSettings(
            excludedCategories = setOf("poultry", "meat"),
            veganOnly = true,
            excludedIngredientTerms = setOf("γάλα"),
            updatedAtEpochMillis = 200L,
        )
        store.readForOwner("owner-a")
        store.replaceForOwner("owner-a", previousOwner)

        store.replacePendingForNextOwner(pending)

        assertEquals(previousOwner, store.settings.first())
        assertEquals(pending, store.readPendingForNextOwner())
        assertEquals(pending, store.readForOwner("owner-b"))
        assertNull(store.readPendingForNextOwner())
    }

    @Test
    fun `pending clear-all edit is claimed even without active selections`() = runTest {
        val store = newStore()
        store.readForOwner("owner-a")
        store.replaceForOwner(
            "owner-a",
            MealPreferenceSettings(veganOnly = true, updatedAtEpochMillis = 100L),
        )
        val pendingClear = MealPreferenceSettings(updatedAtEpochMillis = 200L)

        store.replacePendingForNextOwner(pendingClear)

        assertEquals(pendingClear, store.readForOwner("owner-b"))
        assertNull(store.readPendingForNextOwner())
    }

    @Test
    fun `clear removes pending edit and leaves no settings for the next owner`() = runTest {
        val store = newStore()
        store.readForOwner("owner-a")
        store.replaceForOwner(
            "owner-a",
            MealPreferenceSettings(veganOnly = true, updatedAtEpochMillis = 100L),
        )
        store.replacePendingForNextOwner(
            MealPreferenceSettings(
                excludedCategories = setOf("meat"),
                excludedIngredientTerms = setOf("γάλα"),
                updatedAtEpochMillis = 200L,
            ),
        )

        store.clear()

        assertEquals(MealPreferenceSettings(), store.settings.first())
        assertNull(store.readPendingForNextOwner())
        assertEquals(MealPreferenceSettings(), store.readForOwner("owner-b"))
    }

    @Test
    fun `pending clear-all replacement cannot resurrect an older pending selection`() = runTest {
        val store = newStore()
        store.replacePendingForNextOwner(
            MealPreferenceSettings(
                excludedCategories = setOf("poultry"),
                veganOnly = true,
                excludedIngredientTerms = setOf("αυγό"),
                updatedAtEpochMillis = 100L,
            ),
        )
        val pendingClear = MealPreferenceSettings(updatedAtEpochMillis = 101L)

        store.replacePendingForNextOwner(pendingClear)

        assertEquals(pendingClear, store.readPendingForNextOwner())
        assertEquals(pendingClear, store.readForOwner("owner-a"))
        assertNull(store.readPendingForNextOwner())
    }

    @Test
    fun `weekday defaults and favorites source persist claim pending owner and reset safely`() = runTest {
        val store = newStore()
        val settings = MealPreferenceSettings(
            weekdayCategories = mapOf("MONDAY" to "meat", "WEDNESDAY" to "any"),
            favoritesOnly = true,
            updatedAtEpochMillis = 42L,
        )
        store.replacePendingForNextOwner(settings)
        assertEquals(settings, store.readPendingForNextOwner())
        assertEquals(settings, store.readForOwner("owner-a"))
        assertEquals(settings, store.settings.first())
        assertEquals(MealPreferenceSettings(), store.readForOwner("owner-b"))
        store.update { settings }
        assertEquals(settings, store.settings.first())
        store.clear()
        assertEquals(MealPreferenceSettings(), store.settings.first())
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
