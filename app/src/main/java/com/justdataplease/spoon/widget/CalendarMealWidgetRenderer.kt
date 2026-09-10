package com.justdataplease.spoon.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.justdataplease.spoon.MainActivity
import com.justdataplease.spoon.R
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.isIntentionallyBlank
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Persists only each widget's displayed month and selected date, never recipe or account data. */
internal class CalendarMealWidgetRenderer(private val context: Context) {
    private val manager get() = AppWidgetManager.getInstance(context)
    private val preferences get() = context.getSharedPreferences("calendar-widget-display", Context.MODE_PRIVATE)

    fun widgetIds(): IntArray = manager.getAppWidgetIds(ComponentName(context, CalendarMealWidgetProvider::class.java))
    fun isActive(widgetId: Int): Boolean = widgetId in widgetIds()

    fun changeMonth(widgetId: Int, direction: Int) {
        val editor = preferences.edit().remove(selectionKey(widgetId))
        if (direction == 0) editor.remove(monthKey(widgetId))
        else editor.putString(monthKey(widgetId), shiftedWidgetCalendarMonth(shownMonth(widgetId, LocalDate.now()), direction).toString())
        editor.apply()
    }

    fun selectDay(widgetId: Int, date: LocalDate): Boolean {
        if (YearMonth.from(date) != shownMonth(widgetId, LocalDate.now())) return false
        preferences.edit().putString(selectionKey(widgetId), date.toString()).apply()
        return true
    }

    fun removeWidgets(ids: IntArray) {
        preferences.edit().apply { ids.forEach { remove(monthKey(it)); remove(selectionKey(it)) } }.apply()
    }

    private fun shownMonth(id: Int, today: LocalDate): YearMonth =
        validatedWidgetCalendarMonth(preferences.getString(monthKey(id), null)) ?: YearMonth.from(today)

    private fun selectedDate(id: Int, month: YearMonth, snapshot: TodayRecipeWidgetSnapshot): LocalDate =
        calendarWidgetSelectedDate(month, snapshot.today, preferences.getString(selectionKey(id), null), snapshot.plans)

    suspend fun publish(
        snapshot: TodayRecipeWidgetSnapshot,
        recipes: List<Recipe>,
        canPublish: suspend () -> Boolean,
    ) = coroutineScope {
        val availableRecipes = recipes.filterNot { it.id.startsWith("custom_") } + snapshot.custom.map { it.toRecipe() }
        for (widgetId in widgetIds()) launch {
            val month = shownMonth(widgetId, snapshot.today)
            val selected = selectedDate(widgetId, month, snapshot)
            val plan = snapshot.plans.firstOrNull { it.date == selected.toString() }
            val recipe = validWidgetRecipeId(plan?.recipeId)?.let { id -> availableRecipes.firstOrNull { it.id == id } }
            suspend fun stillCurrent(): Boolean = canPublish() && isActive(widgetId) &&
                shownMonth(widgetId, snapshot.today) == month && selectedDate(widgetId, month, snapshot) == selected
            if (!stillCurrent()) return@launch
            // Clear any previous date's or owner's photo before starting asynchronous work.
            manager.updateAppWidget(widgetId, views(widgetId, month, selected, snapshot, availableRecipes, plan, recipe, null))
            val imageSource = recipe?.imageUrl?.takeIf(String::isNotBlank) ?: return@launch
            val bitmap = loadWidgetRecipePhoto(context, imageSource, intArrayOf(widgetId)) ?: return@launch
            if (!stillCurrent()) return@launch
            manager.updateAppWidget(widgetId, views(widgetId, month, selected, snapshot, availableRecipes, plan, recipe, bitmap))
        }
    }

