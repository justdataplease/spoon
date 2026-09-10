package com.justdataplease.spoon.widget

import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.MealCoursePlan
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.domain.repository.AccountState
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayRecipeWidgetModelTest {
    private val today = LocalDate.of(2026, 9, 10)

    @Test fun showsTodaysMainCourseWithTheSameCatalogTitleAndImageAsPlanner() {
        val plans = listOf(
            DayMealPlan(date = "2026-09-09", recipeId = "akis_yesterday", recipeTitle = "Yesterday"),
            DayMealPlan(date = today.toString(), recipeId = "argiro_17823", recipeTitle = "Saved title",
                side = MealCoursePlan(recipeId = "akis_side", recipeTitle = "Side")),
            DayMealPlan(date = "2026-09-11", recipeId = "akis_tomorrow", recipeTitle = "Tomorrow"),
        )
        val recipe = Recipe(id = "argiro_17823", title = "Current title", imageUrl = "https://example.com/photo.jpg")
        assertEquals(TodayRecipeWidgetContent(recipe.id, recipe.title, recipe.imageUrl), todayRecipeWidgetContent(plans, listOf(recipe), today))
    }

    @Test fun rolloverSelectsNewDayAndDoesNotKeepYesterdayWhenTodayIsEmpty() {
        val plans = listOf(DayMealPlan(date = today.toString(), recipeId = "akis_today", recipeTitle = "Today"))
        assertEquals("Today", todayRecipeWidgetContent(plans, emptyList(), today)?.title)
        assertNull(todayRecipeWidgetContent(plans, emptyList(), today.plusDays(1)))
    }

    @Test fun missingCatalogEntryUsesSavedPlanTitleAndNoStaleImage() {
        val plans = listOf(DayMealPlan(date = today.toString(), recipeId = "akis_today", recipeTitle = "Saved"))
        assertEquals(TodayRecipeWidgetContent("akis_today", "Saved", ""), todayRecipeWidgetContent(plans, listOf(Recipe(id = "akis_other", imageUrl = "other")), today))
    }

    @Test fun missingMainCourseDoesNotFallBackToADifferentCourse() {
        assertNull(todayRecipeWidgetContent(listOf(DayMealPlan(date = today.toString(), side = MealCoursePlan(recipeId = "akis_side"))), emptyList(), today))
        assertNull(todayRecipeWidgetContent(emptyList(), emptyList(), today))
    }

    @Test fun privateRecipesCanOpenLocallyWhileMalformedIdsAreRejected() {
        val id = "custom_7ba0c2ef-18b0-4bb9-81ac-62e89c4d44b0"
        assertEquals(id, validWidgetRecipeId(id))
        for (value in listOf(null, "", "../private", "akis/123", "a".repeat(129))) assertNull(validWidgetRecipeId(value))
        assertEquals("Personal", todayRecipeWidgetContent(listOf(DayMealPlan(date = today.toString(), recipeId = id)), listOf(Recipe(id = id, title = "Personal", imageUrl = "data:image/jpeg;base64,photo")), today)?.title)
    }

    @Test fun staleImageCannotPublishAfterSignOutAccountSwitchDateChangeOrRecipeReplacement() {
        val plan = DayMealPlan(date = today.toString(), recipeId = "akis_today")
        val original = TodayRecipeWidgetSnapshot(listOf(plan), emptyList(), AccountState.Anonymous("owner-a"), today)
        assertTrue(canPublishWidgetSnapshot(original, original))
        for (changed in listOf(
            original.copy(account = AccountState.SignedOut),
            original.copy(account = AccountState.Anonymous("owner-b")),
            original.copy(today = today.plusDays(1)),
            original.copy(plans = emptyList()),
            original.copy(plans = listOf(plan.copy(recipeId = "argiro_other"))),
        )) assertFalse(canPublishWidgetSnapshot(original, changed))
    }

    @Test fun replacingOrDeletingAPersonalPhotoRejectsTheOldDownload() {
        val custom = CustomRecipe(id = "custom_personal", title = "My recipe", photoDataUri = "old photo")
        val original = TodayRecipeWidgetSnapshot(listOf(DayMealPlan(date = today.toString(), recipeId = custom.id)), listOf(custom), AccountState.Anonymous("owner-a"), today)
        assertFalse(canPublishWidgetSnapshot(original, original.copy(custom = listOf(custom.copy(photoDataUri = "new photo")))))
        assertFalse(canPublishWidgetSnapshot(original, original.copy(custom = emptyList())))
    }

    @Test fun midnightRefreshFollowsLocalCalendarAcrossDaylightSavingChanges() {
        val zone = ZoneId.of("Europe/Athens")
        assertEquals(23 * 60 * 60 * 1_000L + 500, nextWidgetRefreshDelayMillis(LocalDate.of(2026, 3, 29).atStartOfDay(zone)))
        assertEquals(25 * 60 * 60 * 1_000L + 500, nextWidgetRefreshDelayMillis(LocalDate.of(2026, 10, 25).atStartOfDay(zone)))
    }
}
