package com.justdataplease.spoon.data.local

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import com.justdataplease.spoon.data.expandedIngredientAliasTokens
import androidx.test.platform.app.InstrumentationRegistry
import com.justdataplease.spoon.data.model.MealCourse
import com.justdataplease.spoon.data.model.*
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.domain.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Executes production SQL and production Kotlin filters over the actual APK catalog. */
class CatalogContractDeviceTest {
    private val selector = RecipeSelector()

    private data class PlannerCase(val name: String, val filters: RecipeFilters, val preferences: MealPreferenceSettings = MealPreferenceSettings())
    private data class ExploreCase(val name: String, val criteria: ExploreCriteria, val preferences: MealPreferenceSettings = MealPreferenceSettings())

    @Test fun plannerSqlAndKotlinAgreeForEveryRecipe() = runBlocking {
        catalog.ensureReady()
        val cases = buildList {
            for (category in MealCategory.entries) {
                add(PlannerCase(category.key, RecipeFilters(category = category.key)))
                add(PlannerCase("${category.key}/combined", RecipeFilters(category = category.key, easeLevel = "easy", minRating = 7.0, maxPrepMinutes = 30)))
                if (category != MealCategory.ANY) add(PlannerCase("only/${category.key}", RecipeFilters(category = "any"), only(category)))
            }
            for (ease in EaseLevel.entries) add(PlannerCase(ease.key, RecipeFilters(category = "any", easeLevel = ease.key)))
            for (rating in listOf(0.0, 5.0, 8.0, 9.9)) add(PlannerCase("rating/$rating", RecipeFilters(category = "any", minRating = rating)))
            for (time in listOf(1, 15, 30, 90)) add(PlannerCase("time/$time", RecipeFilters(category = "any", maxPrepMinutes = time)))
            add(PlannerCase("excluded sources", RecipeFilters(category = "any"), MealPreferenceSettings(excludedSourceKeys = setOf("akis", "cookpad", "tsoulis"))))
            add(PlannerCase("all sources excluded", RecipeFilters(category = "any"), MealPreferenceSettings(excludedSourceKeys = setOf("akis", "argiro", "gastronomos", "tsoulis", "cookpad", "lucacos", "funkycook"))))
            add(PlannerCase("vegan", RecipeFilters(category = "any"), MealPreferenceSettings(veganOnly = true)))
            add(PlannerCase("ingredient exclusions", RecipeFilters(category = "any"), MealPreferenceSettings(excludedIngredientTerms = setOf("Αυγά", "Γάλα καρύδας"))))
            add(PlannerCase("compound exclusions", RecipeFilters(category = "any"), MealPreferenceSettings(excludedCategories = setOf("pasta_rice", "street_food"))))
        }
        val expected = cases.map { linkedSetOf<String>() }
        val policies = cases.map { it.preferences }.distinct()
        val policyIndices = cases.map { policies.indexOf(it.preferences) }
        forEachRecipe { recipe ->
            val eligibility = policies.map { recipe.matchesMealPreferences(it) }
            cases.forEachIndexed { index, case ->
                if (eligibility[policyIndices[index]] && selector.matches(recipe, case.filters)) expected[index].add(recipe.id)
            }
        }
        cases.forEachIndexed { index, case ->
            val actual = sqlIds(CatalogSqlBuilder.forPlanner(case.filters, null, case.preferences))
            assertIds(case.name, expected[index], actual)
            assertEquals(case.name, expected[index].size, catalog.countPlannerMatches(case.filters, null, case.preferences))
            repeat(8) { seed ->
                val picked = catalog.selectRandomRecipe(case.filters, null, seed.toLong(), case.preferences)
                if (expected[index].isEmpty()) assertNull(case.name, picked)
                else assertTrue("${case.name}: rejected random pick ${picked?.id}", picked != null && picked.id in expected[index])
            }
        }
    }

