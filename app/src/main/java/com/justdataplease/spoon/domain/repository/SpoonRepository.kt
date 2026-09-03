package com.justdataplease.spoon.domain.repository

import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** The same observable contract is backed by Firestore or persistent local storage. */
interface SpoonRepository {
    val backendState: StateFlow<BackendState>
    val accountState: StateFlow<AccountState> get() = DEFAULT_ACCOUNT_STATE
    val recipes: Flow<List<Recipe>>
    val mealPlans: Flow<List<DayMealPlan>>
    val favoriteRecipeIds: Flow<Set<String>>
    val shoppingItems: Flow<List<ShoppingListItem>> get() = flowOf(emptyList())
    val recipeNotes: Flow<List<RecipeNote>> get() = flowOf(emptyList())
    val customRecipes: Flow<List<CustomRecipe>> get() = flowOf(emptyList())
    val cookedHistory: Flow<List<CookedMeal>> get() = flowOf(emptyList())

    suspend fun ensureReady()
    suspend fun getRecipeDetails(recipeId: String): Recipe?
    suspend fun upsertMealPlan(plan: DayMealPlan)
    suspend fun setMealCompleted(date: String, completed: Boolean)
    suspend fun deleteCookedHistoryEntry(historyId: String): Unit =
        unsupported("cooked history")
    suspend fun toggleFavorite(recipeId: String): Boolean
    suspend fun upsertShoppingItems(items: List<ShoppingListItem>): Unit = unsupported("shopping list")
    suspend fun setShoppingItemChecked(itemId: String, checked: Boolean): Unit =
        unsupported("shopping list")
    suspend fun deleteShoppingItem(itemId: String): Unit = unsupported("shopping list")
    suspend fun clearCheckedShoppingItems(): Unit = unsupported("shopping list")
    suspend fun upsertRecipeNote(note: RecipeNote): Unit = unsupported("recipe notes")
    suspend fun upsertCustomRecipe(recipe: CustomRecipe): Unit = unsupported("custom recipes")
    suspend fun deleteCustomRecipe(recipeId: String): Unit = unsupported("custom recipes")
    suspend fun registerEmailAccount(email: String, password: String): Unit =
        unsupported("email accounts")
    suspend fun signInWithEmail(email: String, password: String): Unit =
        unsupported("email accounts")
    suspend fun sendPasswordReset(email: String): Unit = unsupported("email accounts")
    suspend fun signOutToAnonymous(): Unit = unsupported("email accounts")
}

private val DEFAULT_ACCOUNT_STATE = MutableStateFlow<AccountState>(AccountState.Unavailable)

private fun unsupported(feature: String): Nothing =
    throw UnsupportedOperationException("Repository does not support $feature")

/** Readable alias for call sites that think of this primarily as a recipe repository. */
typealias RecipeRepository = SpoonRepository
