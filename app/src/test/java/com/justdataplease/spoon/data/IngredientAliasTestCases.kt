package com.justdataplease.spoon.data

/** Shared contract cases for every manually reviewed ingredient identity/display group. */
internal data class IngredientAliasTestCase(
    val canonical: String,
    val aliases: List<String>,
) {
    val providerAlias: String = aliases.last()
}

internal val ReviewedIngredientAliasCases = listOf(
    ingredientCase("Αυγό", "Αυγό", "Αυγά", "Αβγό", "Αβγά", "Αυγών", "Αβγών"),
    ingredientCase("Πατάτα", "Πατάτα", "Πατάτες"),
    ingredientCase("Ντομάτα", "Ντομάτα", "Ντομάτες", "Ντομάτας", "Τομάτα", "Τομάτες", "Τομάτας"),
    ingredientCase("Αγκινάρα", "Αγκινάρα", "Αγκινάρες"),
    ingredientCase("Καρότο", "Καρότο", "Καρότα"),
    ingredientCase("Παντζάρι", "Παντζάρι", "Παντζάρια"),
    ingredientCase("Πράσο", "Πράσο", "Πράσα"),
    ingredientCase("Ρεβίθια", "Ρεβίθια", "Ρεβύθια"),
    ingredientCase("Φράουλα", "Φράουλα", "Φράουλες"),
    ingredientCase("Βερίκοκο", "Βερίκοκο", "Βερίκοκα"),
    ingredientCase("Βατόμουρο", "Βατόμουρο", "Βατόμουρα"),
    ingredientCase("Κόλιανδρος", "Κόλιανδρος", "Κόλιανδρο"),
    ingredientCase("Βλήτα", "Βλήτα", "Βλίτα"),
    ingredientCase("Σουπιά", "Σουπιά", "Σουπιές"),
    ingredientCase("Ελιά", "Ελιά", "Ελιές"),
    ingredientCase("Χουρμάς", "Χουρμάς", "Χουρμάδες"),
    ingredientCase("Αποξηραμένα φρούτα", "Αποξηραμένα φρούτα", "Ξερά φρούτα"),
    ingredientCase("Ψάρι", "Ψάρι", "Ψάρια"),
    ingredientCase("Φουντούκι", "Φουντούκι", "Φουντούκια"),

    // Single-token groups still lock provider capitalization to the reviewed display label.
    ingredientCase("Κοτόπουλο", "Κοτόπουλο", "Κοτόπουλου"),
    ingredientCase("Κιμάς", "Κιμάς"),
    ingredientCase("Μοσχάρι", "Μοσχάρι", "Μοσχαράκι"),
    ingredientCase("Χοιρινό", "Χοιρινό"),
    ingredientCase("Τυρί", "Τυρί", "Τυριά"),
    ingredientCase("Αλεύρι", "Αλεύρι"),
    ingredientCase("Αλεύρι (ζύμες)", "Αλεύρι (ζύμες)"),
    ingredientCase("Ζυμαρικά", "Ζυμαρικά"),
    ingredientCase("Σοκολάτα", "Σοκολάτα", "Σοκολάτας"),
    ingredientCase("Γιαούρτι", "Γιαούρτι"),
)

internal val DistinctIngredientConceptCases = listOf(
    "Αλεύρι" to "Αλεύρι (ζύμες)",
    "Γάλα αμυγδάλου" to "Γάλα βρώμης",
    "Γάλα" to "Ζαχαρούχο γάλα",
    "Γάλα εβαπορέ" to "Ζαχαρούχο γάλα",
    "Αλεύρι βρώμης" to "Αλεύρι ολικής άλεσης",
    "Τυρί" to "Τυρί κότατζ",
    "Μυζήθρα" to "Κεφαλοτύρι",
    "Αυγό" to "Ασπράδι αυγού",
    "Αυγό" to "Κρόκος αυγού",
    "Ασπράδι αυγού" to "Κρόκος αυγού",
    "Κρόκος αυγού" to "Σαφράν",
    "Μπέικιν πάουντερ" to "Μαγειρική σόδα",
)

private fun ingredientCase(canonical: String, vararg aliases: String) =
    IngredientAliasTestCase(canonical = canonical, aliases = aliases.toList())
