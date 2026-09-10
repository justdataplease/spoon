package com.justdataplease.spoon.widget

import kotlin.math.floor
import kotlin.math.sqrt

internal data class WidgetPhotoSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

// A RemoteViews may contain at most 1.5 screenfuls of ARGB pixels. Reserve a
// third of that allowance for other artwork; never grow a small source bitmap.
internal fun widgetPhotoPixelBudget(screen: WidgetPhotoSize): Long =
    screen.pixels.coerceIn(1L, 4_194_304L)

internal fun fitWidgetPhotoSize(size: WidgetPhotoSize, pixelBudget: Long): WidgetPhotoSize {
    val width = size.width.coerceAtLeast(1)
    val height = size.height.coerceAtLeast(1)
    val scale = minOf(1.0, 2560.0 / maxOf(width, height),
        sqrt(pixelBudget.coerceAtLeast(1).toDouble() / (width.toDouble() * height)))
    return WidgetPhotoSize(maxOf(1, floor(width * scale).toInt()), maxOf(1, floor(height * scale).toInt()))
}

/** The launcher reports dp, including alternate orientation sizes, not bitmap pixels. */
internal fun widgetPhotoRequestSize(
    sizesDp: List<Pair<Float, Float>>,
    density: Float,
    screen: WidgetPhotoSize,
): WidgetPhotoSize {
    val valid = sizesDp.filter { (width, height) -> width.isFinite() && height.isFinite() && width > 0 && height > 0 }
    val pixelsPerDp = density.takeIf { it.isFinite() && it > 0 } ?: 1f
    val size = if (valid.isEmpty()) WidgetPhotoSize(screen.width, screen.height / 2)
    else WidgetPhotoSize(
        kotlin.math.ceil(valid.maxOf { it.first }.toDouble() * pixelsPerDp).toInt(),
        kotlin.math.ceil(valid.maxOf { it.second }.toDouble() * pixelsPerDp).toInt(),
    )
    return fitWidgetPhotoSize(size, widgetPhotoPixelBudget(screen))
}
