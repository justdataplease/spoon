package com.justdataplease.spoon.data

import com.justdataplease.spoon.data.model.Recipe

private val SAFE_RECIPE_DOCUMENT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")

internal fun isSafeRecipeDocumentId(recipeId: String): Boolean =
    SAFE_RECIPE_DOCUMENT_ID.matches(recipeId)

/** Rejects path-like or otherwise unexpected values before they reach Firestore.document(). */
internal fun requireSafeRecipeDocumentId(recipeId: String): String {
    require(recipeId.isNotBlank()) { "A recipe details request needs a recipe id" }
    require(isSafeRecipeDocumentId(recipeId)) {
        "Recipe id contains unsupported characters or is too long"
    }
    return recipeId
}

/**
 * Applies the same fail-closed identity and publication checks to local and remote details.
 * Firestore normally injects @DocumentId; the blank-id branch also supports plain decoders.
 */
internal fun eligibleRecipeDetails(
    recipe: Recipe?,
    documentId: String,
    requestedId: String,
): Recipe? {
    if (recipe == null || documentId != requestedId) return null
    if (recipe.id.isNotBlank() && recipe.id != documentId) return null

    val normalized = if (recipe.id == documentId) recipe else recipe.copy(id = documentId)
    return normalized.takeIf {
        normalized.active && normalized.language == "el" && normalized.id == requestedId
    }
}
