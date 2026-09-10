package com.justdataplease.spoon.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.justdataplease.spoon.R
import com.justdataplease.spoon.ui.details.normalizeRecipeLink

/**
 * Shows publisher artwork only when the catalog supplies an approved image URL. The original
 * bundled Spoon artwork remains visible while a remote image loads and whenever loading fails.
 */
@Composable
fun RecipeArtwork(
    imageUrl: String,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bundledArtwork = painterResource(R.drawable.food_hero)
    val description = "Φωτογραφία συνταγής: $title"
    val safeImage = remember(imageUrl) { recipeImageModel(imageUrl) }

    if (safeImage != null) {
        AsyncImage(
            model = safeImage,
            contentDescription = description,
            modifier = modifier,
            placeholder = bundledArtwork,
            error = bundledArtwork,
            fallback = bundledArtwork,
            contentScale = contentScale,
        )
    } else {
        Image(
            painter = bundledArtwork,
            contentDescription = description,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

private const val MaxRecipeImageDataUrlLength = 700_000
private val supportedImageDataPrefixes = listOf(
    "data:image/jpeg;base64,",
    "data:image/png;base64,",
    "data:image/webp;base64,",
)

/** Allows safe HTTPS artwork and the bounded data URLs emitted by the custom-recipe photo editor. */
internal fun normalizeRecipeImageSource(raw: String): String? {
    normalizeRecipeLink(raw)?.let { return it }
    val candidate = raw.trim()
    if (candidate.length !in 32..MaxRecipeImageDataUrlLength) return null
    val prefix = supportedImageDataPrefixes.firstOrNull { candidate.startsWith(it, ignoreCase = true) } ?: return null
    val payload = candidate.substring(prefix.length)
    if (payload.isBlank() || payload.length % 4 != 0) return null
    if (!payload.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }) {
        return null
    }
    return candidate
}

/** Coil 2 accepts image bytes, but has no fetcher for the editor's data URI strings. */
internal fun recipeImageModel(raw: String): Any? {
    val source = normalizeRecipeImageSource(raw) ?: return null
    return if (source.startsWith("data:", ignoreCase = true)) {
        runCatching { java.util.Base64.getDecoder().decode(source.substringAfter(',')) }.getOrNull()
    } else source
}
