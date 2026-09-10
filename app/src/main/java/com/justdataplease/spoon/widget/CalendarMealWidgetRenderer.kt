package com.justdataplease.spoon.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.justdataplease.spoon.MainActivity
import com.justdataplease.spoon.R
import com.justdataplease.spoon.data.model.DayMealPlan
import java.time.LocalDate
import java.time.YearMonth

/** Persists only the month being viewed, never plans, account data or recipe names. */
internal class CalendarMealWidgetRenderer(private val context: Context) {
    private val manager get() = AppWidgetManager.getInstance(context)
    private val preferences get() = context.getSharedPreferences("calendar-widget-display", Context.MODE_PRIVATE)

    fun widgetIds(): IntArray = manager.getAppWidgetIds(ComponentName(context, CalendarMealWidgetProvider::class.java))
    fun isActive(widgetId: Int): Boolean = widgetId in widgetIds()

    fun changeMonth(widgetId: Int, direction: Int) {
        val editor = preferences.edit()
        if (direction == 0) editor.remove(monthKey(widgetId))
        else editor.putString(monthKey(widgetId), shiftedWidgetCalendarMonth(shownMonth(widgetId, LocalDate.now()), direction).toString())
        editor.apply()
    }

    fun removeWidgets(ids: IntArray) {
        preferences.edit().apply { ids.forEach { remove(monthKey(it)) } }.apply()
    }

    private fun shownMonth(id: Int, today: LocalDate): YearMonth =
        validatedWidgetCalendarMonth(preferences.getString(monthKey(id), null)) ?: YearMonth.from(today)

    fun publish(plans: List<DayMealPlan>, today: LocalDate) {
        for (widgetId in widgetIds()) {
            val month = calendarWidgetMonth(shownMonth(widgetId, today), today, plans)
            val views = RemoteViews(context.packageName, R.layout.calendar_meal_widget)
            views.setTextViewText(R.id.calendar_widget_month, month.label)
            views.setContentDescription(R.id.calendar_widget_month,
                context.getString(R.string.calendar_widget_current_month, month.label))
            views.setOnClickPendingIntent(R.id.calendar_widget_previous, monthIntent(widgetId, CalendarMealWidgetProvider.PREVIOUS_MONTH_ACTION))
            views.setOnClickPendingIntent(R.id.calendar_widget_next, monthIntent(widgetId, CalendarMealWidgetProvider.NEXT_MONTH_ACTION))
            views.setOnClickPendingIntent(R.id.calendar_widget_month, monthIntent(widgetId, CalendarMealWidgetProvider.CURRENT_MONTH_ACTION))
            views.removeAllViews(R.id.calendar_widget_weeks)
            for (week in month.days.chunked(7)) {
                val row = RemoteViews(context.packageName, R.layout.calendar_widget_week)
                for (day in week) {
                    val cell = RemoteViews(context.packageName, R.layout.calendar_widget_day)
                    cell.setViewVisibility(R.id.calendar_widget_day_content, if (day.date == null) View.INVISIBLE else View.VISIBLE)
                    day.date?.let { date ->
                        cell.setTextViewText(R.id.calendar_widget_day_number, date.dayOfMonth.toString())
                        cell.setInt(R.id.calendar_widget_day_content, "setBackgroundResource",
                            if (day.isToday) R.drawable.calendar_widget_today else R.drawable.calendar_widget_day_background)
                        cell.setTextColor(R.id.calendar_widget_day_number, if (day.isToday) Color.WHITE else INK)
                        cell.setTextViewText(R.id.calendar_widget_day_marker, if (day.completed) "✓" else "●")
                        cell.setTextColor(R.id.calendar_widget_day_marker, if (day.isToday) Color.WHITE else if (day.completed) SAGE else PAPRIKA)
                        cell.setViewVisibility(R.id.calendar_widget_day_marker, if (day.hasMeal) View.VISIBLE else View.INVISIBLE)
                        cell.setContentDescription(R.id.calendar_widget_day_content, calendarWidgetDayDescription(day))
                        cell.setOnClickPendingIntent(R.id.calendar_widget_day_content, dayIntent(widgetId, date))
                    }
                    row.addView(R.id.calendar_widget_week, cell)
                }
                views.addView(R.id.calendar_widget_weeks, row)
            }
            manager.updateAppWidget(widgetId, views)
        }
    }

    private fun monthIntent(widgetId: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, widgetId,
        Intent(context, CalendarMealWidgetProvider::class.java).apply {
            this.action = action
            data = Uri.parse("peltes-widget://calendar/$widgetId/${action.substringAfterLast('.')}")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun dayIntent(widgetId: Int, date: LocalDate): PendingIntent = PendingIntent.getActivity(
        context, widgetId,
        Intent(context, MainActivity::class.java).apply {
            action = CalendarMealWidgetProvider.OPEN_CALENDAR_DAY_ACTION
            data = Uri.parse("peltes-widget://calendar/$widgetId/day/$date")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CalendarMealWidgetProvider.DATE_EXTRA, date.toString())
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun monthKey(id: Int) = "month_$id"

    private companion object {
        val INK = Color.rgb(45, 37, 32)
        val PAPRIKA = Color.rgb(184, 68, 46)
        val SAGE = Color.rgb(47, 109, 98)
    }
}
