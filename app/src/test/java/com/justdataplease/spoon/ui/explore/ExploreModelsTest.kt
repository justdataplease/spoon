package com.justdataplease.spoon.ui.explore

import com.justdataplease.spoon.data.model.Recipe
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
            sourceKeys = setOf("akis", "argiro"),
            quickOnly = true,
        )

        assertEquals(12, filters.activeCount)
    }

    @Test
    fun emptyExploreFiltersHaveNoActiveConstraints() {
        assertEquals(0, ExploreFiltersUi().activeCount)
    }

    @Test
    fun multipleSelectedSourcesCountAsOneFilterGroup() {
        assertEquals(1, ExploreFiltersUi(sourceKeys = setOf("akis", "argiro")).activeCount)
    }

    @Test
    fun sourceOptionsAreDerivedAndGroupedFromCatalogProvenance() {
        val options = listOf(
            Recipe(id = "a1", sourceKey = "akis", source = "akispetretzikis.com", sourceName = "Άκης Πετρετζίκης"),
            Recipe(id = "a2", sourceKey = "AKIS", sourceName = "Άκης Πετρετζίκης"),
            Recipe(id = "a3", source = "www.akispetretzikis.com", sourceName = "Άκης Πετρετζίκης"),
            Recipe(id = "r1", sourceKey = "argiro", sourceName = "Αργυρώ Μπαρμπαρίγου"),
            Recipe(id = "mine", sourceKey = "personal", sourceName = "Προσωπική συνταγή"),
            Recipe(id = "unknown"),
        ).toExploreSourceOptionsUi()

        assertEquals(
            listOf(
                ExploreSourceOptionUi("akis", "Άκης Πετρετζίκης", 3),
                ExploreSourceOptionUi("argiro", "Αργυρώ Μπαρμπαρίγου", 1),
                ExploreSourceOptionUi("personal", "Προσωπική συνταγή", 1),
            ),
            options,
        )
    }
}
