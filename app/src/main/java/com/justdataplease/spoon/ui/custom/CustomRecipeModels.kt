package com.justdataplease.spoon.ui.custom

import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.RecipeIngredient
import com.justdataplease.spoon.data.model.RecipeIngredientSection
import com.justdataplease.spoon.data.model.RecipeMethodSection
import com.justdataplease.spoon.ui.model.AvailableCategories
import com.justdataplease.spoon.ui.model.RecipeDetailUi
import kotlinx.serialization.Serializable

@Serializable
data class CustomIngredientDraftUi(
    val title: String,
    val quantity: String = "",
    val unit: String = "",
)

data class CustomRecipeDraftUi(
    /** Blank for a new recipe; retained when an existing personal recipe is edited. */
    val recipeId: String = "",
    val title: String,
    val description: String = "",
    val categoryKey: String,
    val prepMinutes: Int = 0,
    val cookMinutes: Int = 0,
    val servings: String = "",
    val ingredients: List<CustomIngredientDraftUi>,
    val steps: List<String>,
    /** Compact JPEG data URL produced by [RecipePhotoInput]. */
    val imageDataUrl: String = "",
)

internal fun CustomRecipeDraftUi.validationMessage(): String? = when {
    title.isBlank() -> "Γράψε έναν τίτλο για τη συνταγή."
    AvailableCategories.none { it.key == categoryKey } -> "Διάλεξε έγκυρη βασική κατηγορία."
    ingredients.none { it.title.isNotBlank() } -> "Πρόσθεσε τουλάχιστον ένα υλικό."
    steps.none(String::isNotBlank) -> "Πρόσθεσε τουλάχιστον ένα βήμα εκτέλεσης."
    prepMinutes < 0 || cookMinutes < 0 -> "Οι χρόνοι δεν μπορούν να είναι αρνητικοί."
    else -> null
}

/** Converts the editable UI aliases to the canonical keys persisted locally and in Firestore. */
internal fun CustomRecipeDraftUi.toDomainCustomRecipe(): CustomRecipe {
    require(validationMessage() == null) { validationMessage().orEmpty() }
    val domainCategory = categoryKey.toDomainCustomRecipeCategory()
    require(domainCategory != MealCategory.ANY.key && MealCategory.fromKey(domainCategory) != null)
    val cleanIngredients = ingredients.asSequence()
        .filter { it.title.isNotBlank() }
        .take(200)
        .map { ingredient ->
            RecipeIngredient(
                title = ingredient.title.trim().take(200),
                quantity = ingredient.quantity.trim().take(100),
                unit = ingredient.unit.trim().take(100),
            )
        }
        .toList()
    val cleanSteps = steps.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .take(100)
        .map { it.take(5_000) }
        .toList()
    return CustomRecipe(
        id = recipeId,
        title = title.trim().take(300),
        description = description.trim().take(10_000),
        category = domainCategory,
        prepMinutes = prepMinutes.coerceIn(0, 10_080),
        cookMinutes = cookMinutes.coerceIn(0, 10_080),
        servings = servings.trim().take(100),
        ingredientSections = listOf(RecipeIngredientSection(ingredients = cleanIngredients)),
        methodSections = listOf(RecipeMethodSection(steps = cleanSteps)),
        photoDataUri = imageDataUrl,
    )
}

/** Rehydrates the form without changing the user-facing planner category. */
internal fun RecipeDetailUi.toCustomRecipeDraftUi(): CustomRecipeDraftUi = CustomRecipeDraftUi(
    recipeId = recipeId,
    title = title,
    description = description,
    categoryKey = categoryKey.takeIf { key -> AvailableCategories.any { it.key == key } } ?: "other",
    prepMinutes = prepMinutes,
    cookMinutes = cookMinutes,
    servings = servings,
    ingredients = ingredientSections.flatMap { section ->
        section.ingredients.map { ingredient ->
            CustomIngredientDraftUi(
                title = ingredient.title,
                quantity = ingredient.quantity,
                unit = ingredient.unit,
            )
        }
    },
    steps = methodSections.flatMap(RecipeMethodSection::steps),
    imageDataUrl = imageUrl,
)

private fun String.toDomainCustomRecipeCategory(): String = when (this) {
    "chicken" -> MealCategory.POULTRY.key
    "vegetarian" -> MealCategory.VEGETABLES.key
    "dirty" -> MealCategory.STREET_FOOD.key
    "pasta" -> MealCategory.PASTA_RICE.key
    else -> this
}
