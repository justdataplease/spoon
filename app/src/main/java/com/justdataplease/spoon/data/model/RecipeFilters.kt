package com.justdataplease.spoon.data.model

import kotlinx.serialization.Serializable

/**
 * Per-day selection constraints. Blank [easeLevel] means any difficulty and a zero
 * [maxPrepMinutes] means no time cap; both are explicit filter states, not fallbacks.
 */
@Serializable
data class RecipeFilters(
    val category: String = MealCategory.ANY.key,
    val easeLevel: String = "",
    val minRating: Double = 0.0,
    val maxPrepMinutes: Int = 0,
) {
    fun isValid(): Boolean =
        MealCategory.fromKey(category) != null &&
            (easeLevel.isBlank() || EaseLevel.fromKey(easeLevel) != null) &&
            minRating.isFinite() && minRating in 0.0..10.0 &&
            maxPrepMinutes >= 0
}

