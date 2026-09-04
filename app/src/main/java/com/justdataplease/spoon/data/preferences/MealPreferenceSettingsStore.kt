package com.justdataplease.spoon.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Device-local meal preferences applied to recipe discovery and automatic planning.
 *
 * Empty sets intentionally mean "include everything". Diet labels are requirements
 * (for example, selecting Vegan keeps only recipes carrying that label), while category
 * and ingredient terms are exclusions.
 */
data class MealPreferenceSettings(
    val excludedCategories: Set<String> = emptySet(),
    val veganOnly: Boolean = false,
    val excludedIngredientTerms: Set<String> = emptySet(),
    val updatedAtEpochMillis: Long = 0L,
) {
    fun hasActiveSelections(): Boolean =
        excludedCategories.isNotEmpty() || veganOnly || excludedIngredientTerms.isNotEmpty()
}

internal const val MAX_MEAL_PREFERENCE_EPOCH_MILLIS = 253_402_300_799_999L

private val Context.mealPreferenceSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = MealPreferenceSettingsStore.FILE_NAME,
)

@Singleton
class MealPreferenceSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.applicationContext.mealPreferenceSettingsDataStore,
    )

    /** Always emits a usable default if the preferences file has a transient read failure. */
    val settings: Flow<MealPreferenceSettings> = dataStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map(::decode)
        .distinctUntilChanged()

    suspend fun setExcludedCategories(values: Set<String>) {
        dataStore.edit { preferences ->
            preferences[EXCLUDED_CATEGORIES] = values.cleanedPreferenceValues()
        }
    }

    suspend fun setVeganOnly(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VEGAN_ONLY] = enabled
        }
    }

    suspend fun setExcludedIngredientTerms(values: Set<String>) {
        dataStore.edit { preferences ->
            preferences[EXCLUDED_INGREDIENT_TERMS] = values.cleanedPreferenceValues()
        }
    }

    /** Atomically updates any combination of preference groups. */
    suspend fun update(transform: (MealPreferenceSettings) -> MealPreferenceSettings) {
        dataStore.edit { preferences ->
            encode(preferences, transform(decode(preferences)))
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(EXCLUDED_CATEGORIES)
            preferences.remove(VEGAN_ONLY)
            preferences.remove(EXCLUDED_INGREDIENT_TERMS)
            preferences.remove(UPDATED_AT_EPOCH_MILLIS)
        }
    }

    /**
     * Activates an account-scoped cache. Legacy v0.7 values are claimed once by the account
     * that is signed in during the upgrade; switching accounts never exposes another owner's data.
     */
    suspend fun readForOwner(ownerUid: String): MealPreferenceSettings {
        require(ownerUid.isNotBlank())
        var result = MealPreferenceSettings()
        dataStore.edit { preferences ->
            val storedOwner = preferences[OWNER_UID]
            val decoded = decode(preferences)
            result = when {
                storedOwner == ownerUid -> decoded
                // Keep legacy values at revision zero. The repository promotes them only after
                // a server-backed empty snapshot, so an existing cloud document always wins.
                storedOwner == null && decoded.hasActiveSelections() -> decoded
                else -> MealPreferenceSettings()
            }
            preferences[OWNER_UID] = ownerUid
            encode(preferences, result)
        }
        return result
    }

    suspend fun replaceForOwner(
        ownerUid: String,
        settings: MealPreferenceSettings,
    ): Boolean {
        require(ownerUid.isNotBlank())
        var replaced = false
        dataStore.edit { preferences ->
            val current = decode(preferences)
            if (
                preferences[OWNER_UID] == ownerUid &&
                settings.updatedAtEpochMillis >= current.updatedAtEpochMillis
            ) {
                encode(preferences, settings)
                replaced = true
            }
        }
        return replaced
    }

    internal companion object {
        const val FILE_NAME = "meal_preference_settings"

        private val EXCLUDED_CATEGORIES = stringSetPreferencesKey("excluded_categories")
        private val VEGAN_ONLY = booleanPreferencesKey("vegan_only")
        private val EXCLUDED_INGREDIENT_TERMS = stringSetPreferencesKey("excluded_ingredient_terms")
        private val UPDATED_AT_EPOCH_MILLIS = longPreferencesKey("updated_at_epoch_millis")
        private val OWNER_UID = stringPreferencesKey("owner_uid")

        fun decode(preferences: Preferences): MealPreferenceSettings = MealPreferenceSettings(
            excludedCategories = preferences[EXCLUDED_CATEGORIES].orEmpty().cleanedPreferenceValues(),
            veganOnly = preferences[VEGAN_ONLY] ?: false,
            excludedIngredientTerms = preferences[EXCLUDED_INGREDIENT_TERMS]
                .orEmpty()
                .cleanedPreferenceValues(),
            updatedAtEpochMillis = (preferences[UPDATED_AT_EPOCH_MILLIS] ?: 0L)
                .coerceIn(0L, MAX_MEAL_PREFERENCE_EPOCH_MILLIS),
        )

        fun encode(
            preferences: androidx.datastore.preferences.core.MutablePreferences,
            settings: MealPreferenceSettings,
        ) {
            preferences[EXCLUDED_CATEGORIES] = settings.excludedCategories.cleanedPreferenceValues()
            preferences[VEGAN_ONLY] = settings.veganOnly
            preferences[EXCLUDED_INGREDIENT_TERMS] = settings.excludedIngredientTerms
                .cleanedPreferenceValues()
            preferences[UPDATED_AT_EPOCH_MILLIS] = settings.updatedAtEpochMillis
                .coerceIn(0L, MAX_MEAL_PREFERENCE_EPOCH_MILLIS)
        }
    }
}

internal fun nextMealPreferenceTimestamp(now: Long, current: Long): Long {
    val boundedCurrent = current.coerceIn(0L, MAX_MEAL_PREFERENCE_EPOCH_MILLIS)
    val incremented = if (boundedCurrent == MAX_MEAL_PREFERENCE_EPOCH_MILLIS) {
        MAX_MEAL_PREFERENCE_EPOCH_MILLIS
    } else {
        boundedCurrent + 1L
    }
    return maxOf(
        now.coerceIn(1L, MAX_MEAL_PREFERENCE_EPOCH_MILLIS),
        incremented,
    )
}

private fun Set<String>.cleanedPreferenceValues(): Set<String> = asSequence()
    .map { it.trim().replace(Whitespace, " ") }
    .filter(String::isNotEmpty)
    .distinct()
    .toSet()

private val Whitespace = Regex("\\s+")
