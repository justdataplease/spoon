package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.data.model.isCustomRecipeId
import com.justdataplease.spoon.data.model.newCustomRecipeId
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.data.isSafeRecipeDocumentId
import com.justdataplease.spoon.domain.repository.AccountState
import com.justdataplease.spoon.domain.repository.CatalogFacetOptions
import com.justdataplease.spoon.domain.repository.RecipePage
import com.justdataplease.spoon.domain.repository.SpoonRepository
import java.time.LocalDate
import java.time.DayOfWeek
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.random.Random

sealed interface MealPlanSelection {
    data class Selected(
        val plan: DayMealPlan,
        val recipe: Recipe,
    ) : MealPlanSelection

    data class NoMatch(
        val date: LocalDate,
        val filters: RecipeFilters,
        val excludedRecipeId: String = "",
    ) : MealPlanSelection
}

sealed interface FavoriteReplacementResult {
    data class Selected(
        val plan: DayMealPlan,
        val recipe: Recipe,
    ) : FavoriteReplacementResult

    data class NotFavorite(
        val date: LocalDate,
        val recipeId: String,
    ) : FavoriteReplacementResult

    data class RecipeUnavailable(
        val date: LocalDate,
        val recipeId: String,
    ) : FavoriteReplacementResult
}

/** Extra courses are optional suggestions and never replace the saved main dish. */
data class MealMenuProposal(
    val main: Recipe?,
    val side: Recipe?,
    val dessert: Recipe?,
)

