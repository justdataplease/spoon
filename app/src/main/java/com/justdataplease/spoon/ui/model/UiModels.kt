package com.justdataplease.spoon.ui.model

import com.justdataplease.spoon.data.model.RecipeIngredientSection
import com.justdataplease.spoon.data.model.RecipeMethodSection
import com.justdataplease.spoon.data.model.RecipeNutritionSection
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.ui.explore.ExploreFacetOptionsUi
import com.justdataplease.spoon.ui.explore.ExploreFiltersUi
import com.justdataplease.spoon.ui.explore.ExploreRecipeUi
import java.time.LocalDate
import java.time.YearMonth

data class CategoryUi(
    val key: String,
    val label: String,
    val emoji: String,
)

val AvailableCategories = listOf(
    CategoryUi("legumes", "Όσπρια", "🫘"),
    CategoryUi("fish", "Ψάρι", "🐟"),
    CategoryUi("meat", "Κρέας", "🥩"),
    CategoryUi("chicken", "Κοτόπουλο", "🍗"),
    CategoryUi("pasta", "Ζυμαρικά", "🍝"),
    CategoryUi("vegetarian", "Λαχανικά", "🥬"),
    CategoryUi("dirty", "Βρώμικο", "🍔"),
)

enum class EaseUi(val key: String, val greekLabel: String, val detail: String) {
    ANY("any", "Όλα", "Χωρίς περιορισμό"),
    UNKNOWN("unknown", "Άγνωστη", "Δεν υπάρχουν αρκετά στοιχεία"),
    EASY("easy", "Εύκολη", "έως 1 παρασκευή και 1–5 βήματα"),
    MEDIUM("medium", "Μέτρια", "2 παρασκευές ή 6–9 βήματα"),
    HARD("hard", "Απαιτητική", "3+ παρασκευές ή 10+ βήματα"),
}

val SelectableEaseOptions = listOf(EaseUi.ANY, EaseUi.EASY, EaseUi.MEDIUM, EaseUi.HARD)

internal fun easeForWorkload(preparationCount: Int, stepCount: Int): EaseUi = when {
    preparationCount <= 0 && stepCount <= 0 -> EaseUi.UNKNOWN
    preparationCount >= 3 || stepCount >= 10 -> EaseUi.HARD
    preparationCount <= 1 && stepCount in 1..5 -> EaseUi.EASY
    else -> EaseUi.MEDIUM
}

data class FiltersUi(
    val categoryKey: String = "legumes",
    val ease: EaseUi = EaseUi.ANY,
    val minRating10: Int = 0,
    val maxPrepMinutes: Int? = null,
)

data class DayPlanUi(
    val date: LocalDate,
    val recipeId: String = "",
    val recipeTitle: String = "",
    val categoryKey: String,
    val rating10: Double = 0.0,
    val ratingCount: Int = 0,
    val prepMinutes: Int = 0,
    val cookMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val stepCount: Int = 0,
    val preparationCount: Int = 0,
    val imageUrl: String = "",
    val sourceUrl: String = "",
    val sourceName: String = "",
    val tags: List<String> = emptyList(),
    val language: String = "el",
    val isFavorite: Boolean = false,
    val isCompleted: Boolean = false,
    val filters: FiltersUi = FiltersUi(categoryKey = categoryKey),
    val isDemo: Boolean = false,
) {
    val category: CategoryUi
        get() = categoryForKey(categoryKey)

    val ease: EaseUi
        get() = easeForWorkload(preparationCount, stepCount)

    val displayPreparationCount: Int
        get() = preparationCount.coerceAtLeast(0)
}

data class FavoriteUi(
    val recipeId: String,
    val title: String,
    val categoryKey: String,
    val rating10: Double,
    val prepMinutes: Int,
    val cookMinutes: Int = 0,
    val totalMinutes: Int,
    val stepCount: Int,
    val preparationCount: Int = 0,
    val imageUrl: String = "",
    val sourceUrl: String,
    val sourceName: String = "",
    val tags: List<String> = emptyList(),
    val language: String = "el",
) {
    val category: CategoryUi
        get() = categoryForKey(categoryKey)

    val ease: EaseUi
        get() = easeForWorkload(preparationCount, stepCount)

    val displayPreparationCount: Int
        get() = preparationCount.coerceAtLeast(0)
}

