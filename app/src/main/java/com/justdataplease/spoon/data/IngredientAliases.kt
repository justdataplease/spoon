package com.justdataplease.spoon.data

import com.justdataplease.spoon.data.local.normalizedCatalogToken
import java.text.Normalizer

/**
 * Small, reviewed equivalence groups observed in the bundled provider taxonomies.
 *
 * Membership is exact after the catalog's accent/case/punctuation normalization. This
 * deliberately does not stem words or merge broader parent/child concepts such as
 * `Αλεύρι`/`Αλεύρι (ζύμες)` and plant milk varieties.
 */
private data class IngredientAliasGroup(
    val displayLabel: String,
    val aliases: List<String>,
) {
    val tokens: List<String> = aliases
        .map(String::normalizedCatalogToken)
        .filter(String::isNotBlank)
        .distinct()
    val identity: String = tokens.first()
}

private fun ingredientAliases(displayLabel: String, vararg aliases: String) =
    IngredientAliasGroup(displayLabel, aliases.toList())

private val ExactIngredientAliasGroups = listOf(
    ingredientAliases("Αυγό", "Αυγό", "Αυγά"),
    ingredientAliases("Πατάτα", "Πατάτα", "Πατάτες"),
    ingredientAliases("Ντομάτα", "Ντομάτα", "Ντομάτες"),
    ingredientAliases("Αγκινάρα", "Αγκινάρα", "Αγκινάρες"),
    ingredientAliases("Καρότο", "Καρότο", "Καρότα"),
    ingredientAliases("Παντζάρι", "Παντζάρι", "Παντζάρια"),
    ingredientAliases("Πράσο", "Πράσο", "Πράσα"),
    ingredientAliases("Ρεβίθια", "Ρεβίθια", "Ρεβύθια"),
    ingredientAliases("Φράουλα", "Φράουλα", "Φράουλες"),
    ingredientAliases("Βερίκοκο", "Βερίκοκο", "Βερίκοκα"),
    ingredientAliases("Βατόμουρο", "Βατόμουρο", "Βατόμουρα"),
    ingredientAliases("Κόλιανδρος", "Κόλιανδρος", "Κόλιανδρο"),
    ingredientAliases("Βλήτα", "Βλήτα", "Βλίτα"),
    ingredientAliases("Σουπιά", "Σουπιά", "Σουπιές"),
    ingredientAliases("Ελιά", "Ελιά", "Ελιές"),
    ingredientAliases("Χουρμάς", "Χουρμάς", "Χουρμάδες"),
    ingredientAliases("Αποξηραμένα φρούτα", "Αποξηραμένα φρούτα", "Ξερά φρούτα"),
    ingredientAliases("Ψάρι", "Ψάρι", "Ψάρια"),
    ingredientAliases("Φουντούκι", "Φουντούκι", "Φουντούκια"),

    // Display-only entries repair all-uppercase labels selected from Argiro's taxonomy.
    ingredientAliases("Κοτόπουλο", "Κοτόπουλο"),
    ingredientAliases("Κιμάς", "Κιμάς", "ΚΙΜΑΣ"),
    ingredientAliases("Μοσχάρι", "Μοσχάρι"),
    ingredientAliases("Χοιρινό", "Χοιρινό"),
    ingredientAliases("Τυρί", "Τυρί"),
    ingredientAliases("Αλεύρι", "Αλεύρι"),
    ingredientAliases("Αλεύρι (ζύμες)", "Αλεύρι (ζύμες)"),
    ingredientAliases("Ζυμαρικά", "Ζυμαρικά"),
    ingredientAliases("Σοκολάτα", "Σοκολάτα"),
    ingredientAliases("Γιαούρτι", "Γιαούρτι"),
)

private val IngredientAliasGroupByToken = buildMap {
    ExactIngredientAliasGroups.forEach { group ->
        group.tokens.forEach { token ->
            check(put(token, group) == null) { "Ingredient alias belongs to multiple groups: $token" }
        }
    }
}

/** Stable identity used to collapse exact provider aliases in settings. */
internal fun canonicalIngredientIdentity(value: String): String {
    val token = value.normalizedCatalogToken()
    return IngredientAliasGroupByToken[token]?.identity ?: token
}

/** Readable label for a reviewed alias, otherwise the provider label with whitespace cleaned. */
internal fun canonicalIngredientDisplayLabel(value: String): String {
    val cleaned = Normalizer.normalize(
        value.trim().replace(IngredientWhitespace, " "),
        Normalizer.Form.NFC,
    )
    if (cleaned.isBlank()) return ""
    return IngredientAliasGroupByToken[cleaned.normalizedCatalogToken()]?.displayLabel ?: cleaned
}

/** Exact normalized aliases that must all match an existing persisted exclusion. */
internal fun expandedIngredientAliasTokens(value: String): List<String> {
    val token = value.normalizedCatalogToken()
    if (token.isBlank()) return emptyList()
    return IngredientAliasGroupByToken[token]?.tokens ?: listOf(token)
}

private val IngredientWhitespace = Regex("\\s+")
