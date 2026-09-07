package com.justdataplease.spoon.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LocalDining
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.ui.components.CategoryPill
import com.justdataplease.spoon.ui.components.RecipeArtwork
import com.justdataplease.spoon.ui.components.formatRating10
import com.justdataplease.spoon.ui.model.EaseUi
import com.justdataplease.spoon.ui.model.RecipeDetailUi
import com.justdataplease.spoon.ui.shopping.ShoppingIngredientDraftUi

internal const val RecipeDetailsBackLabel = "Επιστροφή στις συνταγές"

@Composable
fun RecipeDetailsScreen(
    recipe: RecipeDetailUi,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSource: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    recipeNote: String = "",
    onSaveNote: (String) -> Unit = {},
    onAddIngredients: (List<ShoppingIngredientDraftUi>) -> Unit = {},
    isLoadingDetails: Boolean = false,
) {
    val uriHandler = LocalUriHandler.current
    val openExternal: (String) -> Unit = remember(uriHandler) {
        { rawUrl: String ->
            normalizeRecipeLink(rawUrl)?.let { safeUrl ->
                runCatching { uriHandler.openUri(safeUrl) }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Room for the floating back pill plus navigation-bar inset on compact phones.
            contentPadding = PaddingValues(bottom = 160.dp),
        ) {
            item {
                RichRecipeHero(recipe, onBack, onToggleFavorite, onEdit)
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                RecipeHeading(recipe)
                if (isLoadingDetails) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(15.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Text("Φόρτωση πλήρους συνταγής…", style = MaterialTheme.typography.titleSmall)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                val description = recipe.description.ifBlank { recipe.seoDescription }
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RecipeMetricsGrid(recipe)
                RichEaseExplanation(recipe)
                RatingDistribution(recipe)
                RecipeGallery(recipe)
                RecipeVideoSection(recipe.videoUrls, openExternal)
                IngredientsSection(recipe, onAddIngredients)
                MethodSection(recipe)
                AdviceSections(recipe)
                PersonalRecipeNoteSection(recipe.recipeId, recipeNote, onSaveNote)
                NutritionSection(recipe)
                EquipmentSection(recipe)
                TaxonomySection(recipe)
                CatalogDetailsSection(recipe, openExternal)
                SourceSection(recipe, onOpenSource, openExternal)
                if (recipe.isDemo) {
                    Text(
                        "Αυτή είναι μια πρωτότυπη εγγραφή επίδειξης της εφαρμογής.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                }
            }
        }
        ElevatedButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .height(56.dp),
            shape = CircleShape,
            elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 8.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            Text(
                RecipeDetailsBackLabel,
                modifier = Modifier.padding(start = 8.dp),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun RichRecipeHero(
    recipe: RecipeDetailUi,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    Box(modifier = Modifier.fillMaxWidth().height(350.dp)) {
        RecipeArtwork(
            imageUrl = recipe.displayImageUrls.firstOrNull().orEmpty(),
            title = recipe.title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.38f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.58f),
                    ),
                ),
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HeroCircleButton(Icons.AutoMirrored.Outlined.ArrowBack, "Πίσω", onBack)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onEdit?.let { edit ->
                    HeroCircleButton(Icons.Outlined.Edit, "Επεξεργασία συνταγής", edit)
                }
                HeroCircleButton(
                    icon = if (recipe.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    description = if (recipe.isFavorite) "Αφαίρεση από τη συλλογή" else "Προσθήκη στη συλλογή",
                    onClick = onToggleFavorite,
                    tint = if (recipe.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
            color = Color.Black.copy(alpha = 0.70f),
            contentColor = Color.White,
            shape = CircleShape,
        ) {
            Text(
                text = "${recipe.category.emoji}  ${recipe.categoryLabel.ifBlank { recipe.category.label }}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun HeroCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shape = CircleShape,
        shadowElevation = 5.dp,
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = description, tint = tint)
        }
    }
}

@Composable
private fun RecipeHeading(recipe: RecipeDetailUi) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CategoryPill(
            emoji = recipe.category.emoji,
            label = recipe.categoryLabel.ifBlank { recipe.category.label },
        )
        Text(recipe.title, style = MaterialTheme.typography.displaySmall)
        val attribution = buildList {
            recipe.authorName.takeIf(String::isNotBlank)?.let { add(it) }
            recipe.sourceName.takeIf(String::isNotBlank)?.let { if (it !in this) add(it) }
            recipe.publishedAt.takeIf(String::isNotBlank)?.let { add("Δημοσίευση: $it") }
        }
        if (attribution.isNotEmpty()) {
            Text(
                attribution.joinToString("  •  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class MetricItem(val icon: ImageVector, val value: String, val label: String)

@Composable
private fun RecipeMetricsGrid(recipe: RecipeDetailUi) {
    val totalMinutes = recipe.totalMinutes.takeIf { it > 0 }
        ?: (recipe.prepMinutes + recipe.cookMinutes + recipe.waitMinutes).takeIf { it > 0 }
    val yield = recipe.recipeYield.ifBlank { recipe.servings }
    val metrics = buildList {
        if (recipe.rating10 > 0) {
            add(
                MetricItem(
                    Icons.Outlined.Star,
                    "${formatRating10(recipe.rating10)}/10",
                    if (recipe.ratingCount > 0) "Βαθμολογία • ${recipe.ratingCount} ψήφοι" else "Βαθμολογία",
                ),
            )
        }
        if (recipe.prepMinutes > 0) add(MetricItem(Icons.Outlined.Schedule, "${recipe.prepMinutes}′", "Προετοιμασία"))
        if (recipe.cookMinutes > 0) add(MetricItem(Icons.Outlined.Timer, "${recipe.cookMinutes}′", "Μαγείρεμα"))
        if (recipe.waitMinutes > 0) add(MetricItem(Icons.Outlined.Schedule, "${recipe.waitMinutes}′", "Αναμονή"))
        if (totalMinutes != null) add(MetricItem(Icons.Outlined.AutoAwesome, "$totalMinutes′", "Συνολικός χρόνος"))
        if (recipe.stepCount > 0) add(MetricItem(Icons.Outlined.FormatListNumbered, recipe.stepCount.toString(), "Βήματα"))
        if (recipe.displayPreparationCount > 0) {
            add(MetricItem(Icons.Outlined.LocalDining, recipe.displayPreparationCount.toString(), "Παρασκευές"))
        }
        if (yield.isNotBlank()) add(MetricItem(Icons.Outlined.Groups, yield, "Μερίδες / ποσότητα"))
        if (recipe.sourceDifficulty.isNotBlank()) {
            add(MetricItem(Icons.Outlined.Equalizer, recipe.sourceDifficulty, "Δυσκολία πηγής"))
        }
    }
    if (metrics.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        metrics.chunked(2).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { item ->
                    DetailMetricCard(item, Modifier.weight(1f))
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DetailMetricCard(item: MetricItem, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
            Text(item.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(item.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RichEaseExplanation(recipe: RecipeDetailUi) {
    val preparations = recipe.displayPreparationCount
    val steps = recipe.stepCount.coerceAtLeast(0)
    val explanation = when {
        preparations == 0 && steps == 0 -> "Δεν έχουν καταγραφεί ακόμη παρασκευές ή βήματα για αυτή τη συνταγή."
        preparations == 0 -> "Ο δείκτης βασίζεται στα $steps καταγεγραμμένα βήματα."
        preparations == 1 -> "Η συνταγή χρειάζεται 1 παρασκευή και περιλαμβάνει $steps βήματα."
        else -> "Η συνταγή χρειάζεται $preparations παρασκευές και περιλαμβάνει $steps βήματα."
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(17.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${recipe.ease.greekLabel} • Δείκτης ευκολίας",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
                if (recipe.ease != EaseUi.UNKNOWN) Text(recipe.ease.detail, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun RatingDistribution(recipe: RecipeDetailUi) {
    val ratings = listOf(5 to recipe.rating5, 4 to recipe.rating4, 3 to recipe.rating3, 2 to recipe.rating2, 1 to recipe.rating1)
    val total = ratings.sumOf { it.second }
    if (total <= 0) return
    DetailSectionCard("Κατανομή αξιολογήσεων", Icons.Outlined.Star) {
        ratings.forEach { (stars, count) ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("$stars ★", Modifier.width(34.dp), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(
                    progress = { count.toFloat() / total.toFloat() },
                    modifier = Modifier.weight(1f).height(7.dp).clip(CircleShape),
                )
                Text(count.toString(), Modifier.width(38.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun RecipeGallery(recipe: RecipeDetailUi) {
    if (recipe.displayImageUrls.size <= 1) return
    DetailSectionCard("Φωτογραφίες (${recipe.displayImageUrls.size})", Icons.Outlined.PhotoLibrary) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 4.dp)) {
            items(recipe.displayImageUrls, key = { it }) { imageUrl ->
                RecipeArtwork(
                    imageUrl,
                    recipe.title,
                    Modifier.width(250.dp).height(170.dp).clip(RoundedCornerShape(16.dp)),
                )
            }
        }
    }
}
