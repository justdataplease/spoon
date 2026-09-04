package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.expandedIngredientAliasTokens
import com.justdataplease.spoon.data.model.EaseLevel
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
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
    val sourceKeys: Set<String> = emptySet(),
    val quickOnly: Boolean = false,
) {
    fun isValid(): Boolean =
        minRating.isFinite() &&
            minRating >= 0.0 &&
            minRating < 10.0 &&
            maxPrepMinutes >= 0 &&
            (easeLevel.isBlank() || EaseLevel.fromKey(easeLevel.normalizedKey()) != null)
}

/** Pure, deterministic filtering for the Explore screen. */
object ExploreRecipeFilter {
    fun filter(
        recipes: List<Recipe>,
        criteria: ExploreCriteria = ExploreCriteria(),
        preferences: MealPreferenceSettings = MealPreferenceSettings(),
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
        val selectedDiets = criteria.dietLabels.normalizedFacetSelection()
        val selectedMealTypes = criteria.mealTypeLabels.normalizedFacetSelection()
        val selectedOccasions = criteria.occasionLabels.normalizedFacetSelection()
        val selectedMethods = criteria.methodLabels.normalizedFacetSelection()
        val selectedCuisines = criteria.cuisineLabels.normalizedFacetSelection()
        val selectedIngredients = criteria.ingredientLabels.expandedIngredientFacetTokens()
        val selectedSources = criteria.sourceKeys.normalizedFacetSelection()

        return recipes.asSequence()
            .filter(Recipe::isActiveGreekRecipe)
            .filter { recipe -> recipe.matchesMealPreferences(preferences) }
            .filter { recipe ->
                requestedCategory == null ||
                    requestedCategory == recipe.category.normalizedSearchText() ||
                    requestedCategory == recipe.categoryLabel.normalizedSearchText()
            }
            .filter { requestedEase == null || it.easeLevel == requestedEase }
            .filter { it.rating.isFinite() && it.rating in 0.0..10.0 }
            .filter { criteria.minRating == 0.0 || it.rating > criteria.minRating }
            // Zero is the source's unknown value and must not silently satisfy a time cap.
            .filter {
                criteria.maxPrepMinutes == 0 ||
                    (it.prepMinutes > 0 && it.prepMinutes <= criteria.maxPrepMinutes)
            }
            .filter { selectedDiets.matchesFacet(it.dietLabels) }
            .filter { selectedMealTypes.matchesFacet(it.mealTypeLabels) }
            .filter { selectedOccasions.matchesFacet(it.occasionLabels) }
            .filter { selectedMethods.matchesFacet(it.methodLabels) }
            .filter { selectedCuisines.matchesFacet(it.cuisineLabels) }
            .filter { selectedIngredients.matchesIngredientFacet(it.ingredientLabels) }
            .filter { recipe ->
                selectedSources.isEmpty() ||
                    sequenceOf(recipe.effectiveSourceKey, recipe.source, recipe.sourceName)
                        .map(String::normalizedSearchText)
                        .any { it in selectedSources }
            }
            .filter { !criteria.quickOnly || it.quickRecipe }
            .filter { recipe ->
                queryTerms.isEmpty() || recipe.searchableText().let { searchable ->
                    queryTerms.all(searchable::contains)
                }
            }
            .map { recipe -> RankedRecipe(recipe, recipe.title.normalizedSearchText()) }
            .sortedWith(
                compareByDescending<RankedRecipe> { it.recipe.rating }
                    .thenBy(RankedRecipe::normalizedTitle)
                    .thenBy { it.recipe.id },
            )
            .map(RankedRecipe::recipe)
            .toList()
    }
}

/** Facets are OR-ed within one group; the groups themselves are AND-ed by the filter pipeline. */
private fun Set<String>.normalizedFacetSelection(): Set<String> = asSequence()
    .map(String::normalizedSearchText)
    .filter(String::isNotBlank)
    .toSet()

private fun Set<String>.matchesFacet(actualValues: List<String>): Boolean {
    if (isEmpty()) return true
    return actualValues.asSequence()
        .map(String::normalizedSearchText)
        .any { normalized -> normalized in this }
}

private fun Set<String>.expandedIngredientFacetTokens(): Set<String> = asSequence()
    .flatMap { ingredient -> expandedIngredientAliasTokens(ingredient).asSequence() }
    .toSet()

/** Uses the catalog normalizer on both sides so personal recipes match SQLite semantics. */
private fun Set<String>.matchesIngredientFacet(actualValues: List<String>): Boolean {
    if (isEmpty()) return true
    return actualValues.asSequence()
        .flatMap { ingredient -> expandedIngredientAliasTokens(ingredient).asSequence() }
        .any { token -> token in this }
}

private fun Recipe.searchableText(): String = buildList {
    add(title)
    add(description)
    add(category)
    add(categoryLabel)
    add(effectiveSourceKey)
    add(source)
    add(sourceName)
    addAll(tags)
    addAll(dietLabels)
    addAll(mealTypeLabels)
    addAll(occasionLabels)
    addAll(methodLabels)
    addAll(cuisineLabels)
    addAll(ingredientLabels)
}.joinToString(separator = "\u0000").normalizedSearchText()

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
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .lowercase(Locale.ROOT)
        .replace('ς', 'σ')
        .trim()

private data class RankedRecipe(
    val recipe: Recipe,
    val normalizedTitle: String,
)

private val Whitespace = Regex("\\s+")
