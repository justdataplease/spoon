package com.justdataplease.spoon.data.local

import android.content.SharedPreferences
import com.justdataplease.spoon.data.DemoRecipeCatalog
import com.justdataplease.spoon.data.eligibleRecipeDetails
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.requireSafeRecipeDocumentId
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.domain.repository.SpoonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _mealPlans = MutableStateFlow(readPlans())
    private val _favoriteRecipeIds = MutableStateFlow(readFavoriteIds())

    override val backendState = MutableStateFlow<BackendState>(BackendState.Local).asStateFlow()
    override val recipes: Flow<List<Recipe>> = flowOf(DemoRecipeCatalog.recipes)
    override val mealPlans: Flow<List<DayMealPlan>> = _mealPlans.asStateFlow()
    override val favoriteRecipeIds: Flow<Set<String>> = _favoriteRecipeIds.asStateFlow()

    override suspend fun ensureReady() = Unit

    override suspend fun getRecipeDetails(recipeId: String): Recipe? {
        val safeRecipeId = requireSafeRecipeDocumentId(recipeId)
        val recipe = DemoRecipeCatalog.recipes.firstOrNull { it.id == safeRecipeId }
        return eligibleRecipeDetails(recipe, safeRecipeId, safeRecipeId)
    }

    override suspend fun upsertMealPlan(plan: DayMealPlan) {
        require(plan.date.isNotBlank()) { "A meal plan needs an ISO date" }
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val stored = plan.copy(id = plan.date)
                val updated = (_mealPlans.value.filterNot { it.date == stored.date } + stored)
                    .sortedBy(DayMealPlan::date)
                persistPlans(updated)
                _mealPlans.value = updated
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
                val changed = current.copy(
                    completed = completed,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
                val updated = (_mealPlans.value.filterNot { it.date == date } + changed)
                    .sortedBy(DayMealPlan::date)
                persistPlans(updated)
                _mealPlans.value = updated
            }
        }
    }

    override suspend fun toggleFavorite(recipeId: String): Boolean {
        require(recipeId.isNotBlank()) { "A favorite needs a recipe id" }
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

    private fun persistPlans(plans: List<DayMealPlan>) {
        check(
            preferences.edit()
                .putString(KEY_MEAL_PLANS, json.encodeToString(plans))
                .commit(),
        ) { "Could not persist meal plans" }
    }

    companion object {
        const val PREFERENCES_NAME = "spoon_local_repository"
        private const val KEY_MEAL_PLANS = "meal_plans_v1"
        private const val KEY_FAVORITES = "favorite_recipe_ids_v1"
    }
}
