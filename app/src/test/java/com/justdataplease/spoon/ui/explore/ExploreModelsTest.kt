package com.justdataplease.spoon.ui.explore

import com.justdataplease.spoon.ui.model.EaseUi
import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreModelsTest {
    @Test
    fun `active count includes every independent filter`() {
        val filters = ExploreFiltersUi(
            categoryKey = "fish",
            ease = EaseUi.HARD,
            minRating10 = 7,
            maxPrepMinutes = 45,
            diet = "Χωρίς γλουτένη",
            mealType = "Κυρίως γεύμα",
            occasion = "Κυριακάτικο τραπέζι",
            method = "Στον φούρνο",
            cuisine = "Ελληνική",
            ingredient = "Ψάρι",
            quickOnly = true,
        )

        assertEquals(11, filters.activeCount)
    }

    @Test
    fun `empty explore filters have no active constraints`() {
        assertEquals(0, ExploreFiltersUi().activeCount)
    }
}
