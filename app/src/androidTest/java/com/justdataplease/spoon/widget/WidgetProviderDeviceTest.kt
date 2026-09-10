package com.justdataplease.spoon.widget

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.justdataplease.spoon.MainActivity
import com.justdataplease.spoon.R
import com.justdataplease.spoon.ui.SpoonViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Run only on a disposable AVD or explicitly isolated Android user.
 * Binds real providers to a temporary host, never changes plans/accounts, and
 * deletes only the widget IDs allocated by this test. Launcher visual checks
 * remain separate because host padding and widget placement vary by launcher.
 */
class WidgetProviderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = AppWidgetManager.getInstance(context)
    private val allocatedIds = mutableListOf<Int>()
    private lateinit var host: TrackingWidgetHost
    private var currentActivity: MainActivity? = null

    @Before
    fun createTemporaryHost() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        host = onMain {
            TrackingWidgetHost(context, (SystemClock.elapsedRealtimeNanos() and 0x3fffffff).toInt())
                .also { it.startListening() }
        }
    }

    @After
    fun removeOnlyTestWidgets() {
        try {
            if (::host.isInitialized) {
                onMain {
                    allocatedIds.forEach(host::deleteAppWidgetId)
                    host.stopListening()
                }
            }
        } finally {
            try {
                currentActivity?.let { activity ->
                    onMain { if (!activity.isDestroyed) activity.finish() }
                    instrumentation.waitForIdleSync()
                }
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
        }
    }

    @Test
    fun allThreeProvidersPublishInflatableRemoteViewsAtTheirSupportedSizes() {
        val large = bind(TodayRecipeWidgetProvider::class.java, width = 280, height = 190)
        val compact = bind(TodayRecipeCompactWidgetProvider::class.java, width = 320, height = 90)
        val calendar = bind(CalendarMealWidgetProvider::class.java, width = 320, height = 440)

        listOf(large, compact).forEach { widget ->
            assertPhotoCard(widget)
        }
        assertCalendar(calendar)

        // Exercise the actual options-changed callback and re-inflation at the
        // minimum advertised resize sizes, not just the initial picker layout.
        resize(large, width = 140, height = 110)
        assertPhotoCard(large)
        resize(compact, width = 180, height = 40)
        assertPhotoCard(compact)
        resize(calendar, width = 250, height = 400)
        assertCalendar(calendar)
        resize(calendar, width = 250, height = 280)
        assertCalendar(calendar)
    }

    @Test
    fun calendarMonthButtonsNavigateAndReturnToTheCurrentMonth() {
        val calendar = bind(CalendarMealWidgetProvider::class.java, width = 320, height = 440)
        assertCalendar(calendar)
        val initialLabel = label(calendar)

        click(calendar, R.id.calendar_widget_next)
        awaitCondition("Next-month PendingIntent should update the actual widget") {
            label(calendar).isNotBlank() && label(calendar) != initialLabel
        }
        val nextLabel = label(calendar)

        click(calendar, R.id.calendar_widget_previous)
        awaitCondition("Previous-month PendingIntent should restore the month") {
            label(calendar) == initialLabel
        }

        click(calendar, R.id.calendar_widget_next)
        awaitCondition("Next-month control should remain usable after returning") {
            label(calendar) == nextLabel
        }
        click(calendar, R.id.calendar_widget_month)
        awaitCondition("Month-heading PendingIntent should return to the current month") {
            label(calendar) == initialLabel
        }
        assertCalendar(calendar)
    }

    @Test
    fun calendarDaySelectionStaysInWidgetAndDateCaptionOpensSelectedDayAcrossMonths() {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }) as MainActivity
        currentActivity = activity
        awaitCondition("MainActivity should be active before tapping a widget day") {
            onMain { activity.hasWindowFocus() }
        }
        val calendar = bind(CalendarMealWidgetProvider::class.java, width = 320, height = 440)
        val firstDate = YearMonth.now().atDay(5)
        val initialAction = onMain { activity.intent.action }
        val initialSelection = onMain { ViewModelProvider(activity)[SpoonViewModel::class.java].calendarSelectedDate.value }
        clickDay(calendar, firstDate.dayOfMonth)
        awaitWidgetSelectedDay(calendar, firstDate)
        assertEquals("Selecting a day must not open the app", initialAction, onMain { activity.intent.action })
        assertEquals("Selecting a day stays inside the widget", initialSelection,
            onMain { ViewModelProvider(activity)[SpoonViewModel::class.java].calendarSelectedDate.value })
        click(calendar, R.id.calendar_widget_selected_date)
        awaitCalendarDay(activity, firstDate)
        val firstMonthLabel = label(calendar)

        // Each day first updates the widget through its broadcast PendingIntent.
        // The overlaid date caption then opens that date in the already-running app.
        click(calendar, R.id.calendar_widget_next)
        awaitCondition("Widget should render the next month's days") {
            label(calendar).isNotBlank() && label(calendar) != firstMonthLabel
        }
        val secondDate = YearMonth.from(firstDate).plusMonths(1).atDay(12)
        clickDay(calendar, secondDate.dayOfMonth)
        awaitWidgetSelectedDay(calendar, secondDate)
        assertEquals("Choosing another month must not reopen the app", firstDate.toString(),
            onMain { activity.intent.getStringExtra(CalendarMealWidgetProvider.DATE_EXTRA) })
        click(calendar, R.id.calendar_widget_selected_date)
        awaitCalendarDay(activity, secondDate)
        assertTrue("Warm widget navigation should retain the same activity", onMain { !activity.isDestroyed })
    }

    private fun clickDay(widget: BoundWidget, number: Int) = onMain {
        layout(widget)
        val day = descendants(widget.view).firstOrNull { view ->
            view.id == R.id.calendar_widget_day_content && view.visibility == View.VISIBLE &&
                view.findViewById<TextView>(R.id.calendar_widget_day_number)?.text?.toString() == number.toString()
        }
        assertNotNull("Widget date $number is missing", day)
        assertTrue("Date $number should execute its selection broadcast PendingIntent", checkNotNull(day).performClick())
    }

    private fun awaitWidgetSelectedDay(widget: BoundWidget, date: LocalDate) {
        awaitCondition("Widget should highlight $date and update its recipe footer") {
            onMain {
                layout(widget)
                val selected = descendants(widget.view).filter { view ->
                    view.id == R.id.calendar_widget_day_content && view.visibility == View.VISIBLE &&
                        view.contentDescription?.contains("Επιλεγμένη ημέρα") == true
                }
                selected.size == 1 && selected.single().findViewById<TextView>(R.id.calendar_widget_day_number)
                    .text.toString() == date.dayOfMonth.toString() &&
                    widget.view.findViewById<TextView>(R.id.calendar_widget_selected_date).text.toString() ==
                        calendarWidgetSelectedDateLabel(date, LocalDate.now())
            }
        }
    }

    private fun awaitCalendarDay(activity: MainActivity, date: LocalDate) {
        awaitCondition("Widget date $date should open the matching calendar day and screen") {
            onMain {
                val viewModel = ViewModelProvider(activity)[SpoonViewModel::class.java]
                !activity.isDestroyed && activity.hasWindowFocus() &&
                    activity.intent.action == CalendarMealWidgetProvider.OPEN_CALENDAR_DAY_ACTION &&
                    activity.intent.getStringExtra(CalendarMealWidgetProvider.DATE_EXTRA) == date.toString() &&
                    viewModel.calendarSelectedDate.value == date &&
                    viewModel.uiState.value.shownMonth == YearMonth.from(date)
            } && visibleText("Ημερολόγιο")
        }
    }

    private fun visibleText(expected: String): Boolean {
        fun matches(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.text?.toString() == expected) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                if (matches(child)) return true
            }
            return false
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::matches) == true
    }

    private fun bind(provider: Class<*>, width: Int, height: Int): BoundWidget {
        val component = ComponentName(context, provider)
        val id = onMain { host.allocateAppWidgetId() }
        allocatedIds += id
        val options = dimensions(width, height)
        assertTrue("Could not bind registered widget $component", manager.bindAppWidgetIdIfAllowed(id, component, options))
        val info = checkNotNull(manager.getAppWidgetInfo(id)) { "No provider info for $component" }
        val view = onMain { host.createView(context, id, info) as TrackingWidgetHostView }
        val widget = BoundWidget(id, component, view, width, height)
        val before = view.updates.get()
        context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
            this.component = component
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id))
        })
        awaitCondition("Provider $component should publish RemoteViews after its update broadcast") {
            view.updates.get() > before
        }
        return widget
    }

    private fun resize(widget: BoundWidget, width: Int, height: Int) {
        val before = widget.view.updates.get()
        widget.width = width
        widget.height = height
        manager.updateAppWidgetOptions(widget.id, dimensions(width, height))
        awaitCondition("Provider ${widget.component} should publish the resized layout") {
            // A photo delivery queued before resize is also an update. Wait for
            // the actual layout transition before asserting its minimum size.
            widget.view.updates.get() > before && onMain {
                widget.component.className != CalendarMealWidgetProvider::class.java.name ||
                    widget.view.findViewById<TextView>(R.id.calendar_widget_recipe_title)?.maxLines ==
                    if (height < 420) 1 else 2
            }
        }
    }

    private fun dimensions(width: Int, height: Int) = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
    }

    private fun assertPhotoCard(widget: BoundWidget) = onMain {
        layout(widget)
        val card = widget.view.findViewById<ViewGroup>(R.id.widget_recipe)
        assertNotNull("Provider ${widget.component} rendered an error view instead of its card", card)
        val title = widget.view.findViewById<TextView>(R.id.widget_recipe_title)
        val photo = widget.view.findViewById<ImageView>(R.id.widget_recipe_photo)
        assertNotNull(title)
        assertNotNull(photo)
        assertTrue("Recipe or empty-state title must remain readable", title.text.isNotBlank() && title.height > 0)
        if (widget.component.className == TodayRecipeCompactWidgetProvider::class.java.name) {
            val firstLineHeight = title.layout.getLineBottom(0) - title.layout.getLineTop(0)
            val dimensions = "requestedContent=${widget.width}x${widget.height}dp, " +
                "host=${widget.view.width}x${widget.view.height}px, " +
                "hostPadding=${widget.view.paddingLeft},${widget.view.paddingTop}," +
                "${widget.view.paddingRight},${widget.view.paddingBottom}px, " +
                "card=${card.width}x${card.height}px, title=${title.width}x${title.height}px, " +
                "titleVerticalPadding=${title.paddingTop + title.paddingBottom}px, line=$firstLineHeight px"
            assertEquals("Compact card should use one title line; $dimensions", 1, title.maxLines)
            assertTrue("Compact title must fit within the card; $dimensions", title.height <= card.height)
            assertTrue("Compact title must retain a full line of text at the minimum height; $dimensions",
                firstLineHeight > 0 && title.height - title.paddingTop - title.paddingBottom >= firstLineHeight)
        }
        assertNotNull("Photo or branded loading/empty artwork must render", photo.drawable)
        assertEquals("Photo should cover the complete card, including beneath the title", card.height, photo.height)
        assertTrue("The card must retain its recipe-opening PendingIntent", card.isClickable)
    }

    private fun assertCalendar(widget: BoundWidget) = onMain {
        layout(widget)
        assertTrue("Calendar month label must render", label(widget).isNotBlank())
        listOf(R.id.calendar_widget_previous, R.id.calendar_widget_next, R.id.calendar_widget_month).forEach { id ->
            val control = widget.view.findViewById<View>(id)
            assertNotNull("Calendar navigation control missing", control)
            assertTrue("Calendar navigation PendingIntent missing", control.isClickable)
        }
        val weeks = widget.view.findViewById<ViewGroup>(R.id.calendar_widget_weeks)
        assertNotNull("Calendar provider rendered an error view", weeks)
        assertEquals("Calendar must render six complete week rows", 6, weeks.childCount)
        val dates = descendants(weeks).filter { it.id == R.id.calendar_widget_day_content && it.visibility == View.VISIBLE }
        assertEquals("Current-month grid must include every date once", YearMonth.now().lengthOfMonth(), dates.size)
        assertEquals((1..YearMonth.now().lengthOfMonth()).toList(), dates.map {
            it.findViewById<TextView>(R.id.calendar_widget_day_number).text.toString().toInt()
        })
        dates.forEach { day ->
            assertTrue("Each visible date must select the corresponding day", day.isClickable)
            assertTrue("Date accessibility label must identify its action", day.contentDescription?.contains("Προβολή ημέρας στο widget") == true)
            val number = day.findViewById<TextView>(R.id.calendar_widget_day_number)
            val symbol = day.findViewById<TextView>(R.id.calendar_widget_day_marker)
            val inline = (number.parent as LinearLayout).orientation == LinearLayout.HORIZONTAL
            val requiredHeight = if (inline) maxOf(number.height, symbol.height) else number.height + symbol.height
            assertTrue("Date and category symbol must fit at ${widget.width}x${widget.height}dp: " +
                "number=${number.height}, category=${symbol.height}, available=${day.height}, inline=$inline", requiredHeight <= day.height)
        }
        val footer = widget.view.findViewById<ViewGroup>(R.id.calendar_widget_recipe)
        val photo = widget.view.findViewById<ImageView>(R.id.calendar_widget_recipe_photo)
        val title = widget.view.findViewById<TextView>(R.id.calendar_widget_recipe_title)
        val selectedDate = widget.view.findViewById<TextView>(R.id.calendar_widget_selected_date)
        assertNotNull("Calendar must show the selected recipe photo", photo.drawable)
        assertTrue("Recipe footer must remain clickable", footer.isClickable)
        assertTrue("Date caption must open the selected app day", selectedDate.isClickable)
        assertTrue("Selected date and recipe title must remain readable", selectedDate.text.isNotBlank() && title.text.isNotBlank())
        assertEquals("Photo must cover the complete footer behind its title", footer.height, photo.height)
        assertTrue("Calendar footer should have room for its photograph", footer.height >= (96 * context.resources.displayMetrics.density).toInt())
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun label(widget: BoundWidget): String = onMain {
        widget.view.findViewById<TextView>(R.id.calendar_widget_month)?.text?.toString().orEmpty()
    }

    private fun click(widget: BoundWidget, id: Int) = onMain {
        assertTrue("Widget control did not handle click", widget.view.findViewById<View>(id).performClick())
    }

    private fun layout(widget: BoundWidget) {
        val density = context.resources.displayMetrics.density
        // OPTION_APPWIDGET_MIN/MAX_* describe the provider's content area.
        // AppWidgetHostView.updateAppWidgetSize subtracts its default padding
        // before publishing those options, so the measured host must add it.
        val width = (widget.width * density).toInt() + widget.view.paddingLeft + widget.view.paddingRight
        val height = (widget.height * density).toInt() + widget.view.paddingTop + widget.view.paddingBottom
        widget.view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        widget.view.layout(0, 0, width, height)
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue(description, condition())
    }

    private fun <T> onMain(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private data class BoundWidget(
        val id: Int,
        val component: ComponentName,
        val view: TrackingWidgetHostView,
        var width: Int,
        var height: Int,
    )

    private class TrackingWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo): AppWidgetHostView =
            TrackingWidgetHostView(context)
    }

    private class TrackingWidgetHostView(context: Context) : AppWidgetHostView(context) {
        val updates = AtomicInteger()
        override fun updateAppWidget(remoteViews: RemoteViews?) {
            super.updateAppWidget(remoteViews)
            if (remoteViews != null) updates.incrementAndGet()
        }
    }
}
