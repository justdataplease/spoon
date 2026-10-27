package com.justdataplease.spoon.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A named group inside the collection. «Αγαπημένα» is not stored: it is every favorite, and a
 * list only groups favorites, so removing a recipe from the collection removes it from its lists.
 */
@Serializable
data class RecipeList(
    val id: String,
    val name: String,
    val recipeIds: List<String> = emptyList(),
    val createdAtEpochMillis: Long = 0L,
)

@Serializable
private data class RecipeListsDocument(val lists: List<RecipeList> = emptyList())

internal const val MAX_RECIPE_LIST_NAME_LENGTH = 40
internal const val FAVORITES_LIST_NAME = "Αγαπημένα"

/**
 * Device-local lists per data owner. Favorites remain the synced membership; the cloud rules
 * accept no extra favorite fields, so lists stay on this device until the rules support them.
 */
@Singleton
class RecipeListStore internal constructor(
    private val preferences: SharedPreferences,
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))

    private val mutableLists = MutableStateFlow<List<RecipeList>>(emptyList())
    private var ownerKey: String? = null

    val lists: StateFlow<List<RecipeList>> = mutableLists.asStateFlow()

    /** Loads the owner's lists. A first account login takes over lists made as a guest. */
    @Synchronized
    fun selectOwner(dataOwnerKey: String) {
        if (dataOwnerKey == ownerKey) return
        ownerKey = dataOwnerKey
        var lists = read(dataOwnerKey)
        if (lists.isEmpty() && dataOwnerKey != GUEST_OWNER) {
            val guestLists = read(GUEST_OWNER)
            if (guestLists.isNotEmpty()) {
                write(dataOwnerKey, guestLists)
                write(GUEST_OWNER, emptyList())
                lists = guestLists
            }
        }
        mutableLists.value = lists
    }

    @Synchronized
    fun create(name: String, now: Long = System.currentTimeMillis()): RecipeList? {
        val list = RecipeList(id = "list_${UUID.randomUUID()}", name = name.cleanListName(), createdAtEpochMillis = now)
        if (!isAvailableListName(list.name, mutableLists.value)) return null
        update { it + list }
        return list
    }

    @Synchronized
    fun rename(listId: String, name: String): Boolean {
        val clean = name.cleanListName()
        if (!isAvailableListName(clean, mutableLists.value.filterNot { it.id == listId })) return false
        update { lists -> lists.map { if (it.id == listId) it.copy(name = clean) else it } }
        return true
    }

    @Synchronized
    fun delete(listId: String) = update { lists -> lists.filterNot { it.id == listId } }

    @Synchronized
    fun setRecipeLists(recipeId: String, listIds: Set<String>) = update { it.withRecipeIn(recipeId, listIds) }

    @Synchronized
    fun removeRecipe(recipeId: String) = update { it.withRecipeIn(recipeId, emptySet()) }

    private fun update(transform: (List<RecipeList>) -> List<RecipeList>) {
        val owner = ownerKey ?: return
        val next = transform(mutableLists.value)
        if (next == mutableLists.value) return
        write(owner, next)
        mutableLists.value = next
    }

    private fun read(owner: String): List<RecipeList> =
        preferences.getString(storageKey(owner), null)
            ?.let { runCatching { ListJson.decodeFromString(RecipeListsDocument.serializer(), it).lists }.getOrNull() }
            .orEmpty()

    private fun write(owner: String, lists: List<RecipeList>) {
        preferences.edit()
            .putString(storageKey(owner), ListJson.encodeToString(RecipeListsDocument.serializer(), RecipeListsDocument(lists)))
            .apply()
    }

    private fun storageKey(owner: String): String =
        "lists_" + MessageDigest.getInstance("SHA-256")
            .digest(owner.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        const val PREFERENCES_NAME = "spoon_recipe_lists"
        private const val GUEST_OWNER = "guest"
        private val ListJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

internal fun String.cleanListName(): String =
    trim().replace(ListNameWhitespace, " ").take(MAX_RECIPE_LIST_NAME_LENGTH)

internal fun isAvailableListName(name: String, existing: List<RecipeList>): Boolean =
    recipeListNameProblem(name, existing.map(RecipeList::name)) == null

/** Names are unique ignoring case and accents, and «Αγαπημένα» belongs to the default list. */
internal fun recipeListNameProblem(name: String, existingNames: List<String>): String? {
    val key = name.cleanListName().normalizedCatalogToken()
    return when {
        key.isEmpty() -> "Γράψε ένα όνομα για τη λίστα."
        key == FAVORITES_LIST_NAME.normalizedCatalogToken() -> "Η λίστα «$FAVORITES_LIST_NAME» υπάρχει ήδη."
        existingNames.any { it.normalizedCatalogToken() == key } -> "Υπάρχει ήδη λίστα με αυτό το όνομα."
        else -> null
    }
}

/** Puts [recipeId] in exactly [listIds], keeping each list's existing order. */
internal fun List<RecipeList>.withRecipeIn(recipeId: String, listIds: Set<String>): List<RecipeList> = map { list ->
    val contains = recipeId in list.recipeIds
    when {
        list.id in listIds && !contains -> list.copy(recipeIds = list.recipeIds + recipeId)
        list.id !in listIds && contains -> list.copy(recipeIds = list.recipeIds - recipeId)
        else -> list
    }
}

private val ListNameWhitespace = Regex("\\s+")
