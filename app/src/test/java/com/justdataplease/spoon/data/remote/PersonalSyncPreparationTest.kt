package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.local.PendingPersonalWrite
import com.justdataplease.spoon.data.local.PersonalCollection
import com.justdataplease.spoon.data.local.PersonalDataSnapshot
import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.MealCoursePlan
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalSyncPreparationTest {
    @Test
    fun `sync preserves dependency order when creating and removing referenced data`() {
        val historyCreate = write(PersonalCollection.COOKED_HISTORY, "history-create")
        val historyDelete = write(PersonalCollection.COOKED_HISTORY, "history-delete", null)
        val customCreate = write(PersonalCollection.CUSTOM_RECIPES, "custom-create")
        val customDelete = write(PersonalCollection.CUSTOM_RECIPES, "custom-delete", null)
        val plan = write(PersonalCollection.MEAL_PLANS, "plan")
        val favoriteDelete = write(PersonalCollection.FAVORITES, "favorite-delete", null)
        val noteDelete = write(PersonalCollection.NOTES, "note-delete", null)
        val ordered = listOf(historyDelete, customDelete, plan, noteDelete, favoriteDelete, customCreate, historyCreate)
            .sortedBy(::syncOrder)
        assertTrue(ordered.indexOf(historyCreate) < ordered.indexOf(plan))
        assertTrue(ordered.indexOf(customCreate) < ordered.indexOf(plan))
        assertTrue(ordered.indexOf(plan) < ordered.indexOf(historyDelete))
        assertTrue(ordered.indexOf(favoriteDelete) < ordered.indexOf(customDelete))
        assertTrue(ordered.indexOf(noteDelete) < ordered.indexOf(customDelete))
        assertEquals(ordered, boundedSyncChunks(ordered).flatten())
    }

    @Test
    fun `large multibyte payloads split by encoded bytes without losing or reordering rows`() {
        val writes = (1..17).map { write(PersonalCollection.CUSTOM_RECIPES, "photo-$it", "φ".repeat(300_000)) }
        val chunks = boundedSyncChunks(writes)
        assertEquals(writes, chunks.flatten())
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { chunk ->
            chunk.sumOf { (it.payload?.toByteArray(Charsets.UTF_8)?.size ?: 0) + 1024L } <= 4 * 1024 * 1024
        })
    }

    @Test
    fun `long deletion queues split by count with every tombstone retained`() {
        val writes = (1..251).map { write(PersonalCollection.COOKED_HISTORY, "event-$it", null) }
        val chunks = boundedSyncChunks(writes)
        assertTrue(chunks.all { it.size <= 9 })
        assertEquals(writes, chunks.flatten())
        assertTrue(boundedSyncChunks(emptyList()).isEmpty())
    }

    @Test
    fun `legacy normalization retains each completed course and prior history with stable matching references`() {
        val previous = CookedMeal(id = "previous", date = "2026-09-09", recipeId = "prior", completedAtEpochMillis = 5)
        val original = PersonalDataSnapshot(
            mealPlans = listOf(DayMealPlan(
                date = "2026-09-10", recipeId = "main", recipeTitle = "Main", completed = true,
                updatedAtEpochMillis = 20, completedAtEpochMillis = 10,
                side = MealCoursePlan(recipeId = "side", recipeTitle = "Side", completed = true,
                    updatedAtEpochMillis = 20, completedAtEpochMillis = 11),
            )),
            cookedHistory = listOf(previous),
        )
        val normalized = normalizeLegacyHistory(original)
        assertEquals(3, normalized.cookedHistory.size)
        assertEquals(setOf("prior", "main", "side"), normalized.cookedHistory.map { it.recipeId }.toSet())
        val plan = normalized.mealPlans.single()
        assertEquals("main", normalized.cookedHistory.single { it.id == plan.completionEventId }.recipeId)
        assertEquals("side", normalized.cookedHistory.single { it.id == plan.side?.completionEventId }.recipeId)
        assertEquals(normalized, normalizeLegacyHistory(normalized))
    }

    @Test
    fun `legacy active preferences receive a sync revision while untouched defaults stay absent`() {
        val active = normalizeLegacyHistory(PersonalDataSnapshot(preferences = MealPreferenceSettings(veganOnly = true)))
        assertTrue(active.preferences.veganOnly)
        assertTrue(active.preferences.updatedAtEpochMillis > 0)
        val defaults = normalizeLegacyHistory(PersonalDataSnapshot())
        assertEquals(0L, defaults.preferences.updatedAtEpochMillis)
        assertFalse(defaults.preferences.hasActiveSelections())
    }

    private fun write(collection: PersonalCollection, id: String, payload: String? = "{}") =
        PendingPersonalWrite(collection, id, payload, revision = id)
}
