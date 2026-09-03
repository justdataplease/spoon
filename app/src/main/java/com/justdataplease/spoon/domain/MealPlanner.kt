package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.data.model.newCustomRecipeId
import com.justdataplease.spoon.data.isSafeRecipeDocumentId
import com.justdataplease.spoon.domain.repository.AccountState
import com.justdataplease.spoon.domain.repository.SpoonRepository
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.random.Random

sealed interface MealPlanSelection {
    data class Selected(
        val plan: DayMealPlan,
        val recipe: Recipe,
    ) : MealPlanSelection

    data class NoMatch(
        val date: LocalDate,
        val filters: RecipeFilters,
        val excludedRecipeId: String = "",
    ) : MealPlanSelection
}

sealed interface FavoriteReplacementResult {
    data class Selected(
        val plan: DayMealPlan,
        val recipe: Recipe,
    ) : FavoriteReplacementResult

    data class NotFavorite(
        val date: LocalDate,
        val recipeId: String,
    ) : FavoriteReplacementResult

    data class RecipeUnavailable(
        val date: LocalDate,
        val recipeId: String,
    ) : FavoriteReplacementResult
}

class MealPlanner @Inject constructor(
    private val repository: SpoonRepository,
    private val selector: RecipeSelector,
) {
    val backendState = repository.backendState
    val accountState: Flow<AccountState> = repository.accountState
    val recipes: Flow<List<Recipe>> = repository.recipes
    val mealPlans: Flow<List<DayMealPlan>> = repository.mealPlans
    val favoriteRecipeIds: Flow<Set<String>> = repository.favoriteRecipeIds
    val shoppingItems: Flow<List<ShoppingListItem>> = repository.shoppingItems
    val recipeNotes: Flow<List<RecipeNote>> = repository.recipeNotes
    val customRecipes: Flow<List<CustomRecipe>> = repository.customRecipes
    val cookedHistory: Flow<List<CookedMeal>> = repository.cookedHistory

    suspend fun getRecipeDetails(recipeId: String): Recipe? =
        repository.getRecipeDetails(recipeId)

    fun observeWeek(containingDate: LocalDate): Flow<List<DayMealPlan>> {
        val dates = WeeklyPlanDefaults.dates(containingDate).map(LocalDate::toString).toSet()
        return mealPlans.map { plans ->
            plans.filter { it.date in dates }.sortedBy(DayMealPlan::date)
        }
    }

    fun observeFavorites(): Flow<List<Recipe>> =
        combine(recipes, favoriteRecipeIds) { allRecipes, favoriteIds ->
            allRecipes.filter { it.id in favoriteIds }
        }.flowOn(Dispatchers.Default)

    suspend fun ensureWeek(
        containingDate: LocalDate = LocalDate.now(),
        random: Random = Random.Default,
    ): List<DayMealPlan> {
        repository.ensureReady()
        val recipes = repository.recipes.first().filter(Recipe::isActiveGreekRecipe)
        val recipesById = recipes.associateBy(Recipe::id)
        val existing = repository.mealPlans.first().associateBy(DayMealPlan::date)

        return WeeklyPlanDefaults.dates(containingDate).map { date ->
            val current = existing[date.toString()]
            if (current != null && current.isUsable(recipesById, selector)) {
                current
            } else {
                val replacement = createDay(
                    date = date,
                    recipes = recipes,
                    random = random,
                    previous = current,
                )
                when {
                    replacement.recipeId.isNotBlank() -> replacement.also {
                        repository.upsertMealPlan(it)
                    }
                    current != null -> current
                    else -> replacement
                }
            }
        }
    }

    suspend fun reroll(
        date: LocalDate,
        filters: RecipeFilters? = null,
        random: Random = Random.Default,
    ): MealPlanSelection {
        repository.ensureReady()
        val existing = repository.mealPlans.first().firstOrNull { it.date == date.toString() }
        val requestedFilters = filters ?: existing?.filters ?: WeeklyPlanDefaults.filtersFor(date)
        val excludedId = existing?.recipeId.orEmpty()
        val selected = selector.select(
            recipes = repository.recipes.first(),
            filters = requestedFilters,
            excludingRecipeId = excludedId.ifBlank { null },
            random = random,
        ) ?: return MealPlanSelection.NoMatch(date, requestedFilters, excludedId)

        val plan = DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            category = requestedFilters.category,
            recipeId = selected.id,
            recipeTitle = selected.title,
            filters = requestedFilters,
            // A different proposal has not been cooked yet. Persisting the previous
            // completion here would also create a false cooked-history entry.
            completed = false,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        repository.upsertMealPlan(plan)
        return MealPlanSelection.Selected(plan, selected)
    }

    suspend fun updateFilters(
        date: LocalDate,
        filters: RecipeFilters,
        random: Random = Random.Default,
    ): MealPlanSelection = reroll(date, filters, random)

    suspend fun setCompleted(date: LocalDate, completed: Boolean) {
        repository.ensureReady()
        repository.setMealCompleted(date.toString(), completed)
    }

    suspend fun toggleFavorite(recipeId: String): Boolean {
        repository.ensureReady()
        return repository.toggleFavorite(recipeId)
    }

    /** Adds every non-blank ingredient from the opened rich recipe as an independent item. */
    suspend fun addRecipeIngredientsToShoppingList(recipe: Recipe): Int {
        repository.ensureReady()
        val now = System.currentTimeMillis()
        val items = recipe.ingredientSections.flatMap { section ->
            section.ingredients.mapNotNull { ingredient ->
                val name = ingredient.title.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
                ShoppingListItem(
                    id = "shopping_${UUID.randomUUID()}",
                    name = name.take(200),
                    quantity = ingredient.quantity.take(100),
                    unit = ingredient.unit.take(100),
                    info = ingredient.info.take(500),
                    recipeId = recipe.id,
                    recipeTitle = recipe.title.take(300),
                    sectionTitle = section.title.take(200),
                    checked = false,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
            }
        }
        repository.upsertShoppingItems(items)
        return items.size
    }

    suspend fun upsertShoppingItems(items: List<ShoppingListItem>) {
        repository.ensureReady()
        repository.upsertShoppingItems(items)
    }

    suspend fun setShoppingItemChecked(itemId: String, checked: Boolean) {
        repository.ensureReady()
        repository.setShoppingItemChecked(itemId, checked)
    }

    suspend fun deleteShoppingItem(itemId: String) {
        repository.ensureReady()
        repository.deleteShoppingItem(itemId)
    }

    suspend fun clearCheckedShoppingItems() {
        repository.ensureReady()
        repository.clearCheckedShoppingItems()
    }

    fun observeRecipeNote(recipeId: String): Flow<RecipeNote?> =
        recipeNotes.map { notes -> notes.firstOrNull { it.recipeId == recipeId } }

    suspend fun saveRecipeNote(recipeId: String, text: String) {
        repository.ensureReady()
        repository.upsertRecipeNote(
            RecipeNote(
                id = recipeId,
                recipeId = recipeId,
                text = text,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    /** Assigns a stable custom id and timestamps for a new draft, or updates an existing recipe. */
    suspend fun saveCustomRecipe(draft: CustomRecipe): CustomRecipe {
        repository.ensureReady()
        val now = System.currentTimeMillis()
        val stored = draft.copy(
            id = draft.id.ifBlank(::newCustomRecipeId),
            createdAtEpochMillis = draft.createdAtEpochMillis.takeIf { it > 0L } ?: now,
            updatedAtEpochMillis = now,
        )
        repository.upsertCustomRecipe(stored)
        return stored
    }

    suspend fun deleteCustomRecipe(recipeId: String) {
        repository.ensureReady()
        repository.deleteCustomRecipe(recipeId)
    }

    suspend fun registerEmailAccount(email: String, password: String) {
        repository.ensureReady()
        repository.registerEmailAccount(email, password)
    }

    suspend fun signInWithEmail(email: String, password: String) {
        repository.ensureReady()
        repository.signInWithEmail(email, password)
    }

    suspend fun sendPasswordReset(email: String) {
        repository.sendPasswordReset(email)
    }

    suspend fun signOutToAnonymous() {
        repository.signOutToAnonymous()
    }

    /** Replaces a day's proposal only when [recipeId] is still an available favorite. */
    suspend fun replaceWithFavorite(
        date: LocalDate,
        recipeId: String,
    ): FavoriteReplacementResult {
        repository.ensureReady()
        if (recipeId.isBlank() || recipeId !in repository.favoriteRecipeIds.first()) {
            return FavoriteReplacementResult.NotFavorite(date, recipeId)
        }

        val recipe = repository.recipes.first()
            .firstOrNull { it.id == recipeId && it.isActiveGreekRecipe() }
            ?: return FavoriteReplacementResult.RecipeUnavailable(date, recipeId)
        val existing = repository.mealPlans.first()
            .firstOrNull { it.date == date.toString() }
        val category = recipe.category.ifBlank {
            existing?.category
                ?.takeIf(String::isNotBlank)
                ?: existing?.filters?.category
                    ?.takeIf(String::isNotBlank)
                ?: WeeklyPlanDefaults.filtersFor(date).category
        }
        val filters = RecipeFilters(category = category)
        val plan = DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            category = category,
            recipeId = recipe.id,
            recipeTitle = recipe.title,
            filters = filters,
            completed = false,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        repository.upsertMealPlan(plan)
        return FavoriteReplacementResult.Selected(plan, recipe)
    }

    private fun createDay(
        date: LocalDate,
        recipes: List<Recipe>,
        random: Random,
        previous: DayMealPlan? = null,
    ): DayMealPlan {
        val filters = previous?.filters
            ?.takeIf(RecipeFilters::isValid)
            ?: WeeklyPlanDefaults.filtersFor(date)
        val recipe = selector.select(recipes, filters, random = random)
        return DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            category = filters.category,
            recipeId = recipe?.id.orEmpty(),
            recipeTitle = recipe?.title.orEmpty(),
            filters = filters,
            // createDay is only used when the previous proposal is missing/unusable.
            // A newly selected recipe must never inherit another recipe's completion.
            completed = false,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun DayMealPlan.isUsable(
        recipesById: Map<String, Recipe>,
        selector: RecipeSelector,
    ): Boolean {
        val structurallyValid = date.isNotBlank() &&
            isSafeRecipeDocumentId(recipeId) &&
            recipeTitle.isNotBlank()
        if (completed && structurallyValid) return true
        if (!structurallyValid || category != filters.category || !filters.isValid()) return false
        val currentRecipe = recipesById[recipeId] ?: return false
        return selector.matches(currentRecipe, filters)
    }
}
