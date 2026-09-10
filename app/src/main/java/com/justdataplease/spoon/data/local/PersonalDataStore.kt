package com.justdataplease.spoon.data.local

import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.FavoriteRecipe
import com.justdataplease.spoon.data.model.MealCoursePlan
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.data.model.cookedMealEventId
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The local database is the authority for every personal action, including before sign-in. */
internal interface PersonalDataStore {
    fun read(ownerUid: String?): PersonalDataSnapshot
    fun mutate(ownerUid: String?, transform: (PersonalDataSnapshot) -> PersonalDataSnapshot): PersonalDataSnapshot
    fun mergeRemote(
        ownerUid: String,
        collection: PersonalCollection,
        remote: PersonalDataSnapshot,
        authoritative: Boolean,
    ): PersonalDataSnapshot
    fun claimGuest(ownerUid: String): PersonalDataSnapshot
    fun claimAnonymous(anonymousUid: String, ownerUid: String): PersonalDataSnapshot
    fun pending(ownerUid: String): List<PendingPersonalWrite>
    fun acknowledge(ownerUid: String, write: PendingPersonalWrite)
    fun importLegacy(ownerUid: String?, snapshot: PersonalDataSnapshot, token: String): PersonalDataSnapshot
}

internal enum class PersonalCollection {
    MEAL_PLANS, FAVORITES, SHOPPING, NOTES, CUSTOM_RECIPES, COOKED_HISTORY, PREFERENCES,
}

@Serializable
internal data class PersonalDataSnapshot(
    val mealPlans: List<DayMealPlan> = emptyList(),
    val favorites: List<FavoriteRecipe> = emptyList(),
    val shoppingItems: List<ShoppingListItem> = emptyList(),
    val recipeNotes: List<RecipeNote> = emptyList(),
    val customRecipes: List<CustomRecipe> = emptyList(),
    val cookedHistory: List<CookedMeal> = emptyList(),
    val preferences: MealPreferenceSettings = MealPreferenceSettings(),
)

internal data class PendingPersonalWrite(
    val collection: PersonalCollection,
    val documentId: String,
    /** A serialized entity, or a durable deletion tombstone. */
    val payload: String?,
    val revision: String,
    val acknowledged: Boolean = false,
    val observed: Boolean = false,
    /** Imported rows wait for one authoritative server reconciliation before upload. */
    val imported: Boolean = false,
)

internal val PersonalDataJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
internal const val PERSONAL_PREFERENCE_DOCUMENT_ID = "meal"

