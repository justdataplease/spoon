package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.model.EaseLevel
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import javax.inject.Inject
import kotlin.random.Random

/** Applies every requested constraint before making a random choice. */
class RecipeSelector @Inject constructor() {
    fun candidates(
        recipes: List<Recipe>,
        filters: RecipeFilters,
        excludingRecipeId: String? = null,
    ): List<Recipe> {
        if (!filters.isValid()) return emptyList()

        val category = checkNotNull(MealCategory.fromKey(filters.category))
        val ease = filters.easeLevel
            .takeIf(String::isNotBlank)
            ?.let(EaseLevel::fromKey)

        return recipes.asSequence()
            .filter(Recipe::active)
            .filter { it.id.isNotBlank() && it.id != excludingRecipeId }
            .filter { category == MealCategory.ANY || it.category == category.key }
            .filter { ease == null || it.easeLevel == ease }
            .filter { it.rating.isFinite() && it.rating in 0.0..10.0 }
            .filter { it.rating >= filters.minRating }
            // An unknown time must not pass a time cap: that would silently weaken it.
            .filter {
                filters.maxPrepMinutes == 0 ||
                    (it.prepMinutes > 0 && it.prepMinutes <= filters.maxPrepMinutes)
            }
            .sortedBy(Recipe::id)
            .toList()
    }

    fun select(
        recipes: List<Recipe>,
        filters: RecipeFilters,
        excludingRecipeId: String? = null,
        random: Random = Random.Default,
    ): Recipe? {
        val candidates = candidates(recipes, filters, excludingRecipeId)
        if (candidates.isEmpty()) return null
        return candidates[random.nextInt(candidates.size)]
    }
}
