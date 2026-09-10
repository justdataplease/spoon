package com.justdataplease.spoon.data.remote

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.justdataplease.spoon.data.local.InMemoryRecipeCatalog
import com.justdataplease.spoon.data.local.NoBackupAccountTransferStore
import com.justdataplease.spoon.data.local.PersonalCollection
import com.justdataplease.spoon.data.local.PersonalDataSnapshot
import com.justdataplease.spoon.data.local.SqlitePersonalDataStore
import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.FavoriteRecipe
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeIngredient
import com.justdataplease.spoon.data.model.RecipeIngredientSection
import com.justdataplease.spoon.data.model.RecipeMethodSection
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.data.model.newCustomRecipeId
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.domain.repository.AccountFailureKind
import com.justdataplease.spoon.domain.repository.AccountOperationException
import com.justdataplease.spoon.domain.repository.AccountState
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Opt-in only: the fake project/key and emulator endpoint can never address production Auth. */
class FirebaseRegistrationDeviceTest {
    @Before
    fun requireExplicitLocalAuthEmulator() {
        assumeTrue("Requires -e authEmulator true and the local demo Auth emulator",
            InstrumentationRegistry.getArguments().getString("authEmulator") == "true")
    }

    @Test
    fun guestRegistrationAttachesAllCollectionsAndDuplicateSignupPreservesLaterGuestData() = runBlocking {
        withTimeout(150_000) {
            withIsolatedAuth { fixture ->
                var ownerUid = ""
                var additionalHistoryId = ""
                val email = fixture.email
                SqlitePersonalDataStore(fixture.context, fixture.databaseFile).use { store ->
                    val original = store.mutate(null) { guestSnapshot(historyCount = 1_200) }
                    fixture.withRepository(store) { repository ->
                        assertNull(fixture.auth.currentUser)
                        assertEquals(original, store.read(null))
                        repository.createAccountWithEmail(email, Password)
                        val user = checkNotNull(fixture.auth.currentUser)
                        fixture.createdUsers += user
                        ownerUid = user.uid
                        assertFalse(user.isAnonymous)
                        assertEquals(email, user.email)
                        assertEquals(ownerUid, (repository.accountState.value as AccountState.Email).uid)
                        assertEquals(original, store.read(ownerUid))
                        assertEquals(PersonalDataSnapshot(), store.read(null))
                        assertEquals(1_200, store.read(ownerUid).cookedHistory.size)
                        assertEquals(PersonalCollection.entries.toSet(), store.pending(ownerUid).map { it.collection }.toSet())
                        assertEquals(1_206, store.pending(ownerUid).size)
                        assertEquals(original.shoppingItems, repository.shoppingItems.value)
                        assertEquals(original.customRecipes.single().title,
                            repository.getRecipeDetails(original.customRecipes.single().id)?.title)

                        repository.signOut()
                        assertNull(fixture.auth.currentUser)
                        repository.upsertShoppingItems(listOf(shopping("later-guest-item")))
                        repository.upsertRecipeNote(RecipeNote(recipeId = "argiro_2", text = "Later guest note", updatedAtEpochMillis = 5_000))
                        repository.upsertMealPlan(DayMealPlan(date = "2026-09-11", recipeId = "argiro_2",
                            recipeTitle = "Rice", category = MealCategory.PASTA_RICE.key, updatedAtEpochMillis = 5_000))
                        repository.setMealCompleted("2026-09-11", true)
                        val laterGuest = store.read(null)
                        additionalHistoryId = laterGuest.cookedHistory.single().id
                        val failure = try {
                            repository.createAccountWithEmail(email, Password)
                            null
                        } catch (error: AccountOperationException) {
                            error.failure
                        }
                        assertEquals(AccountFailureKind.EMAIL_IN_USE, failure?.kind)
                        assertNull(fixture.auth.currentUser)
                        assertEquals(laterGuest, store.read(null))
                        assertEquals(original, store.read(ownerUid))
                        assertEquals(laterGuest.shoppingItems, repository.shoppingItems.value)

                        repository.signInWithEmail(email, Password)
                        assertEquals(ownerUid, fixture.auth.currentUser?.uid)
                        assertEquals(PersonalDataSnapshot(), store.read(null))
                        val merged = store.read(ownerUid)
                        assertEquals(1_201, merged.cookedHistory.size)
                        assertTrue(merged.cookedHistory.any { it.id == additionalHistoryId })
                        assertEquals(setOf("initial-item", "later-guest-item"), merged.shoppingItems.map { it.id }.toSet())
                        assertEquals(setOf("argiro_1", "argiro_2"), merged.recipeNotes.map { it.recipeId }.toSet())
                        assertEquals(original.preferences, merged.preferences)
                        assertEquals(original.customRecipes, merged.customRecipes)
                        assertTrue(store.pending(ownerUid).all { !it.acknowledged })
                    }
                }
                // Authentication and the claimed personal database are reopened independently.
                SqlitePersonalDataStore(fixture.context, fixture.databaseFile).use { store ->
                    fixture.withRepository(store) { repository ->
                        assertEquals(ownerUid, (repository.accountState.value as AccountState.Email).uid)
                        assertEquals(1_201, store.read(ownerUid).cookedHistory.size)
                        assertTrue(store.read(ownerUid).cookedHistory.any { it.id == additionalHistoryId })
                        assertEquals(setOf("initial-item", "later-guest-item"), repository.shoppingItems.value.map { it.id }.toSet())
                        assertEquals(PersonalDataSnapshot(), store.read(null))
                        assertTrue(store.pending(ownerUid).isNotEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun anonymousRegistrationTransfersTheExistingOwnerAndClearsItsDurableIntent() = runBlocking {
        withTimeout(90_000) {
            withIsolatedAuth { fixture ->
                val anonymous = checkNotNull(fixture.auth.signInAnonymously().await().user)
                fixture.createdUsers += anonymous
                assertTrue(anonymous.isAnonymous)
                SqlitePersonalDataStore(fixture.context, fixture.databaseFile).use { store ->
                    val original = store.mutate(anonymous.uid) { guestSnapshot(historyCount = 5) }
                    fixture.withRepository(store) { repository ->
                        assertEquals(anonymous.uid, (repository.accountState.value as AccountState.Anonymous).uid)
                        repository.createAccountWithEmail(fixture.email, Password)
                        val user = checkNotNull(fixture.auth.currentUser)
                        fixture.createdUsers += user
                        assertFalse(user.isAnonymous)
                        assertNotEquals(anonymous.uid, user.uid)
                        assertEquals(original, store.read(user.uid))
                        assertEquals(PersonalDataSnapshot(), store.read(anonymous.uid))
                        assertEquals(PersonalDataSnapshot(), store.read(null))
                        assertEquals(original.shoppingItems, repository.shoppingItems.value)
                        assertEquals(PersonalCollection.entries.toSet(), store.pending(user.uid).map { it.collection }.toSet())
                        assertTrue(store.pending(anonymous.uid).isEmpty())
                        assertNull(fixture.transferStore.read())
                    }
                }
            }
        }
    }

    private suspend fun withIsolatedAuth(action: suspend (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = UUID.randomUUID().toString()
        val databaseFile = File(context.cacheDir, "auth-registration-$unique.db")
        val transferFile = File(context.cacheDir, "auth-transfer-$unique.json")
        val app = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId("demo-spoon-registration")
            .setApplicationId("1:1234567890:android:0123456789abcdef")
            .setApiKey("fake-api-key-for-local-auth-emulator")
            .build(), "registration-test-$unique")
        val auth = FirebaseAuth.getInstance(app)
        // Always before any user/session operation; never use the default Firebase app.
        auth.useEmulator("10.0.2.2", 9199)
        val fixture = Fixture(context, databaseFile, NoBackupAccountTransferStore(context, transferFile), auth,
            "registration-$unique@example.test")
        try {
            action(fixture)
        } finally {
            withContext(NonCancellable) {
                val currentTestUser = auth.currentUser?.takeIf { it.email == fixture.email }
                (fixture.createdUsers + listOfNotNull(currentTestUser)).distinctBy { it.uid }.asReversed().forEach { user ->
                    try {
                        withTimeout(10_000) { user.delete().await() }
                    } catch (error: Exception) {
                        Log.w("RegistrationDeviceTest", "Could not remove this test's local-emulator user", error)
                    }
                }
                auth.signOut()
                app.delete()
                SQLiteDatabase.deleteDatabase(databaseFile)
                transferFile.delete()
                File(transferFile.path + ".bak").delete()
                File(transferFile.path + ".new").delete()
            }
        }
    }

    private class Fixture(
        val context: Context,
        val databaseFile: File,
        val transferStore: NoBackupAccountTransferStore,
        val auth: FirebaseAuth,
        val email: String,
    ) {
        val createdUsers = mutableListOf<FirebaseUser>()

        suspend fun withRepository(store: SqlitePersonalDataStore, action: suspend (FirestoreSpoonRepository) -> Unit) {
            val repository = FirestoreSpoonRepository(
                auth = auth, firestore = null,
                recipeCatalog = InMemoryRecipeCatalog(listOf(
                    Recipe(id = "argiro_1", title = "Lentils", category = MealCategory.LEGUMES.key),
                    Recipe(id = "argiro_2", title = "Rice", category = MealCategory.PASTA_RICE.key),
                )),
                mealPlanOutbox = EmptyLegacyOutbox,
                ownerBootstrapStore = object : OwnerBootstrapStore {
                    override fun isComplete(ownerUid: String) = false
                    override fun markComplete(ownerUid: String): Boolean = error("Firestore is absent in this test")
                },
                personalDataStore = store, accountTransferStore = transferStore,
            )
            try {
                repository.ensureReady()
                action(repository)
            } finally {
                repository.close()
            }
        }
    }

    private fun shopping(id: String) = ShoppingListItem(id = id, name = id,
        createdAtEpochMillis = 1_000, updatedAtEpochMillis = 1_000)

    private fun guestSnapshot(historyCount: Int): PersonalDataSnapshot {
        val custom = CustomRecipe(id = newCustomRecipeId(), title = "Private lentils", category = MealCategory.LEGUMES.key,
            ingredientSections = listOf(RecipeIngredientSection(ingredients = listOf(RecipeIngredient(title = "Lentils")))),
            methodSections = listOf(RecipeMethodSection(steps = listOf("Boil the lentils."))),
            photoDataUri = "data:image/jpeg;base64,AQID", createdAtEpochMillis = 1_000, updatedAtEpochMillis = 1_000)
        return PersonalDataSnapshot(
            mealPlans = listOf(DayMealPlan(date = "2026-09-10", recipeId = "argiro_1", recipeTitle = "Lentils",
                category = MealCategory.LEGUMES.key, updatedAtEpochMillis = 1_000)),
            favorites = listOf(FavoriteRecipe(recipeId = "argiro_1", addedAtEpochMillis = 1_000)),
            shoppingItems = listOf(shopping("initial-item")),
            recipeNotes = listOf(RecipeNote(recipeId = "argiro_1", text = "Private note", updatedAtEpochMillis = 1_000)),
            customRecipes = listOf(custom),
            cookedHistory = (0 until historyCount).map { index -> CookedMeal(
                id = "cooked_" + index.toString(16).padStart(32, '0'),
                date = LocalDate.of(2023, 1, 1).plusDays(index.toLong()).toString(),
                recipeId = "argiro_1", recipeTitle = "Lentils",
                completedAtEpochMillis = 1_672_531_200_000L + index * 86_400_000L,
            ) },
            preferences = MealPreferenceSettings(veganOnly = true, excludedSourceKeys = setOf("cookpad"),
                updatedAtEpochMillis = 1_000),
        )
    }

    private object EmptyLegacyOutbox : MealPlanOutbox {
        override fun ownerlessPlans(): List<DayMealPlan> = emptyList()
        override fun pendingPlans(ownerUid: String): List<DayMealPlan> = emptyList()
        override fun claimOwnerless(ownerUid: String): List<DayMealPlan> = error("SQLite owns migration")
        override fun upsertOwnerless(plan: DayMealPlan): Boolean = error("SQLite owns persistence")
        override fun upsertForOwner(ownerUid: String, plan: DayMealPlan): Boolean = error("SQLite owns persistence")
        override fun removeAcknowledged(ownerUid: String, plan: DayMealPlan): Boolean = error("No Firestore in this test")
    }

    private companion object {
        const val Password = "Local-test-password-9199"
    }
}
