package com.justdataplease.spoon.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

class CalendarMealWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) = requestWidgetUpdate(context)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        requestWidgetUpdate(context)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) = requestWidgetUpdate(context)

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        CalendarMealWidgetRenderer(context).removeWidgets(appWidgetIds)
    }

    override fun onDisabled(context: Context) = widgetCoordinator(context).widgetsChanged()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            PREVIOUS_MONTH_ACTION, NEXT_MONTH_ACTION, CURRENT_MONTH_ACTION -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val renderer = CalendarMealWidgetRenderer(context)
                if (!renderer.isActive(widgetId)) return
                renderer.changeMonth(widgetId, when (intent.action) {
                    PREVIOUS_MONTH_ACTION -> -1
                    NEXT_MONTH_ACTION -> 1
                    else -> 0
                })
                requestWidgetUpdate(context)
            }
            SELECT_DAY_ACTION -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val renderer = CalendarMealWidgetRenderer(context)
                val date = validatedWidgetCalendarDate(intent.getStringExtra(DATE_EXTRA)) ?: return
                if (!renderer.isActive(widgetId) || !renderer.selectDay(widgetId, date)) return
                requestWidgetUpdate(context)
            }
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                requestWidgetUpdate(context, clockChanged = true)
        }
    }

    companion object {
        const val OPEN_CALENDAR_DAY_ACTION = "com.justdataplease.spoon.widget.OPEN_CALENDAR_DAY"
        const val DATE_EXTRA = "widget_calendar_date"
        internal const val SELECT_DAY_ACTION = "com.justdataplease.spoon.widget.CALENDAR_SELECT_DAY"
        internal const val PREVIOUS_MONTH_ACTION = "com.justdataplease.spoon.widget.CALENDAR_PREVIOUS_MONTH"
        internal const val NEXT_MONTH_ACTION = "com.justdataplease.spoon.widget.CALENDAR_NEXT_MONTH"
        internal const val CURRENT_MONTH_ACTION = "com.justdataplease.spoon.widget.CALENDAR_CURRENT_MONTH"
    }
}
