package com.justdataplease.spoon.data.model

import com.google.firebase.firestore.DocumentId
import kotlinx.serialization.Serializable

/** One calendar day. [date] is ISO-8601 and is also used as the Firestore document id. */
@Serializable
data class DayMealPlan(
    @set:DocumentId var id: String = "",
    val date: String = "",
    val category: String = "",
    val recipeId: String = "",
    val recipeTitle: String = "",
    val filters: RecipeFilters = RecipeFilters(),
    val completed: Boolean = false,
    /** Id of the immutable history event representing this exact completion. */
    val completionEventId: String = "",
    val updatedAtEpochMillis: Long = 0L,
    val locked: Boolean = false,
)
