package com.justdataplease.spoon.ui.shopping

import org.junit.Assert.assertEquals
import org.junit.Test

class ShoppingModelsTest {
    @Test
    fun `amount joins only present values`() {
        assertEquals("250 γρ.", ShoppingListItemUi("1", "φακές", "250", "γρ.").amountLabel())
        assertEquals("κ.σ.", ShoppingListItemUi("2", "λάδι", unit = "κ.σ.").amountLabel())
    }
}
