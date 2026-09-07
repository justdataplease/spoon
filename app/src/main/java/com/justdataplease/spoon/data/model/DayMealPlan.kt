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
    val completedAtEpochMillis: Long = 0L,
    val side: MealCoursePlan? = null,
    val dessert: MealCoursePlan? = null,
)

/** A menu course has its own selection, filters, lock, and cooking state. */
@Serializable
data class MealCoursePlan(
    val category: String = "any",
    val recipeId: String = "",
    val recipeTitle: String = "",
    val filters: RecipeFilters = RecipeFilters(),
    val completed: Boolean = false,
    val completionEventId: String = "",
    val completedAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
    val locked: Boolean = false,
)

enum class MealCourse(val label: String) {
    MAIN("Κυρίως"), SIDE("Συνοδευτικό"), DESSERT("Γλυκό");
}

internal val DayMealPlan.completionTimestamp: Long
    get() = if (completedAtEpochMillis > 0L) completedAtEpochMillis else updatedAtEpochMillis

internal fun DayMealPlan.coursePlan(course: MealCourse): DayMealPlan? {
    if (course == MealCourse.MAIN) return copy(side = null, dessert = null)
    val stored = if (course == MealCourse.SIDE) side else dessert
    return stored?.let {
        DayMealPlan(id = id, date = date, category = it.category, recipeId = it.recipeId,
            recipeTitle = it.recipeTitle, filters = it.filters, completed = it.completed,
            completionEventId = it.completionEventId, completedAtEpochMillis = it.completedAtEpochMillis,
            updatedAtEpochMillis = it.updatedAtEpochMillis, locked = it.locked)
    }
}

internal fun DayMealPlan.withCourse(course: MealCourse, changed: DayMealPlan): DayMealPlan {
    if (course == MealCourse.MAIN) return changed.copy(
        side = side, dessert = dessert,
        completedAtEpochMillis = if (changed.completed) changed.completionTimestamp else 0L,
        updatedAtEpochMillis = maxOf(updatedAtEpochMillis + 1L, changed.updatedAtEpochMillis),
    )
    val stored = MealCoursePlan(category = changed.category, recipeId = changed.recipeId,
        recipeTitle = changed.recipeTitle, filters = changed.filters, completed = changed.completed,
        completionEventId = changed.completionEventId, completedAtEpochMillis = changed.completedAtEpochMillis,
        updatedAtEpochMillis = changed.updatedAtEpochMillis, locked = changed.locked)
    return copy(
        completedAtEpochMillis = if (completed) completionTimestamp else 0L,
        updatedAtEpochMillis = maxOf(updatedAtEpochMillis + 1L, changed.updatedAtEpochMillis),
        side = if (course == MealCourse.SIDE) stored else side,
        dessert = if (course == MealCourse.DESSERT) stored else dessert,
    )
}

internal fun DayMealPlan.allCourses(): List<DayMealPlan> = MealCourse.entries.mapNotNull(::coursePlan)
