package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.local.normalizedCatalogToken
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings

/** Global discovery rules. Saved recipes and history deliberately bypass these rules. */
internal fun Recipe.matchesMealPreferences(settings: MealPreferenceSettings): Boolean {
    val excludedCategories = settings.excludedCategories
        .map(String::normalizedCatalogToken)
        .filter(String::isNotBlank)
        .toSet()
    if (category.normalizedCatalogToken() in excludedCategories) return false

    if (settings.veganOnly && dietLabels.none { label ->
            label.normalizedCatalogToken().contains(VEGAN_TOKEN)
        }
    ) return false

    val excludedIngredients = settings.excludedIngredientTerms
        .map(String::normalizedCatalogToken)
        .filter(String::isNotBlank)
    if (excludedIngredients.isEmpty()) return true

    val ingredientTexts = sequence {
        yieldAll(ingredientLabels)
        ingredientSections.forEach { section ->
            section.ingredients.forEach { ingredient ->
                yield(listOf(ingredient.title, ingredient.info).joinToString(" "))
            }
        }
    }.map(String::normalizedCatalogToken)
        .filter(String::isNotBlank)
        .toList()

    return excludedIngredients.none { excluded ->
        ingredientTexts.any { ingredient -> ingredient.contains(excluded) }
    }
}

private const val VEGAN_TOKEN = "vegan"
