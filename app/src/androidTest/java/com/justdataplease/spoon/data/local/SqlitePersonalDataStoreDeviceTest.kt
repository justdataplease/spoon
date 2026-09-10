package com.justdataplease.spoon.data.local

import androidx.test.platform.app.InstrumentationRegistry
import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.FavoriteRecipe
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A private disposable database exercises SQLite transactions without touching any user data. */
class SqlitePersonalDataStoreDeviceTest {
    @Test
    fun guestHistoryPhotosAndPendingDeletesSurviveDatabaseReopenAndOwnerClaim() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "personal-store-test-${UUID.randomUUID()}.db")
        val photo = "data:image/jpeg;base64," + "a".repeat(650_000)
        try {
            SqlitePersonalDataStore(context, file).use { store ->
                store.mutate(null) { PersonalDataSnapshot(
                    customRecipes = listOf(CustomRecipe(id = "custom-test", title = "Local recipe", photoDataUri = photo, updatedAtEpochMillis = 10)),
                    cookedHistory = (1..1_200).map { number -> CookedMeal(
                        id = "event-$number", date = "2026-09-10", recipeId = "recipe",
                        recipeTitle = "Recipe", completedAtEpochMillis = number.toLong(),
                    ) },
                    favorites = listOf(FavoriteRecipe(id = "recipe", recipeId = "recipe", addedAtEpochMillis = 10)),
                ) }
                store.mutate(null) { it.copy(favorites = emptyList()) }
                // This independent account already owns the same favorite. A guest deletion
                // must never remove it when the guest profile is claimed.
                store.mergeRemote("provisioned-test-owner", PersonalCollection.FAVORITES,
                    PersonalDataSnapshot(favorites = listOf(FavoriteRecipe(
                        id = "recipe", recipeId = "recipe", addedAtEpochMillis = 20,
                    ))), authoritative = true)
            }
            SqlitePersonalDataStore(context, file).use { store ->
                assertEquals(1_200, store.read(null).cookedHistory.size)
                val claimed = store.claimGuest("provisioned-test-owner")
                assertEquals(1_200, claimed.cookedHistory.size)
                assertEquals(photo, claimed.customRecipes.single().photoDataUri)
                assertEquals("recipe", claimed.favorites.single().recipeId)
                assertEquals(1_201, store.pending("provisioned-test-owner").size)
                assertTrue(store.pending("provisioned-test-owner").none { it.collection == PersonalCollection.FAVORITES })
                // A deletion explicitly made inside this account is durable and must retry
                // after reopening, unlike the unrelated guest tombstone above.
                store.mutate("provisioned-test-owner") { it.copy(favorites = emptyList()) }
                assertEquals(1_202, store.pending("provisioned-test-owner").size)
            }
            SqlitePersonalDataStore(context, file).use { store ->
                assertEquals(PersonalDataSnapshot(), store.read(null))
                assertEquals(1_200, store.read("provisioned-test-owner").cookedHistory.size)
                val deletion = store.pending("provisioned-test-owner").single { it.collection == PersonalCollection.FAVORITES }
                assertNull(deletion.payload)
                assertTrue(store.read("provisioned-test-owner").favorites.isEmpty())
                store.acknowledge("provisioned-test-owner", deletion)
                assertEquals(1_201, store.pending("provisioned-test-owner").size)
                store.mergeRemote("provisioned-test-owner", PersonalCollection.FAVORITES,
                    PersonalDataSnapshot(favorites = listOf(FavoriteRecipe(
                        id = "recipe", recipeId = "recipe", addedAtEpochMillis = 20,
                    ))), authoritative = false)
                assertTrue(store.read("provisioned-test-owner").favorites.isEmpty())
                assertEquals(PersonalDataSnapshot(), store.read("another-test-owner"))
            }
            SqlitePersonalDataStore(context, file).use { store ->
                assertTrue(store.read("provisioned-test-owner").favorites.isEmpty())
                assertTrue(store.pending("provisioned-test-owner").none { it.collection == PersonalCollection.FAVORITES })
                assertEquals(1_200, store.read("provisioned-test-owner").cookedHistory.size)
            }
        } finally {
            // Delete only this test's uniquely named database and its SQLite sidecars.
            android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
        }
    }
}
