package com.justdataplease.spoon.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FacetAliasesTest {
    @Test
    fun `observed provider spellings share readable facet choices`() {
        val cases = listOf(
            Triple("cuisine", listOf("Αυστραλέζικη", "Αυστραλία", "Αυστραλιανή"), "Αυστραλιανή"),
            Triple("cuisine", listOf("Μεξικανινη", "Μεξικό"), "Μεξικάνικη"),
            Triple("cuisine", listOf("Ανατολή", "Ανατολίτικη"), "Ανατολίτικη"),
            Triple("diet", listOf("vegatarian", "Vegetarian"), "Χορτοφαγική"),
            Triple("occasion", listOf("Καλοκαίρι", "Καλοκαιρινές συνταγές"), "Καλοκαίρι"),
            Triple("occasion", listOf("Χειμώνας", "Χειμωνιάτικες συνταγές"), "Χειμώνας"),
            Triple("occasion", listOf("Για παιδιά", "Παιδικά"), "Για παιδιά"),
            Triple("occasion", listOf("Παιδικό πάρτι", "ΠΑΙΔΙΚΑ ΠΑΡΤΙ"), "Παιδικό πάρτι"),
            Triple("meal", listOf("Παιδικά", "ΣΥΝΤΑΓΕΣ ΓΙΑ ΠΑΙΔΙΑ"), "Για παιδιά"),
            Triple("meal", listOf("ΟΡΕΚΤΙΚΑ", "Ορεκτικό"), "Ορεκτικά"),
        )
        cases.forEach { (facet, labels, expected) ->
            assertEquals(facet, listOf(expected), canonicalFacetLabels(facet, labels))
        }
    }

    @Test
    fun `related cuisines occasions diets and meal types retain separate choices`() {
        val cases = listOf(
            "cuisine" to listOf("Αγγλική", "Βρετανική"),
            "cuisine" to listOf("Πολίτικη", "Μικρασιατική", "Σμυρνέικη Κουζίνα"),
            "diet" to listOf("Vegan", "Vegetarian", "Νηστεία"),
            "occasion" to listOf("Για παιδιά", "Παιδικό πάρτι", "Πάρτι"),
            "occasion" to listOf("Νηστεία", "Σαρακοστή", "Πάσχα"),
            "occasion" to listOf("Καλοκαίρι", "Χειμώνας", "Για όλο τον χρόνο"),
            "meal" to listOf("Ροφήματα", "Ροφήματα & ποτά"),
            "meal" to listOf("Ορεκτικά", "Ορεκτικό / Μεζές"),
            "meal" to listOf("Παιδικά", "Βρεφικά"),
        )
        cases.forEach { (facet, labels) ->
            assertEquals(facet, labels.size, canonicalFacetTokens(facet, labels).size)
        }
    }
}
