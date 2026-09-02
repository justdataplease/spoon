package com.justdataplease.spoon.data

import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.domain.repository.BackendFailure
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.domain.repository.BackendUnavailableException
import com.justdataplease.spoon.domain.repository.SpoonRepository
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

    override val backendState =
        MutableStateFlow<BackendState>(BackendState.Error(failure)).asStateFlow()
    override val recipes = emptyRecipes.asStateFlow()
    override val mealPlans = emptyPlans.asStateFlow()
    override val favoriteRecipeIds = emptyFavorites.asStateFlow()

    override suspend fun ensureReady(): Nothing = unavailable()

    override suspend fun getRecipeDetails(recipeId: String): Nothing = unavailable()

    override suspend fun upsertMealPlan(plan: DayMealPlan): Nothing = unavailable()

    override suspend fun setMealCompleted(date: String, completed: Boolean): Nothing = unavailable()

    override suspend fun toggleFavorite(recipeId: String): Nothing = unavailable()

    private fun unavailable(): Nothing = throw BackendUnavailableException(failure)
}
