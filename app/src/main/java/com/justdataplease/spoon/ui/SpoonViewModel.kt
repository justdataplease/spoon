package com.justdataplease.spoon.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseException
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.EaseLevel
import com.justdataplease.spoon.data.model.MAX_SHOPPING_ITEMS_PER_WRITE
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.domain.MealPlanSelection
import com.justdataplease.spoon.domain.MealPlanner
import com.justdataplease.spoon.domain.ExploreCriteria
import com.justdataplease.spoon.domain.FavoriteReplacementResult
import com.justdataplease.spoon.domain.WeeklyPlanDefaults
import com.justdataplease.spoon.domain.isActiveGreekRecipe
import com.justdataplease.spoon.domain.repository.BackendFailureKind
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.domain.repository.BackendUnavailableException
import com.justdataplease.spoon.domain.repository.AccountOperationException
import com.justdataplease.spoon.domain.repository.AccountState
import com.justdataplease.spoon.domain.repository.CatalogFacetOptions
import com.justdataplease.spoon.ui.account.AccountUiState
import com.justdataplease.spoon.ui.model.CalendarMealUi
import com.justdataplease.spoon.ui.model.DayPlanUi
import com.justdataplease.spoon.ui.model.EaseUi
import com.justdataplease.spoon.ui.model.FavoriteUi
import com.justdataplease.spoon.ui.model.FiltersUi
import com.justdataplease.spoon.ui.model.RecipeDetailUi
import com.justdataplease.spoon.ui.model.SpoonUiState
import com.justdataplease.spoon.ui.model.categoryUiOrNull
import com.justdataplease.spoon.ui.model.toDomainCategoryKey
import com.justdataplease.spoon.ui.explore.ExploreFacetOptionsUi
import com.justdataplease.spoon.ui.explore.ExploreFiltersUi
import com.justdataplease.spoon.ui.explore.ExploreRecipeUi
import com.justdataplease.spoon.ui.explore.ExploreSearchState
import com.justdataplease.spoon.ui.explore.ExploreSourceOptionUi
import com.justdataplease.spoon.ui.explore.exploreFilterInputs
import com.justdataplease.spoon.ui.custom.CustomRecipeEditorState
import com.justdataplease.spoon.ui.custom.CustomRecipeEditorStore
import com.justdataplease.spoon.ui.custom.deleteDraftPhoto
import com.justdataplease.spoon.ui.custom.draftPhotoDataUri
import com.justdataplease.spoon.ui.custom.toDomainCustomRecipe
import com.justdataplease.spoon.ui.custom.validationMessage
import com.justdataplease.spoon.ui.history.HistoryEntryUi
import com.justdataplease.spoon.ui.shopping.ShoppingIngredientDraftUi
import com.justdataplease.spoon.ui.shopping.ShoppingListItemUi
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private data class CatalogProjection(
    val recipesById: Map<String, Recipe> = emptyMap(),
)

private data class ExplorePageSnapshot(
    val recipes: List<Recipe>,
    val totalCount: Int,
    val isLoading: Boolean,
    val hasMore: Boolean,
    val options: ExploreFacetOptionsUi,
)

private data class ExplorePresentation(
    val recipes: List<ExploreRecipeUi>,
    val totalCount: Int,
    val isLoading: Boolean,
    val hasMore: Boolean,
    val options: ExploreFacetOptionsUi,
)

private data class CustomRecipeRevision(
    val id: String,
    val updatedAtEpochMillis: Long,
    val active: Boolean,
)

private data class PlannerSnapshot(
    val catalog: CatalogProjection,
    val plans: List<DayMealPlan>,
    val favoriteIds: Set<String>,
    val backendState: BackendState,
    val userContent: UserContentSnapshot,
)

private data class UserContentSnapshot(
    val shoppingItems: List<ShoppingListItem>,
    val notesByRecipeId: Map<String, RecipeNote>,
    val cookedHistory: List<CookedMeal>,
)

private data class DateSelection(
    val weekStart: LocalDate,
    val month: YearMonth,
    val editingDate: LocalDate?,
    val recipeSelection: RecipeSelection,
)

private data class RecipeSelection(
    val recipeId: String?,
    val details: Recipe?,
    val isLoading: Boolean,
)

private data class WorkStatus(
    val loading: Boolean,
    val working: Boolean,
    val savingCustomRecipe: Boolean,
    val message: String?,
    val account: AccountUiState,
)

private data class AccountOperationStatus(
    val busy: Boolean,
    val errorMessage: String?,
)

private data class ExploreSelection(
    val query: String,
    val filters: ExploreFiltersUi,
    val favoriteReplacementDate: LocalDate?,
    val resultGeneration: Long,
)

