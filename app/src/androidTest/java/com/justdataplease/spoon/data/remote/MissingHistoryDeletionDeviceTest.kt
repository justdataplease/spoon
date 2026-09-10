package com.justdataplease.spoon.data.remote

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.justdataplease.spoon.data.local.InMemoryRecipeCatalog
import com.justdataplease.spoon.data.local.PersonalDataSnapshot
import com.justdataplease.spoon.data.local.SqlitePersonalDataStore
import com.justdataplease.spoon.data.model.DayMealPlan
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingHistoryDeletionDeviceTest {
    @Test
    fun actualRepositoryUndoAndHistoryDeletePersistTombstonesBeforeHistoryWasDownloaded() = runBlocking {
        withTimeout(30_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            for (deleteFromHistory in listOf(false, true)) {
                val file = File(context.cacheDir, "missing-history-test-${UUID.randomUUID()}.db")
                val eventId = "cooked_" + (if (deleteFromHistory) "2" else "1").repeat(32)
                try {
                    SqlitePersonalDataStore(context, file).use { store ->
                        // Simulate completed plans arriving before the separate history query.
                        store.mutate(null) { PersonalDataSnapshot(mealPlans = listOf(
                            completedPlan("2026-09-10", eventId),
                        )) }
                        assertTrue(store.read(null).cookedHistory.isEmpty())
                        val repository = FirestoreSpoonRepository(
                            auth = null, firestore = null, recipeCatalog = InMemoryRecipeCatalog(emptyList()),
                            mealPlanOutbox = EmptyOutbox, personalDataStore = store,
                            ownerBootstrapStore = object : OwnerBootstrapStore {
                                override fun isComplete(ownerUid: String) = false
                                override fun markComplete(ownerUid: String) = true
                            },
                        )
                        try {
                            repository.ensureReady()
                            repository.cookedHistory.first { it.size == 1 }
                            if (deleteFromHistory) repository.deleteCookedHistoryEntry(eventId)
                            else repository.setMealCompleted("2026-09-10", false)
                            repository.cookedHistory.first { it.isEmpty() }
                        } finally {
                            repository.close()
                        }
                    }
                    SqlitePersonalDataStore(context, file).use { restarted ->
                        assertTrue(restarted.read(null).cookedHistory.isEmpty())
                        assertTrue(restarted.read(null).mealPlans.none { it.completed })
                    }
                    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("""
                            SELECT document_id, payload, revision FROM personal_documents
                            WHERE owner = ? AND collection = ? ORDER BY document_id
                        """.trimIndent(), arrayOf("guest", "COOKED_HISTORY")).use { cursor ->
                            val ids = mutableSetOf<String>()
                            while (cursor.moveToNext()) {
                                ids += cursor.getString(0)
                                assertTrue(cursor.isNull(1))
                                assertFalse(cursor.isNull(2))
                            }
                            assertEquals(setOf(eventId), ids)
                        }
                    }
                } finally {
                    SQLiteDatabase.deleteDatabase(file)
                }
            }
        }
    }

    private fun completedPlan(date: String, eventId: String) = DayMealPlan(
        id = date, date = date, recipeId = "argiro_1", recipeTitle = "Recipe",
        completed = true, completionEventId = eventId, completedAtEpochMillis = 10, updatedAtEpochMillis = 10,
    )

    private object EmptyOutbox : MealPlanOutbox {
        override fun ownerlessPlans(): List<DayMealPlan> = emptyList()
        override fun pendingPlans(ownerUid: String): List<DayMealPlan> = emptyList()
        override fun claimOwnerless(ownerUid: String): List<DayMealPlan> = emptyList()
        override fun upsertOwnerless(plan: DayMealPlan) = error("Local changes must use SQLite")
        override fun upsertForOwner(ownerUid: String, plan: DayMealPlan) = error("Local changes must use SQLite")
        override fun removeAcknowledged(ownerUid: String, plan: DayMealPlan) = error("No cloud acknowledgement is required")
    }
}
