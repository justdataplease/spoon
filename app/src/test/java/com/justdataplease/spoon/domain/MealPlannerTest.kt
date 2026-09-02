package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.DemoRecipeCatalog
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.EaseLevel
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.domain.repository.SpoonRepository
import com.justdataplease.spoon.domain.repository.BackendState
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPlannerTest {
    @Test
    fun `ensure week creates all seven days once`() = runBlocking {
        val repository = FakeRepository()
        val planner = MealPlanner(repository, RecipeSelector())
        val thursday = LocalDate.of(2026, 9, 3)

        val first = planner.ensureWeek(thursday, kotlin.random.Random(1))
        val second = planner.ensureWeek(thursday, kotlin.random.Random(2))

        assertEquals(7, first.size)
        assertEquals(first, second)
        assertEquals(7, repository.plans.value.size)
        assertEquals(
            MealCategory.LEGUMES.key,
            first.first { LocalDate.parse(it.date).dayOfWeek == java.time.DayOfWeek.MONDAY }.category,
        )
    }

    @Test
    fun `ensure week never persists blank recipe ids for an empty catalog`() = runBlocking {
        val repository = FakeRepository(initialRecipes = emptyList())
        val planner = MealPlanner(repository, RecipeSelector())

        val week = planner.ensureWeek(LocalDate.of(2026, 9, 3))

        assertEquals(7, week.size)
        assertTrue(week.all { it.recipeId.isBlank() })
        assertTrue(repository.plans.value.isEmpty())
    }

    @Test
    fun `failed strict reroll leaves the current plan untouched`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val original = DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            category = MealCategory.FISH.key,
            recipeId = "demo-fish-lemon-parcel",
            recipeTitle = "Current",
            filters = RecipeFilters(category = MealCategory.FISH.key),
        )
        val repository = FakeRepository(initialPlans = listOf(original))
        val planner = MealPlanner(repository, RecipeSelector())

        val result = planner.reroll(
            date = date,
            filters = RecipeFilters(
                category = MealCategory.FISH.key,
                easeLevel = EaseLevel.INVOLVED.key,
                minRating = 10.0,
                maxPrepMinutes = 5,
            ),
        )

        assertTrue(result is MealPlanSelection.NoMatch)
        assertEquals(listOf(original), repository.plans.value)
    }

    @Test
    fun `ensure week repairs a partial plan and preserves completion`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val partial = DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            filters = RecipeFilters(category = MealCategory.FISH.key),
            completed = true,
        )
        val repository = FakeRepository(initialPlans = listOf(partial))
        val planner = MealPlanner(repository, RecipeSelector())

        val repaired = planner.ensureWeek(date, kotlin.random.Random(3))
            .first { it.date == date.toString() }

        assertEquals(MealCategory.FISH.key, repaired.category)
        assertTrue(repaired.recipeId.isNotBlank())
        assertTrue(repaired.completed)
    }

    @Test
    fun `ensure week replaces a stored inactive recipe with an active candidate`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val stale = DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            category = MealCategory.FISH.key,
            recipeId = "retired-fish",
            recipeTitle = "Παλιά πρόταση",
            filters = RecipeFilters(category = MealCategory.FISH.key),
            completed = true,
        )
        val repository = FakeRepository(
            initialPlans = listOf(stale),
            initialRecipes = listOf(
                Recipe(
                    id = "retired-fish",
                    title = "Ανενεργή",
                    category = MealCategory.FISH.key,
                    active = false,
                ),
                Recipe(
                    id = "current-fish",
                    title = "Νέα πρόταση",
                    category = MealCategory.FISH.key,
                ),
            ),
        )
        val planner = MealPlanner(repository, RecipeSelector())

        val repaired = planner.ensureWeek(date, kotlin.random.Random(4))
            .first { it.date == date.toString() }

        assertEquals("current-fish", repaired.recipeId)
        assertEquals("Νέα πρόταση", repaired.recipeTitle)
        assertTrue(repaired.completed)
        assertEquals(repaired, repository.plans.value.single())
    }

    @Test
    fun `ensure week preserves stored title when a stale recipe has no replacement`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val stale = DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            category = MealCategory.FISH.key,
            recipeId = "removed-fish",
            recipeTitle = "Ιστορικός τίτλος",
            filters = RecipeFilters(category = MealCategory.FISH.key),
        )
        val repository = FakeRepository(
            initialPlans = listOf(stale),
            initialRecipes = emptyList(),
        )
        val planner = MealPlanner(repository, RecipeSelector())

        val preserved = planner.ensureWeek(date)
            .first { it.date == date.toString() }

        assertEquals("removed-fish", preserved.recipeId)
        assertEquals("Ιστορικός τίτλος", preserved.recipeTitle)
        assertEquals(listOf(stale), repository.plans.value)
    }

    @Test
    fun `favorite replacement rejects non favorite without writing`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val original = DayMealPlan(id = date.toString(), date = date.toString(), recipeId = "old")
        val candidate = Recipe(id = "candidate", category = MealCategory.MEAT.key)
        val repository = FakeRepository(initialPlans = listOf(original), initialRecipes = listOf(candidate))

        val result = MealPlanner(repository, RecipeSelector()).replaceWithFavorite(date, candidate.id)

        assertTrue(result is FavoriteReplacementResult.NotFavorite)
        assertEquals(listOf(original), repository.plans.value)
        assertEquals(0, repository.upsertCount)
        assertEquals(1, repository.ensureReadyCount)
    }

    @Test
    fun `favorite replacement rejects missing recipe without writing`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val repository = FakeRepository(initialRecipes = emptyList(), initialFavorites = setOf("missing"))

        val result = MealPlanner(repository, RecipeSelector()).replaceWithFavorite(date, "missing")

        assertTrue(result is FavoriteReplacementResult.RecipeUnavailable)
        assertTrue(repository.plans.value.isEmpty())
        assertEquals(0, repository.upsertCount)
    }

    @Test
    fun `favorite replacement rejects inactive or non Greek recipe without writing`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val unavailable = listOf(
            Recipe(id = "inactive", active = false),
            Recipe(id = "english", language = "en"),
        )
        unavailable.forEach { recipe ->
            val repository = FakeRepository(initialRecipes = unavailable, initialFavorites = setOf(recipe.id))
            val result = MealPlanner(repository, RecipeSelector()).replaceWithFavorite(date, recipe.id)

            assertTrue(result is FavoriteReplacementResult.RecipeUnavailable)
            assertTrue(repository.plans.value.isEmpty())
            assertEquals(0, repository.upsertCount)
        }
    }

    @Test
    fun `favorite replacement resets completion and filters and keeps category invariant`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val strict = RecipeFilters(MealCategory.FISH.key, EaseLevel.INVOLVED.key, 9.5, 10)
        val old = DayMealPlan(id = date.toString(), date = date.toString(), category = MealCategory.FISH.key, filters = strict, completed = true)
        val favorite = Recipe(id = "favorite", title = "Αγαπημένη", category = MealCategory.MEAT.key)
        val repository = FakeRepository(listOf(old), listOf(favorite), setOf(favorite.id))

        val result = MealPlanner(repository, RecipeSelector()).replaceWithFavorite(date, favorite.id)
        val plan = (result as FavoriteReplacementResult.Selected).plan

        assertEquals(favorite, result.recipe)
        assertEquals(favorite.id, plan.recipeId)
        assertEquals(favorite.title, plan.recipeTitle)
        assertEquals(plan.category, plan.filters.category)
        assertEquals(RecipeFilters(category = MealCategory.MEAT.key), plan.filters)
        assertEquals(false, plan.completed)
        assertEquals(listOf(plan), repository.plans.value)
        assertEquals(1, repository.upsertCount)
    }

    @Test
    fun `blank favorite category falls back to existing day category`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val old = DayMealPlan(id = date.toString(), date = date.toString(), category = MealCategory.LEGUMES.key)
        val favorite = Recipe(id = "favorite", category = "")
        val repository = FakeRepository(listOf(old), listOf(favorite), setOf(favorite.id))

        val result = MealPlanner(repository, RecipeSelector()).replaceWithFavorite(date, favorite.id)
        val plan = (result as FavoriteReplacementResult.Selected).plan

        assertEquals(MealCategory.LEGUMES.key, plan.category)
        assertEquals(plan.category, plan.filters.category)
    }

    private class FakeRepository(
        initialPlans: List<DayMealPlan> = emptyList(),
        initialRecipes: List<Recipe> = DemoRecipeCatalog.recipes,
        initialFavorites: Set<String> = emptySet(),
    ) : SpoonRepository {
        override val backendState = MutableStateFlow<BackendState>(BackendState.Local)
        override val recipes: Flow<List<Recipe>> = MutableStateFlow(initialRecipes)
        val plans = MutableStateFlow(initialPlans)
        override val mealPlans: Flow<List<DayMealPlan>> = plans
        private val favorites = MutableStateFlow(initialFavorites)
        override val favoriteRecipeIds: Flow<Set<String>> = favorites
        var ensureReadyCount = 0
        var upsertCount = 0

        override suspend fun ensureReady() {
            ensureReadyCount++
        }

        override suspend fun getRecipeDetails(recipeId: String): Recipe? =
            (recipes as MutableStateFlow<List<Recipe>>).value.firstOrNull { it.id == recipeId }

        override suspend fun upsertMealPlan(plan: DayMealPlan) {
            upsertCount++
            plans.value = plans.value.filterNot { it.date == plan.date } + plan
        }

        override suspend fun setMealCompleted(date: String, completed: Boolean) {
            plans.value = plans.value.map {
                if (it.date == date) it.copy(completed = completed) else it
            }
        }

        override suspend fun toggleFavorite(recipeId: String): Boolean {
            val changed = favorites.value.toMutableSet()
            val added = if (changed.remove(recipeId)) false else {
                changed.add(recipeId)
                true
            }
            favorites.value = changed
            return added
        }
    }
}