@HiltViewModel
class SpoonViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val applicationContext: Context,
    private val mealPlanner: MealPlanner,
) : ViewModel() {
    private val computationScope = CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Default)
    private val exploreSearchState = ExploreSearchState(computationScope)
    private val customRecipeEditorStore = CustomRecipeEditorStore(savedStateHandle)
    val customRecipeEditor = customRecipeEditorStore.state
    val mealPreferenceSettings = mealPlanner.mealPreferenceSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MealPreferenceSettings(),
    )
    val customRecipeEditorRetainedPhoto = combine(
        customRecipeEditor,
        mealPlanner.recipes,
    ) { editor, recipes ->
        if (editor.retainExistingPhoto) {
            recipes.firstOrNull { it.id == editor.recipeId }?.imageUrl.orEmpty()
        } else {
            ""
        }
    }.stateIn(
        scope = computationScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "",
    )
    private val selectedWeekStart = MutableStateFlow(WeeklyPlanDefaults.weekStart(LocalDate.now()))
    private val selectedMonth = MutableStateFlow(YearMonth.now())
    private val editingDate = MutableStateFlow<LocalDate?>(null)
    private val selectedRecipeId = MutableStateFlow<String?>(null)
    private val selectedRecipeDetails = MutableStateFlow<Recipe?>(null)
    private val recipeDetailsLoading = MutableStateFlow(false)
    private val exploreFilters = MutableStateFlow(ExploreFiltersUi())
    private val exploreRecipeRows = MutableStateFlow<List<Recipe>>(emptyList())
    private val exploreTotalCount = MutableStateFlow(0)
    private val explorePageLoading = MutableStateFlow(false)
    private val exploreHasMore = MutableStateFlow(true)
    private val exploreFacetOptions = MutableStateFlow(ExploreFacetOptionsUi())
    private val exploreResultGeneration = MutableStateFlow(0L)
    private val favoriteReplacementDate = MutableStateFlow<LocalDate?>(null)
    private val loading = MutableStateFlow(true)
    private val working = MutableStateFlow(false)
    private val savingCustomRecipe = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val accountBusy = MutableStateFlow(false)
    private val accountError = MutableStateFlow<String?>(null)
    private val accountOwnerTracker = AccountOwnerTracker()
    private val catalogWeekRetryGate = CatalogWeekRetryGate()
    private var recipeDetailsJob: Job? = null
    private var recipeDetailsGeneration = 0L
    private var weekEnsureJob: Job? = null
    private var weekEnsureGeneration = 0L
    private var ensuringWeekStart: LocalDate? = null
    private var exploreLoadJob: Job? = null
    private var exploreFacetLoadJob: Job? = null
    private var exploreGeneration = 0L
    private var exploreNextOffset = 0
    private var activeExploreCriteria = ExploreCriteria()

    private val catalogProjection = mealPlanner.recipes
        .map { recipes ->
            CatalogProjection(
                recipesById = recipes.associateBy(Recipe::id),
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = computationScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CatalogProjection(),
        )

    private val userContentSnapshot = combine(
        mealPlanner.shoppingItems,
        mealPlanner.recipeNotes,
        mealPlanner.cookedHistory,
    ) { shoppingItems, notes, cookedHistory ->
        UserContentSnapshot(
            shoppingItems = shoppingItems,
            notesByRecipeId = notes.associateBy(RecipeNote::recipeId),
            cookedHistory = cookedHistory,
        )
    }

    private val plannerSnapshot = combine(
        catalogProjection,
        mealPlanner.mealPlans,
        mealPlanner.favoriteRecipeIds,
        mealPlanner.backendState,
        userContentSnapshot,
    ) { catalog, plans, favorites, backendState, userContent ->
        PlannerSnapshot(catalog, plans, favorites, backendState, userContent)
    }

    private val recipeSelection = combine(
        selectedRecipeId,
        selectedRecipeDetails,
        recipeDetailsLoading,
    ) { recipeId, details, isLoading ->
        RecipeSelection(recipeId, details, isLoading)
    }

    private val dateSelection = combine(
        selectedWeekStart,
        selectedMonth,
        editingDate,
        recipeSelection,
    ) { week, month, editing, recipe ->
        DateSelection(week, month, editing, recipe)
    }

    private val accountOperationStatus = combine(
        accountBusy,
        accountError,
        ::AccountOperationStatus,
    )

    private val accountUiState = combine(
        mealPlanner.accountState,
        accountOperationStatus,
    ) { account, operation ->
        account.toUi(operation)
    }

    private val workStatus = combine(
        loading,
        working,
        savingCustomRecipe,
        message,
        accountUiState,
    ) { isLoading, isWorking, isSavingCustomRecipe, currentMessage, account ->
        WorkStatus(isLoading, isWorking, isSavingCustomRecipe, currentMessage, account)
    }

    private val exploreSelection = combine(
        exploreSearchState.visibleQuery,
        exploreFilters,
        favoriteReplacementDate,
        exploreResultGeneration,
    ) { query, filters, replacementDate, resultGeneration ->
        ExploreSelection(query, filters, replacementDate, resultGeneration)
    }

    private val exploreFilterInput = exploreFilterInputs(
        exploreSearchState.filterQuery,
        exploreFilters,
    )

    private val customRecipeRevisions = mealPlanner.customRecipes
        .map { recipes ->
            recipes.map { recipe ->
                CustomRecipeRevision(
                    id = recipe.id,
                    updatedAtEpochMillis = recipe.updatedAtEpochMillis,
                    active = recipe.active,
                )
            }
        }
        .distinctUntilChanged()

    private val explorePageSnapshot = combine(
        exploreRecipeRows,
        exploreTotalCount,
        explorePageLoading,
        exploreHasMore,
        exploreFacetOptions,
        ::ExplorePageSnapshot,
    )

    private val exploredRecipes = combine(
        explorePageSnapshot,
        mealPlanner.favoriteRecipeIds,
    ) { page, favoriteIds ->
        ExplorePresentation(
            recipes = page.recipes.map { recipe ->
                recipe.toExploreRecipeUi(recipe.id in favoriteIds)
            },
            totalCount = page.totalCount,
            isLoading = page.isLoading,
            hasMore = page.hasMore,
            options = page.options,
        )
    }.flowOn(Dispatchers.Default)

    val uiState = combine(
        plannerSnapshot,
        dateSelection,
        workStatus,
        exploreSelection,
        exploredRecipes,
    ) { snapshot, dates, status, explore, exploreResults ->
        val recipesById = snapshot.catalog.recipesById
        val plansByDate = snapshot.plans.associateBy(DayMealPlan::date)
        val weekPlans = WeeklyPlanDefaults.dates(dates.weekStart).map { date ->
            val stored = plansByDate[date.toString()]
            val filters = stored?.filters ?: WeeklyPlanDefaults.filtersFor(date)
            val recipe = stored?.recipeId?.let(recipesById::get)
            DayPlanUi(
                date = date,
                recipeId = stored?.recipeId.orEmpty(),
                recipeTitle = recipe?.title ?: stored?.recipeTitle.orEmpty(),
                categoryKey = resolvedUiCategoryKey(
                    recipeCategory = recipe?.category,
                    planCategory = stored?.category,
                    filterCategory = filters.category,
                ),
                rating10 = recipe?.rating ?: 0.0,
                ratingCount = recipe?.ratingCount ?: 0,
                prepMinutes = recipe?.prepMinutes ?: 0,
                cookMinutes = recipe?.cookMinutes ?: 0,
                totalMinutes = recipe?.totalMinutes ?: 0,
                stepCount = recipe?.stepCount ?: 0,
                preparationCount = recipe?.preparationCount ?: 0,
                imageUrl = recipe?.imageUrl.orEmpty(),
                sourceUrl = recipe?.sourceUrl.orEmpty(),
                sourceName = recipe?.sourceName.orEmpty(),
                tags = recipe?.tags.orEmpty(),
                language = recipe?.language ?: "el",
                isFavorite = stored?.recipeId?.let(snapshot.favoriteIds::contains) == true,
                isCompleted = stored?.completed == true,
                filters = filters.toUi(),
            )
        }
        val favoriteRecipes = snapshot.favoriteIds.mapNotNull(recipesById::get).map(Recipe::toFavoriteUi)
        val calendarMeals = snapshot.plans.mapNotNull { plan ->
            val date = runCatching { LocalDate.parse(plan.date) }.getOrNull() ?: return@mapNotNull null
            if (YearMonth.from(date) != dates.month) return@mapNotNull null
            val recipe = recipesById[plan.recipeId]
            CalendarMealUi(
                date = date,
                recipeId = plan.recipeId,
                categoryKey = resolvedUiCategoryKey(
                    recipeCategory = recipe?.category,
                    planCategory = plan.category,
                    filterCategory = plan.filters.category,
                ),
                recipeTitle = recipe?.title ?: plan.recipeTitle,
                isCompleted = plan.completed,
            )
        }
        val historyEntries = snapshot.userContent.cookedHistory.mapNotNull { cookedMeal ->
            val date = runCatching { LocalDate.parse(cookedMeal.date) }.getOrNull()
                ?: return@mapNotNull null
            val recipe = recipesById[cookedMeal.recipeId]
            val categoryKey = (recipe?.category ?: plansByDate[cookedMeal.date]?.category.orEmpty())
                .toUiCategoryKey()
            val category = categoryUiOrNull(categoryKey)
            HistoryEntryUi(
                id = cookedMeal.id.ifBlank { cookedMeal.date },
                date = date,
                recipeId = cookedMeal.recipeId,
                title = recipe?.title ?: cookedMeal.recipeTitle,
                categoryLabel = recipe?.categoryLabel?.takeIf(String::isNotBlank)
                    ?: category?.label
                    ?: "Άλλο",
                categoryEmoji = category?.emoji ?: "🍽️",
                imageUrl = recipe?.imageUrl.orEmpty(),
                completedAtEpochMillis = cookedMeal.completedAtEpochMillis,
            )
        }.sortedByDescending(HistoryEntryUi::completedAtEpochMillis)
        val shoppingItems = snapshot.userContent.shoppingItems.map { item ->
            ShoppingListItemUi(
                id = item.id,
                title = item.name,
                quantity = item.quantity,
                unit = item.unit,
                info = item.info,
                recipeId = item.recipeId,
                recipeTitle = item.recipeTitle,
                isChecked = item.checked,
            )
        }
        val selectedRecipeId = dates.recipeSelection.recipeId
        val selectedRecipe = selectedRecipeId
            ?.let { recipeId ->
                val catalogRecipe = recipesById[recipeId]
                val details = dates.recipeSelection.details?.takeIf { it.id == recipeId }
                (details ?: catalogRecipe)?.withCatalogClassification(catalogRecipe)
            }
        SpoonUiState(
            isLoading = status.loading,
            isWorking = status.working,
            backendState = snapshot.backendState,
            weekStart = dates.weekStart,
            weekPlans = weekPlans,
            favorites = favoriteRecipes.sortedBy(FavoriteUi::title),
            shownMonth = dates.month,
            calendarMeals = calendarMeals,
            editingDate = dates.editingDate,
            exploreQuery = explore.query,
            exploreRecipes = exploreResults.recipes,
            exploreTotalRecipeCount = exploreResults.totalCount,
            exploreResultGeneration = explore.resultGeneration,
            exploreIsLoadingPage = exploreResults.isLoading,
            exploreHasMore = exploreResults.hasMore,
            exploreFilters = explore.filters,
            exploreOptions = exploreResults.options,
            favoriteReplacementDate = explore.favoriteReplacementDate,
            isRecipeDetailsLoading = dates.recipeSelection.isLoading,
            selectedRecipe = selectedRecipe
                ?.let { recipe -> recipe.toRecipeDetailUi(recipe.id in snapshot.favoriteIds) },
            selectedRecipeNote = selectedRecipeId
                ?.let(snapshot.userContent.notesByRecipeId::get)
                ?.text
                .orEmpty(),
            historyEntries = historyEntries,
            shoppingItems = shoppingItems,
            isSavingCustomRecipe = status.savingCustomRecipe,
            account = status.account,
            message = status.message,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = computationScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SpoonUiState(weekStart = selectedWeekStart.value),
    )

    init {
        observeAccountOwner()
        observeCatalogReadiness()
        observeExploreFilters()
        ensureWeek(selectedWeekStart.value)
    }

    fun previousWeek() = showWeek(selectedWeekStart.value.minusWeeks(1))

    fun nextWeek() = showWeek(selectedWeekStart.value.plusWeeks(1))

    fun currentWeek() = showWeek(WeeklyPlanDefaults.weekStart(LocalDate.now()))

    fun previousMonth() {
        selectedMonth.value = selectedMonth.value.minusMonths(1)
    }

    fun nextMonth() {
        selectedMonth.value = selectedMonth.value.plusMonths(1)
    }

    fun currentMonth() {
        selectedMonth.value = YearMonth.now()
    }

    fun editFilters(date: LocalDate) {
        editingDate.value = date
    }

    fun dismissFilters() {
        editingDate.value = null
    }

    fun showRecipeDetails(recipeId: String) {
        if (recipeId.isBlank()) return
        recipeDetailsJob?.cancel()
        val generation = ++recipeDetailsGeneration
        selectedRecipeId.value = recipeId
        selectedRecipeDetails.value = null
        recipeDetailsLoading.value = true
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(Dispatchers.Default) { mealPlanner.getRecipeDetails(recipeId) }
                    .let { details ->
                        if (!isCurrentRecipeRequest(generation, recipeId)) return@let
                        selectedRecipeDetails.value = details
                        if (details == null) {
                            message.value = "Οι πλήρεις λεπτομέρειες δεν είναι διαθέσιμες ακόμη."
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrentRecipeRequest(generation, recipeId)) {
                    message.value = error.userMessage()
                }
            } finally {
                if (isCurrentRecipeRequest(generation, recipeId)) {
                    recipeDetailsLoading.value = false
                    recipeDetailsJob = null
                }
            }
        }
        recipeDetailsJob = job
        job.start()
    }

    fun dismissRecipeDetails() {
        recipeDetailsGeneration++
        recipeDetailsJob?.cancel()
        recipeDetailsJob = null
        selectedRecipeId.value = null
        selectedRecipeDetails.value = null
        recipeDetailsLoading.value = false
    }

    private fun isCurrentRecipeRequest(generation: Long, recipeId: String): Boolean =
        recipeDetailsGeneration == generation && selectedRecipeId.value == recipeId

    fun updateExploreQuery(query: String) {
        exploreSearchState.update(query)
    }

    fun applyExploreFilters(filters: ExploreFiltersUi) {
        exploreFilters.value = filters
    }

    fun saveMealPreferenceSettings(settings: MealPreferenceSettings) {
        launchWorking(
            work = { mealPlanner.saveMealPreferenceSettings(settings) },
        ) {
            message.value = "Οι προτιμήσεις φαγητού αποθηκεύτηκαν."
        }
    }

    /**
     * Loads one bounded page from the bundled SQLite catalog. The public catalog is never
     * materialized in memory and this path performs no Firestore catalog reads.
     */
    fun loadMoreExplore() {
        if (explorePageLoading.value || !exploreHasMore.value) return
        val generation = exploreGeneration
        val offset = exploreNextOffset
        val criteria = activeExploreCriteria
        explorePageLoading.value = true
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val page = withContext(Dispatchers.IO) {
                    mealPlanner.queryRecipes(
                        criteria = criteria,
                        limit = EXPLORE_PAGE_SIZE,
                        offset = offset,
                    )
                }
                if (generation != exploreGeneration) return@launch
                val existing = if (offset == 0) emptyList() else exploreRecipeRows.value
                exploreRecipeRows.value = retainExploreWindow(existing, page.recipes)
                exploreTotalCount.value = page.totalCount
                exploreNextOffset = page.offset + page.recipes.size
                exploreHasMore.value = page.hasMore && page.recipes.isNotEmpty()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == exploreGeneration) {
                    exploreHasMore.value = false
                    message.value = "Δεν φορτώθηκαν οι συνταγές. Δοκίμασε ξανά."
                }
            } finally {
                if (generation == exploreGeneration) {
                    explorePageLoading.value = false
                    exploreLoadJob = null
                }
            }
        }
        exploreLoadJob = job
        job.start()
    }

    private fun observeExploreFilters() {
        viewModelScope.launch {
            combine(
                exploreFilterInput,
                customRecipeRevisions,
                mealPreferenceSettings,
            ) { input, _, _ -> input }
                .collect { input ->
                exploreLoadJob?.cancel()
                exploreLoadJob = null
                exploreFacetLoadJob?.cancel()
                exploreFacetLoadJob = null
                exploreGeneration++
                exploreResultGeneration.value = exploreGeneration
                activeExploreCriteria = input.filters.toDomain(input.query)
                exploreNextOffset = 0
                exploreRecipeRows.value = emptyList()
                exploreTotalCount.value = 0
                explorePageLoading.value = false
                exploreHasMore.value = true
                loadExploreFacetOptions(exploreGeneration)
                loadMoreExplore()
            }
        }
    }

    private fun loadExploreFacetOptions(generation: Long) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            runCatching {
                withContext(Dispatchers.IO) { mealPlanner.getCatalogFacetOptions() }
            }.onSuccess { options ->
                if (generation == exploreGeneration) {
                    exploreFacetOptions.value = options.toUi()
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (generation == exploreGeneration) {
                    message.value = "Δεν φορτώθηκαν τα φίλτρα συνταγών."
                }
            }
        }
        exploreFacetLoadJob = job
        job.start()
    }

    fun showFavoriteReplacement(date: LocalDate) {
        favoriteReplacementDate.value = date
    }

    fun dismissFavoriteReplacement() {
        favoriteReplacementDate.value = null
    }

    fun replaceWithFavorite(recipeId: String) {
        val date = favoriteReplacementDate.value ?: return
        if (recipeId.isBlank()) return
        launchWorking(
            work = { mealPlanner.replaceWithFavorite(date, recipeId) },
        ) { result ->
            when (result) {
                is FavoriteReplacementResult.Selected -> {
                    favoriteReplacementDate.value = null
                    message.value = "Η αγαπημένη συνταγή μπήκε στο πρόγραμμα."
                }
                is FavoriteReplacementResult.NotFavorite -> {
                    message.value = "Η συνταγή δεν βρίσκεται πια στα αγαπημένα σου."
                }
                is FavoriteReplacementResult.RecipeUnavailable -> {
                    message.value = "Η συνταγή δεν είναι πλέον διαθέσιμη στον κατάλογο."
                }
            }
        }
    }

    fun reroll(date: LocalDate) = launchSelection { mealPlanner.reroll(date) }

    fun saveFilters(date: LocalDate, filters: FiltersUi) {
        editingDate.value = null
        launchSelection { mealPlanner.updateFilters(date, filters.toDomain()) }
    }

    fun shuffleWeek() {
        launchWorking(
            work = {
                var misses = 0
                WeeklyPlanDefaults.dates(selectedWeekStart.value).forEach { date ->
                    if (mealPlanner.reroll(date) is MealPlanSelection.NoMatch) misses++
                }
                misses
            },
        ) { misses ->
            message.value = when {
                misses == 0 -> "Έτοιμη η νέα εβδομάδα!"
                misses == 1 -> "Μία μέρα δεν είχε άλλη πρόταση με αυτά τα φίλτρα."
                else -> "$misses μέρες δεν είχαν άλλη πρόταση με αυτά τα φίλτρα."
            }
        }
    }

    fun toggleFavorite(recipeId: String) {
        if (recipeId.isBlank()) return
        launchReporting { mealPlanner.toggleFavorite(recipeId) }
    }

    fun addIngredientsToShopping(drafts: List<ShoppingIngredientDraftUi>) {
        val items = drafts.toShoppingItems()
        if (items.isEmpty()) return
        launchWorking(
            work = {
                items.chunked(MAX_SHOPPING_ITEMS_PER_WRITE).forEach { chunk ->
                    mealPlanner.upsertShoppingItems(chunk)
                }
            },
        ) {
            message.value = if (items.size == 1) {
                "Το υλικό προστέθηκε στη λίστα αγορών."
            } else {
                "Προστέθηκαν ${items.size} υλικά στη λίστα αγορών."
            }
        }
    }

    fun addManualShoppingItem(title: String) {
        val cleanTitle = title.trim().take(200)
        if (cleanTitle.isBlank()) return
        val now = System.currentTimeMillis()
        launchReporting {
            mealPlanner.upsertShoppingItems(
                listOf(
                    ShoppingListItem(
                        id = "shopping_${UUID.randomUUID()}",
                        name = cleanTitle,
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    ),
                ),
            )
        }
    }

    fun toggleShoppingItem(itemId: String) {
        val item = uiState.value.shoppingItems.firstOrNull { it.id == itemId } ?: return
        launchReporting { mealPlanner.setShoppingItemChecked(itemId, !item.isChecked) }
    }

    fun removeShoppingItem(itemId: String) {
        launchReporting { mealPlanner.deleteShoppingItem(itemId) }
    }

    fun clearCheckedShoppingItems() {
        launchReporting { mealPlanner.clearCheckedShoppingItems() }
    }

    fun saveRecipeNote(text: String) {
        val recipeId = selectedRecipeId.value ?: return
        launchReporting("Η σημείωση αποθηκεύτηκε.") {
            mealPlanner.saveRecipeNote(recipeId, text.take(10_000))
        }
    }

    fun createCustomRecipe() {
        replaceCustomRecipeEditor(CustomRecipeEditorState.create())
    }

    fun editCustomRecipe(recipe: RecipeDetailUi) {
        replaceCustomRecipeEditor(CustomRecipeEditorState.edit(recipe))
    }

    fun updateCustomRecipeEditor(next: CustomRecipeEditorState) {
        val current = customRecipeEditor.value
        if (
            savingCustomRecipe.value ||
            !current.isOpen ||
            next.mode != current.mode ||
            next.recipeId != current.recipeId
        ) {
            if (current.selectedPhotoPath != next.selectedPhotoPath) {
                applicationContext.deleteDraftPhoto(next.selectedPhotoPath)
            }
            return
        }
        customRecipeEditorStore.set(next)
        if (current.selectedPhotoPath != next.selectedPhotoPath) {
            applicationContext.deleteDraftPhoto(current.selectedPhotoPath)
        }
    }

    fun dismissCustomRecipeEditor() {
        if (savingCustomRecipe.value) return
        clearCustomRecipeEditor()
    }

    private fun clearCustomRecipeEditor() {
        applicationContext.deleteDraftPhoto(customRecipeEditor.value.selectedPhotoPath)
        customRecipeEditorStore.clear()
    }

    fun saveCustomRecipeEditor() {
        if (savingCustomRecipe.value) return
        val editor = customRecipeEditor.value.takeIf(CustomRecipeEditorState::isOpen) ?: return
        val validationMessage = editor.completedDraft(photoDataUri = "").validationMessage()
        if (validationMessage != null) {
            customRecipeEditorStore.set(editor.copy(formMessage = validationMessage))
            return
        }
        savingCustomRecipe.value = true
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val photoDataUri = when {
                        editor.selectedPhotoPath.isNotBlank() ->
                            applicationContext.draftPhotoDataUri(editor.selectedPhotoPath)
                                ?: throw DraftPhotoUnavailableException()
                        editor.retainExistingPhoto ->
                            mealPlanner.getRecipeDetails(editor.recipeId)?.imageUrl.orEmpty()
                        else -> ""
                    }
                    mealPlanner.saveCustomRecipe(
                        editor.completedDraft(photoDataUri).toDomainCustomRecipe(),
                    )
                }
                message.value = "Η δική σου συνταγή αποθηκεύτηκε."
                clearCustomRecipeEditor()
                showRecipeDetails(saved.id)
            } catch (error: Exception) {
                if (error is DraftPhotoUnavailableException) {
                    customRecipeEditorStore.set(
                        editor.copy(
                            formMessage = "Η πρόχειρη φωτογραφία δεν είναι πλέον διαθέσιμη. Διάλεξέ την ξανά.",
                        ),
                    )
                } else {
                    message.value = error.userMessage()
                }
            } finally {
                savingCustomRecipe.value = false
            }
        }
    }

    private fun replaceCustomRecipeEditor(next: CustomRecipeEditorState) {
        if (savingCustomRecipe.value) return
        applicationContext.deleteDraftPhoto(customRecipeEditor.value.selectedPhotoPath)
        customRecipeEditorStore.set(next)
    }

    fun toggleCompleted(date: LocalDate) {
        val state = uiState.value
        val completed = completionStateForDate(
            date = date,
            calendarMeals = state.calendarMeals,
            weekPlans = state.weekPlans,
        )
        launchReporting { mealPlanner.setCompleted(date, !completed) }
    }

    fun removeCookedHistoryEntry(historyId: String) {
        if (historyId.isBlank()) return
        launchReporting("Η εγγραφή αφαιρέθηκε από το ιστορικό.") {
            mealPlanner.deleteCookedHistoryEntry(historyId)
        }
    }

    fun clearMessage() {
        message.value = null
    }

    fun createOrLinkAccount(email: String, password: String) {
        launchAccountOperation("Ο λογαριασμός σου κατοχυρώθηκε και τα δεδομένα σου είναι ασφαλή.") {
            mealPlanner.registerEmailAccount(email, password)
        }
    }

    fun signInWithEmail(email: String, password: String) {
        launchAccountOperation("Συνδέθηκες στον λογαριασμό σου.") {
            mealPlanner.signInWithEmail(email, password)
        }
    }

    fun resetPassword(email: String) {
        launchAccountOperation("Σου στείλαμε μήνυμα επαναφοράς κωδικού στην ηλεκτρονική σου διεύθυνση.") {
            mealPlanner.sendPasswordReset(email)
        }
    }

    fun signOut() {
        launchAccountOperation("Αποσυνδέθηκες. Συνεχίζεις με προσωρινό λογαριασμό.") {
            mealPlanner.signOutToAnonymous()
        }
    }

    fun clearAccountError() {
        accountError.value = null
    }

    private fun showWeek(weekStart: LocalDate) {
        selectedWeekStart.value = WeeklyPlanDefaults.weekStart(weekStart)
        ensureWeek(weekStart)
    }

    private fun observeAccountOwner() {
        viewModelScope.launch {
            mealPlanner.accountState.collect { account ->
                if (accountOwnerTracker.onAccountState(account)) {
                    dismissCustomRecipeEditor()
                    dismissRecipeDetails()
                    resetWeekEnsureForAccountOwner()
                }
            }
        }
    }

    private fun resetWeekEnsureForAccountOwner() {
        weekEnsureGeneration++
        weekEnsureJob?.cancel()
        weekEnsureJob = null
        ensuringWeekStart = null
        loading.value = false
        catalogWeekRetryGate.onAccountOwnerChanged(selectedWeekStart.value)
    }

    private fun observeCatalogReadiness() {
        viewModelScope.launch {
            combine(
                mealPlanner.backendState,
                mealPlanner.recipes,
                ::isCatalogReadyForPlanning,
            ).distinctUntilChanged().collect { ready ->
                catalogWeekRetryGate.onCatalogReadiness(ready)
                    ?.let(::retryWeekAfterCatalog)
            }
        }
    }

    private fun ensureWeek(date: LocalDate) {
        val weekStart = WeeklyPlanDefaults.weekStart(date)
        if (weekEnsureJob?.isActive == true && ensuringWeekStart == weekStart) return
        catalogWeekRetryGate.onDirectRequest(weekStart)
        startWeekEnsure(weekStart, isCatalogRetry = false)
    }

    private fun retryWeekAfterCatalog(weekStart: LocalDate) {
        if (weekEnsureJob?.isActive == true) return
        if (!catalogWeekRetryGate.consumeRetry(weekStart)) return
        startWeekEnsure(weekStart, isCatalogRetry = true)
    }

    private fun startWeekEnsure(
        weekStart: LocalDate,
        isCatalogRetry: Boolean,
    ) {
        if (!isCatalogRetry) weekEnsureJob?.cancel()
        val generation = ++weekEnsureGeneration
        ensuringWeekStart = weekStart
        loading.value = true
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val plans = withUserActionTimeout {
                    withContext(Dispatchers.Default) { mealPlanner.ensureWeek(weekStart) }
                }
                if (plans.isNotEmpty() && plans.all { it.recipeId.isNotBlank() }) {
                    catalogWeekRetryGate.onAttemptSucceeded(weekStart)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message.value = error.userMessage()
            } finally {
                if (weekEnsureGeneration == generation) {
                    weekEnsureJob = null
                    ensuringWeekStart = null
                    loading.value = false
                    catalogWeekRetryGate.retryCandidate()
                        ?.let(::retryWeekAfterCatalog)
                }
            }
        }
        weekEnsureJob = job
        job.start()
    }

    private fun launchSelection(block: suspend () -> MealPlanSelection) {
        launchWorking(work = block) { selection ->
            when (selection) {
                is MealPlanSelection.Selected -> Unit
                is MealPlanSelection.NoMatch -> {
                    message.value = "Δεν βρέθηκε άλλη συνταγή που να ταιριάζει σε όλα τα φίλτρα."
                }
            }
        }
    }

    /** Fire-and-forget write that only surfaces failures (and an optional success toast). */
    private fun launchReporting(successMessage: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { if (successMessage != null) message.value = successMessage }
                .onFailure { message.value = it.userMessage() }
        }
    }

    /** Runs bounded background work behind the "working" chrome; [onResult] owns the success path. */
    private fun <T> launchWorking(work: suspend () -> T, onResult: (T) -> Unit) {
        viewModelScope.launch {
            working.value = true
            try {
                onResult(withUserActionTimeout { withContext(Dispatchers.Default) { work() } })
            } catch (error: Exception) {
                message.value = error.userMessage()
            } finally {
                working.value = false
            }
        }
    }

    private fun launchAccountOperation(
        successMessage: String,
        operation: suspend () -> Unit,
    ) {
        if (accountBusy.value) return
        viewModelScope.launch {
            accountBusy.value = true
            accountError.value = null
            try {
                operation()
                message.value = successMessage
            } catch (error: AccountOperationException) {
                accountError.value = error.failure.greekMessage
            } catch (_: Exception) {
                accountError.value = ACCOUNT_GENERIC_ERROR
            } finally {
                accountBusy.value = false
            }
        }
    }
}