class MealPlanner @Inject constructor(
    private val repository: SpoonRepository,
    private val selector: RecipeSelector,
) {
    val backendState = repository.backendState
    val accountState: Flow<AccountState> = repository.accountState
    val recipes: Flow<List<Recipe>> = repository.recipes
    val mealPlans: Flow<List<DayMealPlan>> = repository.mealPlans
    val favoriteRecipeIds: Flow<Set<String>> = repository.favoriteRecipeIds
    val shoppingItems: Flow<List<ShoppingListItem>> = repository.shoppingItems
    val recipeNotes: Flow<List<RecipeNote>> = repository.recipeNotes
    val customRecipes: Flow<List<CustomRecipe>> = repository.customRecipes
    val cookedHistory: Flow<List<CookedMeal>> = repository.cookedHistory
    val mealPreferenceSettings: Flow<MealPreferenceSettings> = repository.mealPreferenceSettings

    suspend fun getRecipeDetails(recipeId: String): Recipe? =
        repository.getRecipeDetails(recipeId)

    suspend fun queryRecipes(
        criteria: ExploreCriteria = ExploreCriteria(),
        limit: Int = 24,
        offset: Int = 0,
    ): RecipePage = repository.queryRecipes(
        criteria,
        limit,
        offset,
        mealPreferenceSettings.first(),
    )

    suspend fun saveMealPreferenceSettings(settings: MealPreferenceSettings) {
        repository.updateMealPreferenceSettings(settings)
    }

    suspend fun clearMealPreferenceSettings() {
        repository.updateMealPreferenceSettings(MealPreferenceSettings())
    }

    suspend fun getCatalogFacetOptions(): CatalogFacetOptions =
        repository.getCatalogFacetOptions()

    suspend fun ensureWeek(
        containingDate: LocalDate = LocalDate.now(),
        random: Random = Random.Default,
        resetWeekdays: Set<DayOfWeek> = emptySet(),
    ): List<DayMealPlan> {
        repository.ensureReady()
        // Keep one coherent preference snapshot for the entire refresh. A cloud/cache update
        // arriving halfway through the loop must not produce a week with mixed policies.
        val preferences = mealPreferenceSettings.first()
        val favorites = favoritePool(preferences)
        val existing = repository.mealPlans.first().associateBy(DayMealPlan::date)
        val recipesById = repository.getRecipesByIds(
            existing.values.mapNotNullTo(mutableSetOf()) { it.recipeId.takeIf(String::isNotBlank) },
        ).associateBy(Recipe::id)

        return WeeklyPlanDefaults.dates(containingDate).map { date ->
            val current = existing[date.toString()]
            val resetCategory = date.dayOfWeek in resetWeekdays && current?.completed != true && current?.locked != true
            if (current != null && !resetCategory && current.isUsable(recipesById, preferences, favorites)) {
                current
            } else {
                val replacement = createDay(
                    date = date,
                    random = random,
                    previous = if (resetCategory) current?.copy(
                        filters = current.filters.copy(category = WeeklyPlanDefaults.categoryFor(date.dayOfWeek, preferences).key),
                    ) else current,
                    preferences = preferences,
                    favorites = favorites,
                )
                if (current?.recipeId == "" && replacement.recipeId.isBlank() && current.filters == replacement.filters && !current.completed) {
                    current
                } else {
                    replacement.also { repository.upsertMealPlan(it) }
                }
            }
        }
    }

    suspend fun reroll(
        date: LocalDate,
        filters: RecipeFilters? = null,
        random: Random = Random.Default,
    ): MealPlanSelection {
        val preferences = mealPreferenceSettings.first()
        return rerollDay(date, filters, random, preferences, favoritePool(preferences))
    }

    /** One source and preference snapshot for every day; repeats remain eligible. */
    suspend fun rerollWeek(
        containingDate: LocalDate,
        random: Random = Random.Default,
    ): List<MealPlanSelection> {
        val preferences = mealPreferenceSettings.first()
        val favorites = favoritePool(preferences)
        val plansByDate = repository.mealPlans.first().associateBy(DayMealPlan::date)
        return WeeklyPlanDefaults.dates(containingDate).mapNotNull { date ->
            val plan = plansByDate[date.toString()]
            if (plan?.locked == true || plan?.completed == true) null
            else rerollDay(date, null, random, preferences, favorites)
        }
    }

    private suspend fun rerollDay(
        date: LocalDate,
        filters: RecipeFilters?,
        random: Random,
        preferences: MealPreferenceSettings,
        favorites: List<Recipe>?,
    ): MealPlanSelection {
        // Explicit selection uses local recipes; the repository queues the personal write.
        val existing = repository.mealPlans.first().firstOrNull { it.date == date.toString() }
        val requestedFilters = filters ?: existing?.filters ?: WeeklyPlanDefaults.filtersFor(date, preferences)
        if (!requestedFilters.isValid()) return MealPlanSelection.NoMatch(date, requestedFilters)
        val selected = selectEligibleRecipe(
            filters = requestedFilters,
            excludingRecipeId = null,
            randomSeed = random.nextLong(),
            preferences = preferences,
            favorites = favorites,
        )
        val plan = newPlan(date, requestedFilters, selected)
        repository.upsertMealPlan(plan)
        return if (selected == null) MealPlanSelection.NoMatch(date, requestedFilters)
        else MealPlanSelection.Selected(plan, selected)
    }

    suspend fun suggestMenu(
        date: LocalDate,
        random: Random = Random.Default,
    ): MealMenuProposal {
        repository.ensureReady()
        val preferences = mealPreferenceSettings.first()
        val favorites = favoritePool(preferences)
        val plan = repository.mealPlans.first().firstOrNull { it.date == date.toString() }
        val main = plan?.recipeId?.takeIf(String::isNotBlank)
            ?.let { repository.getRecipesByIds(setOf(it)).firstOrNull() }
        val filters = plan?.filters ?: WeeklyPlanDefaults.filtersFor(date, preferences)
        val criteria = ExploreCriteria(
            easeLevel = filters.easeLevel,
            minRating = filters.minRating,
            maxPrepMinutes = filters.maxPrepMinutes,
        )
        val excludedIds = setOfNotNull(plan?.recipeId?.takeIf(String::isNotBlank))
        val side = selectMenuRecipe(
            criteria = criteria.copy(mealTypeLabels = setOf("Συνοδευτικά", "Σαλάτα", "Σαλάτες", "Ορεκτικά", "Ορεκτικό μεζές")),
            preferences = preferences.copy(excludedCategories = preferences.excludedCategories + MealCategory.DESSERT.key),
            favorites = favorites,
            excludedIds = excludedIds,
            random = random,
        )
        val dessert = selectMenuRecipe(
            criteria = criteria.copy(category = MealCategory.DESSERT.key),
            preferences = preferences,
            favorites = favorites,
            excludedIds = excludedIds,
            random = random,
        )
        return MealMenuProposal(main, side, dessert)
    }

    private suspend fun selectMenuRecipe(
        criteria: ExploreCriteria,
        preferences: MealPreferenceSettings,
        favorites: List<Recipe>?,
        excludedIds: Set<String>,
        random: Random,
    ): Recipe? {
        if (favorites != null) {
            return ExploreRecipeFilter.filter(favorites, criteria, preferences)
                .filterNot { it.id in excludedIds }.randomOrNull(random)
        }
        val first = repository.queryRecipes(criteria, 1, 0, preferences)
        if (first.totalCount == 0) return null
        val start = random.nextInt(first.totalCount)
        // At most one main-dish id can be skipped; paging keeps the full catalog out of memory.
        repeat(minOf(first.totalCount, excludedIds.size + 1)) { attempt ->
            val offset = (start + attempt) % first.totalCount
            val page = if (offset == 0) first else repository.queryRecipes(criteria, 1, offset, preferences)
            page.recipes.firstOrNull { it.id !in excludedIds }?.let { return it }
        }
        return null
    }

    suspend fun updateFilters(
        date: LocalDate,
        filters: RecipeFilters,
        random: Random = Random.Default,
    ): MealPlanSelection = reroll(date, filters, random)

    suspend fun setLocked(date: LocalDate, locked: Boolean) {
        repository.ensureReady()
        val plan = repository.mealPlans.first().firstOrNull { it.date == date.toString() } ?: return
        // Completed meals are automatically protected; their completion event stays untouched.
        if (plan.completed || plan.recipeId.isBlank() || plan.locked == locked) return
        repository.upsertMealPlan(plan.copy(
            locked = locked,
            updatedAtEpochMillis = maxOf(System.currentTimeMillis(), plan.updatedAtEpochMillis + 1),
        ))
    }

    suspend fun setCompleted(date: LocalDate, completed: Boolean) {
        repository.ensureReady()
        repository.setMealCompleted(date.toString(), completed)
    }

    suspend fun deleteCookedHistoryEntry(historyId: String) {
        repository.ensureReady()
        repository.deleteCookedHistoryEntry(historyId)
    }

    suspend fun toggleFavorite(recipeId: String): Boolean {
        repository.ensureReady()
        return repository.toggleFavorite(recipeId)
    }

    suspend fun upsertShoppingItems(items: List<ShoppingListItem>) {
        repository.ensureReady()
        repository.upsertShoppingItems(items)
    }

    suspend fun setShoppingItemChecked(itemId: String, checked: Boolean) {
        repository.ensureReady()
        repository.setShoppingItemChecked(itemId, checked)
    }

    suspend fun deleteShoppingItem(itemId: String) {
        repository.ensureReady()
        repository.deleteShoppingItem(itemId)
    }

    suspend fun clearCheckedShoppingItems() {
        repository.ensureReady()
        repository.clearCheckedShoppingItems()
    }

    suspend fun saveRecipeNote(recipeId: String, text: String) {
        repository.ensureReady()
        repository.upsertRecipeNote(
            RecipeNote(
                id = recipeId,
                recipeId = recipeId,
                text = text,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    /** Assigns a stable custom id and timestamps for a new draft, or updates an existing recipe. */
    suspend fun saveCustomRecipe(draft: CustomRecipe): CustomRecipe {
        repository.ensureReady()
        val now = System.currentTimeMillis()
        val recipeId = draft.id.ifBlank(::newCustomRecipeId)
        val existing = repository.customRecipes.first().firstOrNull { it.id == recipeId }
        val stored = draft.copy(
            id = recipeId,
            createdAtEpochMillis = existing?.createdAtEpochMillis
                ?.takeIf { it > 0L }
                ?: draft.createdAtEpochMillis.takeIf { it > 0L }
                ?: now,
            updatedAtEpochMillis = now,
        )
        repository.upsertCustomRecipe(stored)
        return stored
    }

    suspend fun registerEmailAccount(email: String, password: String) {
        repository.ensureReady()
        repository.registerEmailAccount(email, password)
    }

    suspend fun signInWithEmail(email: String, password: String) {
        repository.ensureReady()
        repository.signInWithEmail(email, password)
    }

    suspend fun sendPasswordReset(email: String) {
        repository.sendPasswordReset(email)
    }

    suspend fun signOutToAnonymous() {
        repository.signOutToAnonymous()
    }

    /** Replaces a day's proposal only when [recipeId] is still an available favorite. */
    suspend fun replaceWithFavorite(
        date: LocalDate,
        recipeId: String,
    ): FavoriteReplacementResult {
        repository.ensureReady()
        if (recipeId.isBlank() || recipeId !in repository.favoriteRecipeIds.first()) {
            return FavoriteReplacementResult.NotFavorite(date, recipeId)
        }

        val recipe = repository.getRecipesByIds(setOf(recipeId))
            .firstOrNull { it.id == recipeId && it.isActiveGreekRecipe() }
            ?: return FavoriteReplacementResult.RecipeUnavailable(date, recipeId)
        val existing = repository.mealPlans.first()
            .firstOrNull { it.date == date.toString() }
        val category = recipe.category.ifBlank {
            existing?.category
                ?.takeIf(String::isNotBlank)
                ?: existing?.filters?.category
                    ?.takeIf(String::isNotBlank)
                ?: WeeklyPlanDefaults.filtersFor(date).category
        }
        val plan = newPlan(date, RecipeFilters(category = category), recipe)
        repository.upsertMealPlan(plan)
        return FavoriteReplacementResult.Selected(plan, recipe)
    }

    private suspend fun createDay(
        date: LocalDate,
        random: Random,
        previous: DayMealPlan? = null,
        preferences: MealPreferenceSettings,
        favorites: List<Recipe>?,
    ): DayMealPlan {
        val filters = previous?.filters
            ?.takeIf(RecipeFilters::isValid)
            ?: WeeklyPlanDefaults.filtersFor(date, preferences)
        val recipe = selectEligibleRecipe(
            filters = filters,
            excludingRecipeId = null,
            randomSeed = random.nextLong(),
            preferences = preferences,
            favorites = favorites,
        )
        return newPlan(date, filters, recipe)
    }

    /** Null means the full catalog; an empty list means no available favorites. */
    private suspend fun favoritePool(preferences: MealPreferenceSettings): List<Recipe>? =
        if (preferences.favoritesOnly) {
            repository.ensureReady()
            repository.getRecipesByIds(repository.favoriteRecipeIds.first())
                .filter { it.isActiveGreekRecipe() && it.matchesMealPreferences(preferences) }
        } else null

    /** Defends the planner contract even if a repository implementation returns a bad row. */
    private suspend fun selectEligibleRecipe(
        filters: RecipeFilters,
        excludingRecipeId: String?,
        randomSeed: Long,
        preferences: MealPreferenceSettings,
        favorites: List<Recipe>?,
    ): Recipe? {
        val selected = if (favorites != null) {
            selector.select(favorites, filters, excludingRecipeId, Random(randomSeed))
        } else {
            repository.selectRandomRecipe(filters, excludingRecipeId, randomSeed, preferences)
        }
        return selected?.takeIf { recipe ->
            recipe.id != excludingRecipeId && selector.matches(recipe, filters) &&
                recipe.matchesMealPreferences(preferences)
        }
    }

    private fun newPlan(date: LocalDate, filters: RecipeFilters, recipe: Recipe?): DayMealPlan =
        DayMealPlan(
            id = date.toString(),
            date = date.toString(),
            category = filters.category,
            recipeId = recipe?.id.orEmpty(),
            recipeTitle = recipe?.title.orEmpty(),
            filters = filters,
            // A fresh proposal has not been cooked yet. Inheriting a previous completion
            // would also create a false cooked-history entry.
            completed = false,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )

    private fun DayMealPlan.isUsable(
        recipesById: Map<String, Recipe>,
        preferences: MealPreferenceSettings,
        favorites: List<Recipe>?,
    ): Boolean {
        val structurallyValid = date.isNotBlank() &&
            isSafeRecipeDocumentId(recipeId) &&
            recipeTitle.isNotBlank()
        if ((completed || locked) && structurallyValid) return true
        if (!structurallyValid || category != filters.category || !filters.isValid()) return false
        if (favorites != null && favorites.none { it.id == recipeId }) return false
        val currentRecipe = recipesById[recipeId]
            ?: return recipeId.isCustomRecipeId()
        return selector.matches(currentRecipe, filters) &&
            currentRecipe.matchesMealPreferences(preferences)
    }
}
