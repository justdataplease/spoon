package com.justdataplease.spoon.data

import com.justdataplease.spoon.data.local.normalizedCatalogToken

// Keep aligned with tools/recipe_importer/facet_aliases.json; equivalent labels only.
private val FacetAliasGroups = mapOf(
    "diet" to listOf(
        listOf("Vegan", "Αυστηρά χορτοφαγική (vegan)", "Αυστηρά χορτοφαγική", "VeganDiet"),
        listOf("Χορτοφαγική", "Vegetarian", "VegetarianDiet", "Χορτοφαγικά", "Χορτοφαγική (Vegetarian)", "vegatarian"),
        listOf("Χωρίς γλουτένη", "Gluten Free", "GlutenFreeDiet"),
        listOf("Χωρίς γαλακτοκομικά", "Dairy Free"),
        listOf("Χωρίς λακτόζη", "Lactose Free", "LactoseFreeDiet"),
        listOf("Χωρίς ζάχαρη", "Sugar Free"),
        listOf("Χωρίς αυγά", "Egg Free"),
        listOf("Νηστίσιμα", "Νηστίσιμο", "Νηστεία")
    ),
    "meal" to listOf(
        listOf("Κυρίως γεύμα", "Κυρίως γεύματα", "Main Course", "Main dish"),
        listOf("Γλυκό", "Γλυκά", "Dessert", "Desserts"),
        listOf("Σαλάτα", "Σαλάτες", "Salad"),
        listOf("Συνοδευτικά", "Συνοδευτικό", "Side dish"),
        listOf("Πρωινό", "Breakfast"),
        listOf("Μπραντς", "Brunch"),
        listOf("Σνακ", "Snack", "Snacks"),
        listOf("Για παιδιά", "Παιδικά", "Συνταγές για παιδιά"),
        listOf("Ορεκτικά", "Ορεκτικό")
    ),
    "occasion" to listOf(
        listOf("Χριστούγεννα", "Χριστουγεννιάτικη", "Χριστουγεννιάτικα", "Χριστουγεννιάτικες συνταγές", "Christmas"),
        listOf("Πάσχα", "Easter"),
        listOf("Άγιος Βαλεντίνος", "Αγίου Βαλεντίνου", "Valentine's Day"),
        listOf("Πρωτοχρονιά", "New Year"),
        listOf("Καλοκαίρι", "Καλοκαιρινές συνταγές"),
        listOf("Χειμώνας", "Χειμωνιάτικες συνταγές"),
        listOf("Για παιδιά", "Παιδικά", "Συνταγές για παιδιά"),
        listOf("Παιδικό πάρτι", "Παιδικά πάρτι")
    ),
    "method" to listOf(
        listOf("Air Fryer", "Airfryer", "Φριτέζα αέρος"),
        listOf("BBQ", "Barbeque", "Barbecue", "Μπάρμπεκιου")
    ),
    "cuisine" to listOf(
        listOf("Ελληνική", "Ελλάδα", "Greek", "Ελληνική κουζίνα"),
        listOf("Ιταλική", "Ιταλία", "Italian", "Ιταλική κουζίνα"),
        listOf("Γαλλική", "Γαλλία", "French", "Γαλλική κουζίνα"),
        listOf("Αγγλική", "Αγγλία", "English"),
        listOf("Αμερικάνικη", "Αμερική", "American"),
        listOf("Μεξικάνικη", "Μεξικό", "Mexican", "Μεξικανινη"),
        listOf("Ινδική", "Ινδία", "Indian"),
        listOf("Ισπανική", "Ισπανία", "Spanish"),
        listOf("Πολίτικη", "Πολίτικη κουζίνα"),
        listOf("Μικρασιατική", "Μικρασιατική κουζίνα"),
        listOf("Κυπριακή", "Κύπρος", "Cypriot"),
        listOf("Αυστραλιανή", "Αυστραλέζικη", "Αυστραλία"),
        listOf("Ανατολίτικη", "Ανατολή")
    )
)
private val FacetAliases = FacetAliasGroups.mapValues { (_, groups) ->
    buildMap { groups.forEach { group -> group.forEach { put(it.normalizedCatalogToken(), group.first()) } } }
}

internal fun canonicalFacetLabels(facet: String, values: Iterable<String>): List<String> = values
    .flatMap { if (facet == "cuisine") it.split(',') else listOf(it) }
    .map { FacetAliases[facet]?.get(it.normalizedCatalogToken()) ?: it.trim() }
    .filter(String::isNotBlank)
    .distinctBy(String::normalizedCatalogToken)
    .sortedBy(String::normalizedCatalogToken)

internal fun canonicalFacetTokens(facet: String, values: Iterable<String>): Set<String> =
    canonicalFacetLabels(facet, values).map(String::normalizedCatalogToken).toSet()
