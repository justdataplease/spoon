package com.justdataplease.spoon.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import kotlinx.serialization.Serializable

/** History projection maintained from a day's completion state. */
@Serializable
@IgnoreExtraProperties
data class CookedMeal(
    @set:DocumentId var id: String = "",
    val date: String = "",
    val recipeId: String = "",
    val recipeTitle: String = "",
    val completedAtEpochMillis: Long = 0L,
)

/**
 * Uses completed plans as a compatibility source for users who marked meals before the dedicated
 * history collection existed. Stored history contributes the original completion timestamp.
 */
internal fun mergeCookedHistory(
    plans: List<DayMealPlan>,
    storedHistory: List<CookedMeal>,
): List<CookedMeal> {
    val storedByDate = storedHistory.associateBy(CookedMeal::date)
    return plans.asSequence()
        .filter(DayMealPlan::completed)
        .map { plan ->
            storedByDate[plan.date]
                ?.takeIf {
                    it.recipeId == plan.recipeId &&
                        it.recipeTitle == plan.recipeTitle &&
                        it.completedAtEpochMillis == plan.updatedAtEpochMillis
                }
                ?: CookedMeal(
                    id = plan.date,
                    date = plan.date,
                    recipeId = plan.recipeId,
                    recipeTitle = plan.recipeTitle,
                    completedAtEpochMillis = plan.updatedAtEpochMillis,
                )
        }
        .sortedByDescending(CookedMeal::completedAtEpochMillis)
        .toList()
}
