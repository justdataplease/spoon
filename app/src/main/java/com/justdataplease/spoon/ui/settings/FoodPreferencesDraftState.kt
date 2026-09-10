package com.justdataplease.spoon.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class FoodPreferencesDraftState(
    val ownerKey: String,
    private var baseline: MealPreferenceSettings,
    initialDraft: MealPreferenceSettings = baseline,
) {
    val draft = mutableStateOf(initialDraft)

    /** Remote changes update untouched fields, while edits in this open form take precedence. */
    fun receiveSettings(settings: MealPreferenceSettings) {
        if (settings == baseline) return
        val edited = draft.value
        fun <T> choose(before: T, local: T, remote: T): T = if (local == before) remote else local
        draft.value = settings.copy(
            excludedCategories = choose(baseline.excludedCategories, edited.excludedCategories, settings.excludedCategories),
            veganOnly = choose(baseline.veganOnly, edited.veganOnly, settings.veganOnly),
            excludedIngredientTerms = choose(baseline.excludedIngredientTerms, edited.excludedIngredientTerms, settings.excludedIngredientTerms),
            weekdayCategories = choose(baseline.weekdayCategories, edited.weekdayCategories, settings.weekdayCategories),
            sideWeekdayCategories = choose(baseline.sideWeekdayCategories, edited.sideWeekdayCategories, settings.sideWeekdayCategories),
            dessertWeekdayCategories = choose(baseline.dessertWeekdayCategories, edited.dessertWeekdayCategories, settings.dessertWeekdayCategories),
            favoritesOnly = choose(baseline.favoritesOnly, edited.favoritesOnly, settings.favoritesOnly),
            excludedSourceKeys = choose(baseline.excludedSourceKeys, edited.excludedSourceKeys, settings.excludedSourceKeys),
        )
        baseline = settings
    }

    fun encode(): String = Json.encodeToString(
        PreferenceDraftSnapshot.serializer(), PreferenceDraftSnapshot(ownerKey, baseline, draft.value),
    )

    companion object {
        fun restore(value: String, ownerKey: String): FoodPreferencesDraftState? =
            runCatching { Json.decodeFromString(PreferenceDraftSnapshot.serializer(), value) }
                .getOrNull()?.takeIf { it.ownerKey == ownerKey }
                ?.let { FoodPreferencesDraftState(it.ownerKey, it.baseline, it.draft) }

        fun saver(ownerKey: String) = Saver<FoodPreferencesDraftState, String>(
            save = { it.encode() },
            restore = { restore(it, ownerKey) },
        )
    }
}

@Serializable
private data class PreferenceDraftSnapshot(
    val ownerKey: String,
    val baseline: MealPreferenceSettings,
    val draft: MealPreferenceSettings,
)
