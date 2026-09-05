package com.justdataplease.spoon.data.local

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.core.content.pm.PackageInfoCompat
import com.justdataplease.spoon.data.expandedIngredientAliasTokens
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.data.requireSafeRecipeDocumentId
import com.justdataplease.spoon.domain.ExploreCriteria
import com.justdataplease.spoon.domain.repository.CatalogFacetOptions
import com.justdataplease.spoon.domain.repository.CatalogSourceOption
import com.justdataplease.spoon.domain.repository.RecipePage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.LinkedHashMap
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/**
 * Indexed, offline catalog backed by the immutable SQLite database packaged in the APK.
 * Only decoded rows enter memory and the observable cache has a strict upper bound.
 */
class BundledRecipeCatalog(
    context: Context,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RecipeCatalog {
    private val appContext = context.applicationContext
    private val databaseLock = Any()
    private val cacheLock = Any()
    private val cache = LinkedHashMap<String, Recipe>(CACHE_LIMIT + 1, 0.75f, true)
    private val _cachedRecipes = MutableStateFlow<List<Recipe>>(emptyList())

    @Volatile
    private var openDatabase: SQLiteDatabase? = null

    @Volatile
    private var cachedFacetOptions: CatalogFacetOptions? = null

    override val cachedRecipes: StateFlow<List<Recipe>> = _cachedRecipes.asStateFlow()

    override suspend fun ensureReady() {
        withContext(ioDispatcher) { database() }
    }

    override suspend fun getRecipe(recipeId: String): Recipe? = withContext(ioDispatcher) {
        val safeId = requireSafeRecipeDocumentId(recipeId)
        synchronized(cacheLock) { cache[safeId] }?.let { return@withContext it }
        val decoded = database().rawQuery(
            "SELECT id, recipe_json FROM recipes WHERE id = ? LIMIT 1",
            arrayOf(safeId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            decode(cursor)
        }
        cacheAll(listOf(decoded))
        decoded
    }

    override suspend fun getRecipesByIds(recipeIds: Set<String>): List<Recipe> =
        withContext(ioDispatcher) {
            if (recipeIds.isEmpty()) return@withContext emptyList()
            val safeIds = recipeIds.map(::requireSafeRecipeDocumentId).toSet()
            val found = LinkedHashMap<String, Recipe>()
            synchronized(cacheLock) {
                safeIds.forEach { id -> cache[id]?.let { found[id] = it } }
            }
            val missing = safeIds - found.keys
            val decodedRecipes = mutableListOf<Recipe>()
            missing.chunked(SQLITE_MAX_BOUND_IDS).forEach { chunk ->
                database().rawQuery(
                    "SELECT id, recipe_json FROM recipes WHERE id IN (${placeholders(chunk.size)})",
                    chunk.toTypedArray(),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        decode(cursor).let {
                            decodedRecipes += it
                            found[it.id] = it
                        }
                    }
                }
            }
            cacheAll(decodedRecipes)
            safeIds.mapNotNull(found::get)
        }

    override suspend fun queryRecipes(
        criteria: ExploreCriteria,
        limit: Int,
        offset: Int,
        preferences: MealPreferenceSettings,
    ): RecipePage = withContext(ioDispatcher) {
        requirePageBounds(limit, offset)
        if (!criteria.isValid()) return@withContext RecipePage(emptyList(), 0, offset, limit)
        val selection = CatalogSqlBuilder.forExplore(criteria, preferences)
        val totalCount = countMatches(selection)
        if (offset >= totalCount) {
            return@withContext RecipePage(emptyList(), totalCount, offset, limit)
        }
        val arguments = selection.arguments + limit.toString() + offset.toString()
        val recipes = database().rawQuery(
            """
                SELECT r.id, r.recipe_json
                FROM recipes r
                ${selection.whereSql}
                ORDER BY r.rating DESC, r.title_normalized ASC, r.id ASC
                LIMIT ? OFFSET ?
            """.trimIndent(),
            arguments.toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(decode(cursor))
            }
        }
        cacheAll(recipes)
        RecipePage(recipes, totalCount, offset, limit)
    }

    override suspend fun getFacetOptions(): CatalogFacetOptions = withContext(ioDispatcher) {
        cachedFacetOptions?.let { return@withContext it }
        val database = database()
        val optionsJson = database.metaValue(META_FACET_OPTIONS)
            ?: throw SQLiteException("Catalog is missing $META_FACET_OPTIONS")
        val optionsObject = json.parseToJsonElement(optionsJson).jsonObject
        fun labels(type: String): List<String> = optionsObject[type]
            ?.jsonArray
            .orEmpty()
            .mapNotNull { option ->
                option.jsonObject["label"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
            }

        val sourceCounts = database.metaValue(META_SOURCE_COUNTS)
            ?.let(json::parseToJsonElement)
            ?.jsonObject
            .orEmpty()
        val sources = sourceCounts.entries.map { (key, countElement) ->
            val label = database.rawQuery(
                "SELECT id, recipe_json FROM recipes WHERE source_key = ? ORDER BY id LIMIT 1",
                arrayOf(key),
            ).use { cursor ->
                if (cursor.moveToFirst()) decode(cursor).sourceName.trim() else ""
            }.ifBlank { SOURCE_LABELS[key] ?: key }
            CatalogSourceOption(key, label, countElement.jsonPrimitive.content.toInt())
        }.sortedBy { it.label.normalizedCatalogToken() }

        CatalogFacetOptions(
            sources = sources,
            diets = labels(FACET_DIET),
            mealTypes = labels(FACET_MEAL),
            occasions = labels(FACET_OCCASION),
            methods = labels(FACET_METHOD),
            cuisines = labels(FACET_CUISINE),
            ingredients = labels(FACET_INGREDIENT),
        ).also { cachedFacetOptions = it }
    }

    override suspend fun selectRandomRecipe(
        filters: RecipeFilters,
        excludingRecipeId: String?,
        randomSeed: Long,
        preferences: MealPreferenceSettings,
    ): Recipe? = withContext(ioDispatcher) {
        if (!filters.isValid()) return@withContext null
        val selection = plannerSelection(filters, excludingRecipeId, preferences)
        val candidateCount = countMatches(selection)
        if (candidateCount == 0) return@withContext null
        selectAtOffset(
            selection = selection,
            offset = Random(randomSeed).nextInt(candidateCount),
        )
    }

    override suspend fun countPlannerMatches(
        filters: RecipeFilters,
        excludingRecipeId: String?,
        preferences: MealPreferenceSettings,
    ): Int = withContext(ioDispatcher) {
        if (!filters.isValid()) return@withContext 0
        countMatches(plannerSelection(filters, excludingRecipeId, preferences))
    }

    private fun plannerSelection(
        filters: RecipeFilters,
        excludingRecipeId: String?,
        preferences: MealPreferenceSettings,
    ): CatalogSql =
        CatalogSqlBuilder.forPlanner(
            filters,
            excludingRecipeId?.takeIf(String::isNotBlank)?.let(::requireSafeRecipeDocumentId),
            preferences,
        )

    private fun countMatches(selection: CatalogSql): Int = database().rawQuery(
        "SELECT COUNT(*) FROM recipes r ${selection.whereSql}",
        selection.arguments.toTypedArray(),
    ).singleInt()

    /** Selects one exact uniform index from every matching recipe, independent of provider. */
    private fun selectAtOffset(selection: CatalogSql, offset: Int): Recipe? =
        database().rawQuery(
            """
                SELECT r.id, r.recipe_json
                FROM recipes r
                ${selection.whereSql}
                ORDER BY r.id ASC
                LIMIT 1 OFFSET ?
            """.trimIndent(),
            (selection.arguments + offset.toString()).toTypedArray(),
        ).use { cursor ->
            if (cursor.moveToFirst()) decode(cursor) else null
        }?.also { cacheAll(listOf(it)) }

    private fun decode(cursor: Cursor): Recipe {
        val rowId = cursor.getString(0)
        val compressedJson = cursor.getBlob(1)
        val serialized = InflaterInputStream(ByteArrayInputStream(compressedJson))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val recipe = json.decodeFromString<Recipe>(serialized)
        check(recipe.id == rowId) { "Catalog row id does not match its recipe payload" }
        return recipe
    }

    private fun cacheAll(recipes: Collection<Recipe>) {
        if (recipes.isEmpty()) return
        synchronized(cacheLock) {
            recipes.forEach { recipe -> cache[recipe.id] = recipe }
            while (cache.size > CACHE_LIMIT) {
                cache.remove(cache.entries.first().key)
            }
            val snapshot = cache.values.sortedBy(Recipe::title)
            if (_cachedRecipes.value != snapshot) _cachedRecipes.value = snapshot
        }
    }

    private fun database(): SQLiteDatabase {
        openDatabase?.takeIf(SQLiteDatabase::isOpen)?.let { return it }
        return synchronized(databaseLock) {
            openDatabase?.takeIf(SQLiteDatabase::isOpen) ?: installAndOpenDatabase().also {
                openDatabase = it
            }
        }
    }

    private fun installAndOpenDatabase(): SQLiteDatabase {
        val installDirectory = appContext.noBackupFilesDir
        check(installDirectory.isDirectory || installDirectory.mkdirs() || installDirectory.isDirectory) {
            "Could not create catalog installation directory"
        }
        val target = installedCatalogFile(installDirectory)
        val preferences = appContext.getSharedPreferences(INSTALL_PREFERENCES, Context.MODE_PRIVATE)
        val installStamp = packageInstallStamp()
        val canReuse = target.isFile && preferences.getString(INSTALL_STAMP_KEY, null) == installStamp
        if (!canReuse || runCatching { validateDatabase(target) }.isFailure) {
            // Keep staging beside the target so the validated replacement can be an atomic rename.
            val staging = File(installDirectory, "$INSTALLED_DATABASE_NAME.installing")
            runCatching { Files.deleteIfExists(staging.toPath()) }
            try {
                appContext.assets.open(ASSET_DATABASE_NAME).use { input ->
                    staging.outputStream().buffered().use(input::copyTo)
                }
                validateDatabase(staging)
                try {
                    Files.move(
                        staging.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        staging.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                check(preferences.edit().putString(INSTALL_STAMP_KEY, installStamp).commit())
            } finally {
                Files.deleteIfExists(staging.toPath())
            }
        }
        return SQLiteDatabase.openDatabase(
            target.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
    }

    private fun validateDatabase(file: File) {
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { database ->
            val applicationId = database.rawQuery("PRAGMA application_id", null).singleInt()
            val userVersion = database.rawQuery("PRAGMA user_version", null).singleInt()
            check(applicationId == EXPECTED_APPLICATION_ID) { "Unexpected catalog application id" }
            check(userVersion == EXPECTED_SCHEMA_VERSION) { "Unsupported catalog schema $userVersion" }
            check(database.metaValue(META_SCHEMA_VERSION) == EXPECTED_SCHEMA_VERSION.toString())
            check(database.metaValue(META_JSON_COMPRESSION) == JSON_COMPRESSION_ZLIB)
            check(!database.metaValue(META_CATALOG_HASH).isNullOrBlank())
            check(!database.metaValue(META_CATALOG_VERSION).isNullOrBlank())
            check(!database.metaValue(META_SOURCE_COUNTS).isNullOrBlank())
            check(!database.metaValue(META_FACET_OPTIONS).isNullOrBlank())
            check(database.metaValue(META_INGREDIENT_TEXT_INDEX) == INGREDIENT_TEXT_INDEX_VERSION)
            val expectedCount = database.metaValue(META_CATALOG_COUNT)?.toIntOrNull()
            check(expectedCount != null && expectedCount > 0) { "Invalid catalog count" }
            val actualCount = database.rawQuery("SELECT COUNT(*) FROM recipes", null).singleInt()
            check(actualCount == expectedCount) { "Incomplete bundled catalog" }
            database.rawQuery(
                """
                    SELECT id, title_normalized, search_text, category, ease, rating,
                           prep_minutes, quick_recipe, vegan_eligible, source_key, random_key,
                           recipe_json
                    FROM recipes LIMIT 0
                """.trimIndent(),
                null,
            ).use { }
            database.rawQuery(
                "SELECT facet_type, token, recipe_id FROM recipe_facets LIMIT 0",
                null,
            ).use { }
            database.rawQuery(
                "SELECT recipe_id, normalized_text FROM recipe_ingredient_texts LIMIT 0",
                null,
            ).use { }
        }
    }

    private fun packageInstallStamp(): String {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return "${PackageInfoCompat.getLongVersionCode(packageInfo)}:${packageInfo.lastUpdateTime}"
    }

    private fun SQLiteDatabase.metaValue(key: String): String? = rawQuery(
        "SELECT value FROM catalog_meta WHERE key = ? LIMIT 1",
        arrayOf(key),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun Cursor.singleInt(): Int = use {
        check(moveToFirst())
        getInt(0)
    }

    companion object {
        const val ASSET_DATABASE_NAME = "recipe_catalog.db"
        const val INSTALLED_DATABASE_NAME = "spoon_recipe_catalog.db"
        private const val INSTALL_PREFERENCES = "spoon_catalog_install"
        private const val INSTALL_STAMP_KEY = "apk_install_stamp_v1"
        private const val EXPECTED_APPLICATION_ID = 0x53504F4E
        private const val EXPECTED_SCHEMA_VERSION = 3
        private const val CACHE_LIMIT = 256
        private const val SQLITE_MAX_BOUND_IDS = 800
        private const val META_SCHEMA_VERSION = "schema_version"
        private const val META_CATALOG_COUNT = "catalog_count"
        private const val META_CATALOG_HASH = "catalog_hash"
        private const val META_CATALOG_VERSION = "catalog_version"
        private const val META_JSON_COMPRESSION = "json_compression"
        private const val META_SOURCE_COUNTS = "source_counts"
        private const val META_FACET_OPTIONS = "facet_options_json"
        private const val META_INGREDIENT_TEXT_INDEX = "ingredient_text_index"
        private const val JSON_COMPRESSION_ZLIB = "zlib"
        private const val INGREDIENT_TEXT_INDEX_VERSION =
            "recipe_ingredient_texts-title-info-instr-v1"
        private const val FACET_DIET = "diet"
        private const val FACET_MEAL = "meal"
        private const val FACET_OCCASION = "occasion"
        private const val FACET_METHOD = "method"
        private const val FACET_CUISINE = "cuisine"
        private const val FACET_INGREDIENT = "ingredient"
        private val SOURCE_LABELS = mapOf(
            "akis" to "Άκης Πετρετζίκης",
            "argiro" to "Αργυρώ Μπαρμπαρίγου",
            "gastronomos" to "Γαστρονόμος",
        )
    }
}

internal fun installedCatalogFile(noBackupFilesDirectory: File): File =
    File(noBackupFilesDirectory, BundledRecipeCatalog.INSTALLED_DATABASE_NAME)

internal data class CatalogSql(
    val predicates: List<String>,
    val arguments: List<String>,
) {
    val whereSql: String
        get() = predicates.takeIf(List<String>::isNotEmpty)
            ?.joinToString(prefix = "WHERE ", separator = " AND ")
            .orEmpty()
}

private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")

internal object CatalogSqlBuilder {
    fun forExplore(
        criteria: ExploreCriteria,
        preferences: MealPreferenceSettings = MealPreferenceSettings(),
    ): CatalogSql {
        val predicates = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        criteria.category.trim().lowercase(java.util.Locale.ROOT)
            .takeUnless { it.isBlank() || it == MealCategory.ANY.key }
            ?.let {
                predicates += "r.category = ?"
                arguments += it
            }
        criteria.easeLevel.normalizedCatalogToken().takeIf(String::isNotBlank)?.let {
            predicates += "r.ease = ?"
            arguments += it
        }
        if (criteria.minRating > 0.0) {
            predicates += "r.rating > ?"
            arguments += criteria.minRating.toString()
        }
        if (criteria.maxPrepMinutes > 0) {
            predicates += "r.prep_minutes > 0"
            predicates += "r.prep_minutes <= ?"
            arguments += criteria.maxPrepMinutes.toString()
        }
        if (criteria.quickOnly) predicates += "r.quick_recipe = 1"
        addInPredicate(predicates, arguments, "r.source_key", criteria.sourceKeys)
        criteria.query.normalizedCatalogToken()
            .split(' ')
            .filter(String::isNotBlank)
            .forEach { term ->
                predicates += "instr(r.search_text, ?) > 0"
                arguments += term
            }
        addFacetPredicate(predicates, arguments, FACET_DIET, criteria.dietLabels)
        addFacetPredicate(predicates, arguments, FACET_MEAL, criteria.mealTypeLabels)
        addFacetPredicate(predicates, arguments, FACET_OCCASION, criteria.occasionLabels)
        addFacetPredicate(predicates, arguments, FACET_METHOD, criteria.methodLabels)
        addFacetPredicate(predicates, arguments, FACET_CUISINE, criteria.cuisineLabels)
        addFacetPredicate(
            predicates,
            arguments,
            FACET_INGREDIENT,
            criteria.ingredientLabels,
            expandIngredientAliases = true,
        )
        addPreferencePredicates(predicates, arguments, preferences)
        return CatalogSql(predicates, arguments)
    }

    fun forPlanner(
        filters: RecipeFilters,
        excludingRecipeId: String?,
        preferences: MealPreferenceSettings = MealPreferenceSettings(),
    ): CatalogSql {
        val predicates = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        if (filters.category != MealCategory.ANY.key) {
            predicates += "r.category = ?"
            arguments += filters.category
        }
        if (filters.easeLevel.isNotBlank()) {
            predicates += "r.ease = ?"
            arguments += filters.easeLevel
        }
        if (filters.minRating > 0.0) {
            predicates += "r.rating > ?"
            arguments += filters.minRating.toString()
        }
        if (filters.maxPrepMinutes > 0) {
            predicates += "r.prep_minutes > 0"
            predicates += "r.prep_minutes <= ?"
            arguments += filters.maxPrepMinutes.toString()
        }
        excludingRecipeId?.let {
            predicates += "r.id != ?"
            arguments += it
        }
        addPreferencePredicates(predicates, arguments, preferences)
        return CatalogSql(predicates, arguments)
    }

    private fun addPreferencePredicates(
        predicates: MutableList<String>,
        arguments: MutableList<String>,
        preferences: MealPreferenceSettings,
    ) {
        // Category values are stable machine keys, not searchable labels. Text
        // normalization turns pasta_rice/street_food into values absent from SQLite.
        val excludedCategories = preferences.excludedCategories
            .map { it.trim().lowercase(java.util.Locale.ROOT) }
            .filter(String::isNotBlank)
            .distinct()
        if (excludedCategories.isNotEmpty()) {
            predicates += "r.category NOT IN (${placeholders(excludedCategories.size)})"
            arguments += excludedCategories
        }
        if (preferences.veganOnly) {
            predicates += "r.vegan_eligible = 1"
        }
        preferences.excludedIngredientTerms.expandedIngredientTokens().forEach { term ->
            predicates += """
                NOT EXISTS (
                    SELECT 1 FROM recipe_ingredient_texts ingredient
                    WHERE ingredient.recipe_id = r.id
                      AND instr(ingredient.normalized_text, ?) > 0
                )
            """.trimIndent()
            arguments += term
            predicates += """
                r.id NOT IN (
                    SELECT ingredient_facet.recipe_id FROM recipe_facets ingredient_facet
                    WHERE ingredient_facet.facet_type = ?
                      AND instr(ingredient_facet.token, ?) > 0
                )
            """.trimIndent()
            arguments += FACET_INGREDIENT
            arguments += term
        }
    }

    private fun addInPredicate(
        predicates: MutableList<String>,
        arguments: MutableList<String>,
        column: String,
        rawValues: Set<String>,
    ) {
        val values = rawValues.normalizedTokens()
        if (values.isEmpty()) return
        predicates += "$column IN (${placeholders(values.size)})"
        arguments += values
    }

    private fun addFacetPredicate(
        predicates: MutableList<String>,
        arguments: MutableList<String>,
        facetType: String,
        rawValues: Set<String>,
        expandIngredientAliases: Boolean = false,
    ) {
        val values = if (expandIngredientAliases) {
            rawValues.expandedIngredientTokens()
        } else {
            rawValues.normalizedTokens()
        }
        if (values.isEmpty()) return
        predicates += """
            EXISTS (
                SELECT 1 FROM recipe_facets f
                WHERE f.recipe_id = r.id
                  AND f.facet_type = ?
                  AND f.token IN (${placeholders(values.size)})
            )
        """.trimIndent()
        arguments += facetType
        arguments += values
    }

    private fun Set<String>.normalizedTokens(): List<String> =
        map(String::normalizedCatalogToken).filter(String::isNotBlank).distinct()

    private fun Set<String>.expandedIngredientTokens(): List<String> =
        asSequence()
            .flatMap { ingredient -> expandedIngredientAliasTokens(ingredient).asSequence() }
            .distinct()
            .toList()

    private const val FACET_DIET = "diet"
    private const val FACET_MEAL = "meal"
    private const val FACET_OCCASION = "occasion"
    private const val FACET_METHOD = "method"
    private const val FACET_CUISINE = "cuisine"
    private const val FACET_INGREDIENT = "ingredient"
}
