package com.justdataplease.spoon.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.justdataplease.spoon.R

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

    if (imageUrl.isNotBlank()) {
        AsyncImage(
            model = imageUrl,
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
