package com.justdataplease.spoon.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.SizeF
import android.view.WindowManager
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.justdataplease.spoon.ui.components.recipeImageModel
import kotlinx.coroutines.withTimeoutOrNull

/** Keep the original aspect ratio: each widget's ImageView crops only once. */
internal suspend fun loadWidgetRecipePhoto(
    context: Context,
    imageSource: String,
    widgetIds: IntArray,
    maxPhotoHeightDp: Float? = null,
): Bitmap? {
    val image = recipeImageModel(imageSource) ?: return null
    val manager = AppWidgetManager.getInstance(context)
    val sizes = widgetIds.flatMap { id ->
        val options = manager.getAppWidgetOptions(id)
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                .orEmpty().map { it.width to it.height }
        } else emptyList()
        exact + listOf(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat() to
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat(),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).toFloat() to
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat(),
        )
    }.map { (width, height) -> width to (maxPhotoHeightDp?.let { minOf(height, it) } ?: height) }
    val screen = widgetPhotoScreenSize(context)
    val target = widgetPhotoRequestSize(sizes, context.resources.displayMetrics.density, screen)
    return withTimeoutOrNull(15_000) {
        context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(image)
                .size(target.width, target.height)
                .scale(Scale.FILL)
                .precision(Precision.EXACT)
                .allowHardware(false)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .build(),
        ).drawable?.toBitmap(config = Bitmap.Config.ARGB_8888)?.let { source ->
            val budget = widgetPhotoPixelBudget(screen)
            val bounded = fitWidgetPhotoSize(WidgetPhotoSize(source.width, source.height), budget)
            if (bounded.width == source.width && bounded.height == source.height && source.allocationByteCount <= budget * 4) source
            else Bitmap.createBitmap(bounded.width, bounded.height, Bitmap.Config.ARGB_8888).also { targetBitmap ->
                // A fresh allocation also bounds pooled bitmap backing memory, which
                // Android counts even if the visible bitmap dimensions are smaller.
                Canvas(targetBitmap).drawBitmap(source, null,
                    Rect(0, 0, bounded.width, bounded.height), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
    }
}

internal fun widgetPhotoScreenSize(context: Context): WidgetPhotoSize {
    val windowManager = context.getSystemService(WindowManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.maximumWindowMetrics.bounds
        return WidgetPhotoSize(bounds.width(), bounds.height())
    }
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay.getRealMetrics(metrics)
    return WidgetPhotoSize(metrics.widthPixels, metrics.heightPixels)
}
