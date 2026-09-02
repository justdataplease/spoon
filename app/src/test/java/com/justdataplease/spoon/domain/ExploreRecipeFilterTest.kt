package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.model.EaseLevel
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreRecipeFilterTest {
    @Test
    fun `only active Greek recipes are returned in deterministic rating and title order`() {
        val recipes = listOf(
            recipe(id = "beta", title = "Βήτα", rating = 8.0),
            recipe(id = "alpha", title = "Άλφα", rating = 8.0),
            recipe(id = "top", title = "Κορυφαία", rating = 9.5),
            recipe(id = "inactive", active = false),
            recipe(id = "english", language = "en"),
            recipe(id = "invalid-rating", rating = 11.0),
        )

        assertEquals(
            listOf("top", "alpha", "beta"),
            ExploreRecipeFilter.filter(recipes).map(Recipe::id),
        )
    }

    @Test
    fun `search ignores Greek case accents and final sigma in text and tags`() {
        val recipe = recipe(
            id = "searchable",
            title = "ΚΡΈΑΣ κατσαρόλας",
            description = "Λεμονάτη σάλτσα",
            tags = listOf("Σπιτική επιλογή"),
        )

        listOf("κρεασ", "ΛΕΜΟΝΑΤΗ", "σπιτικη").forEach { query ->
            assertEquals(
                listOf(recipe),
                ExploreRecipeFilter.filter(listOf(recipe), ExploreCriteria(query = query)),
            )
        }
    }

    @Test
    fun `search includes every official facet`() {
        val recipe = recipe(
            id = "facets",
            dietLabels = listOf("Χωρίς γλουτένη"),
            mealTypeLabels = listOf("Πρωινό"),
            occasionLabels = listOf("Πάρτι"),
            methodLabels = listOf("Στον φούρνο"),
            cuisineLabels = listOf("Μεξικάνικη"),
            ingredientLabels = listOf("Αβοκάντο"),
        )

        listOf("γλουτενη", "πρωινο", "παρτι", "φουρνο", "μεξικανικη", "αβοκαντο")
            .forEach { query ->
                assertEquals(
                    listOf(recipe),
                    ExploreRecipeFilter.filter(listOf(recipe), ExploreCriteria(query = query)),
                )
            }
    }

    @Test
    fun `category and ease constraints are applied`() {
        val easyFish = recipe(id = "easy-fish", category = MealCategory.FISH.key)
        val hardFish = recipe(
            id = "hard-fish",
            category = MealCategory.FISH.key,
            preparationCount = 3,
            stepCount = 12,
        )
        val easyMeat = recipe(id = "easy-meat", category = MealCategory.MEAT.key)
        val criteria = ExploreCriteria(
            category = MealCategory.FISH.key,
            easeLevel = EaseLevel.EASY.key,
        )

        assertEquals(
            listOf(easyFish),
            ExploreRecipeFilter.filter(listOf(easyFish, hardFish, easyMeat), criteria),
        )
    }

    @Test
    fun `rating preparation time and quick constraints are applied`() {
        val match = recipe(id = "match", rating = 8.5, prepMinutes = 25, quickRecipe = true)
        val recipes = listOf(
            match,
            recipe(id = "low", rating = 7.9, prepMinutes = 25, quickRecipe = true),
            recipe(id = "long", rating = 9.0, prepMinutes = 31, quickRecipe = true),
            recipe(id = "unknown", rating = 9.0, prepMinutes = 0, quickRecipe = true),
            recipe(id = "not-quick", rating = 9.0, prepMinutes = 20),
        )
        val criteria = ExploreCriteria(minRating = 8.0, maxPrepMinutes = 30, quickOnly = true)

        assertEquals(listOf(match), ExploreRecipeFilter.filter(recipes, criteria))
    }

    @Test
    fun `diet meal type and occasion facets filter independently`() {
        val match = recipe(
            id = "match",
            dietLabels = listOf("Vegan"),
            mealTypeLabels = listOf("Πρωινό"),
            occasionLabels = listOf("Πάρτι"),
        )
        val recipes = listOf(match, recipe(id = "other"))

        listOf(
            ExploreCriteria(dietLabels = setOf("vegan")),
            ExploreCriteria(mealTypeLabels = setOf("πρωινο")),
            ExploreCriteria(occasionLabels = setOf("ΠΑΡΤΙ")),
        ).forEach { criteria ->
            assertEquals(listOf(match), ExploreRecipeFilter.filter(recipes, criteria))
        }
    }

    @Test
    fun `method country and ingredient facets filter independently`() {
        val match = recipe(
            id = "match",
            methodLabels = listOf("Bake"),
            cuisineLabels = listOf("Μεξικάνικη"),
            ingredientLabels = listOf("Avocado"),
        )
        val recipes = listOf(match, recipe(id = "other"))

        listOf(
            ExploreCriteria(methodLabels = setOf("bake")),
            ExploreCriteria(cuisineLabels = setOf("ΜΕΞΙΚΑΝΙΚΗ")),
            ExploreCriteria(ingredientLabels = setOf("avocado")),
        ).forEach { criteria ->
            assertEquals(listOf(match), ExploreRecipeFilter.filter(recipes, criteria))
        }
    }

    @Test
    fun `filter groups combine with AND while choices inside one facet use OR`() {
        val match = recipe(
            id = "match",
            category = MealCategory.FISH.key,
            dietLabels = listOf("Vegan"),
            cuisineLabels = listOf("Greek"),
            quickRecipe = true,
        )
        val wrongDiet = match.copy(id = "wrong-diet", dietLabels = listOf("Vegetarian"))
        val wrongCategory = match.copy(id = "wrong-category", category = MealCategory.MEAT.key)
        val criteria = ExploreCriteria(
            category = MealCategory.FISH.key,
            dietLabels = setOf("Vegan"),
            cuisineLabels = setOf("Italian", "Greek"),
            quickOnly = true,
        )

        assertEquals(
            listOf(match),
            ExploreRecipeFilter.filter(listOf(match, wrongDiet, wrongCategory), criteria),
        )
    }

    @Test
    fun `invalid criteria cannot accidentally broaden results`() {
        val recipes = listOf(recipe(id = "one"))

        assertTrue(
            ExploreRecipeFilter.filter(recipes, ExploreCriteria(minRating = Double.NaN)).isEmpty(),
        )
        assertTrue(
            ExploreRecipeFilter.filter(recipes, ExploreCriteria(maxPrepMinutes = -1)).isEmpty(),
        )
        assertTrue(
            ExploreRecipeFilter.filter(recipes, ExploreCriteria(easeLevel = "invalid")).isEmpty(),
        )
    }

    private fun recipe(
        id: String,
        title: String = id,
        category: String = MealCategory.ANY.key,
        rating: Double = 8.0,
        prepMinutes: Int = 20,
        stepCount: Int = 4,
        preparationCount: Int = 1,
        language: String = "el",
        active: Boolean = true,
        description: String = "",
        tags: List<String> = emptyList(),
        dietLabels: List<String> = emptyList(),
        mealTypeLabels: List<String> = emptyList(),
        occasionLabels: List<String> = emptyList(),
        methodLabels: List<String> = emptyList(),
        cuisineLabels: List<String> = emptyList(),
        ingredientLabels: List<String> = emptyList(),
        quickRecipe: Boolean = false,
    ) = Recipe(
        id = id,
        title = title,
        category = category,
        rating = rating,
        prepMinutes = prepMinutes,
        stepCount = stepCount,
        preparationCount = preparationCount,
        language = language,
        active = active,
        description = description,
        tags = tags,
        dietLabels = dietLabels,
        mealTypeLabels = mealTypeLabels,
        occasionLabels = occasionLabels,
        methodLabels = methodLabels,
        cuisineLabels = cuisineLabels,
        ingredientLabels = ingredientLabels,
        quickRecipe = quickRecipe,
    )
}
