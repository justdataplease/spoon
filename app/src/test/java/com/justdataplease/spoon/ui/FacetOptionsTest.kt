package com.justdataplease.spoon.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FacetOptionsTest {
    @Test
    fun caseAndDiacriticVariantsCollapseToOneReadableDisplayLabel() {
        val options = listOf(
            "VEGAN",
            "Vegan",
            " BRUNCH ",
            "Brunch",
            "ΕΛΛΗΝΙΚΗ",
            "Ελληνικη",
            "Ελληνική",
            "ΝΗΣΤΙΣΙΜΑ",
            "Νηστισιμα",
            "Νηστίσιμα",
            " ",
        ).cleanFacetOptions()

        assertEquals(
            listOf("Brunch", "Vegan", "Ελληνική", "Νηστίσιμα"),
            options,
        )
    }

    @Test
    fun equalQualityVariantsKeepTheFirstCatalogDisplayLabel() {
        assertEquals(
            listOf("Χωρίς ζάχαρη"),
            listOf("  Χωρίς   ζάχαρη  ", "χωρίς ζάχαρη").cleanFacetOptions(),
        )
    }

    @Test
    fun ingredientOptionsUseCanonicalLabelsAndKeepDistinctIngredientConcepts() {
        val options = listOf(
            "ΑΥΓΑ",
            "Αυγό",
            "ΦΟΥΝΤΟΥΚΙΑ",
            "Φουντούκι",
            "ΑΛΕΥΡΙ",
            "Αλεύρι (ζύμες)",
            "Γάλα αμυγδάλου",
            "Γάλα βρώμης",
            "ΚΙΜΑΣ",
        ).cleanIngredientFacetOptions()

        assertEquals(
            listOf(
                "Αλεύρι",
                "Αλεύρι (ζύμες)",
                "Αυγό",
                "Γάλα αμυγδάλου",
                "Γάλα βρώμης",
                "Κιμάς",
                "Φουντούκι",
            ),
            options,
        )
    }
}
