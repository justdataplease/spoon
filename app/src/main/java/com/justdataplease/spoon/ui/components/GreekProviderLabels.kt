package com.justdataplease.spoon.ui.components

import java.text.Normalizer
import java.util.Locale

/**
 * Provider taxonomy stays untouched in the data/domain layers. Only text crossing the UI boundary
 * is translated, so exact raw values continue to drive filters and remain in the source payload.
 */
internal enum class ProviderLabelKind(val fallbackLabel: String) {
    DIET("Άλλη διατροφή"),
    MEAL_TYPE("Άλλο είδος γεύματος"),
    OCCASION("Άλλη περίσταση"),
    METHOD("Άλλος τρόπος μαγειρέματος"),
    CUISINE("Άλλη κουζίνα"),
    INGREDIENT("Άλλο υλικό"),
    TAG("Άλλη επιλογή"),
}

internal fun greekProviderLabel(rawValue: String, kind: ProviderLabelKind): String {
    val value = rawValue.trim().replace(LabelWhitespace, " ")
    if (value.isEmpty()) return ""
    KnownProviderLabels[value.providerLabelKey()]?.let { return it }
    return if (LatinLetter.containsMatchIn(value)) kind.fallbackLabel else value
}

private fun String.providerLabelKey(): String =
    Normalizer.normalize(trim().replace(LabelWhitespace, " "), Normalizer.Form.NFD)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .lowercase(Locale.ROOT)
        .replace('ς', 'σ')

private val LabelWhitespace = Regex("\\s+")
private val LatinLetter = Regex("[A-Za-z]")

private val KnownProviderLabels = mapOf(
    "χριστούγεννα" to "Χριστουγεννιάτικη",
    "christmas" to "Χριστουγεννιάτικη",
    "air fryer" to "Φριτέζα αέρος",
    "barbeque" to "Μπάρμπεκιου",
    "bbq" to "Μπάρμπεκιου",
    "blueberry" to "Μύρτιλο",
    "brownies" to "Μπράουνι",
    "brunch" to "Μπραντς",
    "budget meals" to "Οικονομικά γεύματα",
    "burger" to "Μπέργκερ",
    "cheesecake" to "Τσιζκέικ",
    "cocktails" to "Κοκτέιλ",
    "cupcakes & muffins" to "Κάπκεϊκ και μάφιν",
    "egg free" to "Χωρίς αυγά",
    "editor's choice" to "Επιλογή συντάκτη",
    "finger food" to "Φαγητό στο χέρι",
    "fingerfood" to "Μπουκιές",
    "halloween" to "Χάλογουιν",
    "leftovers" to "Αξιοποίηση περισσευμάτων",
    "light" to "Ελαφριά",
    "low budget" to "Οικονομική επιλογή",
    "lunch box" to "Κολατσιό",
    "one pan" to "Σε ένα σκεύος",
    "pancakes" to "Πάνκεϊκ",
    "superfoods" to "Υπερτροφές",
    "to share" to "Για μοίρασμα",
    "vegan" to "Αυστηρά χορτοφαγική",
    "vegetarian" to "Χορτοφαγική",
    "αυστηρά χορτοφαγική (vegan)" to "Αυστηρά χορτοφαγική",
    "λουκουμάδες & donuts" to "Λουκουμάδες",
    "πατάτες baby" to "Μικρές πατάτες",
    "συνταγές airfryer" to "Συνταγές φριτέζας αέρος",
    "υγιεινά - light γλυκά" to "Υγιεινά ελαφριά γλυκά",
    "χυμοί & smoothies" to "Χυμοί και σμούθι",
).mapKeys { (rawValue, _) -> rawValue.providerLabelKey() }
