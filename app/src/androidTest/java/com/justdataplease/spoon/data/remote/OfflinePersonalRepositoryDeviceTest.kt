package com.justdataplease.spoon.data.remote

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.justdataplease.spoon.data.local.InMemoryRecipeCatalog
import com.justdataplease.spoon.data.local.PersonalDataSnapshot
import com.justdataplease.spoon.data.local.SqlitePersonalDataStore
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.MealCourse
import com.justdataplease.spoon.data.model.MealCoursePlan
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeIngredient
import com.justdataplease.spoon.data.model.RecipeIngredientSection
import com.justdataplease.spoon.data.model.RecipeMethodSection
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.data.model.newCustomRecipeId
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.domain.repository.AccountFailureKind
import com.justdataplease.spoon.domain.repository.AccountOperationException
import com.justdataplease.spoon.domain.repository.PersonalSyncState
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the actual repository with no Firebase Auth or Firestore instance whatsoever. */
class OfflinePersonalRepositoryDeviceTest {
    @Test
    fun everyPersonalActionWorksWithoutAuthenticationAndSurvivesRepositoryAndDatabaseRestart() = runBlocking {
        withTimeout(45_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.cacheDir, "offline-repository-test-${UUID.randomUUID()}.db")
            val customId = newCustomRecipeId()
            var retainedHistoryId = ""
            var preferences = MealPreferenceSettings()
            try {
                withRepository(context, file) { repository ->
                    assertEquals(BackendState.Local, repository.backendState.value)
                    assertEquals(PersonalSyncState.LocalOnly, repository.syncState.value)
                    assertNotNull(repository.getRecipeDetails("argiro_1"))
                    assertTrue(repository.toggleFavorite("argiro_1"))
                    assertFalse(repository.toggleFavorite("argiro_1"))
                    assertTrue(repository.toggleFavorite("argiro_1"))

                    repository.upsertMealPlan(DayMealPlan(
                        date = "2026-09-10", recipeId = "argiro_1", recipeTitle = "Φακές",
                        category = MealCategory.LEGUMES.key, updatedAtEpochMillis = 10,
                    ))
                    repository.setMealCompleted("2026-09-10", true)
                    val undoneEvent = repository.cookedHistory.first { it.size == 1 }.single().id
                    assertTrue(repository.mealPlans.value.single().completed)
                    repository.setMealCompleted("2026-09-10", false)
                    repository.cookedHistory.first { it.isEmpty() }
                    assertFalse(repository.mealPlans.value.single().completed)
                    repository.setMealCompleted("2026-09-10", true)
                    retainedHistoryId = repository.cookedHistory.first { it.size == 1 }.single().id
                    assertNotEquals(undoneEvent, retainedHistoryId)

                    // Replacing the selected recipe must not remove a previous cooked event.
                    repository.upsertMealPlan(DayMealPlan(
                        date = "2026-09-10", recipeId = "argiro_2", recipeTitle = "Ρύζι",
                        category = MealCategory.PASTA_RICE.key,
                        updatedAtEpochMillis = repository.mealPlans.value.single().updatedAtEpochMillis + 1,
                        side = MealCoursePlan(recipeId = "argiro_1", recipeTitle = "Φακές", updatedAtEpochMillis = 10),
                    ))
                    repository.setCourseCompleted("2026-09-10", MealCourse.SIDE, true)
                    repository.cookedHistory.first { it.size == 2 }
                    repository.setCourseCompleted("2026-09-10", MealCourse.SIDE, false)
                    assertEquals(retainedHistoryId, repository.cookedHistory.first { it.size == 1 }.single().id)

                    repository.upsertShoppingItems(listOf(shopping("milk"), shopping("rice")))
                    repository.setShoppingItemChecked("milk", true)
                    assertTrue(repository.shoppingItems.value.single { it.id == "milk" }.checked)
                    repository.deleteShoppingItem("rice")
                    repository.clearCheckedShoppingItems()
                    assertTrue(repository.shoppingItems.value.isEmpty())
                    repository.upsertShoppingItems(listOf(shopping("bread")))
                    repository.setShoppingItemChecked("bread", true)

                    repository.upsertRecipeNote(note("argiro_1", "Πρώτη σημείωση"))
                    repository.upsertRecipeNote(note("argiro_1", "Κράτησε λίγο νερό"))
                    repository.upsertRecipeNote(note("argiro_2", "Προσωρινή"))
                    repository.upsertRecipeNote(note("argiro_2", ""))
                    assertEquals(listOf("Κράτησε λίγο νερό"), repository.recipeNotes.value.map { it.text })

                    val custom = customRecipe(customId)
                    repository.upsertCustomRecipe(custom)
                    repository.upsertCustomRecipe(custom.copy(title = "Οι δικές μου φακές", updatedAtEpochMillis = 20))
                    assertEquals("Οι δικές μου φακές", repository.getRecipeDetails(customId)?.title)
                    assertFalse(checkNotNull(repository.getRecipeDetails(customId)).published)
                    assertTrue(repository.toggleFavorite(customId))
                    repository.upsertRecipeNote(note(customId, "Προσωπική σημείωση"))
                    assertEquals(setOf("argiro_1", customId), repository.getRecipesByIds(setOf("argiro_1", customId)).map { it.id }.toSet())

                    repository.updateMealPreferenceSettings(MealPreferenceSettings(
                        veganOnly = true, excludedSourceKeys = setOf("cookpad"),
                        weekdayCategories = mapOf("MONDAY" to MealCategory.LEGUMES.key),
                    ))
                    preferences = repository.mealPreferenceSettings.value
                    assertTrue(preferences.updatedAtEpochMillis > 0)
                    assertEquals(PersonalSyncState.LocalOnly, repository.syncState.value)
                }

                withRepository(context, file) { repository ->
                    assertEquals("argiro_2", repository.mealPlans.value.single().recipeId)
                    assertEquals(retainedHistoryId, repository.cookedHistory.first { it.size == 1 }.single().id)
                    assertEquals(setOf("argiro_1", customId), repository.favoriteRecipeIds.value)
                    assertEquals("bread", repository.shoppingItems.value.single().id)
                    assertTrue(repository.shoppingItems.value.single().checked)
                    assertEquals(2, repository.recipeNotes.value.size)
                    assertEquals(preferences, repository.mealPreferenceSettings.value)
                    assertEquals("Οι δικές μου φακές", repository.getRecipeDetails(customId)?.title)
                    assertEquals("data:image/jpeg;base64,AQID", repository.customRecipes.value.single().photoDataUri)

                    repository.deleteCustomRecipe(customId)
                    assertNull(repository.getRecipeDetails(customId))
                    assertEquals(setOf("argiro_1"), repository.favoriteRecipeIds.value)
                    assertEquals(listOf("argiro_1"), repository.recipeNotes.value.map { it.recipeId })
                    repository.deleteCookedHistoryEntry(retainedHistoryId)
                    repository.cookedHistory.first { it.isEmpty() }
                    repository.deleteShoppingItem("bread")
                }

                SqlitePersonalDataStore(context, file).use { reopened ->
                    assertTrue(reopened.read(null).cookedHistory.isEmpty())
                    assertTrue(reopened.read(null).customRecipes.isEmpty())
                    assertTrue(reopened.read(null).shoppingItems.isEmpty())
                }
                withRepository(context, file) { repository ->
                    assertTrue(repository.customRecipes.value.isEmpty())
                    assertTrue(repository.shoppingItems.value.isEmpty())
                    assertTrue(repository.cookedHistory.first().isEmpty())
                    assertNull(repository.getRecipeDetails(customId))
                    assertEquals(preferences, repository.mealPreferenceSettings.value)
                    assertEquals(BackendState.Local, repository.backendState.value)
                }
            } finally {
                SQLiteDatabase.deleteDatabase(file)
            }
        }
    }

    @Test
    fun rejectedRegistrationNeverClearsTheExistingLocalProfile() = runBlocking {
        withTimeout(45_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.cacheDir, "offline-registration-test-${UUID.randomUUID()}.db")
            try {
                withRepository(context, file) { repository ->
                    repository.upsertShoppingItems(listOf(shopping("existing-milk")))
                    assertTrue(repository.toggleFavorite("argiro_1"))
                    val rejected = listOf(
                        Triple("invalid", "test-password", AccountFailureKind.INVALID_EMAIL),
                        Triple("member@example.com", "short", AccountFailureKind.WEAK_PASSWORD),
                        Triple("member@example.com", "test-password", AccountFailureKind.UNAVAILABLE),
                    )
                    for ((email, password, expected) in rejected) {
                        val failure = try {
                            repository.createAccountWithEmail(email, password)
                            null
                        } catch (error: AccountOperationException) {
                            error.failure
                        }
                        assertEquals(expected, failure?.kind)
                        assertEquals(BackendState.Local, repository.backendState.value)
                        assertEquals(setOf("argiro_1"), repository.favoriteRecipeIds.value)
                        assertEquals("existing-milk", repository.shoppingItems.value.single().id)
                        assertNotNull(repository.getRecipeDetails("argiro_1"))
                    }
                }
                withRepository(context, file) { repository ->
                    assertEquals("existing-milk", repository.shoppingItems.value.single().id)
                    assertEquals(setOf("argiro_1"), repository.favoriteRecipeIds.value)
                    assertEquals(PersonalSyncState.LocalOnly, repository.syncState.value)
                }
            } finally {
                SQLiteDatabase.deleteDatabase(file)
            }
        }
    }

    @Test
    fun failingOptionalLegacyReaderKeepsLocalRecipesAndShoppingUsableAndRetriesAfterRestart() = runBlocking {
        withTimeout(45_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.cacheDir, "optional-legacy-repository-test-${UUID.randomUUID()}.db")
            val legacyFile = File(context.cacheDir, "optional-legacy-source-test-${UUID.randomUUID()}.json")
            legacyFile.writeText("{unreadable legacy data")
            try {
                withRepository(context, file, legacyLocalSnapshot = {
                    throw IOException("simulated optional legacy reader failure")
                }) { repository ->
                    assertEquals(BackendState.Local, repository.backendState.value)
                    val sync = repository.syncState.value as PersonalSyncState.Waiting
                    assertTrue(sync.needsLocalRecovery)
                    assertNotNull(repository.getRecipeDetails("argiro_1"))
                    repository.upsertShoppingItems(listOf(shopping("local-milk")))
                    assertTrue(repository.toggleFavorite("argiro_1"))
                    assertEquals("local-milk", repository.shoppingItems.value.single().id)
                    assertEquals("{unreadable legacy data", legacyFile.readText())
                }
                SqlitePersonalDataStore(context, file).use { store ->
                    assertFalse(store.isLegacyImported(null, "local_preferences_v1"))
                    assertTrue(store.isLegacyImported(null, "meal_outbox_guest_v1"))
                    assertEquals("local-milk", store.read(null).shoppingItems.single().id)
                }
                withRepository(context, file, legacyLocalSnapshot = {
                    PersonalDataSnapshot(recipeNotes = listOf(note("argiro_2", "Recovered legacy note")))
                }) { repository ->
                    assertEquals(BackendState.Local, repository.backendState.value)
                    assertEquals(PersonalSyncState.LocalOnly, repository.syncState.value)
                    assertEquals("local-milk", repository.shoppingItems.value.single().id)
                    assertTrue("argiro_1" in repository.favoriteRecipeIds.value)
                    assertEquals("Recovered legacy note", repository.recipeNotes.value.single().text)
                }
                // Once migrated, obsolete readers are never consulted during future startup.
                withRepository(context, file, legacyLocalSnapshot = {
                    error("A completed legacy import must not be reread")
                }) { repository ->
                    assertEquals(PersonalSyncState.LocalOnly, repository.syncState.value)
                    assertEquals("local-milk", repository.shoppingItems.value.single().id)
                    assertEquals("Recovered legacy note", repository.recipeNotes.value.single().text)
                    assertNotNull(repository.getRecipeDetails("argiro_2"))
                }
                assertEquals("{unreadable legacy data", legacyFile.readText())
            } finally {
                SQLiteDatabase.deleteDatabase(file)
                legacyFile.delete()
            }
        }
    }

    private suspend fun withRepository(
        context: Context,
        file: File,
        legacyLocalSnapshot: suspend () -> PersonalDataSnapshot = { PersonalDataSnapshot() },
        action: suspend (FirestoreSpoonRepository) -> Unit,
    ) {
        SqlitePersonalDataStore(context, file).use { store ->
            val repository = FirestoreSpoonRepository(
                auth = null, firestore = null,
                recipeCatalog = InMemoryRecipeCatalog(listOf(
                    Recipe(id = "argiro_1", title = "Φακές", category = MealCategory.LEGUMES.key),
                    Recipe(id = "argiro_2", title = "Ρύζι", category = MealCategory.PASTA_RICE.key),
                )),
                mealPlanOutbox = EmptyLegacyOutbox,
                ownerBootstrapStore = object : OwnerBootstrapStore {
                    override fun isComplete(ownerUid: String) = false
                    override fun markComplete(ownerUid: String): Boolean = error("Guest actions must not bootstrap a Firebase owner")
                },
                personalDataStore = store, preferenceStore = null,
                legacyLocalSnapshot = legacyLocalSnapshot,
            )
            try {
                repository.ensureReady()
                action(repository)
            } finally {
                repository.close()
            }
        }
    }

    private fun shopping(id: String) = ShoppingListItem(id = id, name = id, createdAtEpochMillis = 1, updatedAtEpochMillis = 1)
    private fun note(id: String, text: String) = RecipeNote(recipeId = id, text = text, updatedAtEpochMillis = 1)
    private fun customRecipe(id: String) = CustomRecipe(
        id = id, title = "Δική μου συνταγή", category = MealCategory.LEGUMES.key,
        ingredientSections = listOf(RecipeIngredientSection(ingredients = listOf(RecipeIngredient(title = "Φακές")))),
        methodSections = listOf(RecipeMethodSection(steps = listOf("Βράσε τις φακές."))),
        photoDataUri = "data:image/jpeg;base64,AQID", createdAtEpochMillis = 1, updatedAtEpochMillis = 10,
    )

    private object EmptyLegacyOutbox : MealPlanOutbox {
        override fun ownerlessPlans(): List<DayMealPlan> = emptyList()
        override fun pendingPlans(ownerUid: String): List<DayMealPlan> = emptyList()
        override fun claimOwnerless(ownerUid: String): List<DayMealPlan> = error("The SQLite store owns migration")
        override fun upsertOwnerless(plan: DayMealPlan): Boolean = error("Personal writes must use SQLite")
        override fun upsertForOwner(ownerUid: String, plan: DayMealPlan): Boolean = error("Personal writes must use SQLite")
        override fun removeAcknowledged(ownerUid: String, plan: DayMealPlan): Boolean = error("No remote acknowledgement is needed offline")
    }
}
