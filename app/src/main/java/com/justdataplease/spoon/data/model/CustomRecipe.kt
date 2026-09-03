package com.justdataplease.spoon.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import java.util.UUID
import kotlinx.serialization.Serializable

const val CUSTOM_RECIPE_ID_PREFIX = "custom_"
const val MAX_CUSTOM_RECIPE_PHOTO_DATA_URI_LENGTH = 700_000

/**
 * A private recipe authored by the current user.
 *
 * [photoDataUri] is an optional compressed JPEG data URI. Keeping the bounded image in the
 * owner-scoped document makes it available on another device without a separate Storage setup.
 */
@Serializable
@IgnoreExtraProperties
data class CustomRecipe(
    @set:DocumentId var id: String = "",
    val title: String = "",
    val description: String = "",
    val category: String = "any",
    val prepMinutes: Int = 0,
    val cookMinutes: Int = 0,
    val servings: String = "",
    val ingredientSections: List<RecipeIngredientSection> = emptyList(),
    val methodSections: List<RecipeMethodSection> = emptyList(),
    val photoDataUri: String = "",
    val active: Boolean = true,
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
) {
    fun toRecipe(): Recipe {
        val imageUrls = photoDataUri.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
        return Recipe(
            id = id,
            title = title,
            category = category,
            prepMinutes = prepMinutes,
            stepCount = methodSections.sumOf { it.steps.size },
            cookMinutes = cookMinutes,
            totalMinutes = prepMinutes + cookMinutes,
            preparationCount = methodSections.size,
            language = "el",
            imageUrl = photoDataUri,
            sourceName = "Προσωπική συνταγή",
            source = "personal",
            sourceKey = "personal",
            providerRecipeId = id,
            updatedAtEpochMillis = updatedAtEpochMillis,
            active = active,
            description = description,
            categoryLabel = MealCategory.fromKey(category)?.greekLabel ?: "Άλλο",
            servings = servings,
            imageUrls = imageUrls,
            ingredientSections = ingredientSections,
            methodSections = methodSections,
            authorName = "Εγώ",
            published = false,
        )
    }
}

fun newCustomRecipeId(): String = "$CUSTOM_RECIPE_ID_PREFIX${UUID.randomUUID()}"

fun String.isCustomRecipeId(): Boolean =
    startsWith(CUSTOM_RECIPE_ID_PREFIX) &&
        removePrefix(CUSTOM_RECIPE_ID_PREFIX).matches(UUID_PATTERN)

private val UUID_PATTERN = Regex(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
)
