package com.justdataplease.spoon.data.preferences

import android.content.Context
import com.justdataplease.spoon.data.model.MealCategory
import java.time.DayOfWeek
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Device-local meal preferences applied to recipe discovery and automatic planning.
 *
 * The default excludes the catch-all "other" category. An explicitly stored empty set
 * still means "include everything". Diet labels are requirements
 * (for example, selecting Vegan keeps only recipes carrying that label), while category
 * and ingredient terms are exclusions.
 */
data class MealPreferenceSettings(
    val excludedCategories: Set<String> = DEFAULT_EXCLUDED_CATEGORIES,
    val veganOnly: Boolean = false,
    val excludedIngredientTerms: Set<String> = emptySet(),
    val updatedAtEpochMillis: Long = 0L,
    val weekdayCategories: Map<String, String> = emptyMap(),
    val sideWeekdayCategories: Map<String, String> = emptyMap(),
    val dessertWeekdayCategories: Map<String, String> = emptyMap(),
    val favoritesOnly: Boolean = false,
    val excludedSourceKeys: Set<String> = emptySet(),
) {
    fun hasActiveSelections(): Boolean =
        excludedCategories != DEFAULT_EXCLUDED_CATEGORIES || veganOnly ||
            excludedIngredientTerms.isNotEmpty() ||
            weekdayCategories.isNotEmpty() || sideWeekdayCategories.isNotEmpty() ||
            dessertWeekdayCategories.isNotEmpty() || favoritesOnly || excludedSourceKeys.isNotEmpty()
}

