package com.justdataplease.spoon.data.remote

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.justdataplease.spoon.data.local.InMemoryRecipeCatalog
import com.justdataplease.spoon.data.local.PersonalCollection
import com.justdataplease.spoon.data.local.PersonalDataSnapshot
import com.justdataplease.spoon.data.local.SqlitePersonalDataStore
import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.FavoriteRecipe
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.data.model.RecipeIngredient
import com.justdataplease.spoon.data.model.RecipeIngredientSection
import com.justdataplease.spoon.data.model.RecipeMethodSection
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.data.model.newCookedMealEventId
import com.justdataplease.spoon.data.model.newCustomRecipeId
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.domain.repository.AccountState
import com.justdataplease.spoon.domain.repository.PersonalSyncState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Opt in with -e personalSyncEmulator true; both clients always address local demo emulators. */
class FirebasePersonalSyncDeviceTest {
    @Before
    fun requireExplicitLocalEmulators() {
        assumeTrue("Requires local demo-spoon-planning Auth (9199) and Firestore (8187) emulators",
            InstrumentationRegistry.getArguments().getString("personalSyncEmulator") == "true")
    }

    @Test
    fun guestImportReachesEveryCloudCollectionAndOfflineEditsSyncAfterRepositoryRestart() = runBlocking {
        withTimeout(180_000) {
            withIsolatedFirebase { fixture ->
                var ownerUid = ""
                var imported = PersonalDataSnapshot()
                var offline = PersonalDataSnapshot()
                SqlitePersonalDataStore(fixture.context, fixture.databaseFile).use { store ->
                    imported = store.mutate(null) { guestSnapshot() }
                    fixture.withRepository(store) { repository ->
                        assertEquals(PersonalSyncState.LocalOnly, repository.syncState.value)
                        assertEquals(imported, store.read(null))
                        repository.createAccountWithEmail(fixture.email, Password)
                        ownerUid = checkNotNull(fixture.auth.currentUser).uid
                        assertEquals(ownerUid, (repository.accountState.value as AccountState.Email).uid)
                        assertEquals(PersonalDataSnapshot(), store.read(null))
                        awaitSynced(repository, store, ownerUid)
                        assertEquals(imported, store.read(ownerUid))
                        fixture.assertServerSnapshot(ownerUid, imported)

                        fixture.firestore.disableNetwork().await()
                        // Existing favorites are immutable remotely. A rapid remove/add must
                        // preserve membership without updating the server timestamp.
                        assertFalse(repository.toggleFavorite("argiro_1"))
                        assertTrue(repository.toggleFavorite("argiro_1"))
                        repository.setShoppingItemChecked("milk", true)
                        repository.upsertRecipeNote(RecipeNote(recipeId = "argiro_1",
                            text = "Edited offline", updatedAtEpochMillis = 5_000))
                        offline = store.read(ownerUid)
                        assertTrue(store.pending(ownerUid).isNotEmpty())
                        assertNotEquals(PersonalSyncState.Synced, repository.syncState.value)
                    }
                }

                // Reopen SQLite and the repository while Firestore remains offline. Queued
                // SDK writes and durable local revisions must converge after reconnecting.
                SqlitePersonalDataStore(fixture.context, fixture.databaseFile).use { store ->
                    fixture.withRepository(store) { repository ->
                        assertEquals(offline, store.read(ownerUid))
                        assertTrue(repository.shoppingItems.value.single().checked)
                        assertEquals("Edited offline", repository.recipeNotes.value.single().text)
                        assertTrue(store.pending(ownerUid).isNotEmpty())
                        assertNotEquals(PersonalSyncState.Synced, repository.syncState.value)
                        fixture.firestore.enableNetwork().await()
                        awaitSynced(repository, store, ownerUid)
                        // A favorite that already exists keeps its original remote timestamp.
                        fixture.assertServerSnapshot(ownerUid, offline.copy(favorites = imported.favorites))
                    }
                }
            }
        }
    }

    private suspend fun awaitSynced(repository: FirestoreSpoonRepository, store: SqlitePersonalDataStore, owner: String) {
        withTimeout(60_000) { repository.syncState.first { it == PersonalSyncState.Synced } }
        assertTrue("Server acknowledgement must clear every local revision", store.pending(owner).isEmpty())
    }

