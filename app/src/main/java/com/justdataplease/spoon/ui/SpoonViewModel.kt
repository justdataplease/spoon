package com.justdataplease.spoon.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseException
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.EaseLevel
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.domain.MealPlanSelection
import com.justdataplease.spoon.domain.MealPlanner
import com.justdataplease.spoon.domain.ExploreCriteria
import com.justdataplease.spoon.domain.ExploreRecipeFilter
import com.justdataplease.spoon.domain.FavoriteReplacementResult
import com.justdataplease.spoon.domain.WeeklyPlanDefaults
import com.justdataplease.spoon.domain.repository.BackendFailureKind
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.domain.repository.BackendUnavailableException
import com.justdataplease.spoon.ui.model.CalendarMealUi
import com.justdataplease.spoon.ui.model.DayPlanUi
import com.justdataplease.spoon.ui.model.EaseUi
import com.justdataplease.spoon.ui.model.FavoriteUi
import com.justdataplease.spoon.ui.model.FiltersUi
import com.justdataplease.spoon.ui.model.RecipeDetailUi
import com.justdataplease.spoon.ui.model.SpoonUiState
import com.justdataplease.spoon.ui.explore.ExploreFacetOptionsUi
import com.justdataplease.spoon.ui.explore.ExploreFiltersUi
import com.justdataplease.spoon.ui.explore.ExploreRecipeUi
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class PlannerSnapshot(
    val recipes: List<Recipe>,
    val plans: List<DayMealPlan>,
    val favoriteIds: Set<String>,
    val backendState: BackendState,
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
    val message: String?,
)

private data class ExploreSelection(
    val query: String,
    val filters: ExploreFiltersUi,
    val favoriteReplacementDate: LocalDate?,
)

