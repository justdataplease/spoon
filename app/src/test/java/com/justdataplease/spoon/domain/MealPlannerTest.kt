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
import kotlinx.coroutines.flow.flow
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
    fun `ensure week persists unavailable days for an empty catalog`() = runBlocking {
        val repository = FakeRepository(initialRecipes = emptyList())
        val planner = MealPlanner(repository, RecipeSelector())

        val week = planner.ensureWeek(LocalDate.of(2026, 9, 3))

        assertEquals(7, week.size)
        assertTrue(week.all { it.recipeId.isBlank() })
        assertEquals(week, repository.plans.value)
        assertTrue(week.all { !it.completed && it.recipeTitle.isBlank() })
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
    fun `chicken-only preference keeps Monday unavailable instead of changing category`() = runBlocking {
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

        assertTrue(result is MealPlanSelection.NoMatch)
        assertEquals(MealCategory.LEGUMES.key, repository.plans.value.single().category)
        assertEquals("", repository.plans.value.single().recipeId)
    }

    @Test
    fun `repeated chicken rolls can reuse current other-day and other-week recipes`() = runBlocking {
        val date = LocalDate.of(2026, 8, 31)
        val chicken = Recipe(id = "chicken", title = "Chicken", category = "poultry")
        val existing = listOf(date.minusWeeks(1), date, date.plusDays(1), date.plusWeeks(1))
            .map { day ->
                DayMealPlan(
                    id = day.toString(), date = day.toString(),
                    category = "any", recipeId = chicken.id, recipeTitle = chicken.title,
                    filters = RecipeFilters(category = "any"),
                )
            }
        val repository = FakeRepository(
            initialRecipes = listOf(chicken),
            initialPlans = existing,
            initialHistory = listOf(CookedMeal(
                id = "cooked_chicken", date = date.minusWeeks(1).toString(),
                recipeId = chicken.id, recipeTitle = chicken.title, completedAtEpochMillis = 1L,
            )),
            initialPreferences = MealPreferenceSettings(
                excludedCategories = MealCategory.entries
                    .filterNot { it == MealCategory.ANY || it == MealCategory.POULTRY }
                    .map(MealCategory::key).toSet(),
            ),
        )
        val planner = MealPlanner(repository, RecipeSelector())
        repeat(100) { seed ->
            val result = planner.reroll(date, random = kotlin.random.Random(seed))
            assertTrue("Roll $seed must not exhaust the catalog", result is MealPlanSelection.Selected)
            assertEquals(chicken.id, (result as MealPlanSelection.Selected).recipe.id)
        }
        assertEquals(existing.filterNot { it.date == date.toString() },
            repository.plans.value.filterNot { it.date == date.toString() })
        assertEquals(1, repository.history.value.size)
        val explicit = planner.updateFilters(date, RecipeFilters(category = "poultry"))
        assertTrue(explicit is MealPlanSelection.Selected)
    }

    @Test
    fun `every category stays selectable across repeated global preference rolls`() = runBlocking {
        val date = LocalDate.of(2026, 8, 31)
        val categories = MealCategory.entries.filterNot { it == MealCategory.ANY }
        val recipes = categories.map { Recipe(id = it.key, title = it.key, category = it.key) }
        categories.forEach { category ->
            val repository = FakeRepository(
                initialRecipes = recipes,
                initialPreferences = MealPreferenceSettings(
                    excludedCategories = categories.filterNot { it == category }.map(MealCategory::key).toSet(),
                ),
            )
            val planner = MealPlanner(repository, RecipeSelector())
            repeat(25) { seed ->
                val result = planner.reroll(date, filters = RecipeFilters(category = category.key), random = kotlin.random.Random(seed))
                assertTrue("${category.key} roll $seed must succeed", result is MealPlanSelection.Selected)
                assertEquals(category.key, (result as MealPlanSelection.Selected).recipe.category)
            }
        }
    }

    @Test
    fun ensure_week_uses_one_preference_snapshot_and_replaces_unfinished_excluded_recipe() = runBlocking {
        val monday = LocalDate.of(2026, 8, 31)
        val legumes = DemoRecipeCatalog.recipes.first {
            it.category == MealCategory.LEGUMES.key
        }
        val poultry = DemoRecipeCatalog.recipes.first {
            it.category == MealCategory.POULTRY.key
        }
        val stale = DayMealPlan(
            id = monday.toString(),
            date = monday.toString(),
            category = MealCategory.POULTRY.key,
            recipeId = poultry.id,
            recipeTitle = poultry.title,
            filters = RecipeFilters(category = MealCategory.POULTRY.key),
        )
        val preferences = MealPreferenceSettings(
            excludedCategories = MealCategory.entries
                .filterNot { it == MealCategory.ANY || it == MealCategory.LEGUMES }
                .mapTo(mutableSetOf(), MealCategory::key),
        )
        var preferenceReads = 0
        val repository = FakeRepository(
            initialPlans = listOf(stale),
            initialRecipes = listOf(legumes, poultry),
            preferenceFlow = flow {
                preferenceReads++
                emit(preferences)
            },
        )

        val week = MealPlanner(repository, RecipeSelector()).ensureWeek(monday)

        assertEquals(1, preferenceReads)
        assertEquals("", week.first { it.date == monday.toString() }.recipeId)
        assertTrue(week.all { it.recipeId.isBlank() })
        assertEquals(MealCategory.POULTRY.key, week.first().filters.category)
    }

    @Test
    fun ensure_week_preserves_completed_recipe_excluded_by_preferences() = runBlocking {
        val tuesday = LocalDate.of(2026, 9, 1)
        val poultry = DemoRecipeCatalog.recipes.first {
            it.category == MealCategory.POULTRY.key
        }
        val completed = DayMealPlan(
            id = tuesday.toString(),
            date = tuesday.toString(),
            category = MealCategory.POULTRY.key,
            recipeId = poultry.id,
            recipeTitle = poultry.title,
            filters = RecipeFilters(category = MealCategory.POULTRY.key),
            completed = true,
        )
        val repository = FakeRepository(
            initialPlans = listOf(completed),
            initialRecipes = listOf(poultry),
            initialPreferences = MealPreferenceSettings(
                excludedCategories = setOf(MealCategory.POULTRY.key),
            ),
        )

        val preserved = MealPlanner(repository, RecipeSelector())
            .ensureWeek(tuesday)
            .first { it.date == tuesday.toString() }

        assertEquals(completed, preserved)
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
        assertEquals(repaired, repository.plans.value.single { it.date == date.toString() })
    }

    @Test
    fun `ensure week preserves unresolved custom plan while owner cache is warming`() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val customPlan = DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            category = MealCategory.FISH.key,
            recipeId = "custom_01234567-89ab-4def-8123-456789abcdef",
            recipeTitle = "My custom recipe",
            filters = RecipeFilters(category = MealCategory.FISH.key),
        )
        val replacement = Recipe(
            id = "bundled-fish",
            title = "Bundled fish",
            category = MealCategory.FISH.key,
        )
        val repository = FakeRepository(
            initialPlans = listOf(customPlan),
            initialRecipes = listOf(replacement),
        )

        val preserved = MealPlanner(repository, RecipeSelector())
            .ensureWeek(date)
            .first { it.date == date.toString() }

        assertEquals(customPlan, preserved)
        assertEquals(
            customPlan,
            repository.plans.value.first { it.date == date.toString() },
        )
    }

    @Test
    fun `ensure week clears stale unavailable recipe when no replacement matches`() = runBlocking {
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

        assertEquals("", preserved.recipeId)
        assertEquals("", preserved.recipeTitle)
        assertEquals(preserved, repository.plans.value.single { it.date == date.toString() })
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

    @Test
    fun `favorites with meat and legumes leave all other weekday categories unavailable`() = runBlocking {
        val monday = LocalDate.of(2026, 9, 7)
        val favorites = DemoRecipeCatalog.recipes.filter { it.category in setOf("meat", "legumes") }
            .mapTo(mutableSetOf(), Recipe::id)
        val repository = FakeRepository(
            initialFavorites = favorites,
            initialPreferences = MealPreferenceSettings(favoritesOnly = true),
        )
        val planner = MealPlanner(repository, RecipeSelector())
        val week = planner.ensureWeek(monday)
        assertEquals(2, week.count { it.recipeId.isNotBlank() })
        week.forEach { plan ->
            assertEquals(WeeklyPlanDefaults.categoryFor(LocalDate.parse(plan.date).dayOfWeek).key, plan.category)
            assertTrue(plan.recipeId.isBlank() || plan.recipeId in favorites)
        }
        val rerolled = planner.rerollWeek(monday)
        assertEquals(5, rerolled.count { it is MealPlanSelection.NoMatch })
        assertEquals(7, repository.plans.value.size)
    }

    @Test
    fun `one favorite can fill every day with matching weekday defaults`() = runBlocking {
        val recipe = Recipe(id = "only-meat", title = "Κρέας", category = "meat")
        val preferences = MealPreferenceSettings(
            favoritesOnly = true,
            weekdayCategories = java.time.DayOfWeek.entries.associate { it.name to "meat" },
        )
        val repository = FakeRepository(initialRecipes = listOf(recipe), initialFavorites = setOf(recipe.id), initialPreferences = preferences)
        val planner = MealPlanner(repository, RecipeSelector())
        repeat(3) { weekOffset ->
            val week = planner.ensureWeek(LocalDate.of(2026, 9, 7).plusWeeks(weekOffset.toLong()))
            assertTrue(week.all { it.recipeId == recipe.id && it.category == "meat" })
        }
        assertEquals(21, repository.plans.value.size)
    }

    @Test
    fun `favorites mode clears previous catalog proposals and never falls back when favorites are empty`() = runBlocking {
        val repository = FakeRepository()
        val planner = MealPlanner(repository, RecipeSelector())
        val date = LocalDate.of(2026, 9, 7)
        planner.ensureWeek(date)
        assertTrue(repository.plans.value.all { it.recipeId.isNotBlank() })
        (repository.mealPreferenceSettings as MutableStateFlow).value = MealPreferenceSettings(favoritesOnly = true)
        val week = planner.ensureWeek(date)
        assertTrue(week.all { it.recipeId.isBlank() && !it.completed })
        assertEquals(week, repository.plans.value)
        val writes = repository.upsertCount
        assertEquals(week, planner.ensureWeek(date))
        assertEquals(writes, repository.upsertCount)
        val favorite = DemoRecipeCatalog.recipes.first { it.category == "legumes" }
        planner.toggleFavorite(favorite.id)
        assertEquals(favorite.id, planner.ensureWeek(date).first().recipeId)
    }

    @Test
    fun `favorites obey dietary and explicit day filters and ignore missing inactive or foreign recipes`() = runBlocking {
        val recipe = Recipe(id = "meat", title = "Κρέας", category = "meat", prepMinutes = 60)
        val repository = FakeRepository(
            initialRecipes = listOf(recipe, recipe.copy(id = "inactive", active = false), recipe.copy(id = "english", language = "en")),
            initialFavorites = setOf("meat", "inactive", "english", "missing"),
            initialPreferences = MealPreferenceSettings(favoritesOnly = true),
        )
        val planner = MealPlanner(repository, RecipeSelector())
        val date = LocalDate.of(2026, 9, 10)
        assertTrue(planner.reroll(date, RecipeFilters(category = "meat", maxPrepMinutes = 15)) is MealPlanSelection.NoMatch)
        assertEquals(15, repository.plans.value.single().filters.maxPrepMinutes)
        (repository.mealPreferenceSettings as MutableStateFlow).value = MealPreferenceSettings(favoritesOnly = true, excludedCategories = setOf("meat"))
        assertTrue(planner.reroll(date, RecipeFilters(category = "meat")) is MealPlanSelection.NoMatch)
        assertTrue(repository.plans.value.single().recipeId.isBlank())
    }

    @Test
    fun `changed weekday defaults update unfinished days while preserving completed meals and other filters`() = runBlocking {
        val date = LocalDate.of(2026, 9, 7)
        val repository = FakeRepository()
        val planner = MealPlanner(repository, RecipeSelector())
        planner.ensureWeek(date)
        planner.setCompleted(date.plusDays(1), true)
        val completed = repository.plans.value.first { it.date == date.plusDays(1).toString() }
        (repository.mealPreferenceSettings as MutableStateFlow).value = MealPreferenceSettings(
            weekdayCategories = mapOf("MONDAY" to "meat", "TUESDAY" to "meat"),
        )
        val week = planner.ensureWeek(date, resetWeekdays = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY))
        assertEquals("meat", week.first().category)
        assertEquals(completed, week[1])
        assertTrue(week.first().recipeId.isNotBlank())
    }

    @Test
    fun `regenerating a week preserves locked and completed days exactly even with no matching recipes`() = runBlocking {
        val repository = FakeRepository()
        val planner = MealPlanner(repository, RecipeSelector())
        val monday = LocalDate.of(2026, 9, 7)
        planner.ensureWeek(monday)
        planner.setLocked(monday, true)
        planner.setCompleted(monday.plusDays(1), true)
        val protected = repository.plans.value.filter { it.locked || it.completed }
        assertEquals(2, protected.size)
        (repository.recipes as MutableStateFlow).value = emptyList()
        val result = planner.rerollWeek(monday)
        assertEquals(5, result.size)
        assertTrue(result.all { it is MealPlanSelection.NoMatch })
        assertEquals(protected, repository.plans.value.filter { it.locked || it.completed })
        planner.setLocked(monday, false)
        assertEquals(6, planner.rerollWeek(monday).size)
        assertTrue(repository.plans.value.single { it.date == monday.toString() }.recipeId.isBlank())
        assertEquals(protected.single { it.completed }, repository.plans.value.single { it.completed })
    }

    @Test
    fun `preference reconciliation keeps locked meal and reset does not unlock it`() = runBlocking {
        val monday = LocalDate.of(2026, 9, 7)
        val repository = FakeRepository()
        val planner = MealPlanner(repository, RecipeSelector())
        planner.ensureWeek(monday)
        planner.setLocked(monday, true)
        val locked = repository.plans.value.single { it.locked }
        (repository.mealPreferenceSettings as MutableStateFlow).value = MealPreferenceSettings(
            favoritesOnly = true, weekdayCategories = mapOf("MONDAY" to "meat"),
        )
        val week = planner.ensureWeek(monday, resetWeekdays = setOf(java.time.DayOfWeek.MONDAY))
        assertEquals(locked, week.first())
        assertTrue(week.drop(1).all { it.recipeId.isBlank() })
    }

    @Test
    fun `optional menu keeps the saved main and proposes separate side and dessert favorites`() = runBlocking {
        val monday = LocalDate.of(2026, 9, 7)
        val main = Recipe(id = "main", title = "Κρέας", category = "meat", rating = 9.0, prepMinutes = 20)
        val side = Recipe(id = "side", title = "Σαλάτα", category = "vegetables", mealTypeLabels = listOf("Σαλάτα"), rating = 9.0, prepMinutes = 15)
        val dessert = Recipe(id = "dessert", title = "Γλυκό", category = "dessert", rating = 9.0, prepMinutes = 25)
        val saved = DayMealPlan(date = monday.toString(), recipeId = main.id, recipeTitle = main.title,
            category = "meat", filters = RecipeFilters(category = "meat", minRating = 8.0, maxPrepMinutes = 30), locked = true)
        val repository = FakeRepository(
            initialRecipes = listOf(main, side, dessert), initialPlans = listOf(saved),
            initialFavorites = setOf(main.id, side.id, dessert.id), initialPreferences = MealPreferenceSettings(favoritesOnly = true),
        )
        val menu = MealPlanner(repository, RecipeSelector()).suggestMenu(monday)
        assertEquals(MealMenuProposal(main, side, dessert), menu)
        assertEquals(listOf(saved), repository.plans.value)
        assertEquals(0, repository.upsertCount)
        (repository.mealPreferenceSettings as MutableStateFlow).value = MealPreferenceSettings(favoritesOnly = true, excludedCategories = setOf("dessert"))
        assertEquals(null, MealPlanner(repository, RecipeSelector()).suggestMenu(monday).dessert)
    }

    @Test
    fun `optional courses stay unavailable without matching favorites even when catalog has them`() = runBlocking {
        val monday = LocalDate.of(2026, 9, 7)
        val main = Recipe(id = "main", title = "Όσπρια", category = "legumes")
        val repository = FakeRepository(
            initialRecipes = listOf(main,
                Recipe(id = "side", title = "Σαλάτα", category = "vegetables", mealTypeLabels = listOf("Σαλάτα")),
                Recipe(id = "dessert", title = "Γλυκό", category = "dessert")),
            initialFavorites = setOf(main.id), initialPreferences = MealPreferenceSettings(favoritesOnly = true),
        )
        val planner = MealPlanner(repository, RecipeSelector())
        planner.ensureWeek(monday)
        val menu = planner.suggestMenu(monday)
        assertEquals(main, menu.main)
        assertEquals(null, menu.side)
        assertEquals(null, menu.dessert)
    }

    @Test
    fun `catalog menu distinguishes a main from optional courses and retains day time limits`() = runBlocking {
        val monday = LocalDate.of(2026, 9, 7)
        val main = Recipe(id = "main", title = "Σαλάτα ημέρας", category = "vegetables", mealTypeLabels = listOf("Σαλάτα"), prepMinutes = 15)
        val side = main.copy(id = "side", title = "Συνοδευτικό")
        val repository = FakeRepository(
            initialRecipes = listOf(main, side, Recipe(id = "slow-dessert", title = "Γλυκό", category = "dessert", prepMinutes = 60)),
            initialPlans = listOf(DayMealPlan(date = monday.toString(), recipeId = main.id, recipeTitle = main.title,
                category = "vegetables", filters = RecipeFilters(category = "vegetables", maxPrepMinutes = 20))),
        )
        repeat(10) { seed ->
            val menu = MealPlanner(repository, RecipeSelector()).suggestMenu(monday, kotlin.random.Random(seed))
            assertEquals(main, menu.main)
            assertEquals(side, menu.side)
            assertEquals(null, menu.dessert)
        }
    }

    private class FakeRepository(
        initialPlans: List<DayMealPlan> = emptyList(),
        initialRecipes: List<Recipe> = DemoRecipeCatalog.recipes,
        initialFavorites: Set<String> = emptySet(),
        initialCustomRecipes: List<CustomRecipe> = emptyList(),
        initialHistory: List<CookedMeal> = emptyList(),
        initialPreferences: MealPreferenceSettings = MealPreferenceSettings(),
        preferenceFlow: Flow<MealPreferenceSettings>? = null,
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
            preferenceFlow ?: MutableStateFlow(initialPreferences)
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
