package com.justdataplease.spoon.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.R
import java.util.Locale

@Composable
fun PublisherBadge(
    sourceKey: String = "",
    sourceName: String = "",
    sourceUrl: String = "",
    modifier: Modifier = Modifier,
) {
    val icon = publisherIconResource(sourceKey, sourceName, sourceUrl) ?: return
    val description = sourceName.ifBlank { sourceKey }.ifBlank { "πηγή συνταγής" }
    Surface(
        modifier = modifier.size(RecipeBadgeHeight),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = "Πηγή: $description",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

internal fun publisherIconResource(
    sourceKey: String,
    sourceName: String,
    sourceUrl: String,
): Int? {
    val source = "$sourceKey $sourceName $sourceUrl".lowercase(Locale.ROOT)
    return when {
        "argiro" in source || "αργυρ" in source -> R.drawable.source_argiro
        "gastronomos" in source || "γαστρονομ" in source -> R.drawable.source_gastronomos
        "akis" in source || "petretzikis" in source || "άκης" in source ->
            R.drawable.source_akis
        else -> null
    }
}