    @Test fun exploreSqlAndKotlinAgreeForEveryRecipe() = runBlocking {
        catalog.ensureReady()
        val options = catalog.getFacetOptions()
        val cases = buildList {
            MealCategory.entries.forEach { add(ExploreCase(it.key, ExploreCriteria(category = it.key))) }
            add(ExploreCase("search Greek", ExploreCriteria(query = "κοτόπουλο")))
            add(ExploreCase("search punctuation", ExploreCriteria(query = "κοτόπουλο-λεμόνι")))
            add(ExploreCase("combined", ExploreCriteria(category = "poultry", minRating = 7.0, maxPrepMinutes = 30, sourceKeys = setOf("akis", "argiro"))))
            add(ExploreCase("quick", ExploreCriteria(quickOnly = true)))
            add(ExploreCase("christmas aliases", ExploreCriteria(occasionLabels = setOf("Christmas"), quickOnly = true)))
            add(ExploreCase("cuisine aliases", ExploreCriteria(cuisineLabels = setOf("Greek", "Ιταλία"))))
            add(ExploreCase("source preference intersection", ExploreCriteria(sourceKeys = setOf("cookpad", "tsoulis")), MealPreferenceSettings(excludedSourceKeys = setOf("cookpad"))))
            add(ExploreCase("all sources excluded", ExploreCriteria(), MealPreferenceSettings(excludedSourceKeys = setOf("akis", "argiro", "gastronomos", "tsoulis", "cookpad", "lucacos", "funkycook"))))
            add(ExploreCase("vegan", ExploreCriteria(), MealPreferenceSettings(veganOnly = true)))
            add(ExploreCase("global exclusions", ExploreCriteria(), MealPreferenceSettings(excludedCategories = setOf("pasta_rice", "street_food"), excludedIngredientTerms = setOf("Αυγά"))))
            add(ExploreCase("diet", ExploreCriteria(dietLabels = options.diets.take(2).toSet())))
            add(ExploreCase("meal", ExploreCriteria(mealTypeLabels = options.mealTypes.take(2).toSet())))
            add(ExploreCase("occasion", ExploreCriteria(occasionLabels = options.occasions.take(2).toSet())))
            add(ExploreCase("method", ExploreCriteria(methodLabels = options.methods.take(2).toSet())))
            add(ExploreCase("cuisine", ExploreCriteria(cuisineLabels = options.cuisines.take(2).toSet())))
            add(ExploreCase("ingredient aliases", ExploreCriteria(ingredientLabels = setOf("Αυγά", "Ρεβίθια"))))
            add(ExploreCase("facets combined", ExploreCriteria(ingredientLabels = setOf("Κοτόπουλο"), cuisineLabels = options.cuisines.take(2).toSet())))
        }
        val expected = cases.map { linkedSetOf<String>() }
        val ranking = mutableListOf<Recipe>()
        forEachRecipe { recipe ->
            // Keep only ranking fields, rather than materializing all full recipe payloads.
            ranking.add(Recipe(id = recipe.id, title = recipe.title, rating = recipe.rating))
            cases.forEachIndexed { index, case ->
                if (ExploreRecipeFilter.filter(listOf(recipe), case.criteria, case.preferences).isNotEmpty()) expected[index].add(recipe.id)
            }
        }
        val orderedIds = ranking.sortedWith(
            compareByDescending<Recipe> { it.rating }
                .thenBy { it.title.normalizedCatalogToken() }.thenBy { it.id },
        ).map { it.id }
        cases.forEachIndexed { index, case ->
            assertIds(case.name, expected[index], sqlIds(CatalogSqlBuilder.forExplore(case.criteria, case.preferences)))
            val first = catalog.queryRecipes(case.criteria, 24, 0, case.preferences)
            assertEquals(case.name, expected[index].size, first.totalCount)
            assertTrue(case.name, first.recipes.all { it.id in expected[index] })
            val second = catalog.queryRecipes(case.criteria, 24, 24, case.preferences)
            assertEquals(case.name, orderedIds.filter { it in expected[index] }.take(48),
                (first.recipes + second.recipes).map { it.id })
            assertTrue(case.name, (first.recipes.map { it.id }.toSet() intersect second.recipes.map { it.id }.toSet()).isEmpty())
        }
    }

