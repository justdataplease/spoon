package com.justdataplease.spoon.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRecipePhotoSizeTest {
    private val screen = WidgetPhotoSize(1080, 2400)

    @Test fun highDensityHomeScreenReceivesPhysicalPixelsInsteadOfSmallThumbnail() {
        assertEquals(WidgetPhotoSize(960, 570), widgetPhotoRequestSize(listOf(320f to 190f), 3f, screen))
    }

    @Test fun alternateOrientationsAndMultipleWidgetsKeepEnoughDetailForEachSize() {
        assertEquals(WidgetPhotoSize(1200, 400), widgetPhotoRequestSize(
            listOf(320f to 200f, 600f to 120f, 400f to 60f), 2f, screen))
    }

    @Test fun compactCardKeepsItsWideRequestAndDoesNotForceThreeByTwoCrop() {
        assertEquals(WidgetPhotoSize(960, 120), widgetPhotoRequestSize(listOf(320f to 40f), 3f, screen))
        val source = WidgetPhotoSize(1200, 900)
        assertEquals(source, fitWidgetPhotoSize(source, widgetPhotoPixelBudget(screen)))
    }

    @Test fun resizingRequestsMorePixelsAndMissingOptionsUseScreenFallback() {
        val small = widgetPhotoRequestSize(listOf(180f to 110f), 3f, screen)
        val large = widgetPhotoRequestSize(listOf(360f to 220f), 3f, screen)
        assertEquals(small.width * 2, large.width)
        assertEquals(small.height * 2, large.height)
        assertEquals(WidgetPhotoSize(1080, 1200), widgetPhotoRequestSize(
            listOf(0f to 0f, Float.NaN to 200f, 20f to Float.POSITIVE_INFINITY), 3f, screen))
    }

    @Test fun hugeOriginalsAreBoundedWithoutDistortingTheirAspectRatio() {
        for (source in listOf(WidgetPhotoSize(8000, 6000), WidgetPhotoSize(12000, 1000), WidgetPhotoSize(1000, 12000))) {
            val output = fitWidgetPhotoSize(source, widgetPhotoPixelBudget(screen))
            assertTrue(output.pixels <= screen.pixels)
            assertTrue(maxOf(output.width, output.height) <= 2560)
            assertEquals(source.width.toDouble() / source.height, output.width.toDouble() / output.height, 0.06)
        }
    }

    @Test fun personalPhotoModelDecodesValidatedBytesAndRejectsUnsafeOrMalformedSources() {
        val bytes = ByteArray(32) { it.toByte() }
        val uri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
        org.junit.Assert.assertArrayEquals(bytes, com.justdataplease.spoon.ui.components.recipeImageModel(uri) as ByteArray)
        assertEquals("https://example.com/photo.jpg", com.justdataplease.spoon.ui.components.recipeImageModel("https://example.com/photo.jpg"))
        for (invalid in listOf("file:///private/photo", "data:text/html;base64,aaaa", "data:image/png;base64," + "=".repeat(32))) {
            org.junit.Assert.assertNull(com.justdataplease.spoon.ui.components.recipeImageModel(invalid))
        }
    }

    @Test fun smallOriginalsAreNeverUpscaledByTheMemorySafetyPass() {
        assertEquals(WidgetPhotoSize(320, 180), fitWidgetPhotoSize(WidgetPhotoSize(320, 180), screen.pixels))
        assertTrue(widgetPhotoPixelBudget(WidgetPhotoSize(4000, 3000)) <= 4_194_304)
    }
}
