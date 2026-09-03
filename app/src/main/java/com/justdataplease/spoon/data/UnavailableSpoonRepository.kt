package com.justdataplease.spoon.data

import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.domain.repository.BackendFailure
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.domain.repository.BackendUnavailableException
import com.justdataplease.spoon.domain.repository.AccountOperationException
import com.justdataplease.spoon.domain.repository.AccountState
import com.justdataplease.spoon.domain.repository.SpoonRepository
import com.justdataplease.spoon.domain.repository.unavailableAccountFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fail-closed repository used when a configured Firebase installation cannot initialize.
 * It deliberately exposes no demo data, preventing local plans from masquerading as cloud data.
 */
class UnavailableSpoonRepository(
    private val failure: BackendFailure,
) : SpoonRepository {
    private val emptyRecipes = MutableStateFlow<List<Recipe>>(emptyList())
    private val emptyPlans = MutableStateFlow<List<DayMealPlan>>(emptyList())
    private val emptyFavorites = MutableStateFlow<Set<String>>(emptySet())
    private val emptyShoppingItems = MutableStateFlow<List<ShoppingListItem>>(emptyList())
    private val emptyRecipeNotes = MutableStateFlow<List<RecipeNote>>(emptyList())
    private val emptyCustomRecipes = MutableStateFlow<List<CustomRecipe>>(emptyList())
    private val emptyCookedHistory = MutableStateFlow<List<CookedMeal>>(emptyList())
    private val unavailableAccount =
        MutableStateFlow<AccountState>(AccountState.Unavailable).asStateFlow()

    override val backendState =
        MutableStateFlow<BackendState>(BackendState.Error(failure)).asStateFlow()
    override val accountState = unavailableAccount
    override val recipes = emptyRecipes.asStateFlow()
    override val mealPlans = emptyPlans.asStateFlow()
    override val favoriteRecipeIds = emptyFavorites.asStateFlow()
    override val shoppingItems = emptyShoppingItems.asStateFlow()
    override val recipeNotes = emptyRecipeNotes.asStateFlow()
    override val customRecipes = emptyCustomRecipes.asStateFlow()
    override val cookedHistory = emptyCookedHistory.asStateFlow()

    override suspend fun ensureReady(): Nothing = unavailable()

    override suspend fun getRecipeDetails(recipeId: String): Nothing = unavailable()

    override suspend fun upsertMealPlan(plan: DayMealPlan): Nothing = unavailable()

    override suspend fun setMealCompleted(date: String, completed: Boolean): Nothing = unavailable()

    override suspend fun deleteCookedHistoryEntry(historyId: String): Nothing = unavailable()

    override suspend fun toggleFavorite(recipeId: String): Nothing = unavailable()

    override suspend fun upsertShoppingItems(items: List<ShoppingListItem>): Nothing = unavailable()

    override suspend fun setShoppingItemChecked(itemId: String, checked: Boolean): Nothing = unavailable()

    override suspend fun deleteShoppingItem(itemId: String): Nothing = unavailable()

    override suspend fun clearCheckedShoppingItems(): Nothing = unavailable()

    override suspend fun upsertRecipeNote(note: RecipeNote): Nothing = unavailable()

    override suspend fun upsertCustomRecipe(recipe: CustomRecipe): Nothing = unavailable()

    override suspend fun deleteCustomRecipe(recipeId: String): Nothing = unavailable()

    override suspend fun registerEmailAccount(email: String, password: String): Nothing =
        accountUnavailable()

    override suspend fun signInWithEmail(email: String, password: String): Nothing =
        accountUnavailable()

    override suspend fun sendPasswordReset(email: String): Nothing = accountUnavailable()

    override suspend fun signOutToAnonymous(): Nothing = accountUnavailable()

    private fun unavailable(): Nothing = throw BackendUnavailableException(failure)

    private fun accountUnavailable(): Nothing =
        throw AccountOperationException(unavailableAccountFailure())
}
