package com.justdataplease.spoon.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RatingThresholdUiTest {
    @Test
    fun `threshold labels describe strict Greek semantics`() {
        assertEquals("Οποιαδήποτε", ratingThresholdLabel(0))
        assertEquals("Πάνω από 7/10", ratingThresholdLabel(7))
        assertEquals("Πάνω από 9/10", ratingThresholdLabel(MAX_RATING_THRESHOLD))
    }

    @Test
    fun `UI cannot offer an impossible above ten threshold`() {
        assertEquals(9, MAX_RATING_THRESHOLD)
        assertThrows(IllegalArgumentException::class.java) {
            ratingThresholdLabel(10)
        }
    }
}
