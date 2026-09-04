package com.justdataplease.spoon.data.local

import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.domain.ExploreCriteria
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
