package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.data.model.isCustomRecipeId
import com.justdataplease.spoon.data.model.newCustomRecipeId
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.data.isSafeRecipeDocumentId
import com.justdataplease.spoon.domain.repository.AccountState
import com.justdataplease.spoon.domain.repository.CatalogFacetOptions
import com.justdataplease.spoon.domain.repository.RecipePage
import com.justdataplease.spoon.domain.repository.SpoonRepository
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    val mealPreferenceSettings: Flow<MealPreferenceSettings> = repository.mealPreferenceSettings

    suspend fun getRecipeDetails(recipeId: String): Recipe? =
        repository.getRecipeDetails(recipeId)

    suspend fun queryRecipes(
        criteria: ExploreCriteria = ExploreCriteria(),
        limit: Int = 24,
        offset: Int = 0,
    ): RecipePage = repository.queryRecipes(
        criteria,
        limit,
        offset,
        mealPreferenceSettings.first(),
    )

    suspend fun saveMealPreferenceSettings(settings: MealPreferenceSettings) {
        repository.updateMealPreferenceSettings(settings)
    }

    suspend fun clearMealPreferenceSettings() {
        repository.updateMealPreferenceSettings(MealPreferenceSettings())
    }

    suspend fun getCatalogFacetOptions(): CatalogFacetOptions =
        repository.getCatalogFacetOptions()

    suspend fun ensureWeek(
        containingDate: LocalDate = LocalDate.now(),
        random: Random = Random.Default,
    ): List<DayMealPlan> {
        repository.ensureReady()
        // Keep one coherent preference snapshot for the entire refresh. A cloud/cache update
        // arriving halfway through the loop must not produce a week with mixed policies.
        val preferences = mealPreferenceSettings.first()
        val existing = repository.mealPlans.first().associateBy(DayMealPlan::date)
        val recipesById = repository.getRecipesByIds(
            existing.values.mapNotNullTo(mutableSetOf()) { it.recipeId.takeIf(String::isNotBlank) },
        ).associateBy(Recipe::id)

        return WeeklyPlanDefaults.dates(containingDate).map { date ->
            val current = existing[date.toString()]
            if (current != null && current.isUsable(recipesById, preferences)) {
                current
            } else {
                val replacement = createDay(
                    date = date,
                    random = random,
                    previous = current,
                    preferences = preferences,
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
        // A reroll is an explicit user action and recipe selection is served by the bundled
        // catalog. Do not wait for every Firestore owner listener before searching locally;
        // the resulting personal write is queued to Firestore by the repository.
        val existing = repository.mealPlans.first().firstOrNull { it.date == date.toString() }
        val requestedFilters = filters ?: existing?.filters ?: WeeklyPlanDefaults.filtersFor(date)
        val excludedId = existing?.recipeId.orEmpty()
        val preferences = mealPreferenceSettings.first()
        var effectiveFilters = requestedFilters
        var selected = selectEligibleRecipe(
            filters = requestedFilters,
            excludingRecipeId = excludedId.ifBlank { null },
            randomSeed = random.nextLong(),
            preferences = preferences,
        )
        if (
            selected == null &&
            filters == null &&
            preferences.hasActiveSelections() &&
            requestedFilters.category != MealCategory.ANY.key
        ) {
            effectiveFilters = requestedFilters.copy(category = MealCategory.ANY.key)
            selected = selectEligibleRecipe(
                filters = effectiveFilters,
                excludingRecipeId = excludedId.ifBlank { null },
                randomSeed = random.nextLong(),
                preferences = preferences,
            )
        }
        selected ?: return MealPlanSelection.NoMatch(date, effectiveFilters, excludedId)

        val plan = newPlan(date, effectiveFilters, selected)
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

    suspend fun deleteCookedHistoryEntry(historyId: String) {
        repository.ensureReady()
        repository.deleteCookedHistoryEntry(historyId)
    }

    suspend fun toggleFavorite(recipeId: String): Boolean {
        repository.ensureReady()
        return repository.toggleFavorite(recipeId)
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
        val recipeId = draft.id.ifBlank(::newCustomRecipeId)
        val existing = repository.customRecipes.first().firstOrNull { it.id == recipeId }
        val stored = draft.copy(
            id = recipeId,
            createdAtEpochMillis = existing?.createdAtEpochMillis
                ?.takeIf { it > 0L }
                ?: draft.createdAtEpochMillis.takeIf { it > 0L }
                ?: now,
            updatedAtEpochMillis = now,
        )
        repository.upsertCustomRecipe(stored)
        return stored
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

        val recipe = repository.getRecipesByIds(setOf(recipeId))
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
        val plan = newPlan(date, RecipeFilters(category = category), recipe)
        repository.upsertMealPlan(plan)
        return FavoriteReplacementResult.Selected(plan, recipe)
    }

    private suspend fun createDay(
        date: LocalDate,
        random: Random,
        previous: DayMealPlan? = null,
        preferences: MealPreferenceSettings,
    ): DayMealPlan {
        val filters = previous?.filters
            ?.takeIf(RecipeFilters::isValid)
            ?: WeeklyPlanDefaults.filtersFor(date)
        var effectiveFilters = filters
        var recipe = selectEligibleRecipe(
            filters = filters,
            excludingRecipeId = null,
            randomSeed = random.nextLong(),
            preferences = preferences,
        )
        if (
            recipe == null &&
            preferences.hasActiveSelections() &&
            filters.category != MealCategory.ANY.key
        ) {
            effectiveFilters = filters.copy(category = MealCategory.ANY.key)
            recipe = selectEligibleRecipe(
                filters = effectiveFilters,
                excludingRecipeId = null,
                randomSeed = random.nextLong(),
                preferences = preferences,
            )
        }
        return newPlan(date, effectiveFilters, recipe)
    }

    /** Defends the planner contract even if a repository implementation returns a bad row. */
    private suspend fun selectEligibleRecipe(
        filters: RecipeFilters,
        excludingRecipeId: String?,
        randomSeed: Long,
        preferences: MealPreferenceSettings,
    ): Recipe? = repository.selectRandomRecipe(
        filters = filters,
        excludingRecipeId = excludingRecipeId,
        randomSeed = randomSeed,
        preferences = preferences,
    )?.takeIf { recipe ->
        recipe.id != excludingRecipeId &&
            selector.matches(recipe, filters) &&
            recipe.matchesMealPreferences(preferences)
    }

    private fun newPlan(date: LocalDate, filters: RecipeFilters, recipe: Recipe?): DayMealPlan =
        DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            category = filters.category,
            recipeId = recipe?.id.orEmpty(),
            recipeTitle = recipe?.title.orEmpty(),
            filters = filters,
            // A fresh proposal has not been cooked yet. Inheriting a previous completion
            // would also create a false cooked-history entry.
            completed = false,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )

    private fun DayMealPlan.isUsable(
        recipesById: Map<String, Recipe>,
        preferences: MealPreferenceSettings,
    ): Boolean {
        val structurallyValid = date.isNotBlank() &&
            isSafeRecipeDocumentId(recipeId) &&
            recipeTitle.isNotBlank()
        if (completed && structurallyValid) return true
        if (!structurallyValid || category != filters.category || !filters.isValid()) return false
        val currentRecipe = recipesById[recipeId]
            ?: return recipeId.isCustomRecipeId()
        return selector.matches(currentRecipe, filters) &&
            currentRecipe.matchesMealPreferences(preferences)
    }
}
