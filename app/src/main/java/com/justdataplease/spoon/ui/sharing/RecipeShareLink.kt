package com.justdataplease.spoon.ui.sharing

import com.justdataplease.spoon.data.isSafeRecipeDocumentId
import com.justdataplease.spoon.data.model.CUSTOM_RECIPE_ID_PREFIX
import java.net.URI
import java.net.URISyntaxException

/** Links carry only a public catalog identity; personal recipes remain owner-scoped. */
internal object RecipeShareLink {
    const val HOST = "justdataplease.github.io"
    private const val RECIPE_PATH = "/spoon/recipe/"
    private const val MAX_URL_LENGTH = 512
    private val TITLE_WHITESPACE = Regex("\\s+")

    fun create(recipeId: String): String? =
        recipeId.takeIf(::isShareableId)?.let { "https://$HOST$RECIPE_PATH$it" }

    fun parse(rawUrl: String?): String? {
        if (rawUrl.isNullOrEmpty() || rawUrl.length > MAX_URL_LENGTH) return null
        val uri = try {
            URI(rawUrl)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.isOpaque || uri.rawUserInfo != null || uri.port != -1 ||
            uri.rawQuery != null || uri.rawFragment != null
        ) return null

        val pathPrefix = when {
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.rawAuthority.equals(HOST, ignoreCase = true) -> RECIPE_PATH
            uri.scheme.equals("spoon", ignoreCase = true) &&
                uri.rawAuthority.equals("recipe", ignoreCase = true) -> "/"
            else -> return null
        }
        // Inspect the raw path so encoded separators or identities cannot change after decoding.
        val path = uri.rawPath ?: return null
        if (!path.startsWith(pathPrefix)) return null
        return path.removePrefix(pathPrefix).removeSuffix("/").takeIf(::isShareableId)
    }

    fun shareText(recipeId: String, title: String): String? {
        val link = create(recipeId) ?: return null
        val displayTitle = title.trim().replace(TITLE_WHITESPACE, " ").take(160)
        val message = if (displayTitle.isEmpty()) {
            "Δες αυτή τη συνταγή στο Spoon:"
        } else {
            "Δες τη συνταγή «$displayTitle» στο Spoon:"
        }
        return "$message\n$link"
    }

    private fun isShareableId(recipeId: String): Boolean =
        isSafeRecipeDocumentId(recipeId) &&
            !recipeId.startsWith(CUSTOM_RECIPE_ID_PREFIX)
}