    @Test fun everyPublishedFacetMatchesItsRecipePayloads() = runBlocking {
        catalog.ensureReady()
        val options = catalog.getFacetOptions()
        val indexes = listOf("diet", "meal", "occasion", "method", "cuisine", "ingredient")
            .associateWith { mutableMapOf<String, MutableSet<String>>() }
        forEachRecipe { recipe ->
            listOf(
                "diet" to recipe.dietLabels, "meal" to recipe.mealTypeLabels,
                "occasion" to recipe.occasionLabels, "method" to recipe.methodLabels,
                "cuisine" to recipe.cuisineLabels, "ingredient" to recipe.ingredientLabels,
            ).forEach { (group, labels) ->
                labels.forEach { label ->
                    val tokens = if (group == "ingredient") expandedIngredientAliasTokens(label)
                        else setOf(label.normalizedCatalogToken())
                    tokens.filter(String::isNotBlank).forEach { token ->
                        indexes.getValue(group).getOrPut(token) { mutableSetOf() }.add(recipe.id)
                    }
                }
            }
        }
        listOf(
            Triple("diet", options.diets) { label: String -> ExploreCriteria(dietLabels = setOf(label)) },
            Triple("meal", options.mealTypes) { label: String -> ExploreCriteria(mealTypeLabels = setOf(label)) },
            Triple("occasion", options.occasions) { label: String -> ExploreCriteria(occasionLabels = setOf(label)) },
            Triple("method", options.methods) { label: String -> ExploreCriteria(methodLabels = setOf(label)) },
            Triple("cuisine", options.cuisines) { label: String -> ExploreCriteria(cuisineLabels = setOf(label)) },
            Triple("ingredient", options.ingredients) { label: String -> ExploreCriteria(ingredientLabels = setOf(label)) },
        ).forEach { (group, labels, criteria) ->
            labels.forEach { label ->
                val tokens = if (group == "ingredient") expandedIngredientAliasTokens(label)
                    else setOf(label.normalizedCatalogToken())
                val expected = tokens.flatMap { indexes.getValue(group)[it].orEmpty() }.toSet()
                assertTrue("$group/$label has no source recipes", expected.isNotEmpty())
                assertIds(
                    "$group/$label",
                    expected,
                    sqlIds(
                        CatalogSqlBuilder.forExplore(
                            criteria(label),
                            MealPreferenceSettings(excludedCategories = emptySet()),
                        ),
                    ),
                )
            }
        }
    }