@HiltViewModel
class SpoonViewModel @Inject constructor(
    private val mealPlanner: MealPlanner,
) : ViewModel() {
    private val selectedWeekStart = MutableStateFlow(WeeklyPlanDefaults.weekStart(LocalDate.now()))
    private val selectedMonth = MutableStateFlow(YearMonth.now())
    private val editingDate = MutableStateFlow<LocalDate?>(null)
    private val selectedRecipeId = MutableStateFlow<String?>(null)
    private val selectedRecipeDetails = MutableStateFlow<Recipe?>(null)
    private val recipeDetailsLoading = MutableStateFlow(false)
    private val exploreQuery = MutableStateFlow("")
    private val exploreFilters = MutableStateFlow(ExploreFiltersUi())
    private val favoriteReplacementDate = MutableStateFlow<LocalDate?>(null)
    private val loading = MutableStateFlow(true)
    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val plannerSnapshot = combine(
        mealPlanner.recipes,
        mealPlanner.mealPlans,
        mealPlanner.favoriteRecipeIds,
        mealPlanner.backendState,
    ) { recipes, plans, favorites, backendState ->
        PlannerSnapshot(recipes, plans, favorites, backendState)
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

    private val workStatus = combine(loading, working, message) { isLoading, isWorking, currentMessage ->
        WorkStatus(isLoading, isWorking, currentMessage)
    }

    private val exploreSelection = combine(
        exploreQuery,
        exploreFilters,
        favoriteReplacementDate,
    ) { query, filters, replacementDate ->
        ExploreSelection(query, filters, replacementDate)
    }

    val uiState = combine(
        plannerSnapshot,
        dateSelection,
        workStatus,
        exploreSelection,
    ) { snapshot, dates, status, explore ->
        val recipesById = snapshot.recipes.associateBy(Recipe::id)
        val plansByDate = snapshot.plans.associateBy(DayMealPlan::date)
        val weekPlans = WeeklyPlanDefaults.dates(dates.weekStart).map { date ->
            val stored = plansByDate[date.toString()]
            val filters = stored?.filters ?: WeeklyPlanDefaults.filtersFor(date)
            val recipe = stored?.recipeId?.let(recipesById::get)
            DayPlanUi(
                date = date,
                recipeId = stored?.recipeId.orEmpty(),
                recipeTitle = recipe?.title ?: stored?.recipeTitle.orEmpty(),
                categoryKey = filters.category.toUiCategoryKey(),
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
                isDemo = recipe?.tags?.contains("demo") == true,
            )
        }
        val favoriteRecipes = snapshot.favoriteIds.mapNotNull(recipesById::get).map(Recipe::toFavoriteUi)
        val calendarMeals = snapshot.plans.mapNotNull { plan ->
            val date = runCatching { LocalDate.parse(plan.date) }.getOrNull() ?: return@mapNotNull null
            if (YearMonth.from(date) != dates.month) return@mapNotNull null
            val recipe = recipesById[plan.recipeId]
            CalendarMealUi(
                date = date,
                categoryKey = plan.category.toUiCategoryKey(),
                recipeTitle = recipe?.title ?: plan.recipeTitle,
                isCompleted = plan.completed,
            )
        }
        val activeGreekRecipes = snapshot.recipes.filter { recipe ->
            recipe.active && recipe.language.trim().lowercase().let { it == "el" || it.startsWith("el-") || it.startsWith("el_") }
        }
        val exploredRecipes = ExploreRecipeFilter.filter(
            recipes = activeGreekRecipes,
            criteria = explore.filters.toDomain(explore.query),
        ).map { recipe -> recipe.toExploreRecipeUi(recipe.id in snapshot.favoriteIds) }
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
            exploreRecipes = exploredRecipes,
            exploreTotalRecipeCount = activeGreekRecipes.size,
            exploreFilters = explore.filters,
            exploreOptions = activeGreekRecipes.toExploreOptionsUi(),
            favoriteReplacementDate = explore.favoriteReplacementDate,
            isRecipeDetailsLoading = dates.recipeSelection.isLoading,
            selectedRecipe = dates.recipeSelection.recipeId
                ?.let { recipeId ->
                    dates.recipeSelection.details
                        ?.takeIf { it.id == recipeId }
                        ?: recipesById[recipeId]
                }
                ?.let { recipe -> recipe.toRecipeDetailUi(recipe.id in snapshot.favoriteIds) },
            message = status.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SpoonUiState(weekStart = selectedWeekStart.value),
    )

    init {
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
        selectedRecipeId.value = recipeId
        selectedRecipeDetails.value = null
        recipeDetailsLoading.value = true
        viewModelScope.launch {
            runCatching { mealPlanner.getRecipeDetails(recipeId) }
                .onSuccess { details ->
                    if (selectedRecipeId.value == recipeId) {
                        selectedRecipeDetails.value = details
                        if (details == null) {
                            message.value = "Οι πλήρεις λεπτομέρειες δεν είναι διαθέσιμες ακόμη."
                        }
                    }
                }
                .onFailure { error ->
                    if (selectedRecipeId.value == recipeId) message.value = error.userMessage()
                }
            if (selectedRecipeId.value == recipeId) recipeDetailsLoading.value = false
        }
    }

    fun dismissRecipeDetails() {
        selectedRecipeId.value = null
        selectedRecipeDetails.value = null
        recipeDetailsLoading.value = false
    }

    fun updateExploreQuery(query: String) {
        exploreQuery.value = query.take(160)
    }

    fun applyExploreFilters(filters: ExploreFiltersUi) {
        exploreFilters.value = filters
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
        viewModelScope.launch {
            working.value = true
            try {
                when (mealPlanner.replaceWithFavorite(date, recipeId)) {
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
            } catch (error: Exception) {
                message.value = error.userMessage()
            } finally {
                working.value = false
            }
        }
    }

    fun reroll(date: LocalDate) = launchSelection { mealPlanner.reroll(date) }

    fun saveFilters(date: LocalDate, filters: FiltersUi) {
        editingDate.value = null
        launchSelection { mealPlanner.updateFilters(date, filters.toDomain()) }
    }

    fun shuffleWeek() {
        viewModelScope.launch {
            working.value = true
            try {
                var misses = 0
                WeeklyPlanDefaults.dates(selectedWeekStart.value).forEach { date ->
                    if (mealPlanner.reroll(date) is MealPlanSelection.NoMatch) misses++
                }
                message.value = when {
                    misses == 0 -> "Έτοιμη η νέα εβδομάδα!"
                    misses == 1 -> "Μία μέρα δεν είχε άλλη πρόταση με αυτά τα φίλτρα."
                    else -> "$misses μέρες δεν είχαν άλλη πρόταση με αυτά τα φίλτρα."
                }
            } catch (error: Exception) {
                message.value = error.userMessage()
            } finally {
                working.value = false
            }
        }
    }

    fun toggleFavorite(recipeId: String) {
        if (recipeId.isBlank()) return
        viewModelScope.launch {
            runCatching { mealPlanner.toggleFavorite(recipeId) }
                .onFailure { message.value = it.userMessage() }
        }
    }

    fun toggleCompleted(date: LocalDate) {
        val completed = uiState.value.weekPlans.firstOrNull { it.date == date }?.isCompleted ?: false
        viewModelScope.launch {
            runCatching { mealPlanner.setCompleted(date, !completed) }
                .onFailure { message.value = it.userMessage() }
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun showWeek(weekStart: LocalDate) {
        selectedWeekStart.value = WeeklyPlanDefaults.weekStart(weekStart)
        ensureWeek(weekStart)
    }

    private fun ensureWeek(date: LocalDate) {
        viewModelScope.launch {
            loading.value = true
            runCatching { mealPlanner.ensureWeek(date) }
                .onFailure { message.value = it.userMessage() }
            loading.value = false
        }
    }

    private fun launchSelection(block: suspend () -> MealPlanSelection) {
        viewModelScope.launch {
            working.value = true
            try {
                when (block()) {
                    is MealPlanSelection.Selected -> Unit
                    is MealPlanSelection.NoMatch -> {
                        message.value = "Δεν βρέθηκε άλλη συνταγή που να ταιριάζει σε όλα τα φίλτρα."
                    }
                }
            } catch (error: Exception) {
                message.value = error.userMessage()
            } finally {
                working.value = false
            }
        }
    }
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
    val knownCategory = com.justdataplease.spoon.ui.model.AvailableCategories
        .firstOrNull { it.key == uiCategoryKey }
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
        isFavorite = isFavorite,
    )
}

private fun List<Recipe>.toExploreOptionsUi() = ExploreFacetOptionsUi(
    diets = flatMap(Recipe::dietLabels).cleanFacetOptions(),
    mealTypes = flatMap(Recipe::mealTypeLabels).cleanFacetOptions(),
    occasions = flatMap(Recipe::occasionLabels).cleanFacetOptions(),
    methods = flatMap(Recipe::methodLabels).cleanFacetOptions(),
    cuisines = flatMap(Recipe::cuisineLabels).cleanFacetOptions(),
    ingredients = flatMap(Recipe::ingredientLabels).cleanFacetOptions(),
)

private fun List<String>.cleanFacetOptions(): List<String> = asSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()
    .sortedBy { it.lowercase() }
    .toList()

private fun ExploreFiltersUi.toDomain(query: String) = ExploreCriteria(
    query = query,
    category = categoryKey.toDomainCategoryKey(),
    easeLevel = when (ease) {
        EaseUi.EASY -> EaseLevel.EASY.key
        EaseUi.MEDIUM -> EaseLevel.MODERATE.key
        EaseUi.HARD -> EaseLevel.INVOLVED.key
        EaseUi.ANY, EaseUi.UNKNOWN -> ""
    },
    minRating = minRating10.coerceIn(0, 10).toDouble(),
    maxPrepMinutes = maxPrepMinutes?.coerceAtLeast(0) ?: 0,
    dietLabels = diet.asSelectedSet(),
    mealTypeLabels = mealType.asSelectedSet(),
    occasionLabels = occasion.asSelectedSet(),
    methodLabels = method.asSelectedSet(),
    cuisineLabels = cuisine.asSelectedSet(),
    ingredientLabels = ingredient.asSelectedSet(),
    quickOnly = quickOnly,
)

private fun String.asSelectedSet(): Set<String> = if (isBlank()) emptySet() else setOf(this)

private fun RecipeFilters.toUi() = FiltersUi(
    categoryKey = category.toUiCategoryKey(),
    ease = when (easeLevel) {
        EaseLevel.EASY.key -> EaseUi.EASY
        EaseLevel.MODERATE.key -> EaseUi.MEDIUM
        EaseLevel.INVOLVED.key -> EaseUi.HARD
        else -> EaseUi.ANY
    },
    minRating10 = minRating.toInt(),
    maxPrepMinutes = maxPrepMinutes.takeIf { it > 0 },
)

private fun FiltersUi.toDomain() = RecipeFilters(
    category = categoryKey.toDomainCategoryKey(),
    easeLevel = when (ease) {
        EaseUi.EASY -> EaseLevel.EASY.key
        EaseUi.MEDIUM -> EaseLevel.MODERATE.key
        EaseUi.HARD -> EaseLevel.INVOLVED.key
        EaseUi.ANY, EaseUi.UNKNOWN -> ""
    },
    minRating = minRating10.toDouble(),
    maxPrepMinutes = maxPrepMinutes ?: 0,
)

private fun String.toUiCategoryKey(): String = when (this) {
    MealCategory.POULTRY.key -> "chicken"
    MealCategory.VEGETABLES.key -> "vegetarian"
    MealCategory.STREET_FOOD.key -> "dirty"
    MealCategory.PASTA_RICE.key -> "pasta"
    else -> this
}

private fun String.toDomainCategoryKey(): String = when (this) {
    "chicken" -> MealCategory.POULTRY.key
    "vegetarian" -> MealCategory.VEGETABLES.key
    "dirty" -> MealCategory.STREET_FOOD.key
    "pasta" -> MealCategory.PASTA_RICE.key
    else -> this
}

private fun Throwable.userMessage(): String {
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
    return message?.takeIf { it.isNotBlank() } ?: "Κάτι πήγε στραβά. Δοκίμασε ξανά."
}