/** Tracks only ownership, so an email-verification refresh on the same UID keeps the selection. */
internal class AccountOwnerTracker {
    private var hasObservedAccount = false
    private var ownerUid: String? = null

    fun onAccountState(account: AccountState): Boolean {
        val nextOwnerUid = account.ownerUidOrNull()
        val ownerChanged = hasObservedAccount && ownerUid != nextOwnerUid
        hasObservedAccount = true
        ownerUid = nextOwnerUid
        return ownerChanged
    }
}

private fun AccountState.ownerUidOrNull(): String? = when (this) {
    is AccountState.Anonymous -> uid
    is AccountState.Email -> uid
    AccountState.Loading,
    AccountState.Unavailable,
    -> null
}

/**
 * Remembers a direct request made before the complete catalog is observable. A retry is consumed
 * before it starts, so another backend-state emission cannot create duplicate plans or a loop.
 */
internal class CatalogWeekRetryGate {
    private var catalogReady = false
    private var pendingWeekStart: LocalDate? = null

    fun onDirectRequest(weekStart: LocalDate) {
        pendingWeekStart = weekStart.takeUnless { catalogReady }
    }

    fun onCatalogReadiness(ready: Boolean): LocalDate? {
        catalogReady = ready
        return retryCandidate()
    }

    /** Invalidates readiness from the previous owner and queues the visible week for the new one. */
    fun onAccountOwnerChanged(weekStart: LocalDate) {
        catalogReady = false
        pendingWeekStart = weekStart
    }