    private suspend fun withIsolatedFirebase(action: suspend (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = UUID.randomUUID().toString()
        val databaseFile = File(context.cacheDir, "personal-sync-$unique.db")
        val app = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId("demo-spoon-planning")
            .setApplicationId("1:1234567890:android:0123456789abcdef")
            .setApiKey("fake-api-key-for-local-sync-emulators")
            .build(), "personal-sync-test-$unique")
        val auth = FirebaseAuth.getInstance(app)
        auth.useEmulator("10.0.2.2", 9199)
        val firestore = FirebaseFirestore.getInstance(app)
        firestore.useEmulator("10.0.2.2", 8187)
        val fixture = Fixture(context, databaseFile, auth, firestore, "personal-sync-$unique@example.test")
        try {
            action(fixture)
        } finally {
            withContext(NonCancellable) {
                try {
                    withTimeout(15_000) {
                        firestore.enableNetwork().await()
                        auth.currentUser?.takeIf { it.email == fixture.email }?.let { user ->
                            // Some personal documents cannot be deleted by clients. This
                            // unique test UID's emulator-only documents expire on shutdown.
                            user.delete().await()
                        }
                    }
                } catch (error: Exception) {
                    Log.w("PersonalSyncDeviceTest", "Could not finish isolated emulator cleanup", error)
                }
                auth.signOut()
                try {
                    withTimeout(10_000) {
                        firestore.terminate().await()
                        firestore.clearPersistence().await()
                    }
                } finally {
                    app.delete()
                    SQLiteDatabase.deleteDatabase(databaseFile)
                }
            }
        }
    }

    private class Fixture(
        val context: Context,
        val databaseFile: File,
        val auth: FirebaseAuth,
        val firestore: FirebaseFirestore,
        val email: String,
    ) {
        suspend fun withRepository(store: SqlitePersonalDataStore, action: suspend (FirestoreSpoonRepository) -> Unit) {
            val repository = FirestoreSpoonRepository(
                auth = auth, firestore = firestore,
                recipeCatalog = InMemoryRecipeCatalog(listOf(
                    Recipe(id = "argiro_1", title = "Lentils", category = MealCategory.LEGUMES.key),
                    Recipe(id = "argiro_2", title = "Rice", category = MealCategory.PASTA_RICE.key),
                )),
                mealPlanOutbox = EmptyLegacyOutbox,
                ownerBootstrapStore = object : OwnerBootstrapStore {
                    override fun isComplete(ownerUid: String) = false
                    override fun markComplete(ownerUid: String): Boolean = error("SQLite owns local readiness")
                },
                personalDataStore = store,
            )
            try {
                repository.ensureReady()
                action(repository)
            } finally {
                repository.close()
            }
        }

        fun ownerQuery(owner: String, path: String) =
            firestore.collection("spoon").document(owner).collection(path).let {
                if (path == "preferences") it.whereEqualTo(FieldPath.documentId(), "meal") else it
            }

        suspend fun assertServerSnapshot(owner: String, expected: PersonalDataSnapshot) {
            val collections = mapOf(
                "mealPlans" to expected.mealPlans.associate { it.date to it.toFirestoreDocument() },
                "favorites" to expected.favorites.associate { it.recipeId to it.toFirestoreDocument() },
                "shoppingItems" to expected.shoppingItems.associate { it.id to it.toFirestoreDocument() },
                "recipeNotes" to expected.recipeNotes.associate { it.recipeId to it.toFirestoreDocument() },
                "customRecipes" to expected.customRecipes.associate { it.id to it.toFirestoreDocument() },
                "cookedHistory" to expected.cookedHistory.associate { it.id to cookedMealDocument(
                    it.date, it.recipeId, it.recipeTitle, it.completedAtEpochMillis) },
                "preferences" to mapOf("meal" to expected.preferences.toFirestoreDocument()),
            )
            assertEquals(PersonalCollection.entries.size, collections.size)
            for ((path, documents) in collections) {
                val remote = ownerQuery(owner, path).get(Source.SERVER).await()
                assertFalse("$path must be confirmed by the server", remote.metadata.isFromCache)
                assertFalse("$path must have no unacknowledged SDK writes", remote.metadata.hasPendingWrites())
                assertEquals("Unexpected server contents for $path", firestoreWireValue(documents),
                    remote.documents.associate { it.id to checkNotNull(it.data) })
            }
        }
    }


