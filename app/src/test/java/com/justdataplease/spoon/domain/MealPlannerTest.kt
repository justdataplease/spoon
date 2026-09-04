package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.DemoRecipeCatalog
import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.EaseLevel
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.data.model.RecipeIngredient
import com.justdataplease.spoon.data.model.RecipeIngredientSection
import com.justdataplease.spoon.data.model.RecipeMethodSection
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
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
    fun `explicit reroll uses local selection without waiting for full repository readiness`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val repository = FakeRepository()

        val result = MealPlanner(repository, RecipeSelector()).reroll(date)

        assertTrue(result is MealPlanSelection.Selected)
        assertEquals(0, repository.ensureReadyCount)
    }

    @Test
    fun `chicken-only preference falls back from the weekday category to poultry only`() = runBlocking {
        val date = LocalDate.of(2026, 8, 31) // Monday normally requests legumes.
        val poultry = DemoRecipeCatalog.recipes.first { recipe ->
            recipe.category == MealCategory.POULTRY.key
        }
        val meat = DemoRecipeCatalog.recipes.first { recipe ->
            recipe.category == MealCategory.MEAT.key
        }
        val allowed = setOf(MealCategory.POULTRY.key)
        val repository = FakeRepository(
            initialRecipes = listOf(poultry, meat),
            initialPreferences = MealPreferenceSettings(
                excludedCategories = MealCategory.entries
                    .map(MealCategory::key)
                    .filterNot { key -> key == MealCategory.ANY.key || key in allowed }
                    .toSet(),
            ),
        )

        val result = MealPlanner(repository, RecipeSelector()).reroll(date)

        assertTrue(result is MealPlanSelection.Selected)
        val selected = result as MealPlanSelection.Selected
        assertEquals(MealCategory.POULTRY.key, selected.recipe.category)
        assertEquals(poultry.id, repository.plans.value.single().recipeId)
    }

    @Test
    fun `ensure week repairs a partial plan and resets completion`() = runBlocking {
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
        assertEquals(false, repaired.completed)
    }

    @Test
    fun `ensure week replaces an inactive unfinished recipe`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val stale = DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            category = MealCategory.FISH.key,
            recipeId = "retired-fish",
            recipeTitle = "Παλιά πρόταση",
            filters = RecipeFilters(category = MealCategory.FISH.key),
            completed = false,
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
        assertEquals(false, repaired.completed)
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
    fun `reroll preserves the immutable event for the recipe already cooked that date`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val event = CookedMeal(
            id = "cooked_20260904_3000",
            date = date.toString(),
            recipeId = "old-fish",
            recipeTitle = "Παλιό ψάρι",
            completedAtEpochMillis = 3_000,
        )
        val old = DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            category = MealCategory.FISH.key,
            recipeId = event.recipeId,
            recipeTitle = event.recipeTitle,
            filters = RecipeFilters(category = MealCategory.FISH.key),
            completed = true,
            completionEventId = event.id,
            updatedAtEpochMillis = event.completedAtEpochMillis,
        )
        val replacement = Recipe(
            id = "new-fish",
            title = "Νέο ψάρι",
            category = MealCategory.FISH.key,
        )
        val repository = FakeRepository(
            initialPlans = listOf(old),
            initialRecipes = listOf(replacement),
            initialHistory = listOf(event),
        )

        val selected = MealPlanner(repository, RecipeSelector()).reroll(date)

        assertTrue(selected is MealPlanSelection.Selected)
        assertEquals(false, repository.plans.value.single().completed)
        assertEquals(listOf(event), repository.history.value)
        assertEquals(0, repository.deletedHistoryIds.size)
    }

    @Test
    fun `favorite replacement preserves prior cooked event and explicit undo removes only it`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val oldEvent = CookedMeal(
            id = "cooked_20260904_3000",
            date = date.toString(),
            recipeId = "old",
            recipeTitle = "Παλιό",
            completedAtEpochMillis = 3_000,
        )
        val anotherEvent = oldEvent.copy(
            id = "cooked_20260903_2000",
            date = "2026-09-03",
            recipeId = "another",
        )
        val old = DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            recipeId = oldEvent.recipeId,
            recipeTitle = oldEvent.recipeTitle,
            completed = true,
            completionEventId = oldEvent.id,
            updatedAtEpochMillis = oldEvent.completedAtEpochMillis,
        )
        val favorite = Recipe(id = "favorite", title = "Αγαπημένη", category = MealCategory.MEAT.key)
        val repository = FakeRepository(
            initialPlans = listOf(old),
            initialRecipes = listOf(favorite),
            initialFavorites = setOf(favorite.id),
            initialHistory = listOf(oldEvent, anotherEvent),
        )
        val planner = MealPlanner(repository, RecipeSelector())

        planner.replaceWithFavorite(date, favorite.id)
        assertEquals(listOf(oldEvent, anotherEvent), repository.history.value)

        planner.deleteCookedHistoryEntry(oldEvent.id)

        assertEquals(listOf(anotherEvent), repository.history.value)
        assertEquals(listOf(oldEvent.id), repository.deletedHistoryIds)
        assertEquals(favorite.id, repository.plans.value.single().recipeId)
        assertEquals(false, repository.plans.value.single().completed)
    }

    @Test
    fun stale_unfinished_category_is_rerolled_against_current_catalog_truth() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val stale = DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            category = MealCategory.LEGUMES.key,
            recipeId = "3485",
            recipeTitle = "Γλυκό",
            filters = RecipeFilters(category = MealCategory.LEGUMES.key),
            completed = false,
        )
        val repository = FakeRepository(
            initialPlans = listOf(stale),
            initialRecipes = listOf(
                Recipe(
                    id = "3485",
                    title = "Γλυκό",
                    category = MealCategory.DESSERT.key,
                    rating = 9.0,
                ),
                Recipe(
                    id = "current-legumes",
                    title = "Φακές",
                    category = MealCategory.LEGUMES.key,
                    rating = 9.0,
                ),
            ),
        )

        val repaired = MealPlanner(repository, RecipeSelector())
            .ensureWeek(date, kotlin.random.Random(9))
            .first { it.date == date.toString() }

        assertEquals("current-legumes", repaired.recipeId)
        assertEquals(MealCategory.LEGUMES.key, repaired.category)
        assertEquals(false, repaired.completed)
        assertEquals(repaired, repository.plans.value.first { it.date == date.toString() })
    }

    @Test
    fun unfinished_plan_is_repaired_for_rating_time_and_ease_drift() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val replacement = Recipe(
            id = "matching-fish",
            title = "Νέο ψάρι",
            category = MealCategory.FISH.key,
            rating = 9.5,
            prepMinutes = 20,
            stepCount = 7,
            preparationCount = 2,
        )
        val cases = listOf(
            RecipeFilters(category = MealCategory.FISH.key, minRating = 8.0) to
                Recipe(
                    id = "low-rating",
                    title = "Παλιό",
                    category = MealCategory.FISH.key,
                    rating = 7.0,
                    prepMinutes = 20,
                    stepCount = 7,
                    preparationCount = 2,
                ),
            RecipeFilters(category = MealCategory.FISH.key, maxPrepMinutes = 30) to
                Recipe(
                    id = "too-slow",
                    title = "Παλιό",
                    category = MealCategory.FISH.key,
                    rating = 9.0,
                    prepMinutes = 60,
                    stepCount = 7,
                    preparationCount = 2,
                ),
            RecipeFilters(
                category = MealCategory.FISH.key,
                easeLevel = EaseLevel.MODERATE.key,
            ) to Recipe(
                id = "wrong-ease",
                title = "Παλιό",
                category = MealCategory.FISH.key,
                rating = 9.0,
                prepMinutes = 20,
                stepCount = 4,
                preparationCount = 1,
            ),
        )

        cases.forEachIndexed { index, (filters, staleRecipe) ->
            val plan = DayMealPlan(
                id = date.toString(),
                date = date.toString(),
                category = MealCategory.FISH.key,
                recipeId = staleRecipe.id,
                recipeTitle = staleRecipe.title,
                filters = filters,
                completed = false,
            )
            val repository = FakeRepository(
                initialPlans = listOf(plan),
                initialRecipes = listOf(staleRecipe, replacement),
            )

            val repaired = MealPlanner(repository, RecipeSelector())
                .ensureWeek(date, kotlin.random.Random(index))
                .first { it.date == date.toString() }

            assertEquals("case $index", replacement.id, repaired.recipeId)
            assertEquals("case $index", false, repaired.completed)
        }
    }

    @Test
    fun completed_plan_survives_taxonomy_filter_and_availability_drift() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val completed = DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            category = MealCategory.LEGUMES.key,
            recipeId = "retired-dessert",
            recipeTitle = "Γλυκό που φτιάχτηκε",
            filters = RecipeFilters(
                category = MealCategory.LEGUMES.key,
                easeLevel = EaseLevel.EASY.key,
                minRating = 10.0,
                maxPrepMinutes = 5,
            ),
            completed = true,
            updatedAtEpochMillis = 3_000,
        )
        val repository = FakeRepository(
            initialPlans = listOf(completed),
            initialRecipes = listOf(
                Recipe(
                    id = "retired-dessert",
                    title = "Γλυκό που φτιάχτηκε",
                    category = MealCategory.DESSERT.key,
                    active = false,
                ),
            ),
        )

        val preserved = MealPlanner(repository, RecipeSelector())
            .ensureWeek(date)
            .first { it.date == date.toString() }

        assertEquals(completed, preserved)
        assertEquals(completed, repository.plans.value.first { it.date == date.toString() })
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

    @Test
    fun `editing custom category preserves id and original creation time`() = runBlocking {
        val existing = CustomRecipe(
            id = "custom_01234567-89ab-4def-8123-456789abcdef",
            title = "Η συνταγή μου",
            category = MealCategory.POULTRY.key,
            ingredientSections = listOf(
                RecipeIngredientSection(ingredients = listOf(RecipeIngredient(title = "Υλικό"))),
            ),
            methodSections = listOf(RecipeMethodSection(steps = listOf("Βήμα"))),
            createdAtEpochMillis = 1_000,
            updatedAtEpochMillis = 2_000,
        )
        val repository = FakeRepository(initialCustomRecipes = listOf(existing))

        val stored = MealPlanner(repository, RecipeSelector()).saveCustomRecipe(
            existing.copy(
                category = MealCategory.STREET_FOOD.key,
                createdAtEpochMillis = 0,
                updatedAtEpochMillis = 0,
            ),
        )

        assertEquals(existing.id, stored.id)
        assertEquals(existing.createdAtEpochMillis, stored.createdAtEpochMillis)
        assertEquals(MealCategory.STREET_FOOD.key, stored.category)
        assertTrue(stored.updatedAtEpochMillis >= existing.updatedAtEpochMillis)
        assertEquals(stored, repository.custom.value.single())
    }

    private class FakeRepository(
        initialPlans: List<DayMealPlan> = emptyList(),
        initialRecipes: List<Recipe> = DemoRecipeCatalog.recipes,
        initialFavorites: Set<String> = emptySet(),
        initialCustomRecipes: List<CustomRecipe> = emptyList(),
        initialHistory: List<CookedMeal> = emptyList(),
        initialPreferences: MealPreferenceSettings = MealPreferenceSettings(),
    ) : SpoonRepository {
        override val backendState = MutableStateFlow<BackendState>(BackendState.Local)
        override val recipes: Flow<List<Recipe>> = MutableStateFlow(initialRecipes)
        val plans = MutableStateFlow(initialPlans)
        override val mealPlans: Flow<List<DayMealPlan>> = plans
        private val favorites = MutableStateFlow(initialFavorites)
        override val favoriteRecipeIds: Flow<Set<String>> = favorites
        val custom = MutableStateFlow(initialCustomRecipes)
        override val customRecipes: Flow<List<CustomRecipe>> = custom
        val history = MutableStateFlow(initialHistory)
        override val cookedHistory: Flow<List<CookedMeal>> = history
        override val mealPreferenceSettings: Flow<MealPreferenceSettings> =
            MutableStateFlow(initialPreferences)
        var ensureReadyCount = 0
        var upsertCount = 0
        val deletedHistoryIds = mutableListOf<String>()

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

        override suspend fun deleteCookedHistoryEntry(historyId: String) {
            deletedHistoryIds += historyId
            history.value = history.value.filterNot { it.id == historyId }
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

        override suspend fun upsertCustomRecipe(recipe: CustomRecipe) {
            custom.value = custom.value.filterNot { it.id == recipe.id } + recipe
        }
    }
}