internal val DEFAULT_EXCLUDED_CATEGORIES = setOf(MealCategory.OTHER.key)

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
            preferences.remove(WEEKDAY_CATEGORIES)
            preferences.remove(DESSERT_WEEKDAY_CATEGORIES)
            preferences.remove(SIDE_WEEKDAY_CATEGORIES)
            preferences.remove(FAVORITES_ONLY)
            preferences.remove(EXCLUDED_SOURCE_KEYS)
            clearPending(preferences)
        }
    }

    /**
     * Persists a local edit made before Firebase has identified an owner. The pending value is
     * deliberately separate from the last owner's cache, so an offline sign-out/startup cannot
     * expose or overwrite another account's preferences. The next authenticated owner claims it.
     */
    suspend fun replacePendingForNextOwner(settings: MealPreferenceSettings) {
        dataStore.edit { preferences ->
            preferences[HAS_PENDING_SETTINGS] = true
            preferences[PENDING_EXCLUDED_CATEGORIES] =
                settings.excludedCategories.cleanedPreferenceValues()
            preferences[PENDING_VEGAN_ONLY] = settings.veganOnly
            preferences[PENDING_WEEKDAY_CATEGORIES] = settings.weekdayCategories.encodedWeekdayCategories()
            preferences[PENDING_DESSERT_WEEKDAY_CATEGORIES] = settings.dessertWeekdayCategories.encodedWeekdayCategories()
            preferences[PENDING_SIDE_WEEKDAY_CATEGORIES] = settings.sideWeekdayCategories.encodedWeekdayCategories()
            preferences[PENDING_FAVORITES_ONLY] = settings.favoritesOnly
            preferences[PENDING_EXCLUDED_SOURCE_KEYS] = settings.excludedSourceKeys.canonicalExcludedSourceKeys()
            preferences[PENDING_EXCLUDED_INGREDIENT_TERMS] =
                settings.excludedIngredientTerms.cleanedPreferenceValues()
            preferences[PENDING_UPDATED_AT_EPOCH_MILLIS] = settings.updatedAtEpochMillis
                .coerceIn(0L, MAX_MEAL_PREFERENCE_EPOCH_MILLIS)
        }
    }

    /** Returns only an ownerless local edit, never the previous authenticated owner's cache. */
    suspend fun readPendingForNextOwner(): MealPreferenceSettings? = dataStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map(::decodePending)
        .first()

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
                preferences[HAS_PENDING_SETTINGS] == true ->
                    requireNotNull(decodePending(preferences))
                storedOwner == ownerUid -> decoded
                // Keep legacy values at revision zero. The repository promotes them only after
                // a server-backed empty snapshot, so an existing cloud document always wins.
                storedOwner == null && decoded.hasActiveSelections() -> decoded
                else -> MealPreferenceSettings()
            }
            preferences[OWNER_UID] = ownerUid
            encode(preferences, result)
            clearPending(preferences)
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
            val storedOwner = preferences[OWNER_UID]
            if (
                (storedOwner == ownerUid || storedOwner == null) &&
                settings.updatedAtEpochMillis >= current.updatedAtEpochMillis
            ) {
                preferences[OWNER_UID] = ownerUid
                encode(preferences, settings)
                clearPending(preferences)
                replaced = true
            }
        }
        return replaced
    }

    internal companion object {
        const val FILE_NAME = "meal_preference_settings"

        private val EXCLUDED_CATEGORIES = stringSetPreferencesKey("excluded_categories")
        private val VEGAN_ONLY = booleanPreferencesKey("vegan_only")
        private val WEEKDAY_CATEGORIES = stringSetPreferencesKey("weekday_categories")
        private val DESSERT_WEEKDAY_CATEGORIES = stringSetPreferencesKey("dessert_weekday_categories")
        private val SIDE_WEEKDAY_CATEGORIES = stringSetPreferencesKey("side_weekday_categories")
        private val FAVORITES_ONLY = booleanPreferencesKey("favorites_only")
        private val EXCLUDED_SOURCE_KEYS = stringSetPreferencesKey("excluded_source_keys")
        private val EXCLUDED_INGREDIENT_TERMS = stringSetPreferencesKey("excluded_ingredient_terms")
        private val UPDATED_AT_EPOCH_MILLIS = longPreferencesKey("updated_at_epoch_millis")
        private val OWNER_UID = stringPreferencesKey("owner_uid")
        private val HAS_PENDING_SETTINGS = booleanPreferencesKey("has_pending_settings")
        private val PENDING_EXCLUDED_CATEGORIES =
            stringSetPreferencesKey("pending_excluded_categories")
        private val PENDING_VEGAN_ONLY = booleanPreferencesKey("pending_vegan_only")
        private val PENDING_WEEKDAY_CATEGORIES = stringSetPreferencesKey("pending_weekday_categories")
        private val PENDING_DESSERT_WEEKDAY_CATEGORIES = stringSetPreferencesKey("pending_dessert_weekday_categories")
        private val PENDING_SIDE_WEEKDAY_CATEGORIES = stringSetPreferencesKey("pending_side_weekday_categories")
        private val PENDING_FAVORITES_ONLY = booleanPreferencesKey("pending_favorites_only")
        private val PENDING_EXCLUDED_SOURCE_KEYS = stringSetPreferencesKey("pending_excluded_source_keys")
        private val PENDING_EXCLUDED_INGREDIENT_TERMS =
            stringSetPreferencesKey("pending_excluded_ingredient_terms")
        private val PENDING_UPDATED_AT_EPOCH_MILLIS =
            longPreferencesKey("pending_updated_at_epoch_millis")

        fun decode(preferences: Preferences): MealPreferenceSettings = MealPreferenceSettings(
            excludedCategories = preferences[EXCLUDED_CATEGORIES]
                ?.cleanedPreferenceValues()
                ?: DEFAULT_EXCLUDED_CATEGORIES,
            veganOnly = preferences[VEGAN_ONLY] ?: false,
            weekdayCategories = preferences[WEEKDAY_CATEGORIES].orEmpty().decodedWeekdayCategories(),
            dessertWeekdayCategories = preferences[DESSERT_WEEKDAY_CATEGORIES].orEmpty().decodedWeekdayCategories(),
            sideWeekdayCategories = preferences[SIDE_WEEKDAY_CATEGORIES].orEmpty().decodedWeekdayCategories(),
            favoritesOnly = preferences[FAVORITES_ONLY] ?: false,
            excludedSourceKeys = preferences[EXCLUDED_SOURCE_KEYS].orEmpty().canonicalExcludedSourceKeys(),
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
            preferences[WEEKDAY_CATEGORIES] = settings.weekdayCategories.encodedWeekdayCategories()
            preferences[DESSERT_WEEKDAY_CATEGORIES] = settings.dessertWeekdayCategories.encodedWeekdayCategories()
            preferences[SIDE_WEEKDAY_CATEGORIES] = settings.sideWeekdayCategories.encodedWeekdayCategories()
            preferences[FAVORITES_ONLY] = settings.favoritesOnly
            preferences[EXCLUDED_SOURCE_KEYS] = settings.excludedSourceKeys.canonicalExcludedSourceKeys()
            preferences[EXCLUDED_INGREDIENT_TERMS] = settings.excludedIngredientTerms
                .cleanedPreferenceValues()
            preferences[UPDATED_AT_EPOCH_MILLIS] = settings.updatedAtEpochMillis
                .coerceIn(0L, MAX_MEAL_PREFERENCE_EPOCH_MILLIS)
        }

        private fun decodePending(preferences: Preferences): MealPreferenceSettings? {
            if (preferences[HAS_PENDING_SETTINGS] != true) return null
            return MealPreferenceSettings(
                excludedCategories = preferences[PENDING_EXCLUDED_CATEGORIES]
                    ?.cleanedPreferenceValues()
                    ?: DEFAULT_EXCLUDED_CATEGORIES,
                veganOnly = preferences[PENDING_VEGAN_ONLY] ?: false,
                weekdayCategories = preferences[PENDING_WEEKDAY_CATEGORIES].orEmpty().decodedWeekdayCategories(),
                dessertWeekdayCategories = preferences[PENDING_DESSERT_WEEKDAY_CATEGORIES].orEmpty().decodedWeekdayCategories(),
                sideWeekdayCategories = preferences[PENDING_SIDE_WEEKDAY_CATEGORIES].orEmpty().decodedWeekdayCategories(),
                favoritesOnly = preferences[PENDING_FAVORITES_ONLY] ?: false,
                excludedSourceKeys = preferences[PENDING_EXCLUDED_SOURCE_KEYS].orEmpty().canonicalExcludedSourceKeys(),
                excludedIngredientTerms = preferences[PENDING_EXCLUDED_INGREDIENT_TERMS]
                    .orEmpty()
                    .cleanedPreferenceValues(),
                updatedAtEpochMillis =
                    (preferences[PENDING_UPDATED_AT_EPOCH_MILLIS] ?: 0L)
                        .coerceIn(0L, MAX_MEAL_PREFERENCE_EPOCH_MILLIS),
            )
        }

        private fun clearPending(
            preferences: androidx.datastore.preferences.core.MutablePreferences,
        ) {
            preferences.remove(HAS_PENDING_SETTINGS)
            preferences.remove(PENDING_EXCLUDED_CATEGORIES)
            preferences.remove(PENDING_VEGAN_ONLY)
            preferences.remove(PENDING_WEEKDAY_CATEGORIES)
            preferences.remove(PENDING_DESSERT_WEEKDAY_CATEGORIES)
            preferences.remove(PENDING_SIDE_WEEKDAY_CATEGORIES)
            preferences.remove(PENDING_FAVORITES_ONLY)
            preferences.remove(PENDING_EXCLUDED_SOURCE_KEYS)
            preferences.remove(PENDING_EXCLUDED_INGREDIENT_TERMS)
            preferences.remove(PENDING_UPDATED_AT_EPOCH_MILLIS)
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

internal fun Map<String, String>.validWeekdayCategories(): Map<String, String> = filter { (day, category) ->
    DayOfWeek.entries.any { it.name == day } && MealCategory.fromKey(category) != null
}

private fun Map<String, String>.encodedWeekdayCategories(): Set<String> =
    validWeekdayCategories().map { (day, category) -> "$day=$category" }.toSet()

private fun Set<String>.decodedWeekdayCategories(): Map<String, String> = mapNotNull { entry ->
    val parts = entry.split('=', limit = 2)
    if (parts.size == 2) parts[0] to parts[1] else null
}.toMap().validWeekdayCategories()
