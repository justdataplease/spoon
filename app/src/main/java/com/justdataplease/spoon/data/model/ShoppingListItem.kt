package com.justdataplease.spoon.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import kotlinx.serialization.Serializable

/** One owner-scoped ingredient in the shopping list. */
@Serializable
@IgnoreExtraProperties
data class ShoppingListItem(
    @set:DocumentId var id: String = "",
    val name: String = "",
    val quantity: String = "",
    val unit: String = "",
    val info: String = "",
    val recipeId: String = "",
    val recipeTitle: String = "",
    val sectionTitle: String = "",
    val checked: Boolean = false,
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
)
