package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.domain.repository.BackendFailure
import com.justdataplease.spoon.domain.repository.BackendFailureKind
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPlanOutboxTest {
    @Test
    fun `newer plan wins per date and plans remain chronological`() {
        val oldMonday = plan("2026-09-07", "old", revision = 10L)
        val tuesday = plan("2026-09-08", "tuesday", revision = 20L)
        val newMonday = plan("2026-09-07", "new", revision = 11L)

        val merged = mergeMealPlanSnapshots(
            listOf(tuesday, oldMonday),
            listOf(newMonday),
        )

        assertEquals(listOf("2026-09-07", "2026-09-08"), merged.map { it.date })
        assertEquals("new", merged.first().recipeId)
        assertEquals("2026-09-07", merged.first().id)
    }

    @Test
    fun `later snapshot wins an equal revision`() {
        val local = plan("2026-09-07", "local", revision = 10L)
        val remote = plan("2026-09-07", "remote", revision = 10L)

        assertEquals(
            "remote",
            mergeMealPlanSnapshots(listOf(local), listOf(remote)).single().recipeId,
        )
    }

    @Test
    fun `equal revision acknowledgement clears only the exact pending reroll`() {
        val pending = plan("2026-09-07", "latest", revision = 10L)

        assertTrue(
            mealPlanAcknowledgementSupersedes(pending, pending.copy(id = "remote-document-id")),
        )
        assertFalse(
            mealPlanAcknowledgementSupersedes(
                pending,
                pending.copy(recipeId = "older-choice", recipeTitle = "older-choice"),
            ),
        )
        assertFalse(
            mealPlanAcknowledgementSupersedes(
                pending,
                pending.copy(updatedAtEpochMillis = 9L),
            ),
        )
        assertTrue(
            mealPlanAcknowledgementSupersedes(
                pending,
                pending.copy(recipeId = "server-newer", updatedAtEpochMillis = 11L),
            ),
        )
    }

    @Test
    fun `owner keys are stable opaque and isolated`() {
        val ownerA = mealPlanOwnerKey("owner-a")
        val ownerB = mealPlanOwnerKey("owner-b")

        assertEquals(ownerA, mealPlanOwnerKey("owner-a"))
        assertFalse(ownerA.contains("owner-a"))
        assertFalse(ownerA == ownerB)
        assertEquals(64, ownerA.removePrefix("owner_").length)
    }

    @Test
    fun `planning waits for cached meal plan and custom recipe snapshots`() {
        val failure = BackendFailure(BackendFailureKind.NETWORK, true, "offline")

        assertTrue(
            localPlanningCanStart(
                hasOwner = false,
                mealPlanState = CloudComponentState.Pending,
                customRecipeState = CloudComponentState.Pending,
            ),
        )
        assertFalse(
            localPlanningCanStart(
                hasOwner = true,
                mealPlanState = CloudComponentState.Pending,
                customRecipeState = CloudComponentState.Ready,
            ),
        )
        assertFalse(
            localPlanningCanStart(
                hasOwner = true,
                mealPlanState = CloudComponentState.Ready,
                customRecipeState = CloudComponentState.Pending,
            ),
        )
        assertTrue(
            localPlanningCanStart(
                hasOwner = true,
                mealPlanState = CloudComponentState.Ready,
                customRecipeState = CloudComponentState.Ready,
            ),
        )
        assertTrue(
            localPlanningCanStart(
                hasOwner = true,
                mealPlanState = CloudComponentState.Failed(failure),
                customRecipeState = CloudComponentState.Ready,
            ),
        )
    }

    @Test
    fun `remote refresh drops absent nonpending dates but preserves pending dates`() {
        val remote = plan("2026-09-07", "remote", revision = 10L)
        val pending = plan("2026-09-08", "pending", revision = 20L)
        val previousNonpending = plan("2026-09-09", "old-local-nonpending", revision = 30L)

        val merged = mergeRemoteAndPendingMealPlans(listOf(remote), listOf(pending))

        assertTrue(previousNonpending.recipeId !in merged.map { it.recipeId })
        assertEquals(listOf("remote", "pending"), merged.map { it.recipeId })
    }

    @Test
    fun `ownerless plans survive restart and are claimed once without crossing owners`() {
        val storage = TestFileMealPlanOutboxStorage(newOutboxFile())
        val ownerlessMonday = plan("2026-09-07", "ownerless-new", revision = 20L)
        val olderOwnedMonday = plan("2026-09-07", "owned-old", revision = 10L)
        val ownedTuesday = plan("2026-09-08", "owned-tuesday", revision = 30L)
        NoBackupMealPlanOutbox(storage, Json).apply {
            assertTrue(upsertForOwner("owner-a", olderOwnedMonday))
            assertTrue(upsertForOwner("owner-a", ownedTuesday))
            assertTrue(upsertOwnerless(ownerlessMonday))
        }

        val restarted = NoBackupMealPlanOutbox(storage, Json)
        assertEquals(
            listOf(ownerlessMonday.copy(id = ownerlessMonday.date)),
            restarted.ownerlessPlans(),
        )
        assertEquals(
            listOf("ownerless-new", "owned-tuesday"),
            restarted.claimOwnerless("owner-a").map(DayMealPlan::recipeId),
        )
        assertTrue(restarted.ownerlessPlans().isEmpty())

        val restartedAfterClaim = NoBackupMealPlanOutbox(storage, Json)
        assertEquals(
            listOf("ownerless-new", "owned-tuesday"),
            restartedAfterClaim.pendingPlans("owner-a").map(DayMealPlan::recipeId),
        )
        assertTrue(restartedAfterClaim.claimOwnerless("owner-b").isEmpty())
        assertTrue(restartedAfterClaim.pendingPlans("owner-b").isEmpty())
        assertEquals(2, restartedAfterClaim.pendingPlans("owner-a").size)
    }

    @Test
    fun `late acknowledgement cannot clear a newer persisted reroll`() {
        val storage = TestFileMealPlanOutboxStorage(newOutboxFile())
        val first = plan("2026-09-07", "first", revision = 10L)
        val reroll = plan("2026-09-07", "reroll", revision = 11L)
        val outbox = NoBackupMealPlanOutbox(storage, Json)
        assertTrue(outbox.upsertForOwner("owner-a", first))
        assertTrue(outbox.upsertForOwner("owner-a", reroll))

        assertTrue(outbox.removeAcknowledged("owner-a", first))
        assertEquals(
            listOf(reroll.copy(id = reroll.date)),
            NoBackupMealPlanOutbox(storage, Json).pendingPlans("owner-a"),
        )

        assertTrue(outbox.removeAcknowledged("owner-a", reroll.copy(id = "remote-id")))
        assertTrue(NoBackupMealPlanOutbox(storage, Json).pendingPlans("owner-a").isEmpty())
    }

    @Test
    fun `bounded UTF-8 reader uses byte limits without truncating multibyte text`() {
        val text = "γάλα και αυγό"
        val bytes = text.toByteArray(Charsets.UTF_8)

        assertEquals(
            text,
            ByteArrayInputStream(bytes).readUtf8WithinLimit(bytes.size.toLong()),
        )
        assertNull(
            ByteArrayInputStream(bytes).readUtf8WithinLimit(bytes.size.toLong() - 1L),
        )
    }

    private fun plan(date: String, recipeId: String, revision: Long) = DayMealPlan(
        date = date,
        recipeId = recipeId,
        recipeTitle = recipeId,
        updatedAtEpochMillis = revision,
    )

    private fun newOutboxFile(): File = File.createTempFile("meal_plan_outbox_", ".json")
        .also {
            assertTrue(it.delete())
            it.deleteOnExit()
        }

    private class TestFileMealPlanOutboxStorage(
        private val file: File,
    ) : MealPlanOutboxStorage {
        override fun readUtf8(maxBytes: Long): String? = file
            .takeIf { it.isFile && it.length() <= maxBytes }
            ?.readText(Charsets.UTF_8)

        override fun writeUtf8(bytes: ByteArray): Boolean = runCatching {
            file.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }
}
