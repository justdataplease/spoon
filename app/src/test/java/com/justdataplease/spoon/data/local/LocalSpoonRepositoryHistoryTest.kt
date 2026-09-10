package com.justdataplease.spoon.data.local

import android.content.SharedPreferences
import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.MealCourse
import com.justdataplease.spoon.data.model.MealCoursePlan
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.isIntentionallyBlank
import com.justdataplease.spoon.domain.MealPlanner
import com.justdataplease.spoon.domain.RecipeSelector
import java.time.LocalDate
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSpoonRepositoryHistoryTest {
    @Test
    fun blank_day_survives_local_restart_and_keeps_existing_cooking_history() = runBlocking {
        val memory = MemoryPreferences()
        val repository = LocalSpoonRepository(memory.preferences, Json)
        val date = LocalDate.of(2026, 9, 7)
        repository.upsertMealPlan(DayMealPlan(date = date.toString(), recipeId = "cooked-main",
            recipeTitle = "Μαγειρεμένο", category = "legumes", filters = RecipeFilters(category = "legumes")))
        repository.setMealCompleted(date.toString(), true)
        val history = repository.cookedHistory.first()
        assertEquals(1, history.size)

        MealPlanner(repository, RecipeSelector()).setDayBlank(date)
        val blank = repository.mealPlans.first().single()
        assertTrue(blank.isIntentionallyBlank)
        assertEquals(history, repository.cookedHistory.first())

        val restarted = LocalSpoonRepository(memory.preferences, Json)
        assertEquals(blank, restarted.mealPlans.first().single())
        assertEquals(history, restarted.cookedHistory.first())
        assertEquals(blank, MealPlanner(restarted, RecipeSelector()).ensureWeek(date).first())
    }

    @Test
    fun restart_hydrates_bounded_plan_favorite_and_history_recipe_details() = runBlocking {
        val memory = MemoryPreferences()
        val original = LocalSpoonRepository(memory.preferences, Json)
        val date = "2026-09-04"
        original.upsertMealPlan(
            DayMealPlan(
                id = date,
                date = date,
                recipeId = "history-recipe",
                recipeTitle = "Ιστορικό",
                updatedAtEpochMillis = 1_000,
            ),
        )
        original.setMealCompleted(date, true)
        original.upsertMealPlan(
            DayMealPlan(
                id = date,
                date = date,
                recipeId = "plan-recipe",
                recipeTitle = "Πλάνο",
                updatedAtEpochMillis = 2_000,
            ),
        )
        original.toggleFavorite("favorite-recipe")

        val catalog = ReferencedOnlyCatalog(
            listOf(
                Recipe(id = "plan-recipe", title = "Πλάνο", imageUrl = "https://img/plan.jpg"),
                Recipe(id = "favorite-recipe", title = "Αγαπημένο", imageUrl = "https://img/favorite.jpg"),
                Recipe(id = "history-recipe", title = "Ιστορικό", imageUrl = "https://img/history.jpg"),
            ),
        )
        val restarted = LocalSpoonRepository(memory.preferences, Json, catalog)

        val hydrated = restarted.recipes.first()

        assertEquals(
            setOf("plan-recipe", "favorite-recipe", "history-recipe"),
            catalog.requestedIds,
        )
        assertEquals(catalog.requestedIds, hydrated.mapTo(mutableSetOf(), Recipe::id))
        assertTrue(hydrated.all { it.imageUrl.isNotBlank() })
    }

    @Test
    fun referenced_recipe_ids_are_safe_deduplicated_and_bounded() {
        val plans = (0..MAX_LOCAL_REFERENCED_RECIPES).map { index ->
            DayMealPlan(recipeId = "plan-$index")
        }

        val ids = boundedReferencedRecipeIds(
            plans = plans + DayMealPlan(recipeId = "../unsafe"),
            favoriteIds = setOf("favorite-over-limit", "plan-0"),
            history = listOf(CookedMeal(recipeId = "history-over-limit")),
        )

        assertEquals(MAX_LOCAL_REFERENCED_RECIPES, ids.size)
        assertEquals("plan-0", ids.first())
        assertFalse("../unsafe" in ids)
        assertFalse("favorite-over-limit" in ids)
        assertFalse("history-over-limit" in ids)
    }

    @Test
    fun complete_replace_complete_and_explicit_undo_keep_independent_events() = runBlocking {
        val repository = LocalSpoonRepository(MemoryPreferences().preferences, Json)
        val date = "2026-09-03"
        repository.upsertMealPlan(
            DayMealPlan(
                id = date,
                date = date,
                category = MealCategory.LEGUMES.key,
                recipeId = "lentils",
                recipeTitle = "Φακές",
                filters = RecipeFilters(category = MealCategory.LEGUMES.key),
                updatedAtEpochMillis = 1_000,
            ),
        )

        repository.setMealCompleted(date, true)
        val firstPlan = repository.mealPlans.first().single()
        val firstEvent = repository.cookedHistory.first().single()
        assertTrue(firstPlan.completed)
        assertEquals(firstPlan.completionEventId, firstEvent.id)
        assertEquals("lentils", firstEvent.recipeId)

        repository.upsertMealPlan(
            DayMealPlan(
                id = date,
                date = date,
                category = MealCategory.FISH.key,
                recipeId = "fish",
                recipeTitle = "Ψάρι",
                filters = RecipeFilters(category = MealCategory.FISH.key),
                completed = false,
                updatedAtEpochMillis = firstPlan.updatedAtEpochMillis + 1,
            ),
        )
        assertEquals(listOf(firstEvent), repository.cookedHistory.first())
        assertFalse(repository.mealPlans.first().single().completed)

        repository.setMealCompleted(date, true)
        val secondPlan = repository.mealPlans.first().single()
        val twoEvents = repository.cookedHistory.first()
        val secondEvent = twoEvents.first { it.recipeId == "fish" }
        assertEquals(2, twoEvents.size)
        assertNotEquals(firstEvent.id, secondEvent.id)
        assertEquals(secondPlan.completionEventId, secondEvent.id)

        repository.deleteCookedHistoryEntry(firstEvent.id)
        assertEquals(listOf(secondEvent), repository.cookedHistory.first())
        assertTrue(repository.mealPlans.first().single().completed)

        repository.deleteCookedHistoryEntry(secondEvent.id)
        assertTrue(repository.cookedHistory.first().isEmpty())
        assertFalse(repository.mealPlans.first().single().completed)
        assertEquals("", repository.mealPlans.first().single().completionEventId)
    }

    @Test
    fun menu_courses_survive_restart_and_have_independent_history() = runBlocking {
        val memory = MemoryPreferences()
        val repository = LocalSpoonRepository(memory.preferences, Json)
        val date = "2026-09-07"
        repository.upsertMealPlan(DayMealPlan(date = date, recipeId = "main", recipeTitle = "Main",
            side = MealCoursePlan(recipeId = "side", recipeTitle = "Side", locked = true),
            dessert = MealCoursePlan(recipeId = "dessert", recipeTitle = "Dessert")))
        repository.setMealCompleted(date, true)
        val mainEvent = repository.cookedHistory.first().single()
        repository.setCourseCompleted(date, MealCourse.SIDE, true)
        repository.setCourseCompleted(date, MealCourse.DESSERT, true)
        assertEquals(3, repository.cookedHistory.first().size)
        assertEquals(mainEvent, repository.cookedHistory.first().first { it.recipeId == "main" })
        val restarted = LocalSpoonRepository(memory.preferences, Json)
        val plan = restarted.mealPlans.first().single()
        assertTrue(plan.completed && plan.side!!.completed && plan.dessert!!.completed)
        assertTrue(plan.side!!.locked)
        assertEquals(mainEvent.completedAtEpochMillis, plan.completedAtEpochMillis)
        assertEquals(repository.cookedHistory.first(), restarted.cookedHistory.first())
        val sideEvent = restarted.cookedHistory.first().first { it.recipeId == "side" }
        restarted.deleteCookedHistoryEntry(sideEvent.id)
        val afterDelete = restarted.mealPlans.first().single()
        assertTrue(afterDelete.completed && afterDelete.dessert!!.completed)
        assertFalse(afterDelete.side!!.completed)
        assertTrue(afterDelete.side!!.locked)
        assertEquals(2, restarted.cookedHistory.first().size)
        restarted.setCourseCompleted(date, MealCourse.DESSERT, false)
        assertEquals(listOf(mainEvent), restarted.cookedHistory.first())
        assertTrue(restarted.mealPlans.first().single().completed)
    }

    private class MemoryPreferences(
        private val values: MutableMap<String, Any?> = mutableMapOf(),
    ) {
        val preferences: SharedPreferences = proxy(SharedPreferences::class.java) { method, args ->
            val key = args.firstOrNull() as? String
            when (method) {
                "getAll" -> values.toMap()
                "getString" -> values[key] as? String ?: args.getOrNull(1)
                "getStringSet" -> values[key] as? Set<*> ?: args.getOrNull(1)
                "getInt" -> values[key] as? Int ?: args[1]
                "getLong" -> values[key] as? Long ?: args[1]
                "getFloat" -> values[key] as? Float ?: args[1]
                "getBoolean" -> values[key] as? Boolean ?: args[1]
                "contains" -> values.containsKey(key)
                "edit" -> editor()
                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener",
                -> null
                else -> objectMethod(method, args)
            }
        }

        private fun editor(): SharedPreferences.Editor =
            proxy(SharedPreferences.Editor::class.java) { method, args ->
                val key = args.firstOrNull() as? String
                when (method) {
                    "putString", "putStringSet", "putInt", "putLong", "putFloat", "putBoolean" -> {
                        values[key.orEmpty()] = args.getOrNull(1)
                        editor()
                    }
                    "remove" -> {
                        values.remove(key)
                        editor()
                    }
                    "clear" -> {
                        values.clear()
                        editor()
                    }
                    "commit" -> true
                    "apply" -> null
                    else -> objectMethod(method, args)
                }
            }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(
            type: Class<T>,
            handler: (String, Array<out Any?>) -> Any?,
        ): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
            handler(method.name, arguments.orEmpty())
        } as T

        private fun objectMethod(method: String, args: Array<out Any?>): Any? = when (method) {
            "toString" -> "MemoryPreferences"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> args.firstOrNull() === this
            else -> error("Unexpected SharedPreferences call: $method")
        }
    }

    private class ReferencedOnlyCatalog(recipes: List<Recipe>) :
        RecipeCatalog by InMemoryRecipeCatalog(emptyList()) {
        private val recipesById = recipes.associateBy(Recipe::id)
        override val cachedRecipes: StateFlow<List<Recipe>> = MutableStateFlow(emptyList())
        var requestedIds: Set<String> = emptySet()
            private set

        override suspend fun getRecipesByIds(recipeIds: Set<String>): List<Recipe> {
            requestedIds = recipeIds
            return recipeIds.mapNotNull(recipesById::get)
        }
    }
}
