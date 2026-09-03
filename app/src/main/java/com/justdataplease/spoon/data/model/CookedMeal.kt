package com.justdataplease.spoon.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import kotlinx.serialization.Serializable

/** Immutable snapshot of one explicit "cooked" action. */
@Serializable
@IgnoreExtraProperties
data class CookedMeal(
    @set:DocumentId var id: String = "",
    val date: String = "",
    val recipeId: String = "",
    val recipeTitle: String = "",
    val completedAtEpochMillis: Long = 0L,
)

/** Stable id for a completion event; unlike the plan id, more than one event may share a date. */
internal fun cookedMealEventId(date: String, completedAtEpochMillis: Long): String =
    "cooked_${date.replace("-", "")}_$completedAtEpochMillis"

/**
 * Preserves every stored event and only synthesizes missing legacy events from completed plans.
 * The synthesized event lets old local installs migrate without allowing a later reroll to erase
 * something the user already cooked.
 */
internal fun mergeCookedHistory(
    plans: List<DayMealPlan>,
    storedHistory: List<CookedMeal>,
): List<CookedMeal> {
    val normalizedStored = storedHistory.map(CookedMeal::withStableId)
    val storedKeys = normalizedStored.mapTo(mutableSetOf(), CookedMeal::completionKey)
    val legacyEvents = plans.asSequence()
        .filter { plan -> plan.completed && plan.recipeId.isNotBlank() && plan.updatedAtEpochMillis > 0L }
        .filter { plan ->
            Triple(plan.date, plan.recipeId, plan.updatedAtEpochMillis) !in storedKeys
        }
        .map { plan -> plan.toCookedMeal() }
    return (normalizedStored.asSequence() + legacyEvents)
        .distinctBy(CookedMeal::id)
        .sortedByDescending(CookedMeal::completedAtEpochMillis)
        .toList()
}

internal fun DayMealPlan.toCookedMeal(): CookedMeal = CookedMeal(
    id = cookedMealEventId(date, updatedAtEpochMillis),
    date = date,
    recipeId = recipeId,
    recipeTitle = recipeTitle,
    completedAtEpochMillis = updatedAtEpochMillis,
)

internal fun CookedMeal.matchesActiveCompletion(plan: DayMealPlan): Boolean =
    plan.completed &&
        date == plan.date &&
        recipeId == plan.recipeId &&
        completedAtEpochMillis == plan.updatedAtEpochMillis

private fun CookedMeal.withStableId(): CookedMeal =
    if (id.isNotBlank()) this else copy(id = cookedMealEventId(date, completedAtEpochMillis))

private fun CookedMeal.completionKey(): Triple<String, String, Long> =
    Triple(date, recipeId, completedAtEpochMillis)
