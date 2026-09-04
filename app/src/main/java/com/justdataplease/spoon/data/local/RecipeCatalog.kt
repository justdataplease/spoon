package com.justdataplease.spoon.data.local

import com.justdataplease.spoon.data.model.EaseLevel
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.domain.ExploreCriteria
import com.justdataplease.spoon.domain.ExploreRecipeFilter
import com.justdataplease.spoon.domain.RecipeSelector
import com.justdataplease.spoon.domain.isActiveGreekRecipe
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
    suspend fun queryRecipes(criteria: ExploreCriteria, limit: Int, offset: Int): RecipePage
    suspend fun getFacetOptions(): CatalogFacetOptions
    suspend fun countPlannerMatches(filters: RecipeFilters, excludingRecipeId: String?): Int
    suspend fun selectRandomRecipe(
        filters: RecipeFilters,
        excludingRecipeId: String?,
        randomSeed: Long,
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

    override suspend fun queryRecipes(criteria: ExploreCriteria, limit: Int, offset: Int): RecipePage {
        requirePageBounds(limit, offset)
        val matches = ExploreRecipeFilter.filter(catalog, criteria)
        return RecipePage(matches.drop(offset).take(limit), matches.size, offset, limit)
    }

    override suspend fun getFacetOptions(): CatalogFacetOptions = facetOptionsFrom(catalog)

    override suspend fun countPlannerMatches(
        filters: RecipeFilters,
        excludingRecipeId: String?,
    ): Int = catalog.count { recipe ->
        recipe.id != excludingRecipeId && recipe.matchesPlannerFilters(filters)
    }

    override suspend fun selectRandomRecipe(
        filters: RecipeFilters,
        excludingRecipeId: String?,
        randomSeed: Long,
    ): Recipe? = RecipeSelector().select(
        recipes = catalog,
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
): Recipe? {
    if (!filters.isValid()) return null
    val customCandidates = customRecipes.asSequence()
        .filter(CustomRecipe::active)
        .map(CustomRecipe::toRecipe)
        .filter { it.id != excludingRecipeId && it.matchesPlannerFilters(filters) }
        .toList()
    val publicCount = recipeCatalog.countPlannerMatches(filters, excludingRecipeId)
    val totalCount = publicCount + customCandidates.size
    if (totalCount == 0) return null
    val random = Random(randomSeed)
    return if (random.nextInt(totalCount) < customCandidates.size) {
        customCandidates[random.nextInt(customCandidates.size)]
    } else {
        recipeCatalog.selectRandomRecipe(filters, excludingRecipeId, random.nextLong())
            ?: customCandidates.randomOrNull(random)
    }
}

internal const val MAX_RECIPE_PAGE_SIZE = 100
private val CATALOG_WHITESPACE = Regex("\\s+")
