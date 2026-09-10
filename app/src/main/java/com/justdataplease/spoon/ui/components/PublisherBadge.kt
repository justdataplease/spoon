package com.justdataplease.spoon.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.justdataplease.spoon.R

@Composable
fun PublisherBadge(
    sourceKey: String = "",
    sourceName: String = "",
    sourceUrl: String = "",
    modifier: Modifier = Modifier,
) {
    val publisher = recipePublisher(sourceKey, sourceName, sourceUrl) ?: return
    val icon = publisherIconResource(publisher.key, "", "") ?: return
    val uriHandler = LocalUriHandler.current
    val bitmap = ImageBitmap.imageResource(icon)
    val painter = remember(icon, bitmap) {
        // The official Akis PNG includes a large white margin; frame its full seal at the same
        // visual size as the other publishers without altering or upscaling the bundled file.
        val inset = if (icon == R.drawable.source_akis) bitmap.width / 6 else 0
        BitmapPainter(
            image = bitmap,
            srcOffset = IntOffset(inset, inset),
            srcSize = IntSize(bitmap.width - 2 * inset, bitmap.height - 2 * inset),
        )
    }
    Surface(
        modifier = modifier.size(RecipeBadgeHeight).clip(CircleShape).clickable(
            role = Role.Button,
            onClickLabel = "Άνοιγμα ιστοσελίδας του δημιουργού",
            onClick = { runCatching { uriHandler.openUri(publisher.websiteUrl) } },
        ),
        shape = CircleShape,
        // Publisher artwork includes dark/transparent marks designed for a white canvas.
        color = Color.White,
    ) {
        Image(
            painter = painter,
            contentDescription = "Ιστοσελίδα πηγής: ${publisher.name}",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
