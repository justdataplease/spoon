package com.justdataplease.spoon.data.model

import com.google.firebase.firestore.DocumentId
import kotlinx.serialization.Serializable

@Serializable
data class FavoriteRecipe(
    @set:DocumentId var id: String = "",
    val recipeId: String = "",
    val addedAtEpochMillis: Long = 0L,
    val includedInWeeklyPlan: Boolean = true,
    /** Zero distinguishes a legacy/default add from an explicit weekly-plan choice. */
    val weeklyPlanUpdatedAtEpochMillis: Long = 0L,
)
