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
    override fun onEnabled(context: Context) = requestUpdate(context)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        requestUpdate(context)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) = requestUpdate(context)

    override fun onDisabled(context: Context) {
        widgetCoordinator(context).stop()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            requestUpdate(context, clockChanged = true)
        }
    }

    private fun requestUpdate(context: Context, clockChanged: Boolean = false) {
        val coordinator = widgetCoordinator(context)
        coordinator.startIfNeeded()
        if (clockChanged) coordinator.clockChanged() else coordinator.requestRefresh()
        WorkManager.getInstance(context).enqueueUniqueWork(
            REFRESH_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TodayRecipeWidgetWorker>().build(),
        )
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
