package com.justdataplease.spoon.domain.repository

import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.Recipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The same observable contract is backed by Firestore or persistent local storage. */
interface SpoonRepository {
    val backendState: StateFlow<BackendState>
    val recipes: Flow<List<Recipe>>
    val mealPlans: Flow<List<DayMealPlan>>
    val favoriteRecipeIds: Flow<Set<String>>

    suspend fun ensureReady()
    suspend fun getRecipeDetails(recipeId: String): Recipe?
    suspend fun upsertMealPlan(plan: DayMealPlan)
    suspend fun setMealCompleted(date: String, completed: Boolean)
    suspend fun toggleFavorite(recipeId: String): Boolean
}

/** Readable alias for call sites that think of this primarily as a recipe repository. */
typealias RecipeRepository = SpoonRepository
