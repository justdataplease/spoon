package com.justdataplease.spoon.ui.calendar

import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Calendar selection survives activity recreation in the retained ViewModel. */
internal class CalendarNavigationState(initialMonth: YearMonth = YearMonth.now()) {
    private val mutableMonth = MutableStateFlow(initialMonth)
    private val mutableSelectedDate = MutableStateFlow<LocalDate?>(null)
    private val mutableNavigationDate = MutableStateFlow<LocalDate?>(null)

    val shownMonth = mutableMonth.asStateFlow()
    val selectedDate = mutableSelectedDate.asStateFlow()
    val navigationDate = mutableNavigationDate.asStateFlow()

    fun openDate(date: LocalDate) {
        mutableMonth.value = YearMonth.from(date)
        mutableSelectedDate.value = date
        mutableNavigationDate.value = date
    }

    fun consumeNavigation(date: LocalDate) {
        // An older Compose effect must not consume a newer widget request.
        if (mutableNavigationDate.value == date) mutableNavigationDate.value = null
    }

    fun showMonth(month: YearMonth) {
        mutableMonth.value = month
        mutableSelectedDate.value = null
        mutableNavigationDate.value = null
    }

    fun selectDate(date: LocalDate) {
        if (YearMonth.from(date) == mutableMonth.value) mutableSelectedDate.value = date
    }
}