/** The storage adapter supplies rollback and durability; these rules are also exercised on the JVM. */
internal class TransactionalPersonalDataStore(
    private val storage: PersonalRowStorage,
    private val now: () -> Long = System::currentTimeMillis,
    private val revision: () -> String = { UUID.randomUUID().toString() },
) : PersonalDataStore {
    override fun read(ownerUid: String?): PersonalDataSnapshot = storage.transaction {
        decodeSnapshot(rows(personalOwnerKey(ownerUid)))
    }

    override fun mutate(
        ownerUid: String?,
        transform: (PersonalDataSnapshot) -> PersonalDataSnapshot,
    ): PersonalDataSnapshot = storage.transaction {
        val owner = personalOwnerKey(ownerUid)
        val before = rows(owner).associateBy(PersonalRow::key)
        val after = documents(transform(decodeSnapshot(before.values))).associateBy(PersonalRow::key)
        for (key in before.keys + after.keys) {
            val previous = before[key]
            val changed = after[key]
            if (previous?.payload == changed?.payload) continue
            val row = changed ?: checkNotNull(previous).copy(
                payload = null,
                timestamp = maxOf(now(), previous.timestamp + 1L),
            )
            put(owner, row.queued())
        }
        decodeSnapshot(rows(owner))
    }

    override fun mergeRemote(
        ownerUid: String,
        collection: PersonalCollection,
        remote: PersonalDataSnapshot,
        authoritative: Boolean,
    ): PersonalDataSnapshot = storage.transaction {
        val owner = personalOwnerKey(ownerUid)
        val incoming = documents(remote).filter { it.collection == collection }.associateBy(PersonalRow::key)
        if (authoritative && collection == PersonalCollection.COOKED_HISTORY) {
            val local = rows(owner)
            val importedHistoryAndPlans = local.filter {
                it.collection == PersonalCollection.MEAL_PLANS ||
                    it.collection == PersonalCollection.COOKED_HISTORY && it.imported && it.revision != null
            }
            val preserved = preserveHistoryConflicts(importedHistoryAndPlans, incoming)
            val previous = importedHistoryAndPlans.associateBy(PersonalRow::key)
            for (row in preserved) {
                if (row != previous[row.key]) put(owner, row.queued(imported = row.imported))
            }
            val retainedKeys = preserved.mapTo(mutableSetOf(), PersonalRow::key)
            for (row in importedHistoryAndPlans) {
                if (row.key !in retainedKeys) incoming[row.key]?.let { put(owner, it) }
            }
        }
        val before = rows(owner).filter { it.collection == collection }.associateBy(PersonalRow::key)
        for (key in before.keys + incoming.keys) {
            val current = before[key]
            val received = incoming[key]
            val updated = when {
                current?.revision != null -> {
                    // Metadata/cache echoes cannot prove that a server accepted this exact revision.
                    when {
                        !authoritative -> current
                        current.imported && received != null && received.timestamp > current.timestamp -> received
                        current.matchesServer(received) -> current.copy(observed = true, imported = false).finishIfConfirmed()
                        else -> current.copy(imported = false)
                    }
                }
                authoritative -> received ?: current?.copy(payload = null)
                current == null -> received
                current.payload == null -> current // A late cache must not resurrect a deletion.
                received != null && received.timestamp > current.timestamp -> received
                else -> current
            }
            if (updated != null && updated != current) put(owner, updated)
        }
        decodeSnapshot(rows(owner))
    }

    override fun claimGuest(ownerUid: String): PersonalDataSnapshot = claim(null, ownerUid)

    override fun claimAnonymous(anonymousUid: String, ownerUid: String): PersonalDataSnapshot =
        claim(anonymousUid, ownerUid)

    private fun claim(sourceUid: String?, ownerUid: String): PersonalDataSnapshot = storage.transaction {
        val owner = personalOwnerKey(ownerUid)
        val guest = personalOwnerKey(sourceUid)
        if (owner == guest) return@transaction decodeSnapshot(rows(owner))
        val existing = rows(owner).associateBy(PersonalRow::key)
        val source = rows(guest)
        // Removing data in a guest/anonymous profile must not delete an independent
        // destination account's record under the same key. Transfer only visible data;
        // clear the source tombstones atomically with the rest of the claimed profile.
        for (row in preserveHistoryConflicts(source.filter { it.payload != null }, existing)) {
            val current = existing[row.key]
            if (current == null || row.timestamp >= current.timestamp) put(owner, row.queued(imported = true))
        }
        source.forEach { delete(guest, it.key) }
        decodeSnapshot(rows(owner))
    }

    override fun pending(ownerUid: String): List<PendingPersonalWrite> = storage.transaction {
        pendingRows(personalOwnerKey(ownerUid)).mapNotNull { row ->
            row.revision?.let { PendingPersonalWrite(
                row.collection, row.documentId, row.payload, it, row.acknowledged, row.observed, row.imported,
            ) }
        }
    }

    override fun acknowledge(ownerUid: String, write: PendingPersonalWrite) {
        storage.transaction {
            val owner = personalOwnerKey(ownerUid)
            val row = row(owner, PersonalRowKey(write.collection, write.documentId)) ?: return@transaction
            if (row.revision != write.revision) return@transaction
            // The write task succeeds only after server acceptance. A listener can conflate
            // that exact value with a later edit from another device, so waiting for its echo
            // would leave an accepted row pending forever. Preserve the row (including deletion
            // tombstones), and clear only the exact revision whose write was acknowledged.
            put(owner, row.copy(revision = null, acknowledged = false, observed = false, imported = false))
        }
    }

    override fun importLegacy(
        ownerUid: String?,
        snapshot: PersonalDataSnapshot,
        token: String,
    ): PersonalDataSnapshot = storage.transaction {
        require(token.isNotBlank())
        val owner = personalOwnerKey(ownerUid)
        if (!wasImported(owner, token)) {
            val existing = rows(owner).associateBy(PersonalRow::key)
            for (row in preserveHistoryConflicts(documents(snapshot), existing)) {
                val current = existing[row.key]
                if (current != null && (current.revision != null || current.payload == null)) continue
                if (current == null || row.timestamp >= current.timestamp) put(owner, row.queued(imported = true))
            }
            markImported(owner, token)
        }
        decodeSnapshot(rows(owner))
    }

    private fun PersonalRow.queued(imported: Boolean = false) = copy(
        revision = this@TransactionalPersonalDataStore.revision(), acknowledged = false, observed = false,
        imported = imported,
    )

    private fun PersonalRow.matchesServer(received: PersonalRow?): Boolean {
        if (payload == received?.payload) return true
        // Existing favorite documents are immutable in the deployed rules. Membership already
        // present on the server confirms an add even when the two devices added it at different times.
        return collection == PersonalCollection.FAVORITES && payload != null && received?.payload != null &&
            PersonalDataJson.decodeFromString<FavoriteRecipe>(payload).recipeId ==
            PersonalDataJson.decodeFromString<FavoriteRecipe>(received.payload).recipeId
    }

    private fun PersonalRow.finishIfConfirmed(): PersonalRow =
        if (acknowledged && observed) copy(revision = null, acknowledged = false, observed = false, imported = false) else this
}

