package com.justdataplease.spoon.ui.shopping

/** A persisted shopping-list row rendered by [ShoppingScreen]. */
data class ShoppingListItemUi(
    val id: String,
    val title: String,
    val quantity: String = "",
    val unit: String = "",
    val info: String = "",
    val recipeId: String = "",
    val recipeTitle: String = "",
    val isChecked: Boolean = false,
)

/** Neutral payload emitted from a recipe before the data layer assigns an id. */
data class ShoppingIngredientDraftUi(
    val recipeId: String,
    val recipeTitle: String,
    val title: String,
    val quantity: String = "",
    val unit: String = "",
    val info: String = "",
)

internal fun ShoppingListItemUi.amountLabel(): String =
    listOf(quantity.trim(), unit.trim()).filter(String::isNotBlank).joinToString(" ")
