package com.justdataplease.spoon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.ui.model.DayPlanUi
import com.justdataplease.spoon.ui.model.EaseUi
import com.justdataplease.spoon.domain.repository.BackendFailureKind
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.ui.theme.Mint
import com.justdataplease.spoon.ui.theme.PaprikaDark
import com.justdataplease.spoon.ui.theme.Peach
import com.justdataplease.spoon.ui.theme.Sage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val GreekLocale = Locale.forLanguageTag("el-GR")
internal val RecipeBadgeHeight = 34.dp

fun LocalDate.greekDayLabel(): String =
    format(DateTimeFormatter.ofPattern("EEEE", GreekLocale)).replaceFirstChar { it.titlecase(GreekLocale) }

fun LocalDate.greekShortDate(): String =
    format(DateTimeFormatter.ofPattern("d MMM", GreekLocale))

internal fun formatRating10(rating10: Double): String =
    "%.1f".format(GreekLocale, rating10)

@Composable
fun DayDateHeader(date: LocalDate) {
    Column {
        Text(date.greekDayLabel(), style = MaterialTheme.typography.titleLarge)
        Text(date.greekShortDate(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun GradientHeroCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    fillMaxWidth: Boolean = true,
    subtitleStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(30.dp),
    ) {
        Column(
            modifier = Modifier
                .background(Brush.linearGradient(gradient))
                .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.displaySmall)
            }
            Text(subtitle, style = subtitleStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun CategoryPill(emoji: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(RecipeBadgeHeight),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "$emoji  $label", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun MetricPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun EasePill(ease: EaseUi, preparationCount: Int, modifier: Modifier = Modifier) {
    val (background, foreground) = when (ease) {
        EaseUi.EASY -> Mint to Sage
        EaseUi.MEDIUM -> Color(0xFFFFE3AD) to Color(0xFF755500)
        EaseUi.HARD -> Peach to PaprikaDark
        EaseUi.ANY,
        EaseUi.UNKNOWN,
        -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(modifier = modifier, color = background, contentColor = foreground, shape = CircleShape) {
        Text(
            text = if (preparationCount > 0) "${ease.greekLabel} · $preparationCount παρασκευές" else ease.greekLabel,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun EmptyRecipeCard(
    plan: DayPlanUi,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit = {},
    favoritesOnly: Boolean = false,
    onOpenMenu: (() -> Unit)? = null,
    title: String? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (title == null) DayDateHeader(plan.date) else Text(title, style = MaterialTheme.typography.titleLarge)
                CategoryPill(plan.category.emoji, plan.category.label)
            }
            Text(
                "Μη διαθέσιμη συνταγή",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                if (favoritesOnly) "Δεν υπάρχει συνταγή στη συλλογή που να ταιριάζει στην κατηγορία και στα φίλτρα αυτής της ημέρας."
                else "Δεν υπάρχει συνταγή που να ταιριάζει στην κατηγορία και στα φίλτρα αυτής της ημέρας.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onEdit) { Text("Αλλαγή κατηγορίας / φίλτρων") }
            if (onOpenMenu != null) {
                TextButton(onClick = onOpenMenu) {
                    Icon(Icons.Outlined.RestaurantMenu, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Πλήρες μενού")
                }
            }
            Button(onClick = onPick) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Βρες μου κάτι")
            }
        }
    }
}

@Composable
fun CompactFavoriteCard(
    emoji: String,
    title: String,
    category: String,
    rating10: Double,
    prepMinutes: Int,
    imageUrl: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(78.dp).clip(RoundedCornerShape(17.dp)),
            ) {
                RecipeArtwork(
                    imageUrl = imageUrl,
                    title = title,
                    modifier = Modifier.fillMaxSize(),
                )
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    shape = CircleShape,
                ) {
                    Text(emoji, modifier = Modifier.padding(4.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "$category  ·  ★ ${formatRating10(rating10)}/10  ·  $prepMinutes′",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                androidx.compose.material3.IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Favorite,
                        contentDescription = "Αφαίρεση από τη συλλογή",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "Προβολή λεπτομερειών",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun StatusBanner(
    backendState: BackendState,
    modifier: Modifier = Modifier,
) {
    val isCloud = backendState is BackendState.Cloud
    val text = when (backendState) {
        BackendState.Local -> "Λειτουργία επίδειξης · τα δεδομένα μένουν στη συσκευή"
        BackendState.Connecting -> "Σύνδεση με το Firestore…"
        BackendState.Cloud -> "Συγχρονισμένο με το Firestore"
        is BackendState.Error -> backendErrorText(backendState)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isCloud) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (isCloud) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule,
                contentDescription = null,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun backendErrorText(state: BackendState.Error): String {
    val text = when (state.failure.kind) {
        BackendFailureKind.PERMISSION -> "Το Firestore απέρριψε την πρόσβαση."
        BackendFailureKind.CONFIGURATION -> "Η ρύθμιση του Firebase χρειάζεται διόρθωση."
        BackendFailureKind.AUTHENTICATION -> "Η ταυτοποίηση στο Firebase απέτυχε."
        BackendFailureKind.NETWORK -> "Χωρίς σύνδεση · οι τοπικές προτάσεις λειτουργούν."
        BackendFailureKind.UNKNOWN -> "Η σύνδεση με το Firebase απέτυχε."
    }
    return if (state.failure.isRetryable) "$text Θα γίνει νέα προσπάθεια." else text
}