    private fun guestSnapshot(): PersonalDataSnapshot {
        val completed = CookedMeal(id = newCookedMealEventId(), date = "2026-09-10",
            recipeId = "argiro_1", recipeTitle = "Lentils", completedAtEpochMillis = 3_000)
        val archived = CookedMeal(id = newCookedMealEventId(), date = completed.date,
            recipeId = "argiro_2", recipeTitle = "Earlier rice", completedAtEpochMillis = 2_000)
        val withoutPlan = CookedMeal(id = newCookedMealEventId(), date = "2026-09-09",
            recipeId = "argiro_2", recipeTitle = "Yesterday rice", completedAtEpochMillis = 1_000)
        return PersonalDataSnapshot(
            mealPlans = listOf(DayMealPlan(date = completed.date, recipeId = completed.recipeId,
                recipeTitle = completed.recipeTitle, category = MealCategory.LEGUMES.key,
                filters = RecipeFilters(category = MealCategory.LEGUMES.key), completed = true,
                completionEventId = completed.id, completedAtEpochMillis = completed.completedAtEpochMillis,
                updatedAtEpochMillis = completed.completedAtEpochMillis)),
            favorites = listOf(FavoriteRecipe(recipeId = "argiro_1", addedAtEpochMillis = 1_000)),
            shoppingItems = listOf(ShoppingListItem(id = "milk", name = "Milk",
                createdAtEpochMillis = 1_000, updatedAtEpochMillis = 1_000)),
            recipeNotes = listOf(RecipeNote(recipeId = "argiro_1", text = "Original note", updatedAtEpochMillis = 1_000)),
            customRecipes = listOf(CustomRecipe(id = newCustomRecipeId(), title = "Homemade lemonade",
                category = MealCategory.DRINKS.key,
                ingredientSections = listOf(RecipeIngredientSection(ingredients = listOf(RecipeIngredient(title = "Lemon")))),
                methodSections = listOf(RecipeMethodSection(steps = listOf("Mix with water."))),
                photoDataUri = "data:image/jpeg;base64,AQID", createdAtEpochMillis = 1_000, updatedAtEpochMillis = 1_000)),
            cookedHistory = listOf(completed, archived, withoutPlan),
            preferences = MealPreferenceSettings(veganOnly = true, excludedSourceKeys = setOf("cookpad"),
                weekdayCategories = mapOf("MONDAY" to MealCategory.LEGUMES.key),
                sideWeekdayCategories = mapOf("TUESDAY" to MealCategory.VEGETABLES.key),
                dessertWeekdayCategories = mapOf("WEDNESDAY" to MealCategory.DESSERT.key),
                excludedCategories = setOf(MealCategory.OTHER.key, MealCategory.DRINKS.key),
                updatedAtEpochMillis = 1_000),
        )
    }

    private object EmptyLegacyOutbox : MealPlanOutbox {
        override fun ownerlessPlans(): List<DayMealPlan> = emptyList()
        override fun pendingPlans(ownerUid: String): List<DayMealPlan> = emptyList()
        override fun claimOwnerless(ownerUid: String): List<DayMealPlan> = error("SQLite owns migration")
        override fun upsertOwnerless(plan: DayMealPlan): Boolean = error("SQLite owns persistence")
        override fun upsertForOwner(ownerUid: String, plan: DayMealPlan): Boolean = error("SQLite owns persistence")
        override fun removeAcknowledged(ownerUid: String, plan: DayMealPlan): Boolean = error("SQLite owns acknowledgement")
    }

    private companion object {
        const val Password = "Local-test-password-9199"
    }
}

/** Firestore stores integral primitives as Long and floating primitives as Double. */
private fun firestoreWireValue(value: Any?): Any? = when (value) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Float -> value.toDouble()
    is Map<*, *> -> value.mapValues { (_, item) -> firestoreWireValue(item) }
    is List<*> -> value.map(::firestoreWireValue)
    else -> value
}
