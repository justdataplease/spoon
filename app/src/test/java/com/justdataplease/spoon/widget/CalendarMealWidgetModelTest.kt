package com.justdataplease.spoon.widget

import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.domain.repository.AccountState
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarMealWidgetModelTest {
    private val today = LocalDate.of(2026, 9, 10)

    @Test fun onlyCanonicalValidDatesInsideCalendarRangeCanNavigate() {
        assertEquals(today, validatedWidgetCalendarDate("2026-09-10"))
        assertEquals(LocalDate.of(2024, 2, 29), validatedWidgetCalendarDate("2024-02-29"))
        assertEquals(LocalDate.of(1900, 1, 1), validatedWidgetCalendarDate("1900-01-01"))
        assertEquals(LocalDate.of(2100, 12, 31), validatedWidgetCalendarDate("2100-12-31"))
        for (raw in listOf(null, "", "2026-9-10", "2026-02-29", "2026-04-31", "2026-00-10",
            "1899-12-31", "2101-01-01", "2026-09-10T12:00", "../2026-09-10", " 2026-09-10")) {
            assertNull(raw, validatedWidgetCalendarDate(raw))
        }
    }

    @Test fun mondayFirstGridPreservesMonthEdgesAndLeapDay() {
        val september = calendarWidgetMonth(YearMonth.of(2026, 9), today, emptyList())
        assertEquals(42, september.days.size)
        assertNull(september.days.first().date)
        assertEquals(LocalDate.of(2026, 9, 1), september.days[1].date)
        assertEquals(30, september.days.count { it.date != null })
        assertEquals(1, september.days.count { it.isToday })
        assertEquals(today, september.days.single { it.isToday }.date)
        val february = calendarWidgetMonth(YearMonth.of(2024, 2), today, emptyList())
        assertEquals(29, february.days.count { it.date != null })
        assertEquals(LocalDate.of(2024, 2, 29), february.days.mapNotNull { it.date }.last())
        assertTrue(february.days.none { it.isToday })
        assertEquals(LocalDate.of(2026, 11, 1), calendarWidgetMonth(YearMonth.of(2026, 11), today, emptyList()).days[6].date)
    }

    @Test fun markersUseSameMainCourseAndCompletionAsAppCalendar() {
        val plans = listOf(
            DayMealPlan(date = "2026-09-10", recipeId = "cookpad_123", recipeTitle = "Σαλάτα"),
            DayMealPlan(date = "2026-09-11", recipeId = "custom_private", recipeTitle = "Σπιτικό", completed = true),
            DayMealPlan(date = "2026-09-12", recipeTitle = "Missing recipe", completed = true),
            DayMealPlan(date = "2026-09-13", recipeId = "../invalid", recipeTitle = "Invalid"),
            DayMealPlan(date = "bad date", recipeId = "akis_1"),
        )
        val days = calendarWidgetMonth(YearMonth.from(today), today, plans).days
        val tenth = days.single { it.date == today }
        assertTrue(tenth.isToday)
        assertEquals("Σαλάτα", tenth.recipeTitle)
        assertFalse(tenth.completed)
        assertTrue(days.single { it.date == today.plusDays(1) }.completed)
        assertFalse(days.single { it.date == today.plusDays(2) }.hasMeal)
        assertFalse(days.single { it.date == today.plusDays(3) }.hasMeal)
        assertEquals(2, days.count { it.hasMeal })
    }

    @Test fun monthNavigationCrossesYearsButStaysInsideValidDateRange() {
        assertEquals(YearMonth.of(2027, 1), shiftedWidgetCalendarMonth(YearMonth.of(2026, 12), 1))
        assertEquals(YearMonth.of(2025, 12), shiftedWidgetCalendarMonth(YearMonth.of(2026, 1), -1))
        assertEquals(YearMonth.of(1900, 1), shiftedWidgetCalendarMonth(YearMonth.of(1900, 1), -1))
        assertEquals(YearMonth.of(2100, 12), shiftedWidgetCalendarMonth(YearMonth.of(2100, 12), 1))
        assertEquals(YearMonth.from(today), shiftedWidgetCalendarMonth(YearMonth.from(today), Int.MAX_VALUE))
        for (raw in listOf(null, "", "2026-9", "2026-13", "1899-01", "2101-01", "2026-09-10"))
            assertNull(validatedWidgetCalendarMonth(raw))
    }

    @Test fun planUpdatesDoNotChooseTheViewedMonthOrKeepStaleMealMarkers() {
        val viewed = YearMonth.of(2026, 12)
        val original = calendarWidgetMonth(viewed, today, listOf(DayMealPlan(
            date = "2026-12-10", recipeId = "akis_1", recipeTitle = "Γεύμα")))
        val cleared = calendarWidgetMonth(viewed, today.plusDays(1), emptyList())
        assertEquals(original.month, cleared.month)
        assertEquals(1, original.days.count { it.hasMeal })
        assertEquals(0, cleared.days.count { it.hasMeal })
    }

    @Test fun accessibilityIncludesFullDateMealAndTodayWithoutAnnouncingBlankCells() {
        val description = calendarWidgetDayDescription(CalendarWidgetDay(today, true, "Φασολάδα", true))
        assertTrue(description.contains("10 Σεπτεμβρίου 2026"))
        assertTrue(description.contains("Σήμερα"))
        assertTrue(description.contains("Φασολάδα"))
        assertTrue(description.contains("Μαγειρεμένο"))
        assertTrue(calendarWidgetDayDescription(CalendarWidgetDay(today)).contains("Χωρίς προγραμματισμένο γεύμα"))
        assertEquals("", calendarWidgetDayDescription(CalendarWidgetDay(null)))
    }

    @Test fun calendarRejectsOldOwnersAndAnyChangedDayAcrossTheViewedPlan() {
        val original = TodayRecipeWidgetSnapshot(
            plans = listOf(DayMealPlan(date = today.plusDays(5).toString(), recipeId = "akis_1")),
            custom = emptyList(), account = AccountState.Anonymous("owner-a"), today = today,
        )
        assertTrue(canPublishCalendarWidgetSnapshot(original, original))
        for (changed in listOf(
            original.copy(account = AccountState.SignedOut),
            original.copy(account = AccountState.Anonymous("owner-b")),
            original.copy(today = today.plusDays(1)),
            original.copy(plans = emptyList()),
            original.copy(plans = original.plans.map { it.copy(completed = true) }),
        )) assertFalse(canPublishCalendarWidgetSnapshot(original, changed))
    }
}
