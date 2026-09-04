package com.justdataplease.spoon.data

/** Shared contract cases for every manually reviewed ingredient identity/display group. */
internal data class IngredientAliasTestCase(
    val canonical: String,
    val aliases: List<String>,
) {
    val providerAlias: String = aliases.last()
}

internal val ReviewedIngredientAliasCases = listOf(
    ingredientCase("Αυγό", "Αυγό", "Αυγά"),
    ingredientCase("Πατάτα", "Πατάτα", "Πατάτες"),
    ingredientCase("Ντομάτα", "Ντομάτα", "Ντομάτες"),
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
    ingredientCase(
        "Αποξηραμένα φρούτα",
        "Αποξηραμένα φρούτα",
        "Ξερά φρούτα",
    ),
    ingredientCase("Ψάρι", "Ψάρι", "Ψάρια"),
    ingredientCase("Φουντούκι", "Φουντούκι", "Φουντούκια"),

    // Single-token groups still lock provider capitalization to the reviewed display label.
    ingredientCase("Κοτόπουλο", "Κοτόπουλο", "ΚΟΤΟΠΟΥΛΟ"),
    ingredientCase("Κιμάς", "Κιμάς", "ΚΙΜΑΣ"),
    ingredientCase("Μοσχάρι", "Μοσχάρι", "ΜΟΣΧΑΡΙ"),
    ingredientCase("Χοιρινό", "Χοιρινό", "ΧΟΙΡΙΝΟ"),
    ingredientCase("Τυρί", "Τυρί", "ΤΥΡΙ"),
    ingredientCase("Αλεύρι", "Αλεύρι", "ΑΛΕΥΡΙ"),
    ingredientCase("Αλεύρι (ζύμες)", "Αλεύρι (ζύμες)", "ΑΛΕΥΡΙ (ΖΥΜΕΣ)"),
    ingredientCase("Ζυμαρικά", "Ζυμαρικά", "ΖΥΜΑΡΙΚΑ"),
    ingredientCase("Σοκολάτα", "Σοκολάτα", "ΣΟΚΟΛΑΤΑ"),
    ingredientCase("Γιαούρτι", "Γιαούρτι", "ΓΙΑΟΥΡΤΙ"),
)

internal val DistinctIngredientConceptCases = listOf(
    "Αλεύρι" to "Αλεύρι (ζύμες)",
    "Γάλα αμυγδάλου" to "Γάλα βρώμης",
)

private fun ingredientCase(canonical: String, vararg aliases: String) =
    IngredientAliasTestCase(canonical = canonical, aliases = aliases.toList())
