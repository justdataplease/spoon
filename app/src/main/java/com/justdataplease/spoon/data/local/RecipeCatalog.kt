package com.justdataplease.spoon.data.local

import com.justdataplease.spoon.data.eligibleRecipeDetails
import com.justdataplease.spoon.data.model.EaseLevel
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.data.model.isCustomRecipeId
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.domain.ExploreCriteria
import com.justdataplease.spoon.domain.ExploreRecipeFilter
import com.justdataplease.spoon.domain.RecipeSelector
import com.justdataplease.spoon.domain.isActiveGreekRecipe
import com.justdataplease.spoon.domain.matchesMealPreferences
import com.justdataplease.spoon.domain.repository.CatalogFacetOptions
import com.justdataplease.spoon.domain.repository.CatalogSourceOption
import com.justdataplease.spoon.domain.repository.RecipePage
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/** Read-only public recipe catalog. Implementations must never use Firestore. */
interface RecipeCatalog {
    /** A deliberately bounded projection of rows recently needed by app state. */
    val cachedRecipes: StateFlow<List<Recipe>>

    suspend fun ensureReady()
    suspend fun getRecipe(recipeId: String): Recipe?
    suspend fun getRecipesByIds(recipeIds: Set<String>): List<Recipe>
    suspend fun queryRecipes(
        criteria: ExploreCriteria,
        limit: Int,
        offset: Int,
        preferences: MealPreferenceSettings = MealPreferenceSettings(),
    ): RecipePage
    suspend fun getFacetOptions(): CatalogFacetOptions
    suspend fun countPlannerMatches(
        filters: RecipeFilters,
        excludingRecipeId: String?,
        preferences: MealPreferenceSettings = MealPreferenceSettings(),
    ): Int
    suspend fun selectRandomRecipe(
        filters: RecipeFilters,
        excludingRecipeId: String?,
        randomSeed: Long,
        preferences: MealPreferenceSettings = MealPreferenceSettings(),
    ): Recipe?
}

/** Small deterministic fallback used by JVM tests. */
class InMemoryRecipeCatalog(recipes: List<Recipe>) : RecipeCatalog {
    private val catalog = recipes.filter(Recipe::isActiveGreekRecipe)
    private val state = kotlinx.coroutines.flow.MutableStateFlow(catalog)

    override val cachedRecipes: StateFlow<List<Recipe>> = state
    override suspend fun ensureReady() = Unit
    override suspend fun getRecipe(recipeId: String): Recipe? = catalog.firstOrNull { it.id == recipeId }
    override suspend fun getRecipesByIds(recipeIds: Set<String>): List<Recipe> =
        catalog.filter { it.id in recipeIds }

    override suspend fun queryRecipes(
        criteria: ExploreCriteria,
        limit: Int,
        offset: Int,
        preferences: MealPreferenceSettings,
    ): RecipePage {
        requirePageBounds(limit, offset)
        val matches = ExploreRecipeFilter.filter(catalog, criteria, preferences)
        return RecipePage(matches.drop(offset).take(limit), matches.size, offset, limit)
    }

    override suspend fun getFacetOptions(): CatalogFacetOptions = facetOptionsFrom(catalog)

    override suspend fun countPlannerMatches(
        filters: RecipeFilters,
        excludingRecipeId: String?,
        preferences: MealPreferenceSettings,
    ): Int = catalog.count { recipe ->
        recipe.id != excludingRecipeId &&
            recipe.matchesPlannerFilters(filters) &&
            recipe.matchesMealPreferences(preferences)
    }

    override suspend fun selectRandomRecipe(
        filters: RecipeFilters,
        excludingRecipeId: String?,
        randomSeed: Long,
        preferences: MealPreferenceSettings,
    ): Recipe? = RecipeSelector().select(
        recipes = catalog.filter { it.matchesMealPreferences(preferences) },
        filters = filters,
        excludingRecipeId = excludingRecipeId,
        random = Random(randomSeed),
    )
}

internal fun requirePageBounds(limit: Int, offset: Int) {
    require(limit in 1..MAX_RECIPE_PAGE_SIZE) { "limit must be in 1..$MAX_RECIPE_PAGE_SIZE" }
    require(offset >= 0) { "offset cannot be negative" }
}

