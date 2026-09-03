package com.justdataplease.spoon.ui.explore

import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.ui.model.EaseUi
import java.util.Locale

data class ExploreRecipeUi(
    val recipeId: String,
    val title: String,
    val description: String = "",
    val categoryKey: String = "",
    val categoryLabel: String = "",
    val categoryEmoji: String = "🍽️",
    val rating10: Double = 0.0,
    val ratingCount: Int = 0,
    val prepMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val preparationCount: Int = 0,
    val stepCount: Int = 0,
    val imageUrl: String = "",
    val sourceKey: String = "",
    val sourceName: String = "",
    val isFavorite: Boolean = false,
)

data class ExploreFiltersUi(
    val categoryKey: String = "",
    val ease: EaseUi = EaseUi.ANY,
    val minRating10: Int = 0,
    val maxPrepMinutes: Int? = null,
    val diet: String = "",
    val mealType: String = "",
    val occasion: String = "",
    val method: String = "",
    val cuisine: String = "",
    val ingredient: String = "",
    val sourceKeys: Set<String> = emptySet(),
    val quickOnly: Boolean = false,
) {
    val activeCount: Int
        get() = listOf(
            categoryKey.isNotBlank(),
            ease != EaseUi.ANY,
            minRating10 > 0,
            maxPrepMinutes != null,
            diet.isNotBlank(),
            mealType.isNotBlank(),
            occasion.isNotBlank(),
            method.isNotBlank(),
            cuisine.isNotBlank(),
            ingredient.isNotBlank(),
            sourceKeys.any(String::isNotBlank),
            quickOnly,
        ).count { it }
}

data class ExploreSourceOptionUi(
    val key: String,
    val label: String,
    val recipeCount: Int,
)

data class ExploreFacetOptionsUi(
    val sources: List<ExploreSourceOptionUi> = emptyList(),
    val diets: List<String> = emptyList(),
    val mealTypes: List<String> = emptyList(),
    val occasions: List<String> = emptyList(),
    val methods: List<String> = emptyList(),
    val cuisines: List<String> = emptyList(),
    val ingredients: List<String> = emptyList(),
)

/** Builds provider choices from catalog provenance instead of a fixed publisher list. */
internal fun Iterable<Recipe>.toExploreSourceOptionsUi(): List<ExploreSourceOptionUi> = asSequence()
    .mapNotNull { recipe ->
        val key = recipe.effectiveSourceKey.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val label = sequenceOf(recipe.sourceName, recipe.source, recipe.sourceKey)
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            ?: key
        SourceOptionCandidate(key = key, label = label)
    }
    .groupBy(SourceOptionCandidate::key)
    .map { (key, candidates) ->
        val label = candidates
            .groupingBy(SourceOptionCandidate::label)
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { it.key.lowercase(GreekLocale) },
            )
            .first()
            .key
        ExploreSourceOptionUi(key = key, label = label, recipeCount = candidates.size)
    }
    .sortedWith(
        compareBy<ExploreSourceOptionUi> { it.label.lowercase(GreekLocale) }
            .thenBy(ExploreSourceOptionUi::key),
    )

private data class SourceOptionCandidate(val key: String, val label: String)

private val GreekLocale: Locale = Locale.forLanguageTag("el-GR")
