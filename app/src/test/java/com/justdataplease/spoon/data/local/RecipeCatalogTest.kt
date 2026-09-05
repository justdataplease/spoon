package com.justdataplease.spoon.data.local

import com.justdataplease.spoon.data.DistinctIngredientConceptCases
import com.justdataplease.spoon.data.ReviewedIngredientAliasCases
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.domain.ExploreCriteria
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeCatalogTest {
    @Test
    fun `installed catalog remains inside the no-backup directory`() {
        val noBackupDirectory = File("private/no_backup")

        val target = installedCatalogFile(noBackupDirectory)

        assertEquals(noBackupDirectory, target.parentFile)
        assertEquals(BundledRecipeCatalog.INSTALLED_DATABASE_NAME, target.name)
    }

    @Test
    fun `normalization matches Greek accents punctuation and final sigma`() {
        assertEquals("φακεσ σαλατα", "  ΦΑΚΈΣ — σαλάτα! ".normalizedCatalogToken())
    }

    @Test
    fun `explore SQL keeps strict rating unknown-time and OR-within-facet semantics`() {
        val sql = CatalogSqlBuilder.forExplore(
            ExploreCriteria(
                query = "Ψάρι λεμόνι",
                category = MealCategory.FISH.key,
                minRating = 7.0,
                maxPrepMinutes = 30,
                cuisineLabels = setOf("Ελληνική", "Ιταλική"),
                sourceKeys = setOf("akis", "argiro"),
                quickOnly = true,
            ),
        )

        assertTrue(sql.whereSql.contains("r.rating > ?"))
        assertFalse(sql.whereSql.contains("r.rating >= ?"))
        assertTrue(sql.whereSql.contains("r.prep_minutes > 0"))
        assertTrue(sql.whereSql.contains("r.source_key IN (?,?)"))
        assertTrue(sql.whereSql.contains("f.facet_type = ?"))
        assertTrue(sql.whereSql.contains("f.token IN (?,?)"))
        assertTrue(sql.arguments.containsAll(listOf("ψαρι", "λεμονι", "ελληνικη", "ιταλικη")))
    }

    @Test
    fun `every Explore category binds the stored machine key unchanged`() {
        MealCategory.entries.filterNot { it == MealCategory.ANY }.forEach { category ->
            val sql = CatalogSqlBuilder.forExplore(ExploreCriteria(category = category.key))
            assertEquals(category.key, listOf(category.key), sql.arguments)
        }
    }

    @Test
    fun `chicken-only SQL excludes every other stored category including compound keys`() {
        val excluded = MealCategory.entries
            .filterNot { it == MealCategory.ANY || it == MealCategory.POULTRY }
            .map(MealCategory::key).toSet()
        val preferences = MealPreferenceSettings(excludedCategories = excluded)
        val selections = listOf(
            CatalogSqlBuilder.forExplore(ExploreCriteria(), preferences),
            CatalogSqlBuilder.forPlanner(RecipeFilters(category = "any"), null, preferences),
        )
        selections.forEach { sql ->
            val survivingCategories = MealCategory.entries
                .filterNot { it == MealCategory.ANY || it.key in sql.arguments }
            assertEquals(listOf(MealCategory.POULTRY), survivingCategories)
            assertEquals(excluded, sql.arguments.toSet())
            assertFalse(sql.whereSql.contains("r.id !="))
        }
    }

    @Test
    fun `Explore ingredient facet expands reviewed aliases in one OR predicate`() {
        val sql = CatalogSqlBuilder.forExplore(
            ExploreCriteria(ingredientLabels = setOf("Αυγό")),
        )

        assertEquals(listOf("ingredient", "αυγο", "αυγα"), sql.arguments)
        assertTrue(sql.whereSql.contains("f.token IN (?,?)"))
        assertEquals(1, "SELECT 1 FROM recipe_facets f".toRegex().findAll(sql.whereSql).count())
    }

    @Test
    fun `Explore SQL expands every reviewed ingredient group to its exact tokens`() {
        ReviewedIngredientAliasCases.forEach { case ->
            val sql = CatalogSqlBuilder.forExplore(
                ExploreCriteria(ingredientLabels = setOf(case.canonical)),
            )
            val expectedTokens = case.aliases
                .map(String::normalizedCatalogToken)
                .filter(String::isNotBlank)
                .distinct()

            assertEquals(
                "Alias token expansion failed for ${case.canonical}",
                listOf("ingredient") + expectedTokens,
                sql.arguments,
            )
            assertTrue(
                "Alias SQL was not a single OR predicate for ${case.canonical}",
                sql.whereSql.contains(
                    "f.token IN (${List(expectedTokens.size) { "?" }.joinToString(",")})",
                ),
            )
        }
    }

    @Test
    fun `Explore SQL ORs multiple ingredient groups inside one facet predicate`() {
        val sql = CatalogSqlBuilder.forExplore(
            ExploreCriteria(ingredientLabels = setOf("Αυγό", "Πατάτα")),
        )

        assertEquals(
            listOf("ingredient", "αυγο", "αυγα", "πατατα", "πατατεσ"),
            sql.arguments,
        )
        assertTrue(sql.whereSql.contains("f.token IN (?,?,?,?)"))
        assertEquals(1, "SELECT 1 FROM recipe_facets f".toRegex().findAll(sql.whereSql).count())
    }

    @Test
    fun `Explore SQL does not expand reviewed distinct ingredient concepts`() {
        DistinctIngredientConceptCases.forEach { (selected, distinct) ->
            val sql = CatalogSqlBuilder.forExplore(
                ExploreCriteria(ingredientLabels = setOf(selected)),
            )

            assertFalse(
                "Distinct concept $distinct leaked into $selected",
                sql.arguments.contains(distinct.normalizedCatalogToken()),
            )
        }
    }

    @Test
    fun `planner SQL is indexed and excludes the current recipe`() {
        val sql = CatalogSqlBuilder.forPlanner(
            RecipeFilters(
                category = MealCategory.LEGUMES.key,
                easeLevel = "easy",
                minRating = 7.0,
                maxPrepMinutes = 25,
            ),
            excludingRecipeId = "current",
        )

        assertEquals(
            listOf("legumes", "easy", "7.0", "25", "current"),
            sql.arguments,
        )
        assertTrue(sql.whereSql.contains("r.id != ?"))
    }

    @Test
    fun `global preferences add category vegan and raw ingredient predicates to both paths`() {
        val preferences = MealPreferenceSettings(
            excludedCategories = setOf(MealCategory.MEAT.key),
            veganOnly = true,
            excludedIngredientTerms = setOf("γαλα καρυδας"),
        )

        val explore = CatalogSqlBuilder.forExplore(ExploreCriteria(), preferences)
        val planner = CatalogSqlBuilder.forPlanner(
            RecipeFilters(category = MealCategory.ANY.key),
            excludingRecipeId = null,
            preferences = preferences,
        )

        listOf(explore, planner).forEach { sql ->
            assertTrue(sql.whereSql.contains("r.category NOT IN (?)"))
            assertTrue(sql.whereSql.contains("r.vegan_eligible = 1"))
            assertTrue(sql.whereSql.contains("recipe_ingredient_texts"))
            assertTrue(sql.whereSql.contains("instr(ingredient.normalized_text, ?)"))
            assertTrue(sql.whereSql.contains("r.id NOT IN ("))
            assertTrue(
                sql.whereSql.contains(
                    "SELECT ingredient_facet.recipe_id FROM recipe_facets ingredient_facet",
                ),
            )
            assertFalse(sql.whereSql.contains("recipe_facets vegan"))
            assertFalse(sql.whereSql.contains("ingredient_facet.recipe_id = r.id"))
            assertEquals(listOf("meat", "γαλα καρυδασ", "ingredient", "γαλα καρυδασ"), sql.arguments)
        }
    }

    @Test
    fun `SQLite ingredient exclusions expand reviewed aliases for raw and facet indexes`() {
        val sql = CatalogSqlBuilder.forPlanner(
            filters = RecipeFilters(category = MealCategory.ANY.key),
            excludingRecipeId = null,
            preferences = MealPreferenceSettings(excludedIngredientTerms = setOf("Αυγά")),
        )

        assertEquals(
            listOf(
                "αυγο", "ingredient", "αυγο",
                "αυγα", "ingredient", "αυγα",
            ),
            sql.arguments,
        )
        assertEquals(2, "recipe_ingredient_texts".toRegex().findAll(sql.whereSql).count())
        assertEquals(2, "SELECT ingredient_facet.recipe_id".toRegex().findAll(sql.whereSql).count())
    }

    @Test
    fun `SQLite alias expansion keeps flour hierarchy and plant milks distinct`() {
        val sql = CatalogSqlBuilder.forExplore(
            criteria = ExploreCriteria(),
            preferences = MealPreferenceSettings(
                excludedIngredientTerms = setOf("Αλεύρι (ζύμες)", "Γάλα αμυγδάλου"),
            ),
        )

        assertEquals(
            listOf(
                "αλευρι ζυμεσ", "ingredient", "αλευρι ζυμεσ",
                "γαλα αμυγδαλου", "ingredient", "γαλα αμυγδαλου",
            ),
            sql.arguments,
        )
        assertFalse(sql.arguments.contains("αλευρι"))
        assertFalse(sql.arguments.contains("γαλα βρωμησ"))
    }

    @Test
    fun `in memory catalog pages and counts without loading unrelated matches`() = runBlocking {
        val catalog = InMemoryRecipeCatalog(
            listOf(
                Recipe(id = "a", title = "Α", category = MealCategory.FISH.key, rating = 9.0),
                Recipe(id = "b", title = "Β", category = MealCategory.FISH.key, rating = 8.0),
                Recipe(id = "c", title = "Γ", category = MealCategory.MEAT.key, rating = 10.0),
            ),
        )

        val page = catalog.queryRecipes(
            ExploreCriteria(category = MealCategory.FISH.key),
            limit = 1,
            offset = 1,
        )

        assertEquals(2, page.totalCount)
        assertEquals(listOf("b"), page.recipes.map(Recipe::id))
        assertFalse(page.hasMore)
    }

    @Test
    fun `custom recipes retain a per-recipe chance in mixed random selection`() = runBlocking {
        val public = InMemoryRecipeCatalog(
            (1..20).map { index ->
                Recipe(id = "public-$index", title = "Public $index", category = MealCategory.FISH.key)
            },
        )
        val custom = CustomRecipe(
            id = "custom_01234567-89ab-4def-8123-456789abcdef",
            title = "Δική μου",
            category = MealCategory.FISH.key,
        )

        val selectedIds = (0L..500L).mapNotNull { seed ->
            selectIncludingCustomRecipes(
                recipeCatalog = public,
                customRecipes = listOf(custom),
                filters = RecipeFilters(category = MealCategory.FISH.key),
                excludingRecipeId = null,
                randomSeed = seed,
            )?.id
        }

        assertTrue(custom.id in selectedIds)
        assertTrue(selectedIds.any { it.startsWith("public-") })
    }

    @Test
    fun `weekly random selection keeps every public source eligible`() = runBlocking {
        val catalog = InMemoryRecipeCatalog(
            listOf("akis", "argiro", "gastronomos").flatMap { source ->
                (1..12).map { index ->
                    Recipe(
                        id = "$source-$index",
                        title = "$source $index",
                        category = MealCategory.FISH.key,
                        sourceKey = source,
                    )
                }
            },
        )

        val selectedSources = (0L..300L).mapNotNull { seed ->
            catalog.selectRandomRecipe(
                filters = RecipeFilters(category = MealCategory.FISH.key),
                excludingRecipeId = null,
                randomSeed = seed,
            )?.effectiveSourceKey
        }.toSet()

        assertEquals(setOf("akis", "argiro", "gastronomos"), selectedSources)
    }
}
