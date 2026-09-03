package com.justdataplease.spoon.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
import kotlinx.serialization.Serializable
import java.util.Locale

/** Full normalized recipe document used by both Firestore and the local demo catalog. */
@Serializable
@IgnoreExtraProperties
data class Recipe(
    @set:DocumentId var id: String = "",
    val title: String = "",
    val category: String = "",
    val rating: Double = 0.0,
    val prepMinutes: Int = 0,
    val stepCount: Int = 0,
    val cookMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val preparationCount: Int = 0,
    val language: String = "el",
    val imageUrl: String = "",
    val sourceUrl: String = "",
    val sourceName: String = "",
    /** Legacy publisher domain, retained for already-published Firestore documents. */
    val source: String = "",
    /** Stable machine key such as akis, argiro, or gastronomos. */
    val sourceKey: String = "",
    /** Provider-native identity as text; unlike sourceRecipeId it may be non-numeric. */
    val providerRecipeId: String = "",
    /** Canonical publisher page; sourceUrl remains as a backwards-compatible alias. */
    val canonicalUrl: String = "",
    val tags: List<String> = emptyList(),
    val updatedAtEpochMillis: Long = 0L,
    val active: Boolean = true,
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
    /** Canonical key for new and pre-provenance Firestore documents. */
    @get:Exclude
    val effectiveSourceKey: String
        get() {
            sourceKey.trim().takeIf(String::isNotEmpty)?.let {
                return it.lowercase(Locale.ROOT)
            }
            val legacy = source.trim().lowercase(Locale.ROOT).removePrefix("www.")
            return when {
                legacy == "akispetretzikis.com" -> "akis"
                legacy == "argiro.gr" -> "argiro"
                legacy == "gastronomos.gr" -> "gastronomos"
                legacy == "personal" || id.startsWith("custom_") -> "personal"
                sourceName == "Προσωπική συνταγή" -> "personal"
                sourceName == "Δείγμα εφαρμογής" -> "demo"
                legacy.isNotBlank() -> legacy
                else -> ""
            }
        }

    @get:Exclude
    val easeLevel: EaseLevel
        get() = EaseLevel.fromWorkload(preparationCount, stepCount)
}

@Serializable
data class RecipeIngredientSection(
    val title: String = "",
    val ingredients: List<RecipeIngredient> = emptyList(),
)

@Serializable
data class RecipeIngredient(
    val title: String = "",
    val unit: String = "",
    val quantity: String = "",
    val info: String = "",
    val internalLink: String = "",
    val externalLink: String = "",
    val ukUnit: String = "",
    val ukQuantity: String = "",
    val usUnit: String = "",
    val usQuantity: String = "",
)

@Serializable
data class RecipeMethodSection(
    val title: String = "",
    val steps: List<String> = emptyList(),
)

/** Values intentionally remain strings so units and source formatting are never lost. */
@Serializable
data class RecipeNutritionSection(
    val title: String = "",
    val kcalPortion: String = "",
    val kcalPortionPercent: String = "",
    val kcal100g: String = "",
    val kcal100gPercent: String = "",
    val fatPortion: String = "",
    val fatPortionPercent: String = "",
    val fat100g: String = "",
    val fat100gPercent: String = "",
    val saturatedFatPortion: String = "",
    val saturatedFatPortionPercent: String = "",
    val saturatedFat100g: String = "",
    val saturatedFat100gPercent: String = "",
    val carbsPortion: String = "",
    val carbsPortionPercent: String = "",
    val carbs100g: String = "",
    val carbs100gPercent: String = "",
    val sugarsPortion: String = "",
    val sugarsPortionPercent: String = "",
    val sugars100g: String = "",
    val sugars100gPercent: String = "",
    val proteinPortion: String = "",
    val proteinPortionPercent: String = "",
    val protein100g: String = "",
    val protein100gPercent: String = "",
    val fiberPortion: String = "",
    val fiberPortionPercent: String = "",
    val fiber100g: String = "",
    val fiber100gPercent: String = "",
    val sodiumPortion: String = "",
    val sodiumPortionPercent: String = "",
    val sodium100g: String = "",
    val sodium100gPercent: String = "",
)
