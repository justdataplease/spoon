package com.justdataplease.spoon.ui.calendar

import com.justdataplease.spoon.ui.model.CalendarMealUi
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarAccessibilityTest {
    @Test
    fun `calendar day description includes full date meal completion and selection`() {
        val date = LocalDate.of(2026, 9, 3)
        val description = calendarDayDescription(
            date = date,
            meal = CalendarMealUi(
                date = date,
                categoryKey = "fish",
                recipeTitle = "Ψάρι λεμονάτο",
                isCompleted = true,
            ),
            selected = true,
        )

        assertTrue(description.contains("3 Σεπτεμβρίου 2026"))
        assertTrue(description.contains("Γεύμα: Ψάρι λεμονάτο"))
        assertTrue(description.contains("Ολοκληρωμένο"))
        assertTrue(description.contains("Επιλεγμένη ημέρα"))
    }

    @Test
    fun `empty unselected day announces both states`() {
        val description = calendarDayDescription(
            date = LocalDate.of(2026, 9, 4),
            meal = null,
            selected = false,
        )

        assertTrue(description.contains("Χωρίς προγραμματισμένο γεύμα"))
        assertTrue(description.contains("Μη επιλεγμένη ημέρα"))
    }
}