    fun onAttemptSucceeded(weekStart: LocalDate) {
        if (pendingWeekStart == weekStart) pendingWeekStart = null
    }

    fun retryCandidate(): LocalDate? = pendingWeekStart.takeIf { catalogReady }

    fun consumeRetry(weekStart: LocalDate): Boolean {
        if (!catalogReady || pendingWeekStart != weekStart) return false
        pendingWeekStart = null
        return true
    }
}

internal fun isCatalogReadyForPlanning(
    backendState: BackendState,
    recipes: List<Recipe>,
): Boolean = (backendState is BackendState.Cloud || backendState is BackendState.Local) &&
    recipes.any(Recipe::isActiveGreekRecipe)

internal const val USER_ACTION_TIMEOUT_MILLIS = 20_000L
internal const val EXPLORE_PAGE_SIZE = 24
internal const val EXPLORE_MAX_RETAINED_RECIPES = EXPLORE_PAGE_SIZE * 10

internal fun retainExploreWindow(
    existing: List<Recipe>,
    incoming: List<Recipe>,
    maxRetained: Int = EXPLORE_MAX_RETAINED_RECIPES,
): List<Recipe> {
    require(maxRetained > 0)
    return (existing + incoming)
        .distinctBy(Recipe::id)
        .takeLast(maxRetained)
}

