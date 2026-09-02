package com.justdataplease.spoon.ui.explore

import com.justdataplease.spoon.ui.model.EaseUi

data class ExploreRecipeUi(
    val recipeId: String,
    val title: String,
    val description: String = "",
    val categoryKey: String = "",
    val categoryLabel: String = "",
    val categoryEmoji: String = "🍽️",
    val rating10: Double = 0.0,
    val ratingCount: Int = 0,
    val prepMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val preparationCount: Int = 0,
    val stepCount: Int = 0,
    val imageUrl: String = "",
    val isFavorite: Boolean = false,
)

data class ExploreFiltersUi(
    val categoryKey: String = "",
    val ease: EaseUi = EaseUi.ANY,
    val minRating10: Int = 0,
    val maxPrepMinutes: Int? = null,
    val diet: String = "",
    val mealType: String = "",
    val occasion: String = "",
    val method: String = "",
    val cuisine: String = "",
    val ingredient: String = "",
    val quickOnly: Boolean = false,
) {
    val activeCount: Int
        get() = listOf(
            categoryKey.isNotBlank(),
            ease != EaseUi.ANY,
            minRating10 > 0,
            maxPrepMinutes != null,
            diet.isNotBlank(),
            mealType.isNotBlank(),
            occasion.isNotBlank(),
            method.isNotBlank(),
            cuisine.isNotBlank(),
            ingredient.isNotBlank(),
            quickOnly,
        ).count { it }
}

data class ExploreFacetOptionsUi(
    val diets: List<String> = emptyList(),
    val mealTypes: List<String> = emptyList(),
    val occasions: List<String> = emptyList(),
    val methods: List<String> = emptyList(),
    val cuisines: List<String> = emptyList(),
    val ingredients: List<String> = emptyList(),
)
