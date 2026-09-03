package com.justdataplease.spoon.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GreekProviderLabelsTest {
    @Test
    fun `every currently observed Latin provider label has an exact Greek display mapping`() {
        val observed = listOf(
            "Αυστηρά χορτοφαγική (vegan)",
            "VEGAN",
            "LIGHT",
            "VEGETARIAN",
            "SUPERFOODS",
            "EGG FREE",
            "LOW BUDGET",
            "BRUNCH",
            "Finger food",
            "Brunch",
            "Leftovers",
            "Budget meals",
            "BBQ",
            "to Share",
            "One pan",
            "Halloween",
            "Air Fryer",
            "ΠΑΤΑΤΕΣ BABY",
            "BLUEBERRY",
            "LEFTOVERS",
            "BARBEQUE",
            "CUPCAKES & MUFFINS",
            "CHEESECAKE",
            "FINGERFOOD",
            "LUNCH BOX",
            "ΥΓΙΕΙΝΑ - LIGHT ΓΛΥΚΑ",
            "COCKTAILS",
            "EDITOR'S CHOICE",
            "BROWNIES",
            "ΣΥΝΤΑΓΕΣ AIRFRYER",
            "ΛΟΥΚΟΥΜΑΔΕΣ & DONUTS",
            "PANCAKES",
            "BURGER",
            "AIR FRYER",
            "ΧΥΜΟΙ & SMOOTHIES",
        )

        observed.forEach { raw ->
            val displayed = greekProviderLabel(raw, ProviderLabelKind.TAG)
            assertNotEquals(raw, ProviderLabelKind.TAG.fallbackLabel, displayed)
            assertFalse("$raw -> $displayed", LatinLetter.containsMatchIn(displayed))
        }
    }

    @Test
    fun `unknown Latin labels fail closed to a Greek context label`() {
        val raw = "FUTURE PROVIDER VALUE"
        val expected = mapOf(
            ProviderLabelKind.DIET to "Άλλη διατροφή",
            ProviderLabelKind.MEAL_TYPE to "Άλλο είδος γεύματος",
            ProviderLabelKind.OCCASION to "Άλλη περίσταση",
            ProviderLabelKind.METHOD to "Άλλος τρόπος μαγειρέματος",
            ProviderLabelKind.CUISINE to "Άλλη κουζίνα",
            ProviderLabelKind.INGREDIENT to "Άλλο υλικό",
            ProviderLabelKind.TAG to "Άλλη επιλογή",
        )

        expected.forEach { (kind, fallback) ->
            assertEquals(fallback, greekProviderLabel(raw, kind))
        }
        assertEquals("FUTURE PROVIDER VALUE", raw)
    }

    @Test
    fun `Greek provider labels cross the UI boundary unchanged`() {
        assertEquals(
            "Χωρίς γλουτένη",
            greekProviderLabel("  Χωρίς   γλουτένη  ", ProviderLabelKind.DIET),
        )
    }

    private companion object {
        val LatinLetter = Regex("[A-Za-z]")
    }
}
