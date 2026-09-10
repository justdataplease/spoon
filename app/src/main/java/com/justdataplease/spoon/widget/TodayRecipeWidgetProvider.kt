package com.justdataplease.spoon.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class TodayRecipeWidgetProvider : AppWidgetProvider() {
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

    companion object {
        internal const val REFRESH_WORK = "today-recipe-widget-refresh"
        internal const val PERIODIC_WORK = "today-recipe-widget-periodic"
        const val OPEN_RECIPE_ACTION = "com.justdataplease.spoon.widget.OPEN_RECIPE"
        const val RECIPE_ID_EXTRA = "widget_recipe_id"
    }
}

/** Keeps background work alive long enough to read the offline plan and load its image. */
class TodayRecipeWidgetWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        if (widgetCoordinator(applicationContext).refresh()) Result.success() else Result.retry()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TodayRecipeWidgetEntryPoint {
    fun todayRecipeWidgetCoordinator(): TodayRecipeWidgetCoordinator
}

internal fun widgetCoordinator(context: Context): TodayRecipeWidgetCoordinator =
    EntryPointAccessors.fromApplication(context.applicationContext, TodayRecipeWidgetEntryPoint::class.java)
        .todayRecipeWidgetCoordinator()

/** All launcher variants share one repository observer and refresh worker. */
internal fun requestWidgetUpdate(context: Context, clockChanged: Boolean = false) {
    val coordinator = widgetCoordinator(context)
    if (!coordinator.hasWidgets()) {
        coordinator.stop()
        return
    }
    coordinator.startIfNeeded()
    if (clockChanged) coordinator.clockChanged() else coordinator.requestRefresh()
    WorkManager.getInstance(context).enqueueUniqueWork(
        TodayRecipeWidgetProvider.REFRESH_WORK,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<TodayRecipeWidgetWorker>().build(),
    )
}
