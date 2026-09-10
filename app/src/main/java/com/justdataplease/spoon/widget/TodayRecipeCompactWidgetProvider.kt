package com.justdataplease.spoon.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** A compact photo-card option using the same owner-scoped plan and refresh lifecycle. */
class TodayRecipeCompactWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) = requestWidgetUpdate(context)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        requestWidgetUpdate(context)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) = requestWidgetUpdate(context)

    override fun onDisabled(context: Context) {
        widgetCoordinator(context).widgetsChanged()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            requestWidgetUpdate(context, clockChanged = true)
        }
    }
}