/** Must remain equivalent to the generator's NFKD/casefold/token normalization. */
internal fun String.normalizedCatalogToken(): String =
    Normalizer.normalize(this, Normalizer.Form.NFKD)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .lowercase(Locale.ROOT)
        .replace('\u03c2', '\u03c3')
        .map { character -> if (character.isLetterOrDigit()) character else ' ' }
        .joinToString(separator = "")
        .replace(CATALOG_WHITESPACE, " ")
        .trim()

internal fun facetOptionsFrom(recipes: List<Recipe>): CatalogFacetOptions {
    fun labels(selector: (Recipe) -> List<String>): List<String> = recipes.asSequence()
        .flatMap { selector(it).asSequence() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(String::normalizedCatalogToken)
        .sortedBy(String::normalizedCatalogToken)
        .toList()

    val sourceOptions = recipes.asSequence()
        .mapNotNull { recipe ->
            recipe.effectiveSourceKey.takeIf(String::isNotBlank)?.let { key -> key to recipe }
        }
        .groupBy({ it.first }, { it.second })
        .map { (key, sourceRecipes) ->
            CatalogSourceOption(
                key = key,
                label = sourceRecipes.asSequence()
                    .map { sequenceOf(it.sourceName, it.source, key).first(String::isNotBlank) }
                    .groupingBy { it }
                    .eachCount()
                    .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    ?.key
                    .orEmpty(),
                recipeCount = sourceRecipes.size,
            )
        }
        .sortedBy { it.label.normalizedCatalogToken() }

    return CatalogFacetOptions(
        sources = sourceOptions,
        diets = labels(Recipe::dietLabels),
        mealTypes = labels(Recipe::mealTypeLabels),
        occasions = labels(Recipe::occasionLabels),
        methods = labels(Recipe::methodLabels),
        cuisines = labels(Recipe::cuisineLabels),
        ingredients = labels(Recipe::ingredientLabels),
    )
}

internal fun mergeFacetOptions(
    publicOptions: CatalogFacetOptions,
    personalOptions: CatalogFacetOptions,
): CatalogFacetOptions {
    fun mergeLabels(first: List<String>, second: List<String>): List<String> =
        (first + second).distinctBy(String::normalizedCatalogToken)
            .sortedBy(String::normalizedCatalogToken)
    val sources = (publicOptions.sources + personalOptions.sources)
        .groupBy(CatalogSourceOption::key)
        .map { (key, options) ->
            CatalogSourceOption(
                key = key,
                label = options.firstNotNullOfOrNull { it.label.takeIf(String::isNotBlank) }.orEmpty(),
                recipeCount = options.sumOf(CatalogSourceOption::recipeCount),
            )
        }
        .sortedBy { it.label.normalizedCatalogToken() }
    return CatalogFacetOptions(
        sources = sources,
        diets = mergeLabels(publicOptions.diets, personalOptions.diets),
        mealTypes = mergeLabels(publicOptions.mealTypes, personalOptions.mealTypes),
        occasions = mergeLabels(publicOptions.occasions, personalOptions.occasions),
        methods = mergeLabels(publicOptions.methods, personalOptions.methods),
        cuisines = mergeLabels(publicOptions.cuisines, personalOptions.cuisines),
        ingredients = mergeLabels(publicOptions.ingredients, personalOptions.ingredients),
    )
}

internal fun Recipe.matchesPlannerFilters(filters: RecipeFilters): Boolean {
    if (!isActiveGreekRecipe() || !filters.isValid()) return false
    val categoryMatches = filters.category == MealCategory.ANY.key || category == filters.category
    val easeMatches = filters.easeLevel.isBlank() || easeLevel == EaseLevel.fromKey(filters.easeLevel)
    val ratingMatches = rating.isFinite() && rating in 0.0..10.0 &&
        (filters.minRating == 0.0 || rating > filters.minRating)
    val timeMatches = filters.maxPrepMinutes == 0 ||
        (prepMinutes > 0 && prepMinutes <= filters.maxPrepMinutes)
    return categoryMatches && easeMatches && ratingMatches && timeMatches
}

internal suspend fun selectIncludingCustomRecipes(
    recipeCatalog: RecipeCatalog,
    customRecipes: List<CustomRecipe>,
    filters: RecipeFilters,
    excludingRecipeId: String?,
    randomSeed: Long,
    preferences: MealPreferenceSettings = MealPreferenceSettings(),
): Recipe? {
    if (!filters.isValid()) return null
    val customCandidates = customRecipes.toActiveRecipes()
        .filter {
            it.id != excludingRecipeId &&
                it.matchesPlannerFilters(filters) &&
                it.matchesMealPreferences(preferences)
        }
    val publicCount = recipeCatalog.countPlannerMatches(filters, excludingRecipeId, preferences)
    val totalCount = publicCount + customCandidates.size
    if (totalCount == 0) return null
    val random = Random(randomSeed)
    return if (random.nextInt(totalCount) < customCandidates.size) {
        customCandidates[random.nextInt(customCandidates.size)]
    } else {
        recipeCatalog.selectRandomRecipe(filters, excludingRecipeId, random.nextLong(), preferences)
            ?: customCandidates.randomOrNull(random)
    }
}

internal fun List<CustomRecipe>.toActiveRecipes(): List<Recipe> =
    filter(CustomRecipe::active).map(CustomRecipe::toRecipe)

/** The observable projection: bounded catalog cache, referenced rows, then custom recipes. */
internal fun mergeCatalogRecipes(
    cached: List<Recipe>,
    referenced: List<Recipe>,
    customRecipes: List<CustomRecipe>,
): List<Recipe> = (cached + referenced + customRecipes.toActiveRecipes())
    .distinctBy(Recipe::id)
    .sortedBy(Recipe::title)

internal suspend fun RecipeCatalog.getRecipeDetailsIncludingCustom(
    safeRecipeId: String,
    customRecipes: List<CustomRecipe>,
): Recipe? {
    if (safeRecipeId.isCustomRecipeId()) {
        return customRecipes.firstOrNull { it.id == safeRecipeId && it.active }?.toRecipe()
    }
    return eligibleRecipeDetails(getRecipe(safeRecipeId), safeRecipeId, safeRecipeId)
}

internal suspend fun RecipeCatalog.getRecipesByIdsIncludingCustom(
    recipeIds: Set<String>,
    customRecipes: List<CustomRecipe>,
): List<Recipe> {
    if (recipeIds.isEmpty()) return emptyList()
    val customById = customRecipes.toActiveRecipes()
        .filter { it.id in recipeIds }
        .associateBy(Recipe::id)
    val public = getRecipesByIds(recipeIds - customById.keys)
    val all = public.associateBy(Recipe::id) + customById
    return recipeIds.mapNotNull(all::get)
}

/** Custom recipes page first; the public catalog continues after them. */
internal suspend fun RecipeCatalog.queryIncludingCustomRecipes(
    customRecipes: List<CustomRecipe>,
    criteria: ExploreCriteria,
    limit: Int,
    offset: Int,
    preferences: MealPreferenceSettings = MealPreferenceSettings(),
): RecipePage {
    requirePageBounds(limit, offset)
    val customMatches = ExploreRecipeFilter.filter(customRecipes.toActiveRecipes(), criteria, preferences)
    val customPage = customMatches.drop(offset).take(limit)
    val publicOffset = (offset - customMatches.size).coerceAtLeast(0)
    val remaining = limit - customPage.size
    val publicPage = queryRecipes(criteria, limit, publicOffset, preferences)
    return RecipePage(
        recipes = customPage + publicPage.recipes.take(remaining),
        totalCount = customMatches.size + publicPage.totalCount,
        offset = offset,
        limit = limit,
    )
}

internal suspend fun RecipeCatalog.facetOptionsIncludingCustom(
    customRecipes: List<CustomRecipe>,
): CatalogFacetOptions =
    mergeFacetOptions(getFacetOptions(), facetOptionsFrom(customRecipes.toActiveRecipes()))

internal const val MAX_RECIPE_PAGE_SIZE = 100
private val CATALOG_WHITESPACE = Regex("\\s+")
