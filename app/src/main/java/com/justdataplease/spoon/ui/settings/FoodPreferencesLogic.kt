package com.justdataplease.spoon.ui.settings

import com.justdataplease.spoon.data.canonicalIngredientDisplayLabel
import com.justdataplease.spoon.data.canonicalIngredientIdentity
import com.justdataplease.spoon.data.expandedIngredientAliasTokens
import com.justdataplease.spoon.data.local.normalizedCatalogToken
import com.justdataplease.spoon.data.model.MealCategory

internal const val MAX_INGREDIENT_SUGGESTIONS = 30

internal val VEGAN_INCOMPATIBLE_CATEGORY_KEYS = setOf(
    MealCategory.MEAT.key,
    MealCategory.POULTRY.key,
    MealCategory.FISH.key,
)

/**
 * Adapts the persisted exclusion set to the positive category selection shown in settings.
 * An empty (or legacy all-excluded) selection is presented as "all".
 */
internal fun includedCategoryKeys(
    availableCategoryKeys: Collection<String>,
    excludedCategoryKeys: Set<String>,
): Set<String> {
    val available = availableCategoryKeys.toSet()
    val included = available - excludedCategoryKeys
    return included.ifEmpty { available }
}

/**
 * Toggles a positively-presented category while retaining the exclusion-based storage model.
 * The first category chosen from "all" becomes the sole category; removing the last returns to all.
 */
internal fun toggleCategoryInclusions(
    availableCategoryKeys: Collection<String>,
    excludedCategoryKeys: Set<String>,
    categoryKey: String,
): Set<String> {
    val available = availableCategoryKeys.toSet()
    if (categoryKey !in available) return excludedCategoryKeys

    val included = includedCategoryKeys(available, excludedCategoryKeys)
    val nextIncluded = when {
        included == available -> setOf(categoryKey)
        categoryKey in included -> included - categoryKey
        else -> included + categoryKey
    }
    return if (nextIncluded.isEmpty() || nextIncluded == available) {
        emptySet()
    } else {
        available - nextIncluded
    }
}

/**
 * Keeps an explicit positive category choice meaningful when vegan mode is enabled.
 * The default empty exclusions continue to mean all compatible vegan categories.
 */
internal fun reconcileCategoryExclusionsForVegan(
    availableCategoryKeys: Collection<String>,
    excludedCategoryKeys: Set<String>,
    veganOnly: Boolean,
): Set<String> {
    if (!veganOnly || excludedCategoryKeys.isEmpty()) return excludedCategoryKeys

    val available = availableCategoryKeys.toSet()
    val compatibleIncluded = includedCategoryKeys(available, excludedCategoryKeys) -
        VEGAN_INCOMPATIBLE_CATEGORY_KEYS
    return if (compatibleIncluded.isEmpty()) {
        emptySet()
    } else {
        available - compatibleIncluded
    }
}

/** Returns the catalog's display label for a canonical option, or null for arbitrary input. */
internal fun canonicalIngredientOption(
    ingredientOptions: Collection<String>,
    candidate: String,
): String? {
    val candidateIdentity = canonicalIngredientIdentity(candidate)
    if (candidateIdentity.isBlank()) return null
    val matchingOption = ingredientOptions.firstOrNull { option ->
        canonicalIngredientIdentity(option) == candidateIdentity
    } ?: return null
    return canonicalIngredientDisplayLabel(matchingOption)
}

/**
 * Produces a bounded autocomplete list from catalog facets. Prefix matches lead substring matches,
 * and already-excluded ingredients are omitted using the catalog's accent-insensitive normalizer.
 */
internal fun ingredientSuggestions(
    ingredientOptions: Collection<String>,
    excludedIngredients: Set<String>,
    query: String,
    limit: Int = MAX_INGREDIENT_SUGGESTIONS,
): List<String> {
    if (limit <= 0) return emptyList()
    val queryKey = query.normalizedCatalogToken()
    val excludedIdentities = excludedIngredients
        .map(::canonicalIngredientIdentity)
        .filter(String::isNotBlank)
        .toSet()

    return ingredientOptions.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { label ->
            IngredientSuggestion(
                label = canonicalIngredientDisplayLabel(label),
                identity = canonicalIngredientIdentity(label),
                searchTokens = expandedIngredientAliasTokens(label),
            )
        }
        .distinctBy(IngredientSuggestion::identity)
        .filter { suggestion -> suggestion.identity !in excludedIdentities }
        .filter { suggestion ->
            queryKey.isBlank() || suggestion.searchTokens.any { token -> queryKey in token }
        }
        .sortedWith(
            compareBy<IngredientSuggestion> { suggestion ->
                if (
                    queryKey.isBlank() ||
                    suggestion.searchTokens.any { token -> token.startsWith(queryKey) }
                ) 0 else 1
            }.thenBy(IngredientSuggestion::identity),
        )
        .take(limit)
        .map(IngredientSuggestion::label)
        .toList()
}

private data class IngredientSuggestion(
    val label: String,
    val identity: String,
    val searchTokens: List<String>,
)
