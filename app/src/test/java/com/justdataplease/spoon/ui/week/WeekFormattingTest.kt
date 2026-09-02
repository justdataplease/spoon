package com.justdataplease.spoon.ui.week

import org.junit.Assert.assertEquals
import org.junit.Test

class WeekFormattingTest {
    @Test
    fun `rating uses Greek decimal punctuation`() {
        assertEquals("8,5", formatRating10(8.5))
    }
}
