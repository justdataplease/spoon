package com.justdataplease.spoon.widget

import com.justdataplease.spoon.data.model.DayMealPlan
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WidgetGreekLocale = Locale.forLanguageTag("el-GR")
private val WidgetFullDate = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", WidgetGreekLocale)
private val WidgetMonth = DateTimeFormatter.ofPattern("LLLL yyyy", WidgetGreekLocale)

internal data class CalendarWidgetDay(
    val date: LocalDate?,
    val isToday: Boolean = false,
    val recipeTitle: String? = null,
    val completed: Boolean = false,
) {
    val hasMeal: Boolean get() = recipeTitle != null
}

internal data class CalendarWidgetMonth(
    val month: YearMonth,
    val label: String,
    val days: List<CalendarWidgetDay>,
)

/** Keeps widget navigation canonical and inside the same ordinary calendar range. */
internal fun validatedWidgetCalendarDate(raw: String?): LocalDate? {
    if (raw == null || !raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return null
    return runCatching { LocalDate.parse(raw) }.getOrNull()?.takeIf {
        it.year in 1900..2100 && it.toString() == raw
    }
}

internal fun validatedWidgetCalendarMonth(raw: String?): YearMonth? {
    if (raw == null || !raw.matches(Regex("[0-9]{4}-[0-9]{2}"))) return null
    return runCatching { YearMonth.parse(raw) }.getOrNull()?.takeIf { it.year in 1900..2100 }
}

internal fun shiftedWidgetCalendarMonth(month: YearMonth, direction: Int): YearMonth =
    if (direction in listOf(-1, 1)) month.plusMonths(direction.toLong())
        .takeIf { it.year in 1900..2100 } ?: month else month

/** Monday-first, six complete weeks. Empty edge cells never open an unrelated date. */
internal fun calendarWidgetMonth(
    month: YearMonth,
    today: LocalDate,
    plans: List<DayMealPlan>,
): CalendarWidgetMonth {
    val byDate = plans.mapNotNull { plan ->
        validatedWidgetCalendarDate(plan.date)?.let { it to plan }
    }.toMap()
    val leading = month.atDay(1).dayOfWeek.value - 1
    return CalendarWidgetMonth(
        month = month,
        label = month.format(WidgetMonth).replaceFirstChar { it.titlecase(WidgetGreekLocale) },
        days = List(42) { index ->
            val number = index - leading + 1
            if (number !in 1..month.lengthOfMonth()) CalendarWidgetDay(null)
            else {
                val date = month.atDay(number)
                val plan = byDate[date]?.takeIf { validWidgetRecipeId(it.recipeId) != null }
                CalendarWidgetDay(date, date == today, plan?.recipeTitle, plan?.completed == true)
            }
        },
    )
}

internal fun calendarWidgetDayDescription(day: CalendarWidgetDay): String {
    val date = day.date ?: return ""
    return buildList {
        add(date.format(WidgetFullDate).replaceFirstChar { it.titlecase(WidgetGreekLocale) })
        if (day.isToday) add("Σήμερα")
        if (day.hasMeal) {
            add(day.recipeTitle?.takeIf(String::isNotBlank)?.let { "Γεύμα: $it" } ?: "Προγραμματισμένο γεύμα")
            add(if (day.completed) "Μαγειρεμένο" else "Προγραμματισμένο")
        } else add("Χωρίς προγραμματισμένο γεύμα")
        add("Άνοιγμα ημέρας")
    }.joinToString(". ")
}

internal fun canPublishCalendarWidgetSnapshot(
    requested: TodayRecipeWidgetSnapshot,
    current: TodayRecipeWidgetSnapshot,
): Boolean = requested.account == current.account && requested.today == current.today && requested.plans == current.plans
