package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.local.PendingPersonalWrite
import com.justdataplease.spoon.data.local.PersonalCollection
import com.justdataplease.spoon.data.local.PersonalDataSnapshot
import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.MealCourse
import com.justdataplease.spoon.data.model.coursePlan
import com.justdataplease.spoon.data.model.completionTimestamp
import com.justdataplease.spoon.data.model.toCookedMeal
import com.justdataplease.spoon.data.model.withCourse

internal fun normalizeLegacyHistory(snapshot: PersonalDataSnapshot): PersonalDataSnapshot {
    fun stableId(event: CookedMeal): String {
        val key = "${event.date}|${event.recipeId}|${event.completedAtEpochMillis}"
        return "cooked_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
    }
    val history = linkedMapOf<String, CookedMeal>()
    snapshot.cookedHistory.forEach { original ->
        var event = if (original.id.isBlank()) original.copy(id = stableId(original)) else original
        if (history[event.id]?.let { it != event } == true) event = event.copy(id = stableId(event))
        history[event.id] = event
    }
    val plans = snapshot.mealPlans.map { parent ->
        MealCourse.entries.fold(parent) { plan, course ->
            val value = plan.coursePlan(course)
            if (value?.completed != true || value.recipeId.isBlank() || value.completionTimestamp <= 0L) plan
            else {
                val candidate = value.toCookedMeal()
                val matching = history.values.firstOrNull { it.date == value.date && it.recipeId == value.recipeId &&
                    it.completedAtEpochMillis == value.completionTimestamp &&
                    (value.completionEventId.isBlank() || it.id == value.completionEventId) }
                val event = matching ?: candidate.copy(id = value.completionEventId.takeIf { id ->
                    id.isNotBlank() && (history[id] == null || history[id] == candidate)
                } ?: stableId(candidate)).also { history[it.id] = it }
                if (value.completionEventId == event.id && value.completedAtEpochMillis == event.completedAtEpochMillis) plan
                else plan.withCourse(course, value.copy(completionEventId = event.id,
                    completedAtEpochMillis = event.completedAtEpochMillis)).copy(updatedAtEpochMillis = plan.updatedAtEpochMillis)
            }
        }
    }
    return snapshot.copy(mealPlans = plans, cookedHistory = history.values.sortedByDescending { it.completedAtEpochMillis },
        preferences = snapshot.preferences.let { if (it.updatedAtEpochMillis == 0L && it.hasActiveSelections())
            it.normalizedForSync(1L) else it })
}

internal fun syncOrder(write: PendingPersonalWrite): Int = when (write.collection) {
    PersonalCollection.COOKED_HISTORY -> if (write.payload == null) 8 else 0
    PersonalCollection.CUSTOM_RECIPES -> if (write.payload == null) 7 else 1
    PersonalCollection.MEAL_PLANS -> 2
    PersonalCollection.FAVORITES -> 3
    PersonalCollection.SHOPPING -> 4
    PersonalCollection.NOTES -> 5
    PersonalCollection.PREFERENCES -> 6
}

/** Photos are embedded; bound bytes as well as document count below Firestore's request limit. */
internal fun boundedSyncChunks(writes: List<PendingPersonalWrite>): List<List<PendingPersonalWrite>> = buildList {
    var chunk = mutableListOf<PendingPersonalWrite>()
    var bytes = 0L
    var ruleReads = 0
    for (write in writes) {
        val size = (write.payload?.toByteArray(Charsets.UTF_8)?.size ?: 0) + 1024L
        // Completed menus may read three history documents. History deletion reads its plan.
        val reads = if (write.collection == PersonalCollection.MEAL_PLANS) 3
            else if (write.collection == PersonalCollection.COOKED_HISTORY && write.payload == null) 2 else 0
        if (chunk.isNotEmpty() && (chunk.size >= 100 || bytes + size > 4 * 1024 * 1024 || ruleReads + reads > 18)) {
            add(chunk); chunk = mutableListOf(); bytes = 0; ruleReads = 0
        }
        chunk += write; bytes += size; ruleReads += reads
    }
    if (chunk.isNotEmpty()) add(chunk)
}
