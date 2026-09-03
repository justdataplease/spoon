package com.justdataplease.spoon.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SpoonViewModelCategoryTest {
    @Test
    fun current_catalog_category_wins_over_legacy_plan_and_filter() {
        assertEquals(
            "dessert",
            resolvedUiCategoryKey(
                recipeCategory = "dessert",
                planCategory = "legumes",
                filterCategory = "legumes",
            ),
        )
    }

    @Test
    fun missing_catalog_category_falls_back_to_plan_then_filter() {
        assertEquals(
            "fish",
            resolvedUiCategoryKey(
                recipeCategory = "",
                planCategory = "fish",
                filterCategory = "meat",
            ),
        )
        assertEquals(
            "chicken",
            resolvedUiCategoryKey(
                recipeCategory = null,
                planCategory = "",
                filterCategory = "poultry",
            ),
        )
    }
}
