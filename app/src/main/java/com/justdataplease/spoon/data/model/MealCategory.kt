package com.justdataplease.spoon.data.model

import kotlinx.serialization.Serializable

/** Stable values stored in Firestore and SharedPreferences. */
@Serializable
enum class MealCategory(
    val key: String,
    val greekLabel: String,
) {
    LEGUMES("legumes", "Όσπρια"),
    POULTRY("poultry", "Κοτόπουλο"),
    VEGETABLES("vegetables", "Λαχανικά"),
    MEAT("meat", "Κρέας"),
    FISH("fish", "Ψάρι"),
    STREET_FOOD("street_food", "Βρώμικο"),
    PASTA_RICE("pasta_rice", "Ζυμαρικά & ρύζι"),
    ANY("any", "Όλα"),
    ;

    companion object {
        fun fromKey(key: String): MealCategory? = entries.firstOrNull { it.key == key }
    }
}

