package com.justdataplease.spoon.ui.custom

data class CustomIngredientDraftUi(
    val title: String,
    val quantity: String = "",
    val unit: String = "",
)

data class CustomRecipeDraftUi(
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
    categoryKey.isBlank() -> "Διάλεξε βασική κατηγορία."
    ingredients.none { it.title.isNotBlank() } -> "Πρόσθεσε τουλάχιστον ένα υλικό."
    steps.none(String::isNotBlank) -> "Πρόσθεσε τουλάχιστον ένα βήμα εκτέλεσης."
    prepMinutes < 0 || cookMinutes < 0 -> "Οι χρόνοι δεν μπορούν να είναι αρνητικοί."
    else -> null
}
