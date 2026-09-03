package com.justdataplease.spoon.data

import com.justdataplease.spoon.data.model.Recipe

/** Only remotely published Greek recipes may enter cloud-backed selection. */
internal fun eligibleRemoteRecipes(remoteRecipes: List<Recipe>): List<Recipe> =
    remoteRecipes.asSequence()
        .filter { recipe -> recipe.active && recipe.language == "el" }
        .map(Recipe::withCurrentAkisPlannerCategory)
        .toList()

/**
 * Keeps clients correct while older Akis projections await their next catalog import.
 * Generic publisher "Snack" metadata is not Street Food evidence; only the exact
 * Sandwich/Finger-food facets used by the current importer remain in Βρώμικο.
 */
internal fun Recipe.withCurrentAkisPlannerCategory(): Recipe {
    if (effectiveSourceKey != "akis" || category != "street_food") return this
    val hasStreetFoodFacet = mealTypeLabels.any { label ->
        label.trim().equals("Σάντουιτς", ignoreCase = true) ||
            label.trim().equals("Finger food", ignoreCase = true)
    }
    if (hasStreetFoodFacet) return this

    return copy(
        category = "other",
        categoryLabel = "Άλλο",
        tags = tags.filterNot { it.trim().equals("Βρώμικο", ignoreCase = true) },
    )
}
