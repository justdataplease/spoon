package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.matchesActiveCompletion
import com.justdataplease.spoon.data.model.mergeCookedHistory
import com.justdataplease.spoon.data.model.toCookedMeal

/**
 * Pure local projection of a completion toggle. Firestore can persist the resulting plan and
 * history changes as one offline-capable write batch without needing an online transaction read.
 */
internal data class MealCompletionProjection(
    val plans: List<DayMealPlan>,
    val storedHistory: List<CookedMeal>,
    val changedPlan: DayMealPlan?,
    val historyToCreate: CookedMeal?,
    val historyIdsToDelete: Set<String>,
)

internal fun projectMealCompletion(
    plans: List<DayMealPlan>,
    storedHistory: List<CookedMeal>,
    date: String,
    completed: Boolean,
    nowEpochMillis: Long,
    newCompletionEventId: String,
): MealCompletionProjection {
    val current = checkNotNull(plans.firstOrNull { it.date == date }) {
        "Cannot complete a meal plan that does not exist: $date"
    }
    if (current.completed == completed) {
        return MealCompletionProjection(
            plans = plans,
            storedHistory = storedHistory,
            changedPlan = null,
            historyToCreate = null,
            historyIdsToDelete = emptySet(),
        )
    }

    val changed = current.copy(
        id = current.id.ifBlank { current.date },
        completed = completed,
        completionEventId = if (completed) newCompletionEventId else "",
        updatedAtEpochMillis = nowEpochMillis,
    )
    val updatedPlans = (plans.filterNot { it.date == date } + changed)
        .sortedBy(DayMealPlan::date)

    if (completed) {
        val history = changed.toCookedMeal()
        return MealCompletionProjection(
            plans = updatedPlans,
            storedHistory = (storedHistory.filterNot { it.id == history.id } + history)
                .sortedByDescending(CookedMeal::completedAtEpochMillis),
            changedPlan = changed,
            historyToCreate = history,
            historyIdsToDelete = emptySet(),
        )
    }

    val matchingStoredIds = storedHistory.asSequence()
        .filter { event -> event.matchesActiveCompletion(current) }
        .map(CookedMeal::id)
        .filter(String::isNotBlank)
        .toMutableSet()
    // A non-blank event id is guaranteed by the Firestore plan rules to point to the active
    // history row, even if that row has not reached the local query snapshot yet.
    current.completionEventId.takeIf(String::isNotBlank)?.let(matchingStoredIds::add)

    return MealCompletionProjection(
        plans = updatedPlans,
        storedHistory = storedHistory.filterNot { it.id in matchingStoredIds },
        changedPlan = changed,
        historyToCreate = null,
        historyIdsToDelete = matchingStoredIds,
    )
}

/** Local projection for deleting one visible history row, including legacy synthesized rows. */
internal data class HistoryDeletionProjection(
    val plans: List<DayMealPlan>,
    val storedHistory: List<CookedMeal>,
    val changedPlan: DayMealPlan?,
    val historyIdToDelete: String?,
)

internal fun projectHistoryDeletion(
    plans: List<DayMealPlan>,
    storedHistory: List<CookedMeal>,
    historyId: String,
    nowEpochMillis: Long,
): HistoryDeletionProjection {
    val visibleEvent = mergeCookedHistory(plans, storedHistory)
        .firstOrNull { it.id == historyId }
        ?: return HistoryDeletionProjection(plans, storedHistory, null, null)
    val currentPlan = plans.firstOrNull { it.date == visibleEvent.date }
    val changedPlan = currentPlan
        ?.takeIf(visibleEvent::matchesActiveCompletion)
        ?.copy(
            id = currentPlan.id.ifBlank { currentPlan.date },
            completed = false,
            completionEventId = "",
            updatedAtEpochMillis = nowEpochMillis,
        )
    val updatedPlans = if (changedPlan == null) {
        plans
    } else {
        plans.map { plan -> if (plan.date == changedPlan.date) changedPlan else plan }
    }
    val storedRowExists = storedHistory.any { it.id == historyId }
    val serverRowGuaranteed = currentPlan?.completionEventId
        ?.takeIf(String::isNotBlank) == historyId

    return HistoryDeletionProjection(
        plans = updatedPlans,
        storedHistory = storedHistory.filterNot { it.id == historyId },
        changedPlan = changedPlan,
        // Legacy rows synthesized from an old plan do not necessarily exist as documents.
        historyIdToDelete = historyId.takeIf { storedRowExists || serverRowGuaranteed },
    )
}
