package com.justdataplease.spoon.widget

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.justdataplease.spoon.R
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercises the real decoder and widget Binder transport without network or saved-plan changes. */
class WidgetRecipePhotoDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = AppWidgetManager.getInstance(context)
    private val allocatedIds = mutableListOf<Int>()
    private lateinit var host: PhotoWidgetHost

    @Before
    fun createTemporaryHost() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        host = onMain {
            PhotoWidgetHost(context, (SystemClock.elapsedRealtimeNanos() and 0x3fffffff).toInt())
                .also { it.startListening() }
        }
    }

    @After
    fun removeOnlyTestWidgets() {
        try {
            if (::host.isInitialized) onMain {
                allocatedIds.forEach(host::deleteAppWidgetId)
                host.stopListening()
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun largeAndCompactPhotosRetainNativePixelDetailAndUncroppedSourceAspect() = runBlocking {
        val large = bind(TodayRecipeWidgetProvider::class.java, R.layout.today_recipe_widget, 320, 190)
        val compact = bind(TodayRecipeCompactWidgetProvider::class.java,
            R.layout.today_recipe_compact_widget, 320, 90)
        val photo = generatedPhotoDataUri()

        for (widget in listOf(large, compact)) {
            val bitmap = checkNotNull(loadWidgetRecipePhoto(context, photo, intArrayOf(widget.id))) {
                "The supported deterministic photo data URI should decode"
            }
            assertNativeDetail(widget, bitmap)
            assertSourceAspectAndSafeMemory(bitmap)
            assertPublishedPhoto(widget, bitmap, "photo-detail-${widget.id}")
        }
    }

    @Test
    fun resizingRequestsMorePixelsAndPublishesTheLargerPhotoThroughBinder() = runBlocking {
        val widget = bind(TodayRecipeWidgetProvider::class.java, R.layout.today_recipe_widget, 180, 110)
        val photo = generatedPhotoDataUri()
        val initial = checkNotNull(loadWidgetRecipePhoto(context, photo, intArrayOf(widget.id)))
        assertSourceAspectAndSafeMemory(initial)
        assertPublishedPhoto(widget, initial, "photo-before-resize-${widget.id}")

        widget.widthDp = 360
        widget.heightDp = 220
        onMain {
            widget.view.contentWidthDp = widget.widthDp
            widget.view.contentHeightDp = widget.heightDp
        }
        manager.updateAppWidgetOptions(widget.id, dimensions(widget.widthDp, widget.heightDp))
        val resized = checkNotNull(loadWidgetRecipePhoto(context, photo, intArrayOf(widget.id)))
        assertTrue("A larger widget must decode more image pixels: " +
            "${initial.width}x${initial.height} -> ${resized.width}x${resized.height}",
            resized.width > initial.width && resized.height > initial.height)
        assertNativeDetail(widget, resized)
        assertSourceAspectAndSafeMemory(resized)
        assertPublishedPhoto(widget, resized, "photo-after-resize-${widget.id}")
    }

    private fun assertNativeDetail(widget: BoundPhotoWidget, bitmap: Bitmap) {
        val density = context.resources.displayMetrics.density
        val requiredWidth = ceil(widget.widthDp * density).toInt()
        val requiredHeight = ceil(widget.heightDp * density).toInt()
        val detail = "widget=${widget.widthDp}x${widget.heightDp}dp, density=$density, " +
            "required=${requiredWidth}x${requiredHeight}px, decoded=${bitmap.width}x${bitmap.height}px"
        android.util.Log.i("WidgetPhotoVerification", detail)
        assertTrue("The photo must cover the widget's physical width without enlarging a thumbnail; $detail",
            bitmap.width >= requiredWidth)
        assertTrue("The photo must cover the widget's physical height without enlarging a thumbnail; $detail",
            bitmap.height >= requiredHeight)
        if (requiredWidth > 480) {
            assertTrue("A high-density widget must exceed the old 480px-wide photo; $detail", bitmap.width > 480)
        }
    }

    private fun assertSourceAspectAndSafeMemory(bitmap: Bitmap) {
        assertEquals("The loader must preserve the source framing for each widget's centerCrop",
            SOURCE_WIDTH.toDouble() / SOURCE_HEIGHT, bitmap.width.toDouble() / bitmap.height, 0.01)
        assertEquals("RemoteViews needs a software ARGB bitmap", Bitmap.Config.ARGB_8888, bitmap.config)
        val metrics = context.resources.displayMetrics
        val androidLimit = metrics.widthPixels.toLong() * metrics.heightPixels * 6L
        assertTrue("Decoded bitmap allocation ${bitmap.allocationByteCount} must stay below the " +
            "Android widget limit $androidLimit", bitmap.allocationByteCount.toLong() < androidLimit)
    }

    private fun assertPublishedPhoto(widget: BoundPhotoWidget, bitmap: Bitmap, marker: String) {
        val views = RemoteViews(context.packageName, widget.layout).apply {
            setTextViewText(R.id.widget_recipe_title, marker)
            setImageViewBitmap(R.id.widget_recipe_photo, bitmap)
        }
        // This travels through AppWidgetService's real bitmap-memory validation.
        // Capture our marked delivery, since normal provider refreshes may follow it.
        manager.updateAppWidget(widget.id, views)
        awaitCondition("The real widget host should receive the photo: $marker") {
            widget.view.deliveries.containsKey(marker)
        }
        val delivered = checkNotNull(widget.view.deliveries[marker])
        assertEquals("Binder must preserve the decoded bitmap width", bitmap.width, delivered.bitmapWidth)
        assertEquals("Binder must preserve the decoded bitmap height", bitmap.height, delivered.bitmapHeight)
        assertTrue("The photo must have visible bounds", delivered.photoWidth > 0 && delivered.photoHeight > 0)
        assertEquals("The photo must cover the complete card width", delivered.cardWidth, delivered.photoWidth)
        assertEquals("The photo must cover the complete card height", delivered.cardHeight, delivered.photoHeight)
        assertEquals("The launcher must crop the source to the actual card", ImageView.ScaleType.CENTER_CROP,
            delivered.scaleType)
    }

    private fun bind(provider: Class<*>, layout: Int, width: Int, height: Int): BoundPhotoWidget {
        val id = onMain { host.allocateAppWidgetId() }
        allocatedIds += id
        val component = ComponentName(context, provider)
        assertTrue("Could not bind photo widget $component", manager.bindAppWidgetIdIfAllowed(
            id, component, dimensions(width, height)))
        val info = checkNotNull(manager.getAppWidgetInfo(id))
        val view = onMain {
            (host.createView(context, id, info) as PhotoWidgetHostView).apply {
                contentWidthDp = width
                contentHeightDp = height
            }
        }
        return BoundPhotoWidget(id, layout, view, width, height)
    }

    private fun dimensions(width: Int, height: Int) = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
    }

    private fun generatedPhotoDataUri(): String {
        val bitmap = Bitmap.createBitmap(SOURCE_WIDTH, SOURCE_HEIGHT, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(225, 192, 116))
            val paint = Paint().apply { color = Color.rgb(37, 85, 58) }
            for (x in 0 until SOURCE_WIDTH step 80) {
                canvas.drawRect(x.toFloat(), 0f, (x + 20).toFloat(), SOURCE_HEIGHT.toFloat(), paint)
            }
            paint.color = Color.rgb(158, 54, 29)
            for (y in 0 until SOURCE_HEIGHT step 100) {
                canvas.drawRect(0f, y.toFloat(), SOURCE_WIDTH.toFloat(), (y + 15).toFloat(), paint)
            }
            assertTrue("Synthetic PNG must encode", bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
        } finally {
            bitmap.recycle()
        }
        return "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP).also {
            assertTrue("Fixture must remain below the app's 700k data URI limit", it.length + 22 < 700_000)
        }
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

    private data class BoundPhotoWidget(
        val id: Int,
        val layout: Int,
        val view: PhotoWidgetHostView,
        var widthDp: Int,
        var heightDp: Int,
    )

    private data class PhotoDelivery(
        val bitmapWidth: Int,
        val bitmapHeight: Int,
        val photoWidth: Int,
        val photoHeight: Int,
        val cardWidth: Int,
        val cardHeight: Int,
        val scaleType: ImageView.ScaleType,
    )

    private class PhotoWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo): AppWidgetHostView =
            PhotoWidgetHostView(context)
    }

    private class PhotoWidgetHostView(context: Context) : AppWidgetHostView(context) {
        var contentWidthDp = 1
        var contentHeightDp = 1
        val deliveries = ConcurrentHashMap<String, PhotoDelivery>()

        override fun updateAppWidget(remoteViews: RemoteViews?) {
            super.updateAppWidget(remoteViews)
            if (remoteViews == null) return
            val title = findViewById<TextView>(R.id.widget_recipe_title)?.text?.toString().orEmpty()
            if (!title.startsWith("photo-")) return
            val density = resources.displayMetrics.density
            val width = (contentWidthDp * density).toInt() + paddingLeft + paddingRight
            val height = (contentHeightDp * density).toInt() + paddingTop + paddingBottom
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            layout(0, 0, width, height)
            val card = findViewById<ViewGroup>(R.id.widget_recipe) ?: return
            val photo = findViewById<ImageView>(R.id.widget_recipe_photo) ?: return
            val bitmap = (photo.drawable as? BitmapDrawable)?.bitmap ?: return
            deliveries[title] = PhotoDelivery(bitmap.width, bitmap.height, photo.width, photo.height,
                card.width, card.height, photo.scaleType)
        }
    }

    private companion object {
        const val SOURCE_WIDTH = 2000
        const val SOURCE_HEIGHT = 1400
    }
}
