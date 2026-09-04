package com.justdataplease.spoon.domain.repository

import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.domain.ExploreCriteria
import com.justdataplease.spoon.domain.ExploreRecipeFilter
import com.justdataplease.spoon.domain.RecipeSelector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlin.random.Random

data class RecipePage(
    val recipes: List<Recipe>,
    val totalCount: Int,
    val offset: Int,
    val limit: Int,
) {
    val hasMore: Boolean get() = offset + recipes.size < totalCount
}

data class CatalogSourceOption(
    val key: String,
    val label: String,
    val recipeCount: Int,
)

data class CatalogFacetOptions(
    val sources: List<CatalogSourceOption> = emptyList(),
    val diets: List<String> = emptyList(),
    val mealTypes: List<String> = emptyList(),
    val occasions: List<String> = emptyList(),
    val methods: List<String> = emptyList(),
    val cuisines: List<String> = emptyList(),
    val ingredients: List<String> = emptyList(),
)

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
    suspend fun getRecipesByIds(recipeIds: Set<String>): List<Recipe> {
        if (recipeIds.isEmpty()) return emptyList()
        return recipes.first().filter { it.id in recipeIds }
    }
    suspend fun queryRecipes(
        criteria: ExploreCriteria = ExploreCriteria(),
        limit: Int = 24,
        offset: Int = 0,
    ): RecipePage {
        require(limit in 1..100)
        require(offset >= 0)
        val matches = ExploreRecipeFilter.filter(recipes.first(), criteria)
        return RecipePage(matches.drop(offset).take(limit), matches.size, offset, limit)
    }
    suspend fun getCatalogFacetOptions(): CatalogFacetOptions = CatalogFacetOptions()
    suspend fun selectRandomRecipe(
        filters: RecipeFilters,
        excludingRecipeId: String? = null,
        randomSeed: Long = Random.Default.nextLong(),
    ): Recipe? = RecipeSelector().select(
        recipes = recipes.first(),
        filters = filters,
        excludingRecipeId = excludingRecipeId,
        random = Random(randomSeed),
    )
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