internal class UserActionTimeoutException : Exception()

/** Bounds user-triggered work so a stalled operation cannot leave progress chrome forever. */
internal suspend fun <T> withUserActionTimeout(
    timeoutMillis: Long = USER_ACTION_TIMEOUT_MILLIS,
    block: suspend () -> T,
): T {
    require(timeoutMillis > 0L)
    return try {
        withTimeout(timeoutMillis) { block() }
    } catch (_: TimeoutCancellationException) {
        throw UserActionTimeoutException()
    }
}

private fun AccountState.toUi(operation: AccountOperationStatus): AccountUiState = when (this) {
    AccountState.Loading -> AccountUiState(
        isSignedIn = false,
        isAnonymous = false,
        isBusy = true,
        errorMessage = operation.errorMessage,
    )

    is AccountState.Anonymous -> AccountUiState(
        isSignedIn = true,
        isAnonymous = true,
        isBusy = operation.busy,
        errorMessage = operation.errorMessage,
    )

    is AccountState.Email -> AccountUiState(
        isSignedIn = true,
        isAnonymous = false,
        email = email,
        isEmailVerified = emailVerified,
        isBusy = operation.busy,
        errorMessage = operation.errorMessage,
    )

    AccountState.Unavailable -> AccountUiState(
        isSignedIn = false,
        isAnonymous = false,
        isBusy = operation.busy,
        errorMessage = operation.errorMessage,
    )
}

