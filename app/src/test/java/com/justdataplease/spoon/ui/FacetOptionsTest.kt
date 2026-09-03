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
}