data class RecipeDetailUi(
    val recipeId: String,
    val title: String,
    val categoryKey: String,
    val rating10: Double,
    val prepMinutes: Int,
    val cookMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val stepCount: Int,
    val preparationCount: Int = 0,
    val imageUrl: String,
    val sourceUrl: String,
    val sourceName: String,
    val tags: List<String>,
    val language: String = "el",
    val isFavorite: Boolean,
    val sourceRecipeId: Int = 0,
    val slug: String = "",
    val description: String = "",
    val seoTitle: String = "",
    val seoDescription: String = "",
    val categoryLabel: String = "",
    val categorySourceId: Int = 0,
    val ratingCount: Int = 0,
    val rating1: Int = 0,
    val rating2: Int = 0,
    val rating3: Int = 0,
    val rating4: Int = 0,
    val rating5: Int = 0,
    val waitMinutes: Int = 0,
    val sourceDifficulty: String = "",
    val servings: String = "",
    val recipeYield: String = "",
    val imageUrls: List<String> = emptyList(),
    val shortUrl: String = "",
    val dietLabels: List<String> = emptyList(),
    val mealTypeLabels: List<String> = emptyList(),
    val occasionLabels: List<String> = emptyList(),
    val methodLabels: List<String> = emptyList(),
    val cuisineLabels: List<String> = emptyList(),
    val ingredientLabels: List<String> = emptyList(),
    val quickRecipe: Boolean = false,
    val videoUrls: List<String> = emptyList(),
    val ingredientSections: List<RecipeIngredientSection> = emptyList(),
    val methodSections: List<RecipeMethodSection> = emptyList(),
    val tips: List<String> = emptyList(),
    val nutritionTips: List<String> = emptyList(),
    val nutritionPer: String = "",
    val nutritionSections: List<RecipeNutritionSection> = emptyList(),
    val equipment: List<String> = emptyList(),
    val authorName: String = "",
    val publishedAt: String = "",
    val published: Boolean = true,
    val createdAt: String = "",
    val sourceUpdatedAt: String = "",
    val shares: Int = 0,
    val sponsorLogoUrl: String = "",
    val notes: List<String> = emptyList(),
) {
    val category: CategoryUi
        get() = categoryForKey(categoryKey)

    val displayPreparationCount: Int
        get() = preparationCount.coerceAtLeast(0)

    val ease: EaseUi
        get() = easeForWorkload(preparationCount, stepCount)

    val isDemo: Boolean
        get() = "demo" in tags

    val displayImageUrls: List<String>
        get() = (listOf(imageUrl) + imageUrls).filter(String::isNotBlank).distinct()
}

private fun categoryForKey(categoryKey: String): CategoryUi =
    AvailableCategories.firstOrNull { it.key == categoryKey }
        ?: CategoryUi(
            key = categoryKey,
            label = if (categoryKey.isBlank()) "Χωρίς κατηγορία" else "Άλλο",
            emoji = "🍽️",
        )

data class CalendarMealUi(
    val date: LocalDate,
    val recipeId: String,
    val categoryKey: String,
    val recipeTitle: String,
    val isCompleted: Boolean,
) {
    val emoji: String
        get() = AvailableCategories.firstOrNull { it.key == categoryKey }?.emoji ?: "🍽️"
}

data class SpoonUiState(
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val backendState: BackendState = BackendState.Connecting,
    val weekStart: LocalDate = LocalDate.now(),
    val weekPlans: List<DayPlanUi> = emptyList(),
    val favorites: List<FavoriteUi> = emptyList(),
    val shownMonth: YearMonth = YearMonth.now(),
    val calendarMeals: List<CalendarMealUi> = emptyList(),
    val editingDate: LocalDate? = null,
    val favoriteReplacementDate: LocalDate? = null,
    val exploreQuery: String = "",
    val exploreRecipes: List<ExploreRecipeUi> = emptyList(),
    val exploreTotalRecipeCount: Int = 0,
    val exploreFilters: ExploreFiltersUi = ExploreFiltersUi(),
    val exploreOptions: ExploreFacetOptionsUi = ExploreFacetOptionsUi(),
    val isRecipeDetailsLoading: Boolean = false,
    val selectedRecipe: RecipeDetailUi? = null,
    val message: String? = null,
)
