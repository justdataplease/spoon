package com.justdataplease.spoon.data.local

import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.FavoriteRecipe
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.data.model.mergeCookedHistory
import com.justdataplease.spoon.data.remote.projectMealCompletion
import com.justdataplease.spoon.data.remote.projectHistoryDeletion
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalDataStoreTest {
    @Test
    fun `guest can save every collection before authentication and claim once after restart`() {
        val disk = MemoryRows()
        val store = store(disk)
        val personal = completeSnapshot()
        val saved = store.mutate(null) { personal }
        assertEquals(PersonalCollection.entries.toSet(), disk.saved.values.single().values.map { it.collection }.toSet())

        val restarted = store(disk)
        assertEquals(saved, restarted.read(null))
        assertEquals(saved, restarted.claimGuest("alice"))
        assertEquals(PersonalDataSnapshot(), restarted.read(null))
        assertEquals(PersonalCollection.entries.toSet(), restarted.pending("alice").map { it.collection }.toSet())
        assertEquals(PersonalDataSnapshot(), restarted.claimGuest("bob"))
        assertEquals(saved, store(disk).read("alice"))
    }

    @Test
    fun `guest claim unions all history and resolves same document by timestamp`() {
        val store = store()
        store.mergeRemote("alice", PersonalCollection.COOKED_HISTORY, PersonalDataSnapshot(
            cookedHistory = listOf(history("owned", 5), history("same", 9)),
        ), true)
        store.mergeRemote("alice", PersonalCollection.MEAL_PLANS, PersonalDataSnapshot(
            mealPlans = listOf(plan("owned-new", 20)),
        ), true)
        store.mutate(null) { PersonalDataSnapshot(
            cookedHistory = listOf(history("guest", 10), history("same", 11)),
            mealPlans = listOf(plan("guest-old", 10)),
        ) }
        val claimed = store.claimGuest("alice")
        assertEquals(4, claimed.cookedHistory.size)
        assertEquals(setOf(5L, 9L, 10L, 11L), claimed.cookedHistory.map { it.completedAtEpochMillis }.toSet())
        assertEquals("owned-new", claimed.mealPlans.single().recipeId)
        assertEquals(2, store.pending("alice").size)
        assertTrue(store.pending("alice").all { it.collection == PersonalCollection.COOKED_HISTORY })
    }

    @Test
    fun `guest and anonymous claims discard source tombstones without deleting destination records`() {
        for (sourceOwner in listOf<String?>(null, "anonymous-id")) {
            val disk = MemoryRows()
            val store = store(disk)
            val source = completeSnapshot().copy(cookedHistory = listOf(
                history("2026-09-10", 10).copy(recipeId = "source-recipe"),
            ))
            val destination = source.copy(cookedHistory = listOf(
                history("2026-09-10", 10).copy(recipeId = "destination-recipe"),
            ))
            PersonalCollection.entries.forEach { collection ->
                store.mergeRemote("alice", collection, destination, true)
            }
            store.mutate(sourceOwner) { source }
            store.mutate(sourceOwner) { PersonalDataSnapshot() }
            val sourceRows = disk.saved.getValue(personalOwnerKey(sourceOwner)).values
            assertEquals(PersonalCollection.entries.size, sourceRows.size)
            assertTrue(sourceRows.all { it.payload == null && it.revision != null })

            val claimed = if (sourceOwner == null) store.claimGuest("alice")
                else store.claimAnonymous(sourceOwner, "alice")
            assertEquals(destination, claimed)
            assertEquals("destination-recipe", claimed.cookedHistory.single().recipeId)
            assertEquals("recipe", claimed.favorites.single().recipeId)
            assertTrue(store.pending("alice").isEmpty())
            assertEquals(PersonalDataSnapshot(), store.read(sourceOwner))
            assertTrue(disk.saved[personalOwnerKey(sourceOwner)].orEmpty().isEmpty())
        }
    }

    @Test
    fun `same owner claim preserves every pending deletion tombstone`() {
        val disk = MemoryRows()
        val store = store(disk)
        store.mutate("alice") { completeSnapshot() }
        store.mutate("alice") { PersonalDataSnapshot() }
        val pending = store.pending("alice")
        assertEquals(PersonalCollection.entries.size, pending.size)
        assertTrue(pending.all { it.payload == null })
        assertEquals(PersonalDataSnapshot(), store.claimAnonymous("alice", "alice"))
        assertEquals(pending, store(disk).pending("alice"))
    }

    @Test
    fun `successful write acknowledgement clears exact revision without a listener echo`() {
        val disk = MemoryRows()
        val store = store(disk)
        val changed = store.mutate("alice") { PersonalDataSnapshot(mealPlans = listOf(plan("mine", 10))) }
        val pending = store.pending("alice").single()
        store.acknowledge("alice", pending)
        assertTrue(store.pending("alice").isEmpty())
        assertEquals(changed, store(disk).read("alice"))
        assertTrue(store(disk).pending("alice").isEmpty())
    }

    @Test
    fun `cache echo never acknowledges an unaccepted local write`() {
        val store = store()
        val changed = store.mutate("alice") { PersonalDataSnapshot(mealPlans = listOf(plan("mine", 10))) }
        val pending = store.pending("alice").single()
        store.mergeRemote("alice", PersonalCollection.MEAL_PLANS, changed, false)
        assertEquals(pending, store.pending("alice").single())
        store.mergeRemote("alice", PersonalCollection.MEAL_PLANS, PersonalDataSnapshot(), false)
        assertEquals(pending, store.pending("alice").single())
        assertEquals(changed, store.read("alice"))
    }

    @Test
    fun `another device can replace or delete an acknowledged value when its exact echo was skipped`() {
        val store = store()
        store.mutate("alice") { PersonalDataSnapshot(recipeNotes = listOf(note("recipe", "mine", 10))) }
        val pending = store.pending("alice").single()
        store.acknowledge("alice", pending)
        val newer = PersonalDataSnapshot(recipeNotes = listOf(note("recipe", "other-device", 11)))
        store.mergeRemote("alice", PersonalCollection.NOTES, newer, true)
        assertEquals(newer, store.read("alice"))
        assertTrue(store.pending("alice").isEmpty())
        store.mergeRemote("alice", PersonalCollection.NOTES, PersonalDataSnapshot(), true)
        assertTrue(store.read("alice").recipeNotes.isEmpty())
        assertTrue(store.pending("alice").isEmpty())
    }

    @Test
    fun `server observation before task completion remains pending until acknowledged`() {
        val store = store()
        val changed = store.mutate("alice") { PersonalDataSnapshot(mealPlans = listOf(plan("mine", 10))) }
        val pending = store.pending("alice").single()
        store.mergeRemote("alice", PersonalCollection.MEAL_PLANS, changed, true)
        assertTrue(store.pending("alice").single().observed)
        assertFalse(store.pending("alice").single().acknowledged)
        store.acknowledge("alice", pending)
        assertTrue(store.pending("alice").isEmpty())
        assertEquals(changed, store.read("alice"))
    }

    @Test
    fun `late write completion and remote echo cannot overwrite a newer local edit`() {
        val store = store()
        val first = store.mutate("alice") { PersonalDataSnapshot(mealPlans = listOf(plan("first", 10))) }
        val oldWrite = store.pending("alice").single()
        store.mergeRemote("alice", PersonalCollection.MEAL_PLANS, first, true)
        val second = store.mutate("alice") { it.copy(mealPlans = listOf(plan("second", 11))) }
        store.acknowledge("alice", oldWrite)
        store.mergeRemote("alice", PersonalCollection.MEAL_PLANS, first, true)
        assertNotEquals(oldWrite.revision, store.pending("alice").single().revision)
        assertFalse(store.pending("alice").single().acknowledged)
        assertFalse(store.pending("alice").single().observed)
        assertEquals(second, store.read("alice"))
    }

    @Test
    fun `deletions survive restart retry and stale cache after successful sync`() {
        val disk = MemoryRows()
        val store = store(disk)
        val remote = PersonalDataSnapshot(favorites = listOf(favorite("recipe", 10)))
        store.mergeRemote("alice", PersonalCollection.FAVORITES, remote, true)
        store.mutate("alice") { it.copy(favorites = emptyList()) }
        val restarted = store(disk)
        val deletion = restarted.pending("alice").single()
        assertNull(deletion.payload)
        restarted.mergeRemote("alice", PersonalCollection.FAVORITES, remote, true)
        assertTrue(restarted.read("alice").favorites.isEmpty())
        restarted.acknowledge("alice", deletion)
        assertTrue(restarted.pending("alice").isEmpty())
        restarted.mergeRemote("alice", PersonalCollection.FAVORITES, remote, false)
        assertTrue(store(disk).read("alice").favorites.isEmpty())
        restarted.mergeRemote("alice", PersonalCollection.FAVORITES, PersonalDataSnapshot(), true)
        assertTrue(restarted.pending("alice").isEmpty())
        restarted.mergeRemote("alice", PersonalCollection.FAVORITES, remote, false)
        assertTrue(store(disk).read("alice").favorites.isEmpty())
    }

    @Test
    fun `empty cache cannot erase local rows and authoritative refresh only replaces its collection`() {
        val store = store()
        val remote = PersonalDataSnapshot(favorites = listOf(favorite("recipe", 10)))
        store.mergeRemote("alice", PersonalCollection.FAVORITES, remote, true)
        store.mutate("alice") { it.copy(recipeNotes = listOf(note("recipe", "local", 20))) }
        store.mergeRemote("alice", PersonalCollection.FAVORITES, PersonalDataSnapshot(), false)
        assertEquals(1, store.read("alice").favorites.size)
        store.mergeRemote("alice", PersonalCollection.FAVORITES, PersonalDataSnapshot(), true)
        assertTrue(store.read("alice").favorites.isEmpty())
        assertEquals("local", store.read("alice").recipeNotes.single().text)
    }

    @Test
    fun `only newer cache values replace stable rows while pending local intent always wins`() {
        val store = store()
        fun remote(text: String, timestamp: Long, authoritative: Boolean = false) = store.mergeRemote(
            "alice", PersonalCollection.NOTES,
            PersonalDataSnapshot(recipeNotes = listOf(note("recipe", text, timestamp))), authoritative,
        )
        remote("original", 10, true)
        remote("old", 9)
        assertEquals("original", store.read("alice").recipeNotes.single().text)
        remote("new", 11)
        assertEquals("new", store.read("alice").recipeNotes.single().text)
        store.mutate("alice") { it.copy(recipeNotes = listOf(note("recipe", "pending", 12))) }
        remote("other-device", 100, true)
        assertEquals("pending", store.read("alice").recipeNotes.single().text)
    }

    @Test
    fun `account switching cannot read or acknowledge another account`() {
        val store = store()
        store.mutate("alice") { PersonalDataSnapshot(favorites = listOf(favorite("recipe", 1))) }
        store.mutate("bob") { PersonalDataSnapshot(favorites = listOf(favorite("recipe", 2))) }
        val alice = store.pending("alice").single()
        store.acknowledge("bob", alice)
        assertFalse(store.pending("bob").single().acknowledged)
        assertEquals(1L, store.read("alice").favorites.single().addedAtEpochMillis)
        assertEquals(2L, store.read("bob").favorites.single().addedAtEpochMillis)
        assertEquals(PersonalDataSnapshot(), store.read(null))
        assertNotEquals(personalOwnerKey(null), personalOwnerKey("guest"))
        assertFalse(personalOwnerKey("alice").contains("alice"))
    }

    @Test
    fun `legacy import is one time and cannot overwrite newer pending changes`() {
        val disk = MemoryRows()
        val store = store(disk)
        store.mutate("alice") { PersonalDataSnapshot(recipeNotes = listOf(note("one", "pending", 10))) }
        val imported = store.importLegacy("alice", PersonalDataSnapshot(
            recipeNotes = listOf(note("one", "legacy", 100), note("two", "imported", 20)),
        ), "legacy-v1")
        assertEquals(setOf("pending", "imported"), imported.recipeNotes.map { it.text }.toSet())
        store.mutate("alice") { it.copy(recipeNotes = it.recipeNotes.filterNot { note -> note.id == "two" }) }
        store(disk).importLegacy("alice", PersonalDataSnapshot(recipeNotes = listOf(note("two", "imported", 20))), "legacy-v1")
        assertEquals(listOf("pending"), store.read("alice").recipeNotes.map { it.text })
    }

    @Test
    fun `transaction failure rolls back both rows and migration marker so import can retry`() {
        val disk = MemoryRows()
        val store = store(disk)
        disk.failAfterWrites = 1
        val legacy = PersonalDataSnapshot(favorites = listOf(favorite("one", 1), favorite("two", 2)))
        assertThrows(IllegalStateException::class.java) { store.importLegacy(null, legacy, "legacy-v1") }
        assertEquals(PersonalDataSnapshot(), store.read(null))
        assertTrue(disk.imports.isEmpty())
        assertFalse(store.isLegacyImported(null, "legacy-v1"))
        disk.failAfterWrites = null
        assertEquals(2, store.importLegacy(null, legacy, "legacy-v1").favorites.size)
        assertTrue(store(disk).isLegacyImported(null, "legacy-v1"))
        assertFalse(store.isLegacyImported("another-owner", "legacy-v1"))
    }

    @Test
    fun `history is never silently capped and photo rows do not change on shopping edits`() {
        val disk = MemoryRows()
        val store = store(disk)
        store.mutate(null) { PersonalDataSnapshot(
            customRecipes = listOf(CustomRecipe(id = "custom", photoDataUri = "photo".repeat(100_000), updatedAtEpochMillis = 1)),
            cookedHistory = (1..1_500).map { history("event-$it", it.toLong()) },
        ) }
        disk.written.clear()
        store.mutate(null) { it.copy(shoppingItems = listOf(ShoppingListItem(id = "milk", name = "milk", updatedAtEpochMillis = 2))) }
        assertEquals(listOf(PersonalCollection.SHOPPING), disk.written.map { it.collection })
        assertEquals(1_500, store(disk).claimGuest("alice").cookedHistory.size)
        assertEquals(1_502, store.pending("alice").size)
    }

    @Test
    fun `default preferences do not create a cloud row and explicit changes round trip`() {
        val store = store()
        store.mutate("alice") { PersonalDataSnapshot() }
        assertTrue(store.pending("alice").isEmpty())
        val settings = MealPreferenceSettings(veganOnly = true, excludedSourceKeys = setOf("cookpad"), updatedAtEpochMillis = 10)
        store.mutate("alice") { it.copy(preferences = settings) }
        assertEquals(PERSONAL_PREFERENCE_DOCUMENT_ID, store.pending("alice").single().documentId)
        assertEquals(settings, store.read("alice").preferences)
    }

    @Test
    fun `corrupt payload surfaces failure and remains present for recovery`() {
        val disk = MemoryRows()
        val broken = PersonalRow(PersonalCollection.NOTES, "recipe", "{broken", 1)
        disk.transaction { put(personalOwnerKey("alice"), broken) }
        assertThrows(IllegalArgumentException::class.java) { store(disk).read("alice") }
        assertEquals(broken, disk.saved.getValue(personalOwnerKey("alice")).values.single())
    }

    @Test
    fun `legacy anonymous owner moves once to provisioned account without leaking to another account`() {
        val disk = MemoryRows()
        val store = store(disk)
        store.mutate("anonymous-id") { completeSnapshot() }
        store.mergeRemote("alice", PersonalCollection.COOKED_HISTORY,
            PersonalDataSnapshot(cookedHistory = listOf(history("previous-account-history", 5))), true)
        val claimed = store.claimAnonymous("anonymous-id", "alice")
        assertEquals(setOf("event", "previous-account-history"), claimed.cookedHistory.map { it.id }.toSet())
        assertEquals(PersonalDataSnapshot(), store.read("anonymous-id"))
        assertEquals(PersonalDataSnapshot(), store(disk).claimAnonymous("anonymous-id", "bob"))
        assertEquals(claimed, store.claimAnonymous("alice", "alice"))
        assertEquals(PersonalCollection.entries.toSet(), store.pending("alice").map { it.collection }.toSet())
    }

    @Test
    fun `existing immutable favorite confirms membership despite a different added timestamp`() {
        val store = store()
        store.mutate("alice") { PersonalDataSnapshot(favorites = listOf(favorite("recipe", 20))) }
        val write = store.pending("alice").single()
        store.mergeRemote("alice", PersonalCollection.FAVORITES,
            PersonalDataSnapshot(favorites = listOf(favorite("recipe", 10))), true)
        assertTrue(store.pending("alice").single().observed)
        store.acknowledge("alice", write)
        assertTrue(store.pending("alice").isEmpty())
        assertEquals("recipe", store.read("alice").favorites.single().recipeId)
    }

    @Test
    fun `imported guest rows wait for server reconciliation and newer server documents win`() {
        val store = store()
        store.mutate(null) { PersonalDataSnapshot(
            mealPlans = listOf(plan("guest-old", 10)),
            recipeNotes = listOf(note("recipe", "guest-new", 30), note("only-guest", "retain", 10)),
            preferences = MealPreferenceSettings(veganOnly = true, updatedAtEpochMillis = 10),
        ) }
        store.claimGuest("alice")
        assertTrue(store.pending("alice").all { it.imported })
        store.mergeRemote("alice", PersonalCollection.MEAL_PLANS,
            PersonalDataSnapshot(mealPlans = listOf(plan("server-new", 20))), false)
        assertEquals("guest-old", store.read("alice").mealPlans.single().recipeId)
        store.mergeRemote("alice", PersonalCollection.MEAL_PLANS,
            PersonalDataSnapshot(mealPlans = listOf(plan("server-new", 20))), true)
        store.mergeRemote("alice", PersonalCollection.NOTES,
            PersonalDataSnapshot(recipeNotes = listOf(note("recipe", "server-old", 20))), true)
        store.mergeRemote("alice", PersonalCollection.PREFERENCES,
            PersonalDataSnapshot(preferences = MealPreferenceSettings(favoritesOnly = true, updatedAtEpochMillis = 20)), true)
        assertEquals("server-new", store.read("alice").mealPlans.single().recipeId)
        assertEquals(setOf("guest-new", "retain"), store.read("alice").recipeNotes.map { it.text }.toSet())
        assertTrue(store.read("alice").preferences.favoritesOnly)
        assertEquals(2, store.pending("alice").size)
        assertTrue(store.pending("alice").none { it.imported })
    }

    @Test
    fun `claim preserves colliding legacy history and rewrites matching plan completion reference`() {
        val store = store()
        val old = history("2026-09-10", 10).copy(recipeId = "old")
        val guest = history("2026-09-10", 20).copy(recipeId = "guest")
        store.mergeRemote("alice", PersonalCollection.COOKED_HISTORY, PersonalDataSnapshot(cookedHistory = listOf(old)), true)
        store.mutate(null) { PersonalDataSnapshot(
            cookedHistory = listOf(guest),
            mealPlans = listOf(plan("guest", 20).copy(completed = true, completedAtEpochMillis = 20, completionEventId = guest.id)),
        ) }
        val claimed = store.claimGuest("alice")
        assertEquals(2, claimed.cookedHistory.size)
        val preserved = claimed.cookedHistory.single { it.recipeId == "guest" }
        assertTrue(preserved.id.matches(Regex("cooked_[0-9a-f]{32}")))
        assertEquals(preserved.id, claimed.mealPlans.single().completionEventId)
        assertEquals(old, claimed.cookedHistory.single { it.recipeId == "old" })
    }

    @Test
    fun `first server snapshot preserves imported history collision and completion reference atomically`() {
        val disk = MemoryRows()
        val store = store(disk)
        val guest = history("2026-09-10", 20).copy(recipeId = "guest")
        store.mutate(null) { PersonalDataSnapshot(
            cookedHistory = listOf(guest),
            mealPlans = listOf(plan("guest", 20).copy(completed = true, completedAtEpochMillis = 20, completionEventId = guest.id)),
        ) }
        store.claimGuest("alice")
        store.mergeRemote("alice", PersonalCollection.COOKED_HISTORY,
            PersonalDataSnapshot(cookedHistory = listOf(history("2026-09-10", 10).copy(recipeId = "old"))), true)
        val restarted = store(disk).read("alice")
        assertEquals(2, restarted.cookedHistory.size)
        val preserved = restarted.cookedHistory.single { it.recipeId == "guest" }
        assertNotEquals(guest.id, preserved.id)
        assertEquals(preserved.id, restarted.mealPlans.single().completionEventId)
        assertTrue(store.pending("alice").single { it.collection == PersonalCollection.COOKED_HISTORY }.imported.not())
    }

    @Test
    fun `duplicate legacy IDs inside one import retain both historical events`() {
        val store = store()
        val imported = store.importLegacy(null, PersonalDataSnapshot(cookedHistory = listOf(
            history("2026-09-10", 10), history("2026-09-10", 20),
        )), "legacy-history")
        assertEquals(2, imported.cookedHistory.size)
        assertEquals(setOf(10L, 20L), imported.cookedHistory.map { it.completedAtEpochMillis }.toSet())
    }

    @Test
    fun `undo before history snapshot arrives queues durable deletion and rejects late history`() {
        val disk = MemoryRows()
        val store = store(disk)
        val completed = plan("recipe", 10).copy(completed = true, completedAtEpochMillis = 10, completionEventId = "event")
        store.mergeRemote("alice", PersonalCollection.MEAL_PLANS, PersonalDataSnapshot(mealPlans = listOf(completed)), true)
        assertTrue(store.read("alice").cookedHistory.isEmpty())
        store.mutateWithDeletions("alice") { current ->
            val projection = projectMealCompletion(current.mealPlans,
                mergeCookedHistory(current.mealPlans, current.cookedHistory), completed.date, false, 20, "unused")
            PersonalDataChange(current.copy(mealPlans = projection.plans, cookedHistory = projection.storedHistory),
                projection.historyIdsToDelete.mapTo(mutableSetOf()) { PersonalRowKey(PersonalCollection.COOKED_HISTORY, it) })
        }
        val restarted = store(disk)
        assertFalse(restarted.read("alice").mealPlans.single().completed)
        val deletion = restarted.pending("alice").single { it.collection == PersonalCollection.COOKED_HISTORY }
        assertEquals("event", deletion.documentId)
        assertNull(deletion.payload)
        val late = PersonalDataSnapshot(cookedHistory = listOf(history("event", 10)))
        restarted.mergeRemote("alice", PersonalCollection.COOKED_HISTORY, late, true)
        assertTrue(restarted.read("alice").cookedHistory.isEmpty())
        restarted.acknowledge("alice", deletion)
        restarted.mergeRemote("alice", PersonalCollection.COOKED_HISTORY, late, false)
        assertTrue(store(disk).read("alice").cookedHistory.isEmpty())
    }

    @Test
    fun `removing a synthesized history row durably deletes its not yet received server document`() {
        val store = store()
        val completed = plan("recipe", 10).copy(completed = true, completedAtEpochMillis = 10, completionEventId = "event")
        store.mergeRemote("alice", PersonalCollection.MEAL_PLANS, PersonalDataSnapshot(mealPlans = listOf(completed)), true)
        store.mutateWithDeletions("alice") { current ->
            val projection = projectHistoryDeletion(current.mealPlans,
                mergeCookedHistory(current.mealPlans, current.cookedHistory), "event", 20)
            PersonalDataChange(current.copy(mealPlans = projection.plans, cookedHistory = projection.storedHistory),
                listOfNotNull(projection.historyIdToDelete).mapTo(mutableSetOf()) { PersonalRowKey(PersonalCollection.COOKED_HISTORY, it) })
        }
        assertNull(store.pending("alice").single { it.collection == PersonalCollection.COOKED_HISTORY }.payload)
        store.mergeRemote("alice", PersonalCollection.COOKED_HISTORY,
            PersonalDataSnapshot(cookedHistory = listOf(history("event", 10))), true)
        assertTrue(store.read("alice").cookedHistory.isEmpty())
        assertFalse(store.read("alice").mealPlans.single().completed)
    }

    @Test
    fun `failed missing history deletion transaction rolls back the completion and outbox together`() {
        val disk = MemoryRows()
        val store = store(disk)
        val completed = plan("recipe", 10).copy(completed = true, completedAtEpochMillis = 10, completionEventId = "event")
        store.mergeRemote("alice", PersonalCollection.MEAL_PLANS, PersonalDataSnapshot(mealPlans = listOf(completed)), true)
        disk.failAfterWrites = 1
        assertThrows(IllegalStateException::class.java) {
            store.mutateWithDeletions("alice") { current -> PersonalDataChange(
                current.copy(mealPlans = listOf(completed.copy(completed = false, completionEventId = "", completedAtEpochMillis = 0))),
                setOf(PersonalRowKey(PersonalCollection.COOKED_HISTORY, "event")),
            ) }
        }
        assertEquals(completed, store(disk).read("alice").mealPlans.single())
        assertTrue(store.pending("alice").isEmpty())
    }

    private fun store(disk: MemoryRows = MemoryRows()) = TransactionalPersonalDataStore(disk, now = { 1_000L })
    private fun plan(recipe: String, timestamp: Long) = DayMealPlan(
        id = "2026-09-10", date = "2026-09-10", recipeId = recipe, recipeTitle = recipe,
        updatedAtEpochMillis = timestamp,
    )
    private fun history(id: String, timestamp: Long) = CookedMeal(
        id = id, date = "2026-09-10", recipeId = "recipe", recipeTitle = "Recipe", completedAtEpochMillis = timestamp,
    )
    private fun favorite(id: String, timestamp: Long) = FavoriteRecipe(id = id, recipeId = id, addedAtEpochMillis = timestamp)
    private fun note(id: String, text: String, timestamp: Long) = RecipeNote(id = id, recipeId = id, text = text, updatedAtEpochMillis = timestamp)
    private fun completeSnapshot() = PersonalDataSnapshot(
        mealPlans = listOf(plan("recipe", 10)), favorites = listOf(favorite("recipe", 10)),
        shoppingItems = listOf(ShoppingListItem(id = "milk", name = "milk", updatedAtEpochMillis = 10)),
        recipeNotes = listOf(note("recipe", "My note", 10)),
        customRecipes = listOf(CustomRecipe(id = "custom", title = "Mine", photoDataUri = "data:image/jpeg;base64,photo", updatedAtEpochMillis = 10)),
        cookedHistory = listOf(history("event", 10)),
        preferences = MealPreferenceSettings(veganOnly = true, updatedAtEpochMillis = 10),
    )

    private class MemoryRows : PersonalRowStorage {
        var saved = mutableMapOf<String, MutableMap<PersonalRowKey, PersonalRow>>()
        var imports = mutableSetOf<Pair<String, String>>()
        val written = mutableListOf<PersonalRowKey>()
        var failAfterWrites: Int? = null

        override fun <T> transaction(block: PersonalRowTransaction.() -> T): T {
            val working = saved.mapValues { (_, rows) -> rows.toMutableMap() }.toMutableMap()
            val tokens = imports.toMutableSet()
            val writes = mutableListOf<PersonalRowKey>()
            val transaction = object : PersonalRowTransaction {
                override fun rows(owner: String) = working[owner]?.values?.toList().orEmpty()
                override fun put(owner: String, row: PersonalRow) {
                    check(failAfterWrites == null || writes.size < checkNotNull(failAfterWrites))
                    working.getOrPut(owner) { mutableMapOf() }[row.key] = row
                    writes += row.key
                }
                override fun delete(owner: String, key: PersonalRowKey) { working[owner]?.remove(key) }
                override fun wasImported(owner: String, token: String) = (owner to token) in tokens
                override fun markImported(owner: String, token: String) { tokens += owner to token }
            }
            val result = block(transaction)
            saved = working
            imports = tokens
            written += writes
            return result
        }
    }
}
