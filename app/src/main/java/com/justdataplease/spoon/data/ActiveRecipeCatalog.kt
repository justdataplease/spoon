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
 * Generic publisher "Snack" metadata is not Street Food evidence. Exact
 * Sandwich/Finger-food facets are promoted even when an older projection gave an
 * ingredient category precedence; explicit dessert/other categories stay terminal.
 */
internal fun Recipe.withCurrentAkisPlannerCategory(): Recipe {
    if (effectiveSourceKey != "akis") return this
    val hasStreetFoodFacet = mealTypeLabels.any { label ->
        label.trim().equals("Σάντουιτς", ignoreCase = true) ||
            label.trim().equals("Finger food", ignoreCase = true)
    }
    if (category == "dessert" || category == "other") return this
    if (hasStreetFoodFacet) {
        val previousCategoryLabel = categoryLabel.trim()
        val secondaryTags = tags.filterNot { tag ->
            tag.trim().equals("Βρώμικο", ignoreCase = true) ||
                previousCategoryLabel.isNotEmpty() &&
                tag.trim().equals(previousCategoryLabel, ignoreCase = true)
        }
        return copy(
            category = "street_food",
            categoryLabel = "Βρώμικο",
            tags = listOf("Βρώμικο", *secondaryTags.toTypedArray()),
        )
    }
    if (category != "street_food") return this

    return copy(
        category = "other",
        categoryLabel = "Άλλο",
        tags = tags.filterNot { it.trim().equals("Βρώμικο", ignoreCase = true) },
    )
}
