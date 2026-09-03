package com.justdataplease.spoon.data.local

import android.content.SharedPreferences
import com.justdataplease.spoon.data.DemoRecipeCatalog
import com.justdataplease.spoon.data.eligibleRecipeDetails
import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.MAX_SHOPPING_ITEMS_PER_WRITE
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.data.model.isCustomRecipeId
import com.justdataplease.spoon.data.model.matchesActiveCompletion
import com.justdataplease.spoon.data.model.mergeCookedHistory
import com.justdataplease.spoon.data.model.requireValid
import com.justdataplease.spoon.data.model.toCookedMeal
import com.justdataplease.spoon.data.requireSafeRecipeDocumentId
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.domain.repository.AccountOperationException
import com.justdataplease.spoon.domain.repository.AccountState
import com.justdataplease.spoon.domain.repository.SpoonRepository
import com.justdataplease.spoon.domain.repository.unavailableAccountFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persistent, account-free implementation used by builds without a Firebase configuration. */
class LocalSpoonRepository(
    private val preferences: SharedPreferences,
    private val json: Json,
) : SpoonRepository {
    private val mutationMutex = Mutex()
    private val _accountState = MutableStateFlow<AccountState>(AccountState.Unavailable)

    private val _mealPlans = MutableStateFlow(readPlans())
    private val _favoriteRecipeIds = MutableStateFlow(readFavoriteIds())
    private val _shoppingItems = MutableStateFlow(readList<ShoppingListItem>(KEY_SHOPPING_ITEMS))
    private val _recipeNotes = MutableStateFlow(readList<RecipeNote>(KEY_RECIPE_NOTES))
    private val _customRecipes = MutableStateFlow(readList<CustomRecipe>(KEY_CUSTOM_RECIPES))
    private val _cookedHistory = MutableStateFlow(readList<CookedMeal>(KEY_COOKED_HISTORY))

    override val backendState = MutableStateFlow<BackendState>(BackendState.Local).asStateFlow()
    override val accountState = _accountState.asStateFlow()
    override val recipes: Flow<List<Recipe>> =
        combine(flowOf(DemoRecipeCatalog.recipes), _customRecipes) { catalog, custom ->
            (catalog + custom.filter(CustomRecipe::active).map(CustomRecipe::toRecipe))
                .sortedBy(Recipe::title)
        }
    override val mealPlans: Flow<List<DayMealPlan>> = _mealPlans.asStateFlow()
    override val favoriteRecipeIds: Flow<Set<String>> = _favoriteRecipeIds.asStateFlow()
    override val shoppingItems: Flow<List<ShoppingListItem>> = _shoppingItems.asStateFlow()
    override val recipeNotes: Flow<List<RecipeNote>> = _recipeNotes.asStateFlow()
    override val customRecipes: Flow<List<CustomRecipe>> = _customRecipes.asStateFlow()
    override val cookedHistory: Flow<List<CookedMeal>> =
        combine(_mealPlans, _cookedHistory, ::mergeCookedHistory)

    override suspend fun ensureReady() = Unit

    override suspend fun getRecipeDetails(recipeId: String): Recipe? {
        val safeRecipeId = requireSafeRecipeDocumentId(recipeId)
        if (safeRecipeId.isCustomRecipeId()) {
            return _customRecipes.value
                .firstOrNull { it.id == safeRecipeId && it.active }
                ?.toRecipe()
        }
        val recipe = DemoRecipeCatalog.recipes.firstOrNull { it.id == safeRecipeId }
        return eligibleRecipeDetails(recipe, safeRecipeId, safeRecipeId)
    }

    override suspend fun upsertMealPlan(plan: DayMealPlan) {
        require(plan.date.isNotBlank()) { "A meal plan needs an ISO date" }
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val preservedHistory = mergeCookedHistory(_mealPlans.value, _cookedHistory.value)
                val stored = plan.copy(id = plan.date)
                val updated = (_mealPlans.value.filterNot { it.date == stored.date } + stored)
                    .sortedBy(DayMealPlan::date)
                val history = mergeCookedHistory(updated, preservedHistory)
                persistPlansAndHistory(updated, history)
                _mealPlans.value = updated
                _cookedHistory.value = history
            }
        }
    }

    override suspend fun setMealCompleted(date: String, completed: Boolean) {
        require(date.isNotBlank()) { "A meal plan needs an ISO date" }
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val current = checkNotNull(_mealPlans.value.firstOrNull { it.date == date }) {
                    "Cannot complete a meal plan that does not exist: $date"
                }
                if (current.completed == completed) return@withLock
                val historyBefore = mergeCookedHistory(_mealPlans.value, _cookedHistory.value)
                val changed = current.copy(
                    completed = completed,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
                val updated = (_mealPlans.value.filterNot { it.date == date } + changed)
                    .sortedBy(DayMealPlan::date)
                val history = if (completed) {
                    (historyBefore + changed.toCookedMeal())
                        .distinctBy(CookedMeal::id)
                        .sortedByDescending(CookedMeal::completedAtEpochMillis)
                } else {
                    historyBefore.filterNot { event -> event.matchesActiveCompletion(current) }
                }
                persistPlansAndHistory(updated, history)
                _mealPlans.value = updated
                _cookedHistory.value = history
            }
        }
    }

    override suspend fun deleteCookedHistoryEntry(historyId: String) {
        requireSafeRecipeDocumentId(historyId)
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val historyBefore = mergeCookedHistory(_mealPlans.value, _cookedHistory.value)
                val event = historyBefore.firstOrNull { it.id == historyId } ?: return@withLock
                val currentPlan = _mealPlans.value.firstOrNull { it.date == event.date }
                val updatedPlans = if (currentPlan != null && event.matchesActiveCompletion(currentPlan)) {
                    _mealPlans.value.map { plan ->
                        if (plan.date == currentPlan.date) {
                            plan.copy(completed = false, updatedAtEpochMillis = System.currentTimeMillis())
                        } else {
                            plan
                        }
                    }
                } else {
                    _mealPlans.value
                }
                val history = historyBefore.filterNot { it.id == historyId }
                persistPlansAndHistory(updatedPlans, history)
                _mealPlans.value = updatedPlans
                _cookedHistory.value = history
            }
        }
    }

    override suspend fun toggleFavorite(recipeId: String): Boolean {
        requireSafeRecipeDocumentId(recipeId)
        return withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val updated = _favoriteRecipeIds.value.toMutableSet()
                val isFavorite = if (updated.remove(recipeId)) false else {
                    updated.add(recipeId)
                    true
                }
                check(
                    preferences.edit()
                        .putStringSet(KEY_FAVORITES, updated.toSet())
                        .commit(),
                ) { "Could not persist favorites" }
                _favoriteRecipeIds.value = updated.toSet()
                isFavorite
            }
        }
    }

    override suspend fun upsertShoppingItems(items: List<ShoppingListItem>) {
        if (items.isEmpty()) return
        require(items.size <= MAX_SHOPPING_ITEMS_PER_WRITE)
        items.forEach(ShoppingListItem::requireValid)
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val incomingIds = items.mapTo(mutableSetOf(), ShoppingListItem::id)
                val updated = (_shoppingItems.value.filterNot { it.id in incomingIds } + items)
                    .sortedBy(ShoppingListItem::createdAtEpochMillis)
                persistList(KEY_SHOPPING_ITEMS, updated)
                _shoppingItems.value = updated
            }
        }
    }

    override suspend fun setShoppingItemChecked(itemId: String, checked: Boolean) {
        val safeItemId = requireSafeRecipeDocumentId(itemId)
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val current = checkNotNull(_shoppingItems.value.firstOrNull { it.id == safeItemId })
                val changed = current.copy(
                    checked = checked,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
                val updated = _shoppingItems.value.map { if (it.id == safeItemId) changed else it }
                persistList(KEY_SHOPPING_ITEMS, updated)
                _shoppingItems.value = updated
            }
        }
    }

    override suspend fun deleteShoppingItem(itemId: String) {
        val safeItemId = requireSafeRecipeDocumentId(itemId)
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val updated = _shoppingItems.value.filterNot { it.id == safeItemId }
                persistList(KEY_SHOPPING_ITEMS, updated)
                _shoppingItems.value = updated
            }
        }
    }

    override suspend fun clearCheckedShoppingItems() {
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val updated = _shoppingItems.value.filterNot(ShoppingListItem::checked)
                persistList(KEY_SHOPPING_ITEMS, updated)
                _shoppingItems.value = updated
            }
        }
    }

    override suspend fun upsertRecipeNote(note: RecipeNote) {
        val recipeId = requireSafeRecipeDocumentId(note.recipeId.ifBlank { note.id })
        require(note.id.isBlank() || note.id == recipeId)
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val updated = if (note.text.isBlank()) {
                    _recipeNotes.value.filterNot { it.recipeId == recipeId }
                } else {
                    val stored = note.copy(id = recipeId, recipeId = recipeId).requireValid()
                    (_recipeNotes.value.filterNot { it.recipeId == recipeId } + stored)
                        .sortedBy(RecipeNote::recipeId)
                }
                persistList(KEY_RECIPE_NOTES, updated)
                _recipeNotes.value = updated
            }
        }
    }

    override suspend fun upsertCustomRecipe(recipe: CustomRecipe) {
        recipe.requireValid()
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val updated = (_customRecipes.value.filterNot { it.id == recipe.id } + recipe)
                    .sortedByDescending(CustomRecipe::updatedAtEpochMillis)
                persistList(KEY_CUSTOM_RECIPES, updated)
                _customRecipes.value = updated
            }
        }
    }

    /**
     * Plans and cooked-history snapshots intentionally remain after deletion: both retain the
     * title needed to explain the historical choice even when its editable recipe is gone.
     */
    override suspend fun deleteCustomRecipe(recipeId: String) {
        require(recipeId.isCustomRecipeId())
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val recipes = _customRecipes.value.filterNot { it.id == recipeId }
                val notes = _recipeNotes.value.filterNot { it.recipeId == recipeId }
                val favorites = _favoriteRecipeIds.value - recipeId
                check(
                    preferences.edit()
                        .putString(KEY_CUSTOM_RECIPES, json.encodeToString(recipes))
                        .putString(KEY_RECIPE_NOTES, json.encodeToString(notes))
                        .putStringSet(KEY_FAVORITES, favorites)
                        .commit(),
                ) { "Could not delete custom recipe" }
                _customRecipes.value = recipes
                _recipeNotes.value = notes
                _favoriteRecipeIds.value = favorites
            }
        }
    }

    override suspend fun registerEmailAccount(email: String, password: String): Nothing =
        accountUnavailable()

    override suspend fun signInWithEmail(email: String, password: String): Nothing =
        accountUnavailable()

    override suspend fun sendPasswordReset(email: String): Nothing = accountUnavailable()

    override suspend fun signOutToAnonymous(): Nothing = accountUnavailable()

    private fun accountUnavailable(): Nothing =
        throw AccountOperationException(unavailableAccountFailure())

    private fun readPlans(): List<DayMealPlan> {
        val stored = preferences.getString(KEY_MEAL_PLANS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<DayMealPlan>>(stored) }
            .getOrDefault(emptyList())
            .filter { it.date.isNotBlank() }
            .distinctBy(DayMealPlan::date)
            .sortedBy(DayMealPlan::date)
    }

    private fun readFavoriteIds(): Set<String> =
        preferences.getStringSet(KEY_FAVORITES, emptySet())
            .orEmpty()
            .filter(String::isNotBlank)
            .toSet()

    private inline fun <reified T> readList(key: String): List<T> {
        val stored = preferences.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<T>>(stored) }.getOrDefault(emptyList())
    }

    private fun <T> persistList(key: String, values: List<T>, serializer: (List<T>) -> String) {
        check(
            preferences.edit()
                .putString(key, serializer(values))
                .commit(),
        ) { "Could not persist $key" }
    }

    private inline fun <reified T> persistList(key: String, values: List<T>) =
        persistList(key, values) { json.encodeToString(it) }

    private fun persistPlansAndHistory(
        plans: List<DayMealPlan>,
        history: List<CookedMeal>,
    ) {
        check(
            preferences.edit()
                .putString(KEY_MEAL_PLANS, json.encodeToString(plans))
                .putString(KEY_COOKED_HISTORY, json.encodeToString(history))
                .commit(),
        ) { "Could not persist meal plans and cooked history" }
    }

    companion object {
        const val PREFERENCES_NAME = "spoon_local_repository"
        private const val KEY_MEAL_PLANS = "meal_plans_v1"
        private const val KEY_FAVORITES = "favorite_recipe_ids_v1"
        private const val KEY_SHOPPING_ITEMS = "shopping_items_v1"
        private const val KEY_RECIPE_NOTES = "recipe_notes_v1"
        private const val KEY_CUSTOM_RECIPES = "custom_recipes_v1"
        private const val KEY_COOKED_HISTORY = "cooked_history_v1"
    }
}
