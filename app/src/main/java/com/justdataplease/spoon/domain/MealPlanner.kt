package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.domain.repository.SpoonRepository
import java.time.LocalDate
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
    val recipes: Flow<List<Recipe>> = repository.recipes
    val mealPlans: Flow<List<DayMealPlan>> = repository.mealPlans
    val favoriteRecipeIds: Flow<Set<String>> = repository.favoriteRecipeIds

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
        val recipes = repository.recipes.first().filter(Recipe::active)
        val availableRecipeIds = recipes.mapTo(mutableSetOf(), Recipe::id)
        val existing = repository.mealPlans.first().associateBy(DayMealPlan::date)

        return WeeklyPlanDefaults.dates(containingDate).map { date ->
            val current = existing[date.toString()]
            if (current != null && current.isUsable(availableRecipeIds)) {
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
            completed = existing?.completed ?: false,
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
            completed = previous?.completed ?: false,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun DayMealPlan.isUsable(availableRecipeIds: Set<String>): Boolean =
        date.isNotBlank() &&
            recipeId.isNotBlank() &&
            recipeId in availableRecipeIds &&
            category == filters.category &&
            filters.isValid()
}