    @Test fun repeatedPlanningSurvivesHistoryCacheEvictionAndReopen() = runBlocking {
        val preferences = context.getSharedPreferences("personal-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val repository = LocalSpoonRepository(preferences, json, catalog)
        val planner = MealPlanner(repository, selector)
        val day = LocalDate.of(2026, 9, 7)
        for (category in MealCategory.entries.filterNot { it == MealCategory.ANY }) {
            repository.updateMealPreferenceSettings(only(category).copy(
                weekdayCategories = java.time.DayOfWeek.entries.associate { it.name to category.key },
            ))
            for (week in listOf(day.minusWeeks(1), day, day.plusWeeks(1))) {
                val plan = planner.ensureWeek(week, resetWeekdays = java.time.DayOfWeek.entries.toSet())
                assertTrue(category.key, plan.all { it.recipeId.isNotBlank() })
            }
            repeat(20) { seed ->
                val result = planner.reroll(day, filters = RecipeFilters(category = category.key), random = Random(seed))
                assertTrue("${category.key}/$seed", result is MealPlanSelection.Selected)
                assertEquals(category.key, (result as MealPlanSelection.Selected).recipe.category)
            }
            planner.setCompleted(day, true)
        }
        val before = repository.mealPlans.first()
        val history = repository.cookedHistory.first()
        assertEquals(10, history.size)
        repeat(12) { page -> catalog.queryRecipes(ExploreCriteria(), 100, page * 100, MealPreferenceSettings()) }
        assertTrue(catalog.cachedRecipes.value.size <= 256)
        val reopened = LocalSpoonRepository(preferences, json, BundledRecipeCatalog(context, json))
        assertEquals(before, reopened.mealPlans.first())
        assertEquals(history, reopened.cookedHistory.first())
        val original = reopened.mealPlans.first()
        val noMatch = MealPlanner(reopened, selector).updateFilters(day, RecipeFilters(category = "poultry", minRating = 9.99, maxPrepMinutes = 1))
        assertTrue(noMatch is MealPlanSelection.NoMatch)
        val after = reopened.mealPlans.first()
        assertEquals(original.filterNot { it.date == day.toString() }, after.filterNot { it.date == day.toString() })
        assertTrue(after.single { it.date == day.toString() }.recipeId.isBlank())
        assertEquals(history, reopened.cookedHistory.first())
    }

    @Test fun favoritesOnlyWeekPersistsUnavailableDaysAndRecoversAfterAddingFavorite() = runBlocking {
        catalog.ensureReady()
        val preferences = context.getSharedPreferences("favorites-planner-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val repository = LocalSpoonRepository(preferences, json, catalog)
        val planner = MealPlanner(repository, selector)
        val monday = LocalDate.of(2026, 9, 7)
        val meat = catalog.queryRecipes(ExploreCriteria(category = "meat"), 1, 0, MealPreferenceSettings()).recipes.single()
        val legumes = catalog.queryRecipes(ExploreCriteria(category = "legumes"), 1, 0, MealPreferenceSettings()).recipes.single()
        planner.toggleFavorite(meat.id)
        planner.toggleFavorite(legumes.id)
        repository.updateMealPreferenceSettings(MealPreferenceSettings(favoritesOnly = true))
        val week = planner.ensureWeek(monday)
        assertEquals(legumes.id, week[0].recipeId)
        assertEquals(meat.id, week[3].recipeId)
        assertEquals(5, week.count { it.recipeId.isBlank() })
        assertEquals(week, LocalSpoonRepository(preferences, json, catalog).mealPlans.first())
        val fish = catalog.queryRecipes(ExploreCriteria(category = "fish"), 1, 0, MealPreferenceSettings()).recipes.single()
        planner.toggleFavorite(fish.id)
        assertEquals(fish.id, planner.ensureWeek(monday)[4].recipeId)
        repository.updateMealPreferenceSettings(MealPreferenceSettings(
            favoritesOnly = true,
            weekdayCategories = java.time.DayOfWeek.entries.associate { it.name to "meat" },
        ))
        assertTrue(planner.ensureWeek(monday, resetWeekdays = java.time.DayOfWeek.entries.toSet()).all { it.recipeId == meat.id })
    }

    @Test fun lockedAndCookedRecipesSurviveRegenerationAndRepositoryReopen() = runBlocking {
        val preferences = context.getSharedPreferences("locks-planner-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val repository = LocalSpoonRepository(preferences, json, catalog)
        val planner = MealPlanner(repository, selector)
        val monday = LocalDate.of(2026, 9, 7)
        planner.ensureWeek(monday)
        planner.setLocked(monday, true)
        planner.setCompleted(monday.plusDays(1), true)
        val protected = repository.mealPlans.first().filter { it.locked || it.completed }
        val history = repository.cookedHistory.first()
        val reopened = LocalSpoonRepository(preferences, json, catalog)
        assertEquals(protected, reopened.mealPlans.first().filter { it.locked || it.completed })
        reopened.updateMealPreferenceSettings(MealPreferenceSettings(favoritesOnly = true))
        assertEquals(19, MealPlanner(reopened, selector).rerollWeek(monday).size)
        protected.forEach { saved ->
            val actual = reopened.mealPlans.first().single { it.date == saved.date }
            assertEquals(saved.copy(side = actual.side, dessert = actual.dessert,
                updatedAtEpochMillis = actual.updatedAtEpochMillis), actual)
        }
        assertEquals(history, reopened.cookedHistory.first())
    }

    @Test fun fullMenuPersistsCoursesAcrossRegenerationCookingAndRestart() = runBlocking {
        val preferences = context.getSharedPreferences("full-menu-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val repository = LocalSpoonRepository(preferences, json, catalog)
        val planner = MealPlanner(repository, selector)
        val monday = LocalDate.of(2026, 9, 7)
        planner.ensureWeek(monday)
        planner.suggestMenu(monday, Random(1))
        planner.setLocked(monday, true, MealCourse.SIDE)
        planner.setCompleted(monday, true, MealCourse.DESSERT)
        val menu = repository.mealPlans.first().first { it.date == monday.toString() }
        assertTrue(menu.side!!.recipeId.isNotBlank())
        assertTrue(menu.dessert!!.completed)
        val history = repository.cookedHistory.first()
        planner.rerollWeek(monday, Random(2))
        val reopened = LocalSpoonRepository(preferences, json, BundledRecipeCatalog(context, json))
        val reopenedPlanner = MealPlanner(reopened, selector)
        repeat(3) { reopenedPlanner.suggestMenu(monday, Random(it + 10)) }
        val stored = reopened.mealPlans.first().first { it.date == monday.toString() }
        assertEquals(menu.side, stored.side)
        assertEquals(menu.dessert, stored.dessert)
        assertEquals(history, reopened.cookedHistory.first())
        val hydratedIds = reopened.recipes.first().map { it.id }.toSet()
        assertTrue(menu.side!!.recipeId in hydratedIds)
        assertTrue(menu.dessert!!.recipeId in hydratedIds)
        reopenedPlanner.toggleFavorite(menu.side!!.recipeId)
        reopenedPlanner.replaceWithFavorite(monday, menu.side!!.recipeId, MealCourse.SIDE)
        assertEquals(menu.dessert, reopened.mealPlans.first().first { it.date == monday.toString() }.dessert)
        reopenedPlanner.setCompleted(monday, false, MealCourse.DESSERT)
        assertTrue(reopened.cookedHistory.first().isEmpty())
    }

    @Test fun staleInstallStampReinstallsCatalogWithoutTouchingPersonalData() = runBlocking {
        catalog.ensureReady()
        val sentinel = context.getSharedPreferences("sentinel-test", Context.MODE_PRIVATE)
        sentinel.edit().putString("saved", "keep").commit()
        val installed = File(context.noBackupFilesDir, BundledRecipeCatalog.INSTALLED_DATABASE_NAME)
        val expected = installed.length()
        val assetDigest = sha256(context.assets.open(BundledRecipeCatalog.ASSET_DATABASE_NAME))
        assertArrayEquals("Installed catalog must match the current APK asset", assetDigest, sha256(installed.inputStream()))
        val expectedCount = catalog.queryRecipes(
            ExploreCriteria(), 1, 0, MealPreferenceSettings(excludedCategories = emptySet()),
        ).totalCount
        assertTrue(expectedCount > 0)
        context.getSharedPreferences("spoon_catalog_install", Context.MODE_PRIVATE).edit().putString("apk_install_stamp_v1", "old-version").commit()
        val replacement = BundledRecipeCatalog(context, json)
        replacement.ensureReady()
        assertEquals(expected, installed.length())
        assertArrayEquals("Reinstalled catalog must match the current APK asset", assetDigest, sha256(installed.inputStream()))
        assertEquals("keep", sentinel.getString("saved", null))
        assertEquals(
            expectedCount,
            replacement.queryRecipes(
                ExploreCriteria(),
                1,
                0,
                MealPreferenceSettings(excludedCategories = emptySet()),
            ).totalCount,
        )
    }

    private fun only(category: MealCategory) = MealPreferenceSettings(excludedCategories = MealCategory.entries.filterNot { it == MealCategory.ANY || it == category }.map(MealCategory::key).toSet())

    private fun sha256(input: InputStream): ByteArray = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest()
    }

    private fun forEachRecipe(block: (Recipe) -> Unit) {
        database().use { db ->
            db.rawQuery("SELECT recipe_json FROM recipes ORDER BY id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val text = InflaterInputStream(ByteArrayInputStream(cursor.getBlob(0))).bufferedReader().use { it.readText() }
                    block(json.decodeFromString<Recipe>(text))
                }
            }
        }
    }

    private fun sqlIds(sql: CatalogSql): Set<String> = database().use { db ->
        db.rawQuery("SELECT r.id FROM recipes r ${sql.whereSql} ORDER BY r.id", sql.arguments.toTypedArray()).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    private fun assertIds(name: String, expected: Set<String>, actual: Set<String>) {
        assertTrue("$name: missing=${(expected - actual).take(8)}, extra=${(actual - expected).take(8)}, expected=${expected.size}, actual=${actual.size}", expected == actual)
    }

    private fun database() = SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, BundledRecipeCatalog.INSTALLED_DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val context: Context by lazy {
            object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir(): File = File(super.getNoBackupFilesDir(), "catalog-device-verification").also { it.mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("verification_$name", mode)
            }
        }
        private val catalog by lazy { BundledRecipeCatalog(context, json) }
    }
}
