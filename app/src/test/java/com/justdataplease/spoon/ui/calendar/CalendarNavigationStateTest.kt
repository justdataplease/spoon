package com.justdataplease.spoon.ui.calendar

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarNavigationStateTest {
    @Test
    fun `widget date selects its month and day and requests calendar navigation`() {
        val state = CalendarNavigationState(YearMonth.of(2026, 9))
        val date = LocalDate.of(2027, 2, 18)

        state.openDate(date)

        assertEquals(YearMonth.of(2027, 2), state.shownMonth.value)
        assertEquals(date, state.selectedDate.value)
        assertEquals(date, state.navigationDate.value)
    }

    @Test
    fun `consuming navigation retains the selected day for the visible card`() {
        val state = CalendarNavigationState()
        val date = LocalDate.of(2026, 12, 31)
        state.openDate(date)

        state.consumeNavigation(date)

        assertNull(state.navigationDate.value)
        assertEquals(date, state.selectedDate.value)
        assertEquals(YearMonth.of(2026, 12), state.shownMonth.value)
    }

    @Test
    fun `warm repeat of same widget day overrides a later manual day selection`() {
        val state = CalendarNavigationState()
        val widgetDate = LocalDate.of(2026, 9, 10)
        state.openDate(widgetDate)
        state.consumeNavigation(widgetDate)
        state.selectDate(widgetDate.plusDays(1))

        state.openDate(widgetDate)

        assertEquals(widgetDate, state.selectedDate.value)
        assertEquals(widgetDate, state.navigationDate.value)
    }

    @Test
    fun `older navigation effect cannot discard newer widget date`() {
        val state = CalendarNavigationState()
        val oldDate = LocalDate.of(2026, 9, 10)
        val newDate = LocalDate.of(2027, 1, 3)
        state.openDate(oldDate)
        state.openDate(newDate)

        state.consumeNavigation(oldDate)

        assertEquals(newDate, state.navigationDate.value)
        assertEquals(newDate, state.selectedDate.value)
        assertEquals(YearMonth.of(2027, 1), state.shownMonth.value)
    }

    @Test
    fun `manual month navigation clears the previous selected-day card`() {
        val state = CalendarNavigationState()
        state.openDate(LocalDate.of(2026, 9, 10))

        state.showMonth(YearMonth.of(2026, 10))

        assertEquals(YearMonth.of(2026, 10), state.shownMonth.value)
        assertNull(state.selectedDate.value)
        assertNull(state.navigationDate.value)
    }

    @Test
    fun `stale displayed month cannot select a day outside the active month`() {
        val state = CalendarNavigationState(YearMonth.of(2026, 9))
        val selected = LocalDate.of(2026, 9, 10)
        state.selectDate(selected)

        state.selectDate(LocalDate.of(2026, 8, 31))

        assertEquals(selected, state.selectedDate.value)
    }
}
