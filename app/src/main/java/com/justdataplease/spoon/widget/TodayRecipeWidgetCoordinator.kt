package com.justdataplease.spoon.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.justdataplease.spoon.MainActivity
import com.justdataplease.spoon.R
import com.justdataplease.spoon.domain.repository.SpoonRepository
import com.justdataplease.spoon.data.model.isIntentionallyBlank
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Observes the same owner-scoped repository as the planner; stores no separate personal cache. */
@Singleton
class TodayRecipeWidgetCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repositoryProvider: Lazy<SpoonRepository>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshes = MutableStateFlow(0L)
    private val generation = AtomicLong()
    private var observer: Job? = null
    private var midnight: Job? = null
    private val manager get() = AppWidgetManager.getInstance(context)
    private val repository get() = repositoryProvider.get()
    private val calendarRenderer = CalendarMealWidgetRenderer(context)
    private fun widgetIds() = manager.getAppWidgetIds(ComponentName(context, TodayRecipeWidgetProvider::class.java)) +
        manager.getAppWidgetIds(ComponentName(context, TodayRecipeCompactWidgetProvider::class.java))
    internal fun hasWidgets() = widgetIds().isNotEmpty() || calendarRenderer.widgetIds().isNotEmpty()

    @Synchronized
    fun widgetsChanged() {
        if (!hasWidgets()) stop()
        else {
            startIfNeeded()
            requestRefresh()
        }
    }

    @Synchronized
    fun startIfNeeded() {
        if (!hasWidgets() || observer?.isActive == true) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TodayRecipeWidgetProvider.PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TodayRecipeWidgetWorker>(30, TimeUnit.MINUTES).build(),
        )
        observer = scope.launch {
            combine(repository.mealPlans, repository.customRecipes, repository.accountState, refreshes) {
                    plans, custom, account, _ ->
                TodayRecipeWidgetSnapshot(plans, custom, account, LocalDate.now())
            }.collectLatest { snapshot ->
                try {
                    render(snapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Could not refresh today's recipe widget", error)
                }
            }
        }
        restartMidnightRefresh()
    }

    private fun restartMidnightRefresh() {
        midnight?.cancel()
        midnight = scope.launch {
            while (isActive) {
                delay(nextWidgetRefreshDelayMillis(ZonedDateTime.now()))
                requestRefresh()
            }
        }
    }

    @Synchronized
    fun clockChanged() {
        if (observer?.isActive == true) restartMidnightRefresh()
        requestRefresh()
    }

    fun requestRefresh() = refreshes.update { it + 1 }

    @Synchronized
    fun stop() {
        generation.incrementAndGet()
        observer?.cancel()
        midnight?.cancel()
        observer = null
        midnight = null
        WorkManager.getInstance(context).cancelUniqueWork(TodayRecipeWidgetProvider.PERIODIC_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(TodayRecipeWidgetProvider.REFRESH_WORK)
    }

    suspend fun refresh(): Boolean {
        if (!hasWidgets()) {
            stop()
            return true
        }
        startIfNeeded()
        return try {
            // Firestore hydrates its existing disk cache; a network connection is not required.
            withTimeoutOrNull(20_000) { repository.ensureReady() }
            render(TodayRecipeWidgetSnapshot(
                plans = repository.mealPlans.first(),
                custom = repository.customRecipes.first(),
                account = repository.accountState.value,
                today = LocalDate.now(),
            ))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not refresh today's recipe widget", error)
            false
        }
    }

    private suspend fun render(snapshot: TodayRecipeWidgetSnapshot) = coroutineScope {
        val request = generation.incrementAndGet()
        if (calendarRenderer.widgetIds().isNotEmpty()) launch {
            val customIds = snapshot.custom.mapTo(mutableSetOf()) { it.id }
            val ids = snapshot.plans.mapNotNull { validWidgetRecipeId(it.recipeId) }.toSet() - customIds
            val recipes = if (ids.isEmpty()) emptyList() else repository.getRecipesByIds(ids)
            calendarRenderer.publish(snapshot, recipes) {
                currentCoroutineContext().ensureActive()
                val current = TodayRecipeWidgetSnapshot(
                    plans = repository.mealPlans.first(),
                    custom = repository.customRecipes.first(),
                    account = repository.accountState.value,
                    today = LocalDate.now(),
                )
                generation.get() == request && canPublishCalendarWidgetSnapshot(snapshot, current)
            }
        }
        if (widgetIds().isNotEmpty()) launch { renderToday(snapshot, request) }
    }

    private suspend fun renderToday(snapshot: TodayRecipeWidgetSnapshot, request: Long) {
        val plan = snapshot.plans.firstOrNull { it.date == snapshot.today.toString() }
        val initial = todayRecipeWidgetContent(snapshot.plans, emptyList(), snapshot.today)
        // Clear the previous photo immediately, including across sign-out/account changes.
        publish(snapshot, request, initial, null)
        val id = initial?.recipeId ?: return
        val custom = snapshot.custom.firstOrNull { it.id == id }?.toRecipe()
        val recipes = if (custom != null) listOf(custom) else repository.getRecipesByIds(setOf(id))
        val content = todayRecipeWidgetContent(listOfNotNull(plan), recipes, snapshot.today)
        publish(snapshot, request, content, null)
        val bitmap = loadWidgetRecipePhoto(context, content?.imageUrl.orEmpty(), widgetIds())
        publish(snapshot, request, content, bitmap)
    }

    private suspend fun publish(
        snapshot: TodayRecipeWidgetSnapshot,
        request: Long,
        content: TodayRecipeWidgetContent?,
        bitmap: Bitmap?,
    ) {
        currentCoroutineContext().ensureActive()
        if (generation.get() != request || !canPublishWidgetSnapshot(snapshot, TodayRecipeWidgetSnapshot(
                plans = repository.mealPlans.first(),
                custom = repository.customRecipes.first(),
                account = repository.accountState.value,
                today = LocalDate.now(),
            ))) return
        val emptyTitle = if (snapshot.plans.any { it.date == snapshot.today.toString() && it.isIntentionallyBlank })
            R.string.today_recipe_widget_blank else R.string.today_recipe_widget_empty
        val title = content?.title?.takeIf(String::isNotBlank) ?: context.getString(emptyTitle)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = if (content == null) Intent.ACTION_MAIN else TodayRecipeWidgetProvider.OPEN_RECIPE_ACTION
            content?.let { putExtra(TodayRecipeWidgetProvider.RECIPE_ID_EXTRA, it.recipeId) }
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val variants = listOf(
            TodayRecipeWidgetProvider::class.java to R.layout.today_recipe_widget,
            TodayRecipeCompactWidgetProvider::class.java to R.layout.today_recipe_compact_widget,
        )
        for ((provider, layout) in variants) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isEmpty()) continue
            val views = RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.widget_recipe_title, title)
                setContentDescription(R.id.widget_recipe_photo, context.getString(R.string.today_recipe_widget_photo, title))
                setOnClickPendingIntent(R.id.widget_recipe, pendingIntent)
                if (bitmap == null) setImageViewResource(R.id.widget_recipe_photo, R.drawable.today_recipe_widget_placeholder)
                else setImageViewBitmap(R.id.widget_recipe_photo, bitmap)
            }
            manager.updateAppWidget(ids, views)
        }
    }

    private companion object { const val TAG = "TodayRecipeWidget" }
}