internal data class PersonalRowKey(val collection: PersonalCollection, val documentId: String)

/** Payload is stored once, even while pending; changing a checkbox never rewrites recipe photos. */
internal data class PersonalRow(
    val collection: PersonalCollection,
    val documentId: String,
    val payload: String?,
    val timestamp: Long,
    val revision: String? = null,
    val acknowledged: Boolean = false,
    val observed: Boolean = false,
    /** Imported rows wait for one authoritative server reconciliation before upload. */
    val imported: Boolean = false,
) {
    val key: PersonalRowKey get() = PersonalRowKey(collection, documentId)
}

internal interface PersonalRowStorage {
    fun <T> transaction(block: PersonalRowTransaction.() -> T): T
}

internal interface PersonalRowTransaction {
    fun rows(owner: String): List<PersonalRow>
    fun row(owner: String, key: PersonalRowKey): PersonalRow? = rows(owner).firstOrNull { it.key == key }
    fun pendingRows(owner: String): List<PersonalRow> = rows(owner).filter { it.revision != null }
    fun put(owner: String, row: PersonalRow)
    fun delete(owner: String, key: PersonalRowKey)
    fun wasImported(owner: String, token: String): Boolean
    fun markImported(owner: String, token: String)
}

internal fun personalOwnerKey(ownerUid: String?): String {
    if (ownerUid == null) return "guest"
    require(ownerUid.isNotBlank())
    return "owner_" + MessageDigest.getInstance("SHA-256")
        .digest(ownerUid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** IDs are canonicalized once at the storage boundary, including legacy history without IDs. */
private fun documents(snapshot: PersonalDataSnapshot): List<PersonalRow> = buildList {
    snapshot.mealPlans.forEach { value ->
        val id = value.date.ifBlank { value.id }
        add(document(PersonalCollection.MEAL_PLANS, id, value.copy(id = id), value.updatedAtEpochMillis))
    }
    snapshot.favorites.forEach { value ->
        val id = value.recipeId.ifBlank { value.id }
        add(document(PersonalCollection.FAVORITES, id, value.copy(id = id), value.addedAtEpochMillis))
    }
    snapshot.shoppingItems.forEach { value ->
        add(document(PersonalCollection.SHOPPING, value.id, value, value.updatedAtEpochMillis))
    }
    snapshot.recipeNotes.forEach { value ->
        val id = value.recipeId.ifBlank { value.id }
        add(document(PersonalCollection.NOTES, id, value.copy(id = id), value.updatedAtEpochMillis))
    }
    snapshot.customRecipes.forEach { value ->
        add(document(PersonalCollection.CUSTOM_RECIPES, value.id, value, value.updatedAtEpochMillis))
    }
    snapshot.cookedHistory.forEach { value ->
        val id = value.id.ifBlank { cookedMealEventId(value.date, value.completedAtEpochMillis) }
        add(document(PersonalCollection.COOKED_HISTORY, id, value.copy(id = id), value.completedAtEpochMillis))
    }
    snapshot.preferences.takeIf { it.updatedAtEpochMillis > 0L }?.let { value ->
        val normalized = value.copy(
            excludedCategories = value.excludedCategories.toSortedSet(),
            excludedIngredientTerms = value.excludedIngredientTerms.toSortedSet(),
            excludedSourceKeys = value.excludedSourceKeys.toSortedSet(),
        )
        add(document(PersonalCollection.PREFERENCES, PERSONAL_PREFERENCE_DOCUMENT_ID, normalized, value.updatedAtEpochMillis))
    }
}.let { preserveHistoryConflicts(it, emptyMap()) }
    .groupBy(PersonalRow::key).map { (_, versions) -> versions.maxBy(PersonalRow::timestamp) }

private inline fun <reified T> document(
    collection: PersonalCollection,
    id: String,
    value: T,
    timestamp: Long,
): PersonalRow {
    require(id.isNotBlank()) { "A personal document must have a stable ID" }
    val payload = PersonalDataJson.parseToJsonElement(PersonalDataJson.encodeToString(value))
        .canonical().toString()
    return PersonalRow(collection, id, payload, timestamp)
}

private fun JsonElement.canonical(): JsonElement = when (this) {
    is JsonObject -> JsonObject(toSortedMap().mapValues { (_, value) -> value.canonical() })
    is JsonArray -> JsonArray(map(JsonElement::canonical))
    else -> this
}

private fun decodeSnapshot(rows: Collection<PersonalRow>): PersonalDataSnapshot {
    val visible = rows.filter { it.payload != null }.groupBy(PersonalRow::collection)
    check(visible[PersonalCollection.PREFERENCES].orEmpty().size <= 1) { "Multiple local preference documents" }
    return PersonalDataSnapshot(
        mealPlans = visible.decode<DayMealPlan>(PersonalCollection.MEAL_PLANS).sortedBy(DayMealPlan::date),
        favorites = visible.decode<FavoriteRecipe>(PersonalCollection.FAVORITES).sortedByDescending(FavoriteRecipe::addedAtEpochMillis),
        shoppingItems = visible.decode<ShoppingListItem>(PersonalCollection.SHOPPING).sortedBy(ShoppingListItem::createdAtEpochMillis),
        recipeNotes = visible.decode<RecipeNote>(PersonalCollection.NOTES).sortedBy(RecipeNote::recipeId),
        customRecipes = visible.decode<CustomRecipe>(PersonalCollection.CUSTOM_RECIPES).sortedByDescending(CustomRecipe::updatedAtEpochMillis),
        cookedHistory = visible.decode<CookedMeal>(PersonalCollection.COOKED_HISTORY).sortedByDescending(CookedMeal::completedAtEpochMillis),
        preferences = visible.decode<MealPreferenceSettings>(PersonalCollection.PREFERENCES).singleOrNull() ?: MealPreferenceSettings(),
    )
}

private inline fun <reified T> Map<PersonalCollection, List<PersonalRow>>.decode(collection: PersonalCollection): List<T> =
    get(collection).orEmpty().map { PersonalDataJson.decodeFromString<T>(checkNotNull(it.payload)) }

/** Legacy history used a date as its ID, so two accounts may own different events under one key. */
private fun preserveHistoryConflicts(
    source: List<PersonalRow>,
    existing: Map<PersonalRowKey, PersonalRow>,
): List<PersonalRow> {
    val occupied = existing.toMutableMap()
    val remapped = mutableListOf<Pair<CookedMeal, String>>()
    val preserved = source.map { row ->
        if (row.collection != PersonalCollection.COOKED_HISTORY || row.payload == null) return@map row
        val previous = occupied[row.key]
        if (previous?.payload == null || previous.payload == row.payload) {
            occupied[row.key] = row
            return@map row
        }
        val event = PersonalDataJson.decodeFromString<CookedMeal>(row.payload)
        val identity = PersonalDataJson.encodeToString(event.copy(id = ""))
        var suffix = 0
        var replacement: PersonalRow
        var conflict: PersonalRow?
        do {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$identity:$suffix".toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it) }
            val id = "cooked_$digest"
            val encoded = document(PersonalCollection.COOKED_HISTORY, id, event.copy(id = id), row.timestamp)
            replacement = row.copy(documentId = id, payload = encoded.payload)
            conflict = occupied[replacement.key]
            suffix++
        } while (conflict?.payload != null && conflict.payload != replacement.payload)
        occupied[replacement.key] = replacement
        remapped += event to replacement.documentId
        replacement
    }
    if (remapped.isEmpty()) return preserved
    return preserved.map { row ->
        if (row.collection != PersonalCollection.MEAL_PLANS || row.payload == null) return@map row
        val plan = PersonalDataJson.decodeFromString<DayMealPlan>(row.payload)
        fun eventId(completed: Boolean, recipeId: String, completedAt: Long, updatedAt: Long, oldId: String): String =
            remapped.firstOrNull { (event, _) ->
                completed && plan.date == event.date && recipeId == event.recipeId &&
                    (if (completedAt > 0L) completedAt else updatedAt) == event.completedAtEpochMillis &&
                    (oldId.isBlank() || oldId == event.id)
            }?.second ?: oldId
        fun course(value: MealCoursePlan?): MealCoursePlan? = value?.copy(completionEventId = eventId(
            value.completed, value.recipeId, value.completedAtEpochMillis, value.updatedAtEpochMillis, value.completionEventId,
        ))
        val updated = plan.copy(
            completionEventId = eventId(plan.completed, plan.recipeId, plan.completedAtEpochMillis, plan.updatedAtEpochMillis, plan.completionEventId),
            side = course(plan.side), dessert = course(plan.dessert),
        )
        if (updated == plan) row else row.copy(payload = document(row.collection, row.documentId, updated, row.timestamp).payload)
    }
}
