package com.justdataplease.spoon.data.model

import com.justdataplease.spoon.data.requireSafeRecipeDocumentId

internal const val MAX_SHOPPING_ITEMS_PER_WRITE = 250
internal const val MAX_RECIPE_NOTE_LENGTH = 10_000
internal const val MAX_CUSTOM_INGREDIENTS = 200
internal const val MAX_CUSTOM_STEPS = 100

internal fun ShoppingListItem.requireValid(): ShoppingListItem {
    requireSafeRecipeDocumentId(id)
    require(name.isNotBlank() && name.length <= 200) { "A shopping item needs a short name" }
    require(quantity.length <= 100 && unit.length <= 100 && info.length <= 500)
    if (recipeId.isNotEmpty()) requireSafeRecipeDocumentId(recipeId)
    require(recipeTitle.length <= 300 && sectionTitle.length <= 200)
    requireValidEpochMillis(createdAtEpochMillis)
    requireValidEpochMillis(updatedAtEpochMillis)
    require(updatedAtEpochMillis >= createdAtEpochMillis)
    return this
}

internal fun RecipeNote.requireValid(): RecipeNote {
    requireSafeRecipeDocumentId(recipeId)
    require(id.isEmpty() || id == recipeId) { "A note id must match its recipe id" }
    require(text.isNotBlank() && text.length <= MAX_RECIPE_NOTE_LENGTH)
    requireValidEpochMillis(updatedAtEpochMillis)
    return this
}

internal fun CustomRecipe.requireValid(): CustomRecipe {
    require(id.isCustomRecipeId()) { "A custom recipe needs a generated custom_ UUID" }
    require(title.isNotBlank() && title.length <= 300)
    require(description.length <= 10_000)
    require(MealCategory.fromKey(category)?.let { it != MealCategory.ANY } == true) {
        "A custom recipe needs a concrete planner category"
    }
    require(prepMinutes in 0..10_080 && cookMinutes in 0..10_080)
    require(servings.length <= 100)
    require(ingredientSections.isNotEmpty() && ingredientSections.size <= 20)
    require(methodSections.isNotEmpty() && methodSections.size <= 20)
    require(ingredientSections.all(RecipeIngredientSection::isValidCustomSection))
    require(methodSections.all(RecipeMethodSection::isValidCustomSection))
    require(ingredientSections.sumOf { it.ingredients.size } <= MAX_CUSTOM_INGREDIENTS)
    require(methodSections.sumOf { it.steps.size } <= MAX_CUSTOM_STEPS)
    require(
        photoDataUri.isEmpty() ||
            (
                photoDataUri.length <= MAX_CUSTOM_RECIPE_PHOTO_DATA_URI_LENGTH &&
                    photoDataUri.startsWith(JPEG_DATA_URI_PREFIX) &&
                    photoDataUri.substring(JPEG_DATA_URI_PREFIX.length).isValidBase64Payload()
                ),
    ) { "The custom photo must be a bounded JPEG data URI" }
    requireValidEpochMillis(createdAtEpochMillis)
    requireValidEpochMillis(updatedAtEpochMillis)
    require(updatedAtEpochMillis >= createdAtEpochMillis)
    return this
}

private fun RecipeIngredientSection.isValidCustomSection(): Boolean =
    title.length <= 200 &&
        ingredients.isNotEmpty() &&
        ingredients.size <= 100 &&
        ingredients.all { ingredient ->
            ingredient.title.isNotBlank() &&
                ingredient.title.length <= 200 &&
                ingredient.quantity.length <= 100 &&
                ingredient.unit.length <= 100 &&
                ingredient.info.length <= 500
        }

private fun RecipeMethodSection.isValidCustomSection(): Boolean =
    title.length <= 200 &&
        steps.isNotEmpty() &&
        steps.size <= MAX_CUSTOM_STEPS &&
        steps.all { it.isNotBlank() && it.length <= 5_000 }

private fun String.isValidBase64Payload(): Boolean =
    isNotEmpty() && all { character ->
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '+' ||
            character == '/' ||
            character == '='
    }

private fun requireValidEpochMillis(value: Long) {
    require(value in 1..253_402_300_799_999L)
}

private const val JPEG_DATA_URI_PREFIX = "data:image/jpeg;base64,"
