package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.MealCourse
import com.justdataplease.spoon.data.model.coursePlan
import com.justdataplease.spoon.data.model.withCourse
import com.justdataplease.spoon.data.model.allCourses
import com.justdataplease.spoon.data.model.isIntentionallyBlank
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // Each operation reads and replaces one day document; serialize them to preserve sibling courses.
    private val planMutationMutex = Mutex()

    suspend fun ensureWeek(containingDate: LocalDate = LocalDate.now(), random: Random = Random.Default,
        resetWeekdays: Set<DayOfWeek> = emptySet(),
        resetCourseWeekdays: Map<MealCourse, Set<DayOfWeek>> = emptyMap()): List<DayMealPlan> = planMutationMutex.withLock {
        ensureWeekUnlocked(containingDate, random, resetWeekdays, resetCourseWeekdays)
    }

    suspend fun reroll(date: LocalDate, filters: RecipeFilters? = null,
        random: Random = Random.Default): MealPlanSelection = planMutationMutex.withLock {
        rerollUnlocked(date, filters, random)
    }

    suspend fun rerollWeek(containingDate: LocalDate, random: Random = Random.Default): List<MealPlanSelection> =
        planMutationMutex.withLock { rerollWeekUnlocked(containingDate, random) }

    suspend fun suggestMenu(date: LocalDate, random: Random = Random.Default): MealMenuProposal =
        planMutationMutex.withLock { suggestMenuUnlocked(date, random) }

    suspend fun rerollCourse(date: LocalDate, course: MealCourse, filters: RecipeFilters? = null,
        random: Random = Random.Default): MealPlanSelection = planMutationMutex.withLock {
        rerollCourseUnlocked(date, course, filters, random)
    }

    suspend fun setLocked(date: LocalDate, locked: Boolean, course: MealCourse = MealCourse.MAIN) =
        planMutationMutex.withLock { setLockedUnlocked(date, locked, course) }

    /** Keeps an explicitly empty day until the user successfully adds a meal again. */
    suspend fun setDayBlank(date: LocalDate) = planMutationMutex.withLock {
        repository.ensureReady()
        val previous = repository.mealPlans.first().firstOrNull { it.date == date.toString() }
        if (previous?.isIntentionallyBlank == true) return@withLock
        val filters = previous?.filters ?: WeeklyPlanDefaults.filtersFor(date, mealPreferenceSettings.first())
        repository.upsertMealPlan(DayMealPlan(
            id = date.toString(), date = date.toString(), category = filters.category,
            filters = filters, locked = true,
            updatedAtEpochMillis = maxOf(System.currentTimeMillis(), (previous?.updatedAtEpochMillis ?: 0L) + 1L),
        ))
    }

    suspend fun setCompleted(date: LocalDate, completed: Boolean, course: MealCourse = MealCourse.MAIN) =
        planMutationMutex.withLock { setCompletedUnlocked(date, completed, course) }

    suspend fun deleteCookedHistoryEntry(historyId: String) =
        planMutationMutex.withLock { deleteCookedHistoryEntryUnlocked(historyId) }

    suspend fun replaceWithFavorite(date: LocalDate, recipeId: String,
        course: MealCourse = MealCourse.MAIN): FavoriteReplacementResult = planMutationMutex.withLock {
        replaceWithFavoriteUnlocked(date, recipeId, course)
    }

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

    private suspend fun ensureWeekUnlocked(
        containingDate: LocalDate = LocalDate.now(),
        random: Random = Random.Default,
        resetWeekdays: Set<DayOfWeek> = emptySet(),
        resetCourseWeekdays: Map<MealCourse, Set<DayOfWeek>> = emptyMap(),
    ): List<DayMealPlan> {
        repository.ensureReady()
        // Keep one coherent preference snapshot for the entire refresh. A cloud/cache update
        // arriving halfway through the loop must not produce a week with mixed policies.
        val preferences = mealPreferenceSettings.first()
        val favorites = favoritePool(preferences)
        // Changing publisher choices affects future picks, while saved selections stay visible.
        val existingPreferences = preferences.copy(excludedSourceKeys = emptySet())
        val existingFavorites = if (preferences.excludedSourceKeys.isEmpty()) favorites else favoritePool(existingPreferences)
        val existing = repository.mealPlans.first().associateBy(DayMealPlan::date)
        val recipesById = repository.getRecipesByIds(
            existing.values.mapNotNullTo(mutableSetOf()) { it.recipeId.takeIf(String::isNotBlank) },
        ).associateBy(Recipe::id)

        return WeeklyPlanDefaults.dates(containingDate).map { date ->
            val current = existing[date.toString()]
            if (current?.isIntentionallyBlank == true) return@map current
            val resetCategory = (date.dayOfWeek in resetWeekdays ||
                date.dayOfWeek in resetCourseWeekdays[MealCourse.MAIN].orEmpty()) &&
                current?.completed != true && current?.locked != true
            var plan = if (current != null && !resetCategory && current.isUsable(recipesById, existingPreferences, existingFavorites)) {
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
                    replacement
                }
            }
            for (course in listOf(MealCourse.SIDE, MealCourse.DESSERT)) {
                val saved = plan.coursePlan(course) ?: continue
                if (date.dayOfWeek !in resetCourseWeekdays[course].orEmpty() || saved.locked || saved.completed) continue
                val filters = saved.filters.copy(category = WeeklyPlanDefaults.categoryFor(date.dayOfWeek, preferences, course).key)
                val recipe = chooseCourseRecipe(plan, course, filters, preferences, favorites, random)
                plan = plan.withCourse(course, newPlan(date, filters, recipe))
            }
            if (plan != current) repository.upsertMealPlan(plan)
            plan
        }
    }

    private suspend fun rerollUnlocked(
        date: LocalDate,
        filters: RecipeFilters? = null,
        random: Random = Random.Default,
    ): MealPlanSelection {
        val preferences = mealPreferenceSettings.first()
        return rerollDay(date, filters, random, preferences, favoritePool(preferences))
    }

    /** One source and preference snapshot for every day; repeats remain eligible. */
    private suspend fun rerollWeekUnlocked(
        containingDate: LocalDate,
        random: Random = Random.Default,
    ): List<MealPlanSelection> {
        val preferences = mealPreferenceSettings.first()
        val favorites = favoritePool(preferences)
        val plansByDate = repository.mealPlans.first().associateBy(DayMealPlan::date)
        return WeeklyPlanDefaults.dates(containingDate).flatMap { date ->
            val current = plansByDate[date.toString()]
            if (current?.isIntentionallyBlank == true) return@flatMap emptyList()
            var plan = current ?: DayMealPlan(id = date.toString(), date = date.toString(),
                filters = WeeklyPlanDefaults.filtersFor(date, preferences))
            val selections = mutableListOf<Pair<RecipeFilters, Recipe?>>()
            for (course in MealCourse.entries) {
                val saved = plan.coursePlan(course)
                if (saved?.locked == true || saved?.completed == true) continue
                val filters = saved?.filters ?: courseFilters(date, plan, course, preferences)
                if (!filters.isValid()) {
                    selections += filters to null
                    continue
                }
                val recipe = if (course == MealCourse.MAIN) {
                    selectEligibleRecipe(filters, null, random.nextLong(), preferences, favorites)
                } else {
                    chooseCourseRecipe(plan, course, filters, preferences, favorites, random)
                }
                plan = plan.withCourse(course, newPlan(date, filters, recipe))
                selections += filters to recipe
            }
            if (plan != current) repository.upsertMealPlan(plan)
            selections.map { (filters, recipe) ->
                if (recipe == null) MealPlanSelection.NoMatch(date, filters)
                else MealPlanSelection.Selected(plan, recipe)
            }
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
        // A failed explicit add must not silently re-enable automatic generation later.
        if (selected == null && existing?.isIntentionallyBlank == true) {
            return MealPlanSelection.NoMatch(date, requestedFilters)
        }
        val selectedPlan = newPlan(date, requestedFilters, selected)
        val plan = existing?.withCourse(MealCourse.MAIN, selectedPlan) ?: selectedPlan
        repository.upsertMealPlan(plan)
        return if (selected == null) MealPlanSelection.NoMatch(date, requestedFilters)
        else MealPlanSelection.Selected(plan, selected)
    }

    /** Generates only missing courses. Reopening a menu never redraws saved selections. */
    private suspend fun suggestMenuUnlocked(date: LocalDate, random: Random = Random.Default): MealMenuProposal {
        repository.ensureReady()
        var plan = repository.mealPlans.first().firstOrNull { it.date == date.toString() }
            ?: ensureWeekUnlocked(date, random).first { it.date == date.toString() }
        if (plan.isIntentionallyBlank) return MealMenuProposal(null, null, null)
        if (plan.side == null || plan.dessert == null) {
            val preferences = mealPreferenceSettings.first()
            val favorites = favoritePool(preferences)
            for (course in listOf(MealCourse.SIDE, MealCourse.DESSERT)) {
                if (plan.coursePlan(course) != null) continue
                val filters = courseFilters(date, plan, course, preferences)
                val recipe = chooseCourseRecipe(plan, course, filters, preferences, favorites, random)
                plan = plan.withCourse(course, newPlan(date, filters, recipe))
            }
            repository.upsertMealPlan(plan)
        }
        val recipes = repository.getRecipesByIds(plan.allCourses().mapNotNullTo(mutableSetOf()) {
            it.recipeId.takeIf(String::isNotBlank)
        }).associateBy(Recipe::id)
        return MealMenuProposal(recipes[plan.recipeId], recipes[plan.side?.recipeId], recipes[plan.dessert?.recipeId])
    }

    private suspend fun rerollCourseUnlocked(
        date: LocalDate,
        course: MealCourse,
        filters: RecipeFilters? = null,
        random: Random = Random.Default,
    ): MealPlanSelection {
        if (course == MealCourse.MAIN) return rerollUnlocked(date, filters, random)
        repository.ensureReady()
        val parent = repository.mealPlans.first().firstOrNull { it.date == date.toString() }
            ?: return MealPlanSelection.NoMatch(date, filters ?: RecipeFilters())
        val current = parent.coursePlan(course)
        val preferences = mealPreferenceSettings.first()
        val requested = filters ?: current?.filters ?: courseFilters(date, parent, course, preferences)
        if (!requested.isValid()) return MealPlanSelection.NoMatch(date, requested)
        val selected = chooseCourseRecipe(parent, course, requested, preferences, favoritePool(preferences), random)
        val changed = parent.withCourse(course, newPlan(date, requested, selected))
        repository.upsertMealPlan(changed)
        return if (selected == null) MealPlanSelection.NoMatch(date, requested)
        else MealPlanSelection.Selected(changed, selected)
    }

    private fun courseFilters(
        date: LocalDate,
        parent: DayMealPlan,
        course: MealCourse,
        preferences: MealPreferenceSettings,
    ): RecipeFilters = parent.filters.copy(
        category = WeeklyPlanDefaults.categoryFor(date.dayOfWeek, preferences, course).key,
    )

    private suspend fun chooseCourseRecipe(
        parent: DayMealPlan,
        course: MealCourse,
        filters: RecipeFilters,
        preferences: MealPreferenceSettings,
        favorites: List<Recipe>?,
        random: Random,
    ): Recipe? = selectMenuRecipe(
        criteria = ExploreCriteria(
            category = filters.category, easeLevel = filters.easeLevel,
            minRating = filters.minRating, maxPrepMinutes = filters.maxPrepMinutes,
            mealTypeLabels = if (course == MealCourse.SIDE && filters.category == "any") {
                setOf("Συνοδευτικά", "Σαλάτα", "Σαλάτες", "Ορεκτικά", "Ορεκτικό μεζές")
            } else emptySet(),
        ),
        preferences = if (course == MealCourse.SIDE && filters.category == "any") {
            preferences.copy(excludedCategories = preferences.excludedCategories + "dessert")
        } else preferences,
        favorites = favorites,
        excludedIds = MealCourse.entries.filterNot { it == course }.mapNotNullTo(mutableSetOf()) {
            parent.coursePlan(it)?.recipeId?.takeIf(String::isNotBlank)
        },
        random = random,
    )

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
        // Skip other saved courses without loading the full catalog into memory.
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

    private suspend fun setLockedUnlocked(date: LocalDate, locked: Boolean, course: MealCourse = MealCourse.MAIN) {
        repository.ensureReady()
        val parent = repository.mealPlans.first().firstOrNull { it.date == date.toString() } ?: return
        val plan = parent.coursePlan(course) ?: return
        // Completed meals are automatically protected; their completion event stays untouched.
        if (plan.completed || plan.recipeId.isBlank() || plan.locked == locked) return
        repository.upsertMealPlan(parent.withCourse(course, plan.copy(
            locked = locked,
            updatedAtEpochMillis = maxOf(System.currentTimeMillis(), parent.updatedAtEpochMillis + 1),
        )))
    }

    private suspend fun setCompletedUnlocked(date: LocalDate, completed: Boolean, course: MealCourse = MealCourse.MAIN) {
        repository.ensureReady()
        repository.setCourseCompleted(date.toString(), course, completed)
    }

    private suspend fun deleteCookedHistoryEntryUnlocked(historyId: String) {
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

    suspend fun signInWithEmail(email: String, password: String) {
        repository.ensureReady()
        repository.signInWithEmail(email, password)
    }

    suspend fun sendPasswordReset(email: String) {
        repository.sendPasswordReset(email)
    }

    suspend fun signOut() {
        repository.signOut()
    }

    /** Replaces a day's proposal only when [recipeId] is still an available favorite. */
    private suspend fun replaceWithFavoriteUnlocked(
        date: LocalDate,
        recipeId: String,
        course: MealCourse = MealCourse.MAIN,
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
        val selection = newPlan(date, RecipeFilters(category = category), recipe)
        val plan = existing?.withCourse(course, selection) ?: selection
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
        val selection = newPlan(date, filters, recipe)
        return previous?.withCourse(MealCourse.MAIN, selection) ?: selection
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
