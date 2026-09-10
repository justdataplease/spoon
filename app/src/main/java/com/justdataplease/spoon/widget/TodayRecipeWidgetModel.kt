package com.justdataplease.spoon.widget

import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.domain.repository.AccountState
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

internal data class TodayRecipeWidgetContent(
    val recipeId: String,
    val title: String,
    val imageUrl: String,
)

/** Uses the planner's main course and its same catalog-title / saved-title fallback. */
internal fun todayRecipeWidgetContent(
    plans: List<DayMealPlan>,
    recipes: List<Recipe>,
    today: LocalDate,
): TodayRecipeWidgetContent? {
    val plan = plans.firstOrNull { it.date == today.toString() } ?: return null
    val recipeId = validWidgetRecipeId(plan.recipeId) ?: return null
    val recipe = recipes.firstOrNull { it.id == recipeId }
    return TodayRecipeWidgetContent(
        recipeId = recipeId,
        title = recipe?.title ?: plan.recipeTitle,
        imageUrl = recipe?.imageUrl.orEmpty(),
    )
}

internal fun validWidgetRecipeId(raw: String?): String? =
    raw?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) }

internal data class TodayRecipeWidgetSnapshot(
    val plans: List<DayMealPlan>,
    val custom: List<CustomRecipe>,
    val account: AccountState,
    val today: LocalDate,
)

/** Rejects an image that finished loading for an old day, owner, selection or personal photo. */
internal fun canPublishWidgetSnapshot(
    requested: TodayRecipeWidgetSnapshot,
    current: TodayRecipeWidgetSnapshot,
): Boolean {
    if (requested.account != current.account || requested.today != current.today) return false
    val date = requested.today.toString()
    val plan = requested.plans.firstOrNull { it.date == date }
    if (plan != current.plans.firstOrNull { it.date == date }) return false
    val id = plan?.recipeId ?: return true
    return requested.custom.firstOrNull { it.id == id } == current.custom.firstOrNull { it.id == id }
}

internal fun nextWidgetRefreshDelayMillis(now: ZonedDateTime): Long =
    Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone))
        .toMillis().coerceAtLeast(1_000) + 500