private const val ACCOUNT_GENERIC_ERROR =
    "Δεν ολοκληρώθηκε η ενέργεια λογαριασμού. Δοκίμασε ξανά."

private class DraftPhotoUnavailableException : IllegalStateException()

internal fun completionStateForDate(
    date: LocalDate,
    calendarMeals: List<CalendarMealUi>,
    weekPlans: List<DayPlanUi>,
): Boolean = calendarMeals.firstOrNull { it.date == date }?.isCompleted
    ?: weekPlans.firstOrNull { it.date == date }?.isCompleted
    ?: false

private fun List<ShoppingIngredientDraftUi>.toShoppingItems(): List<ShoppingListItem> {
    val now = System.currentTimeMillis()
    return asSequence()
        .filter { it.title.isNotBlank() }
        .map { draft ->
            ShoppingListItem(
                id = "shopping_${UUID.randomUUID()}",
                name = draft.title.trim().take(200),
                quantity = draft.quantity.trim().take(100),
                unit = draft.unit.trim().take(100),
                info = draft.info.trim().take(500),
                recipeId = draft.recipeId,
                recipeTitle = draft.recipeTitle.trim().take(300),
                checked = false,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        }
        .toList()
}

private fun Recipe.withCatalogClassification(catalogRecipe: Recipe?): Recipe {
    if (catalogRecipe == null || catalogRecipe.id != id) return this
    return copy(
        category = catalogRecipe.category,
        categoryLabel = catalogRecipe.categoryLabel,
        categorySourceId = catalogRecipe.categorySourceId,
        tags = catalogRecipe.tags.ifEmpty { tags },
    )
}

private fun Recipe.toFavoriteUi() = FavoriteUi(
    recipeId = id,
    title = title,
    categoryKey = category.toUiCategoryKey(),
    rating10 = rating,
    prepMinutes = prepMinutes,
    cookMinutes = cookMinutes,
    totalMinutes = totalMinutes,
    stepCount = stepCount,
    preparationCount = preparationCount,
    imageUrl = imageUrl,
    sourceUrl = sourceUrl,
    sourceName = sourceName,
    tags = tags,
    language = language,
)

private fun Recipe.toRecipeDetailUi(isFavorite: Boolean) = RecipeDetailUi(
    recipeId = id,
    title = title,
    categoryKey = category.toUiCategoryKey(),
    rating10 = rating,
    prepMinutes = prepMinutes,
    cookMinutes = cookMinutes,
    totalMinutes = totalMinutes,
    stepCount = stepCount,
    preparationCount = preparationCount,
    imageUrl = imageUrl,
    sourceUrl = sourceUrl,
    sourceName = sourceName,
    tags = tags,
    language = language,
    isFavorite = isFavorite,
    sourceRecipeId = sourceRecipeId,
    slug = slug,
    description = description,
    seoTitle = seoTitle,
    seoDescription = seoDescription,
    categoryLabel = categoryLabel,
    categorySourceId = categorySourceId,
    ratingCount = ratingCount,
    rating1 = rating1,
    rating2 = rating2,
    rating3 = rating3,
    rating4 = rating4,
    rating5 = rating5,
    waitMinutes = waitMinutes,
    sourceDifficulty = sourceDifficulty,
    servings = servings,
    recipeYield = recipeYield,
    imageUrls = imageUrls,
    shortUrl = shortUrl,
    dietLabels = dietLabels,
    mealTypeLabels = mealTypeLabels,
    occasionLabels = occasionLabels,
    methodLabels = methodLabels,
    cuisineLabels = cuisineLabels,
    ingredientLabels = ingredientLabels,
    quickRecipe = quickRecipe,
    videoUrls = videoUrls,
    ingredientSections = ingredientSections,
    methodSections = methodSections,
    tips = tips,
    nutritionTips = nutritionTips,
    nutritionPer = nutritionPer,
    nutritionSections = nutritionSections,
    equipment = equipment,
    authorName = authorName,
    publishedAt = publishedAt,
    published = published,
    createdAt = createdAt,
    sourceUpdatedAt = sourceUpdatedAt,
    shares = shares,
    sponsorLogoUrl = sponsorLogoUrl,
    notes = notes,
)

private fun Recipe.toExploreRecipeUi(isFavorite: Boolean): ExploreRecipeUi {
    val uiCategoryKey = category.toUiCategoryKey()
    val knownCategory = categoryUiOrNull(uiCategoryKey)
    return ExploreRecipeUi(
        recipeId = id,
        title = title,
        description = description,
        categoryKey = uiCategoryKey,
        categoryLabel = categoryLabel.ifBlank { knownCategory?.label ?: "Άλλη κατηγορία" },
        categoryEmoji = knownCategory?.emoji ?: "🍽️",
        rating10 = rating,
        ratingCount = ratingCount,
        prepMinutes = prepMinutes,
        totalMinutes = totalMinutes,
        preparationCount = preparationCount,
        stepCount = stepCount,
        imageUrl = imageUrl,
        sourceKey = effectiveSourceKey,
        sourceName = sourceName,
        isFavorite = isFavorite,
    )
}

private fun CatalogFacetOptions.toUi() = ExploreFacetOptionsUi(
    sources = sources.map { source ->
        ExploreSourceOptionUi(
            key = source.key,
            label = source.label,
            recipeCount = source.recipeCount,
        )
    },
    diets = diets.cleanFacetOptions(),
    mealTypes = mealTypes.cleanFacetOptions(),
    occasions = occasions.cleanFacetOptions(),
    methods = methods.cleanFacetOptions(),
    cuisines = cuisines.cleanFacetOptions(),
    ingredients = ingredients.cleanFacetOptions(),
)

internal fun List<String>.cleanFacetOptions(): List<String> = asSequence()
    .map { it.trim().replace(FacetWhitespace, " ") }
    .filter(String::isNotBlank)
    .groupBy(String::normalizedFacetOptionKey)
    .values
    .map { variants ->
        variants.reduce { best, candidate ->
            if (candidate.facetDisplayQuality() > best.facetDisplayQuality()) candidate else best
        }
    }
    .sortedBy(String::normalizedFacetOptionKey)

private fun String.normalizedFacetOptionKey(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .lowercase(Locale.ROOT)
        .replace('ς', 'σ')
        .replace(FacetWhitespace, " ")
        .trim()

private fun String.facetDisplayQuality(): Int =
    (if (any(Char::isLowerCase)) 2 else 0) +
        if (
            Normalizer.normalize(this, Normalizer.Form.NFD)
                .any { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        ) 1 else 0

private val FacetWhitespace = Regex("\\s+")

private fun ExploreFiltersUi.toDomain(query: String) = ExploreCriteria(
    query = query,
    category = categoryKey.toDomainCategoryKey(),
    easeLevel = ease.toDomainEaseKey(),
    minRating = minRating10.coerceIn(0, 9).toDouble(),
    maxPrepMinutes = maxPrepMinutes?.coerceAtLeast(0) ?: 0,
    dietLabels = diet.asSelectedSet(),
    mealTypeLabels = mealType.asSelectedSet(),
    occasionLabels = occasion.asSelectedSet(),
    methodLabels = method.asSelectedSet(),
    cuisineLabels = cuisine.asSelectedSet(),
    ingredientLabels = ingredient.asSelectedSet(),
    sourceKeys = sourceKeys.filter(String::isNotBlank).toSet(),
    quickOnly = quickOnly,
)

private fun String.asSelectedSet(): Set<String> = if (isBlank()) emptySet() else setOf(this)

private fun EaseUi.toDomainEaseKey(): String = when (this) {
    EaseUi.EASY -> EaseLevel.EASY.key
    EaseUi.MEDIUM -> EaseLevel.MODERATE.key
    EaseUi.HARD -> EaseLevel.INVOLVED.key
    EaseUi.ANY, EaseUi.UNKNOWN -> ""
}

private fun RecipeFilters.toUi() = FiltersUi(
    categoryKey = category.toUiCategoryKey(),
    ease = when (easeLevel) {
        EaseLevel.EASY.key -> EaseUi.EASY
        EaseLevel.MODERATE.key -> EaseUi.MEDIUM
        EaseLevel.INVOLVED.key -> EaseUi.HARD
        else -> EaseUi.ANY
    },
    minRating10 = minRating.toInt().coerceIn(0, 9),
    maxPrepMinutes = maxPrepMinutes.takeIf { it > 0 },
)

private fun FiltersUi.toDomain() = RecipeFilters(
    category = categoryKey.toDomainCategoryKey(),
    easeLevel = ease.toDomainEaseKey(),
    minRating = minRating10.coerceIn(0, 9).toDouble(),
    maxPrepMinutes = maxPrepMinutes ?: 0,
)

private fun String.toUiCategoryKey(): String = when (this) {
    MealCategory.POULTRY.key -> "chicken"
    MealCategory.VEGETABLES.key -> "vegetarian"
    MealCategory.STREET_FOOD.key -> "dirty"
    MealCategory.PASTA_RICE.key -> "pasta"
    else -> this
}

internal fun resolvedUiCategoryKey(
    recipeCategory: String?,
    planCategory: String?,
    filterCategory: String,
): String = (
    recipeCategory
        ?.takeIf(String::isNotBlank)
        ?: planCategory
            ?.takeIf(String::isNotBlank)
        ?: filterCategory
    ).toUiCategoryKey()

internal fun Throwable.userMessage(): String {
    if (this is UserActionTimeoutException) {
        return "Η ενέργεια άργησε περισσότερο από το αναμενόμενο. Δοκίμασε ξανά."
    }
    if (this is BackendUnavailableException) {
        return when (failure.kind) {
            BackendFailureKind.PERMISSION -> "Το Firestore απέρριψε την πρόσβαση. Έλεγξε τους κανόνες ασφαλείας."
            BackendFailureKind.CONFIGURATION -> "Η ρύθμιση του Firebase χρειάζεται διόρθωση."
            BackendFailureKind.AUTHENTICATION -> "Η ταυτοποίηση στο Firebase απέτυχε."
            BackendFailureKind.NETWORK -> "Δεν υπάρχει σύνδεση με το Firestore. Θα γίνει νέα προσπάθεια."
            BackendFailureKind.UNKNOWN -> "Η σύνδεση με το Firebase απέτυχε."
        }
    }
    if (this is FirebaseException) {
        return "Δεν ολοκληρώθηκε ο συγχρονισμός με το Firebase. Δοκίμασε ξανά."
    }
    return "Κάτι πήγε στραβά. Δοκίμασε ξανά."
}