    private fun views(
        widgetId: Int,
        shownMonth: YearMonth,
        selected: LocalDate,
        snapshot: TodayRecipeWidgetSnapshot,
        recipes: List<Recipe>,
        plan: DayMealPlan?,
        recipe: Recipe?,
        bitmap: Bitmap?,
    ): RemoteViews {
        val options = manager.getAppWidgetOptions(widgetId)
        val compact by lazy { sizedViews(widgetId, shownMonth, selected, snapshot, recipes, plan, recipe, bitmap, compact = true) }
        val full by lazy { sizedViews(widgetId, shownMonth, selected, snapshot, recipes, plan, recipe, bitmap, compact = false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                .orEmpty().filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }.distinct().take(16)
            if (sizes.isNotEmpty()) return RemoteViews(sizes.associateWith { if (it.height < 420f) compact else full })
        }
        val minimumHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 420)
        val maximumHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minimumHeight)
        return when {
            maximumHeight < 420 -> compact
            minimumHeight >= 420 -> full
            else -> RemoteViews(compact, full)
        }
    }

    private fun sizedViews(
        widgetId: Int,
        shownMonth: YearMonth,
        selected: LocalDate,
        snapshot: TodayRecipeWidgetSnapshot,
        recipes: List<Recipe>,
        plan: DayMealPlan?,
        recipe: Recipe?,
        bitmap: Bitmap?,
        compact: Boolean,
    ): RemoteViews {
        val month = calendarWidgetMonth(shownMonth, snapshot.today, snapshot.plans, recipes, selected)
        return RemoteViews(context.packageName,
            if (compact) R.layout.calendar_meal_widget_small else R.layout.calendar_meal_widget).apply {
            setTextViewText(R.id.calendar_widget_month, month.label)
            setContentDescription(R.id.calendar_widget_month,
                context.getString(R.string.calendar_widget_current_month, month.label))
            setOnClickPendingIntent(R.id.calendar_widget_previous, monthIntent(widgetId, CalendarMealWidgetProvider.PREVIOUS_MONTH_ACTION))
            setOnClickPendingIntent(R.id.calendar_widget_next, monthIntent(widgetId, CalendarMealWidgetProvider.NEXT_MONTH_ACTION))
            setOnClickPendingIntent(R.id.calendar_widget_month, monthIntent(widgetId, CalendarMealWidgetProvider.CURRENT_MONTH_ACTION))
            removeAllViews(R.id.calendar_widget_weeks)
            for (week in month.days.chunked(7)) {
                val row = RemoteViews(context.packageName, R.layout.calendar_widget_week)
                for (day in week) {
                    val cell = RemoteViews(context.packageName,
                        if (compact) R.layout.calendar_widget_day_small else R.layout.calendar_widget_day)
                    cell.setViewVisibility(R.id.calendar_widget_day_content, if (day.date == null) View.INVISIBLE else View.VISIBLE)
                    day.date?.let { date ->
                        cell.setTextViewText(R.id.calendar_widget_day_number, date.dayOfMonth.toString())
                        cell.setInt(R.id.calendar_widget_day_content, "setBackgroundResource", when {
                            day.isSelected -> R.drawable.calendar_widget_today
                            day.isToday -> R.drawable.calendar_widget_current_day
                            else -> R.drawable.calendar_widget_day_background
                        })
                        cell.setTextColor(R.id.calendar_widget_day_number, if (day.isSelected) Color.WHITE else INK)
                        cell.setTextViewText(R.id.calendar_widget_day_marker,
                            if (day.isIntentionallyBlank) "—" else calendarWidgetCategorySymbol(day.category))
                        cell.setTextColor(R.id.calendar_widget_day_marker, if (day.isSelected) Color.WHITE else MUTED)
                        cell.setViewVisibility(R.id.calendar_widget_day_marker,
                            if (day.hasMeal || day.isIntentionallyBlank) View.VISIBLE else View.INVISIBLE)
                        cell.setViewVisibility(R.id.calendar_widget_day_completed, if (day.completed) View.VISIBLE else View.GONE)
                        cell.setContentDescription(R.id.calendar_widget_day_content, calendarWidgetDayDescription(day))
                        cell.setOnClickPendingIntent(R.id.calendar_widget_day_content, selectIntent(widgetId, date))
                    }
                    row.addView(R.id.calendar_widget_week, cell)
                }
                addView(R.id.calendar_widget_weeks, row)
            }
            val recipeId = validWidgetRecipeId(plan?.recipeId)
            val title = recipe?.title?.takeIf(String::isNotBlank) ?: plan?.takeIf { recipeId != null }?.recipeTitle?.takeIf(String::isNotBlank)
                ?: context.getString(if (plan?.isIntentionallyBlank == true) R.string.today_recipe_widget_blank
                    else R.string.calendar_widget_choose_meal)
            setTextViewText(R.id.calendar_widget_recipe_title, title)
            setTextViewText(R.id.calendar_widget_selected_date, calendarWidgetSelectedDateLabel(selected, snapshot.today))
            setContentDescription(R.id.calendar_widget_selected_date,
                context.getString(R.string.calendar_widget_open_selected_day, calendarWidgetSelectedDateLabel(selected, snapshot.today)))
            setContentDescription(R.id.calendar_widget_recipe,
                context.getString(if (recipeId == null) R.string.calendar_widget_open_selected_day
                    else R.string.calendar_widget_open_selected_recipe, title))
            setContentDescription(R.id.calendar_widget_recipe_photo, context.getString(R.string.today_recipe_widget_photo, title))
            setOnClickPendingIntent(R.id.calendar_widget_selected_date, openDayIntent(widgetId, selected))
            setOnClickPendingIntent(R.id.calendar_widget_recipe, openRecipeIntent(widgetId, selected, recipeId))
            if (bitmap == null) setImageViewResource(R.id.calendar_widget_recipe_photo, R.drawable.today_recipe_widget_placeholder)
            else setImageViewBitmap(R.id.calendar_widget_recipe_photo, bitmap)
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

    private fun selectIntent(widgetId: Int, date: LocalDate): PendingIntent = PendingIntent.getBroadcast(
        context, widgetId,
        Intent(context, CalendarMealWidgetProvider::class.java).apply {
            action = CalendarMealWidgetProvider.SELECT_DAY_ACTION
            data = Uri.parse("peltes-widget://calendar/$widgetId/select/$date")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra(CalendarMealWidgetProvider.DATE_EXTRA, date.toString())
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openDayIntent(widgetId: Int, date: LocalDate): PendingIntent = activityIntent(widgetId,
        Intent(context, MainActivity::class.java).apply {
            action = CalendarMealWidgetProvider.OPEN_CALENDAR_DAY_ACTION
            data = Uri.parse("peltes-widget://calendar/$widgetId/day/$date")
            putExtra(CalendarMealWidgetProvider.DATE_EXTRA, date.toString())
        })

    private fun openRecipeIntent(widgetId: Int, date: LocalDate, recipeId: String?): PendingIntent =
        if (recipeId == null) openDayIntent(widgetId, date) else activityIntent(widgetId,
            Intent(context, MainActivity::class.java).apply {
                action = TodayRecipeWidgetProvider.OPEN_RECIPE_ACTION
                data = Uri.parse("peltes-widget://calendar/$widgetId/recipe/$date/$recipeId")
                putExtra(TodayRecipeWidgetProvider.RECIPE_ID_EXTRA, recipeId)
            })

    private fun activityIntent(widgetId: Int, intent: Intent): PendingIntent = PendingIntent.getActivity(
        context, widgetId, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun monthKey(id: Int) = "month_$id"
    private fun selectionKey(id: Int) = "selected_$id"

    private companion object {
        val INK: Int = Color.rgb(45, 37, 32)
        val MUTED: Int = Color.rgb(119, 103, 93)
    }
}
