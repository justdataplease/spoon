package com.justdataplease.spoon.data.model

import com.google.firebase.firestore.DocumentId

/** Reflection-safe transport model for the single owner-scoped Firestore preferences document. */
data class MealPreferenceDocument(
    @set:DocumentId var id: String = "",
    val excludedCategories: List<String> = emptyList(),
    val veganOnly: Boolean = false,
    val excludedIngredientTerms: List<String> = emptyList(),
    val updatedAtEpochMillis: Long = 0L,
    val weekdayCategories: Map<String, String> = emptyMap(),
    val favoritesOnly: Boolean = false,
)
