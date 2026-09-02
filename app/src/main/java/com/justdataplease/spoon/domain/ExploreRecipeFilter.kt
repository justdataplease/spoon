package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.model.EaseLevel
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import java.text.Normalizer
import java.util.Locale

/** All constraints used by the recipe explorer. Empty values leave that facet unconstrained. */
data class ExploreCriteria(
    val query: String = "",
    val category: String = "",
    val easeLevel: String = "",
    val minRating: Double = 0.0,
    val maxPrepMinutes: Int = 0,
    val dietLabels: Set<String> = emptySet(),
    val mealTypeLabels: Set<String> = emptySet(),
    val occasionLabels: Set<String> = emptySet(),
    val methodLabels: Set<String> = emptySet(),
    val cuisineLabels: Set<String> = emptySet(),
    val ingredientLabels: Set<String> = emptySet(),
    val quickOnly: Boolean = false,
) {
    fun isValid(): Boolean =
        minRating.isFinite() &&
            minRating in 0.0..10.0 &&
            maxPrepMinutes >= 0 &&
            (easeLevel.isBlank() || EaseLevel.fromKey(easeLevel.normalizedKey()) != null)
}

/** Pure, deterministic filtering for the Explore screen. */
object ExploreRecipeFilter {
    fun filter(
        recipes: List<Recipe>,
        criteria: ExploreCriteria = ExploreCriteria(),
    ): List<Recipe> {
        if (!criteria.isValid()) return emptyList()

        val requestedCategory = criteria.category.normalizedSearchText()
            .takeUnless { it.isBlank() || it == MealCategory.ANY.key }
        val requestedEase = criteria.easeLevel
            .takeIf(String::isNotBlank)
            ?.normalizedKey()
            ?.let(EaseLevel::fromKey)
        val queryTerms = criteria.query.normalizedSearchText()
            .split(Whitespace)
            .filter(String::isNotBlank)

        return recipes.asSequence()
            .filter(Recipe::isActiveGreekRecipe)
            .filter { recipe ->
                requestedCategory == null ||
                    requestedCategory == recipe.category.normalizedSearchText() ||
                    requestedCategory == recipe.categoryLabel.normalizedSearchText()
            }
            .filter { requestedEase == null || it.easeLevel == requestedEase }
            .filter { it.rating.isFinite() && it.rating in 0.0..10.0 }
            .filter { it.rating >= criteria.minRating }
            // Zero is the source's unknown value and must not silently satisfy a time cap.
            .filter {
                criteria.maxPrepMinutes == 0 ||
                    (it.prepMinutes > 0 && it.prepMinutes <= criteria.maxPrepMinutes)
            }
            .filter { criteria.dietLabels.matchesFacet(it.dietLabels) }
            .filter { criteria.mealTypeLabels.matchesFacet(it.mealTypeLabels) }
            .filter { criteria.occasionLabels.matchesFacet(it.occasionLabels) }
            .filter { criteria.methodLabels.matchesFacet(it.methodLabels) }
            .filter { criteria.cuisineLabels.matchesFacet(it.cuisineLabels) }
            .filter { criteria.ingredientLabels.matchesFacet(it.ingredientLabels) }
            .filter { !criteria.quickOnly || it.quickRecipe }
            .filter { recipe ->
                queryTerms.isEmpty() || recipe.searchableText().let { searchable ->
                    queryTerms.all(searchable::contains)
                }
            }
            .sortedWith(
                compareByDescending<Recipe>(Recipe::rating)
                    .thenBy { it.title.normalizedSearchText() }
                    .thenBy(Recipe::id),
            )
            .toList()
    }
}

/** Facets are OR-ed within one group; the groups themselves are AND-ed by the filter pipeline. */
private fun Set<String>.matchesFacet(actualValues: List<String>): Boolean {
    val selected = asSequence()
        .map(String::normalizedSearchText)
        .filter(String::isNotBlank)
        .toSet()
    if (selected.isEmpty()) return true
    return actualValues.asSequence()
        .map(String::normalizedSearchText)
        .any(selected::contains)
}

private fun Recipe.searchableText(): String = buildList {
    add(title)
    add(description)
    add(category)
    add(categoryLabel)
    addAll(tags)
    addAll(dietLabels)
    addAll(mealTypeLabels)
    addAll(occasionLabels)
    addAll(methodLabels)
    addAll(cuisineLabels)
    addAll(ingredientLabels)
}.joinToString(separator = "\u0000") { it.normalizedSearchText() }

internal fun Recipe.isActiveGreekRecipe(): Boolean {
    val normalizedLanguage = language.trim().lowercase(Locale.ROOT)
    return active &&
        (normalizedLanguage == "el" ||
            normalizedLanguage.startsWith("el-") ||
            normalizedLanguage.startsWith("el_"))
}

private fun String.normalizedKey(): String = trim().lowercase(Locale.ROOT)

private fun String.normalizedSearchText(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .asSequence()
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .joinToString(separator = "")
        .lowercase(Locale.ROOT)
        .replace('ς', 'σ')
        .trim()

private val Whitespace = Regex("\\s+")
