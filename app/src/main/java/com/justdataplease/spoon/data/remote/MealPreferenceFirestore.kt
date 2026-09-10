package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.MealPreferenceDocument
import com.justdataplease.spoon.data.preferences.MAX_MEAL_PREFERENCE_EPOCH_MILLIS
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.data.preferences.AllowedRecipePublisherKeys
import com.justdataplease.spoon.data.preferences.canonicalExcludedSourceKeys
import com.justdataplease.spoon.data.preferences.nextMealPreferenceTimestamp
import com.justdataplease.spoon.data.preferences.validWeekdayCategories

internal const val MEAL_PREFERENCE_DOCUMENT_ID = "meal"
internal const val MAX_EXCLUDED_INGREDIENT_TERMS = 40
internal const val MAX_EXCLUDED_INGREDIENT_TERM_LENGTH = 60
internal const val MIN_EXCLUDED_INGREDIENT_TERM_LENGTH = 2

private val AllowedExcludedCategories = MealCategory.entries
    .filterNot { it == MealCategory.ANY }
    .mapTo(mutableSetOf(), MealCategory::key)
private val PreferenceWhitespace = Regex("\\s+")

internal fun MealPreferenceSettings.toFirestoreDocument(): Map<String, Any> {
    val categories = excludedCategories
        .map(String::trim)
        .filter(AllowedExcludedCategories::contains)
        .distinct()
        .sorted()
    val ingredients = excludedIngredientTerms
        .map { it.trim().replace(PreferenceWhitespace, " ") }
        .filter { it.length in MIN_EXCLUDED_INGREDIENT_TERM_LENGTH..MAX_EXCLUDED_INGREDIENT_TERM_LENGTH }
        .distinct()
        .sorted()
        .take(MAX_EXCLUDED_INGREDIENT_TERMS)
    val sources = excludedSourceKeys.canonicalExcludedSourceKeys().sorted()
    return mapOf(
        "excludedCategories" to categories,
        "veganOnly" to veganOnly,
        "excludedIngredientTerms" to ingredients,
        "updatedAtEpochMillis" to updatedAtEpochMillis,
        "weekdayCategories" to weekdayCategories.validWeekdayCategories(),
        "dessertWeekdayCategories" to dessertWeekdayCategories.validWeekdayCategories(),
        "sideWeekdayCategories" to sideWeekdayCategories.validWeekdayCategories(),
        "favoritesOnly" to favoritesOnly,
    ).let { document ->
        // Absence means all sources, preserving the legacy rules contract for ordinary saves.
        // Repository writes replace the entire document, so absence also clears old exclusions.
        if (sources.isEmpty()) document else document + ("excludedSourceKeys" to sources)
    }
}

internal fun MealPreferenceDocument.toSettingsOrNull(): MealPreferenceSettings? {
    if (id.isNotBlank() && id != MEAL_PREFERENCE_DOCUMENT_ID) return null
    if (updatedAtEpochMillis !in 1L..MAX_MEAL_PREFERENCE_EPOCH_MILLIS) return null
    if (weekdayCategories != weekdayCategories.validWeekdayCategories()) return null
    if (dessertWeekdayCategories != dessertWeekdayCategories.validWeekdayCategories()) return null
    if (sideWeekdayCategories != sideWeekdayCategories.validWeekdayCategories()) return null
    if (
        excludedCategories.size > AllowedExcludedCategories.size ||
        excludedCategories.any { it !in AllowedExcludedCategories } ||
        excludedCategories.distinct().size != excludedCategories.size
    ) return null
    if (excludedSourceKeys.size > AllowedRecipePublisherKeys.size ||
        excludedSourceKeys.any { it !in AllowedRecipePublisherKeys } ||
        excludedSourceKeys.distinct().size != excludedSourceKeys.size
    ) return null
    val ingredients = excludedIngredientTerms.map { it.trim().replace(PreferenceWhitespace, " ") }
    if (
        ingredients.size > MAX_EXCLUDED_INGREDIENT_TERMS ||
        ingredients.any {
            it.length !in MIN_EXCLUDED_INGREDIENT_TERM_LENGTH..MAX_EXCLUDED_INGREDIENT_TERM_LENGTH
        } ||
        ingredients.distinct().size != ingredients.size
    ) return null
    return MealPreferenceSettings(
        excludedCategories = excludedCategories.toSet(),
        veganOnly = veganOnly,
        excludedIngredientTerms = ingredients.toSet(),
        updatedAtEpochMillis = updatedAtEpochMillis,
        weekdayCategories = weekdayCategories,
        dessertWeekdayCategories = dessertWeekdayCategories,
        sideWeekdayCategories = sideWeekdayCategories,
        favoritesOnly = favoritesOnly,
        excludedSourceKeys = excludedSourceKeys.toSet(),
    )
}

internal fun MealPreferenceSettings.normalizedForSync(timestamp: Long): MealPreferenceSettings {
    val document = toFirestoreDocument()
    @Suppress("UNCHECKED_CAST")
    return MealPreferenceSettings(
        excludedCategories = (document.getValue("excludedCategories") as List<String>).toSet(),
        veganOnly = document.getValue("veganOnly") as Boolean,
        excludedIngredientTerms =
            (document.getValue("excludedIngredientTerms") as List<String>).toSet(),
        updatedAtEpochMillis = timestamp,
        weekdayCategories = weekdayCategories.validWeekdayCategories(),
        dessertWeekdayCategories = dessertWeekdayCategories.validWeekdayCategories(),
        sideWeekdayCategories = sideWeekdayCategories.validWeekdayCategories(),
        favoritesOnly = favoritesOnly,
        excludedSourceKeys = (document["excludedSourceKeys"] as? List<String>).orEmpty().toSet(),
    )
}

internal fun nextPreferenceTimestamp(now: Long, current: Long): Long =
    nextMealPreferenceTimestamp(now, current)
