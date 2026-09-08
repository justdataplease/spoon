package com.justdataplease.spoon.ui.week

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.ui.components.CategoryPill
import com.justdataplease.spoon.ui.components.PublisherBadge
import com.justdataplease.spoon.ui.components.DayDateHeader
import com.justdataplease.spoon.ui.components.EasePill
import com.justdataplease.spoon.ui.components.EmptyRecipeCard
import com.justdataplease.spoon.ui.components.GreekLocale
import com.justdataplease.spoon.ui.components.MetricPill
import com.justdataplease.spoon.ui.components.RecipeArtwork
import com.justdataplease.spoon.ui.components.StatusBanner
import com.justdataplease.spoon.ui.components.MAX_RATING_THRESHOLD
import com.justdataplease.spoon.ui.components.formatRating10
import com.justdataplease.spoon.ui.components.greekDayLabel
import com.justdataplease.spoon.ui.components.ratingThresholdLabel
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.ui.model.AvailableCategories
import com.justdataplease.spoon.ui.model.DayPlanUi
import com.justdataplease.spoon.ui.model.EaseUi
import com.justdataplease.spoon.ui.model.FiltersUi
import com.justdataplease.spoon.ui.model.SelectableEaseOptions
import com.justdataplease.spoon.ui.model.SpoonUiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun WeekScreen(
    state: SpoonUiState,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
    onReroll: (LocalDate) -> Unit,
    onEdit: (LocalDate) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onToggleCompleted: (LocalDate) -> Unit,
    onOpenRecipe: (String) -> Unit,
    onShuffleWeek: () -> Unit,
    onSaveFilters: (LocalDate, FiltersUi) -> Unit,
    onDismissEditor: () -> Unit,
    onChooseFavorite: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    favoritesOnly: Boolean = false,
    onToggleLock: (LocalDate) -> Unit = {},
    onOpenMenu: (LocalDate) -> Unit = {},
) {
    val editingPlan = state.editingDate?.let { date -> state.weekPlans.firstOrNull { it.date == date } }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                WeekHero(
                    weekStart = state.weekStart,
                    onPreviousWeek = onPreviousWeek,
                    onNextWeek = onNextWeek,
                    onCurrentWeek = onCurrentWeek,
                    onShuffleWeek = onShuffleWeek,
                )
            }
            item {
                Text(
                    if (favoritesOnly) "Προτάσεις μόνο από τη συλλογή · επιτρέπονται επαναλήψεις"
                    else "Προτάσεις από όλες τις συνταγές",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    "Οι κλειδωμένες και μαγειρεμένες συνταγές δεν αλλάζουν στην ανανέωση της εβδομάδας.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                StatusBanner(
                    backendState = state.backendState,
                    isSignedIn = state.account.isSignedIn,
                )
            }
            if (state.isLoading && state.weekPlans.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                items(state.weekPlans, key = { it.date.toString() }) { plan ->
                    if (plan.recipeId.isBlank()) {
                        EmptyRecipeCard(
                            plan = plan,
                            onPick = { onReroll(plan.date) },
                            onEdit = { onEdit(plan.date) },
                            favoritesOnly = favoritesOnly,
                            onOpenMenu = { onOpenMenu(plan.date) },
                        )
                    } else {
                        DayRecipeCard(
                            plan = plan,
                            isToday = plan.date == LocalDate.now(),
                            onOpenMenu = { onOpenMenu(plan.date) },
                            onToggleLock = { onToggleLock(plan.date) },
                            onReroll = { onReroll(plan.date) },
                            onEdit = { onEdit(plan.date) },
                            onToggleFavorite = { onToggleFavorite(plan.recipeId) },
                            onToggleCompleted = { onToggleCompleted(plan.date) },
                            onOpenRecipe = { onOpenRecipe(plan.recipeId) },
                            onChooseFavorite = { onChooseFavorite(plan.date) },
                        )
                    }
                }
            }
        }

        if (shouldShowMealSearchBanner(state.isWorking, state.backendState)) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                shape = CircleShape,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Ψάχνω κάτι νόστιμο…", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (editingPlan != null) {
        FilterEditorSheet(
            plan = editingPlan,
            onDismiss = onDismissEditor,
            onSave = { onSaveFilters(editingPlan.date, it) },
        )
    }
}

/** Backend status already explains connecting/offline failures; avoid a duplicate stuck overlay. */
internal fun shouldShowMealSearchBanner(
    isWorking: Boolean,
    backendState: BackendState,
): Boolean = isWorking && (backendState is BackendState.Cloud || backendState is BackendState.Local)

@Composable
private fun WeekHero(
    weekStart: LocalDate,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
    onShuffleWeek: () -> Unit,
) {
    val formatter = DateTimeFormatter.ofPattern("d MMM", GreekLocale)
    val range = "${weekStart.format(formatter)} – ${weekStart.plusDays(6).format(formatter)}"
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(30.dp),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer),
                    ),
                )
                .padding(22.dp),
        ) {
            Text("Τι θα φάμε;", style = MaterialTheme.typography.displaySmall)
            Text(
                "Επτά μέρες, μία λιγότερη απόφαση κάθε μέρα.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onPreviousWeek) {
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = "Προηγούμενη εβδομάδα")
                }
                TextButton(onClick = onCurrentWeek) { Text(range, fontWeight = FontWeight.Bold) }
                IconButton(onClick = onNextWeek) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "Επόμενη εβδομάδα")
                }
            }
            Button(onClick = onShuffleWeek, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Spacer(Modifier.size(9.dp))
                Text("Νέες προτάσεις για όλη την εβδομάδα")
            }
        }
    }
}

@Composable
internal fun DayRecipeCard(
    plan: DayPlanUi,
    isToday: Boolean,
    onReroll: () -> Unit,
    onEdit: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleCompleted: () -> Unit,
    onOpenRecipe: () -> Unit,
    onChooseFavorite: () -> Unit,
    onToggleLock: () -> Unit,
    onOpenMenu: (() -> Unit)? = null,
    courseTitle: String? = null,
) {
    Card(
        onClick = onOpenRecipe,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (plan.isCompleted) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isToday) 4.dp else 1.dp),
        border = if (isToday) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)) else null,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (courseTitle == null) DayDateHeader(plan.date)
                    else Text(courseTitle, style = MaterialTheme.typography.titleLarge)
                    if (isToday) {
                        Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = CircleShape) {
                            Text("ΣΗΜΕΡΑ", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                        }
                    }
                }
                Row {
                    IconToggleButton(
                        checked = plan.isLocked || plan.isCompleted,
                        onCheckedChange = { onToggleLock() },
                        enabled = !plan.isCompleted,
                    ) {
                        Icon(
                            if (plan.isLocked || plan.isCompleted) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                            contentDescription = when {
                                plan.isCompleted -> "Μαγειρεμένη: διατηρείται στην ανανέωση εβδομάδας"
                                plan.isLocked -> "Ξεκλείδωμα συνταγής"
                                else -> "Κλείδωμα συνταγής για την ανανέωση εβδομάδας"
                            },
                        )
                    }
                    IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Αλλαγή φίλτρων") }
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (plan.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (plan.isFavorite) "Αφαίρεση από τη συλλογή" else "Στη συλλογή",
                            tint = if (plan.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecipeArtwork(
                    imageUrl = plan.imageUrl,
                    title = plan.recipeTitle,
                    modifier = Modifier.size(102.dp).clip(RoundedCornerShape(18.dp)),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CategoryPill(plan.category.emoji, plan.category.label)
                            PublisherBadge(sourceName = plan.sourceName, sourceUrl = plan.sourceUrl)
                        }
                        if (onOpenMenu != null) {
                            IconButton(onClick = onOpenMenu) {
                                Icon(Icons.Outlined.RestaurantMenu, contentDescription = "Πλήρες μενού: κυρίως, συνοδευτικό και γλυκό")
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    AnimatedContent(targetState = plan.recipeTitle, label = "recipeTitle") { title ->
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(13.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item { MetricPill("★ ${formatRating10(plan.rating10)}/10") }
                item { MetricPill("Προετοιμασία ${plan.prepMinutes}′") }
                item { EasePill(plan.ease, plan.displayPreparationCount) }
            }
            if (plan.isDemo) {
                Spacer(Modifier.height(10.dp))
                Text("Δείγμα καταλόγου", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onOpenRecipe) {
                    Text("Λεπτομέρειες")
                    Spacer(Modifier.size(5.dp))
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(17.dp))
                }
                TextButton(onClick = onChooseFavorite) {
                    Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Από τη συλλογή")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                OutlinedButton(onClick = onReroll, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Άλλη")
                }
                Button(onClick = onToggleCompleted, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (plan.isCompleted) Icons.Filled.Check else Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (plan.isCompleted) "Έγινε" else "Το έφτιαξα")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterEditorSheet(
    plan: DayPlanUi,
    onDismiss: () -> Unit,
    onSave: (FiltersUi) -> Unit,
) {
    var categoryKey by remember(plan.date) { mutableStateOf(plan.filters.categoryKey) }
    var ease by remember(plan.date) { mutableStateOf(plan.filters.ease) }
    var rating by remember(plan.date) {
        mutableFloatStateOf(plan.filters.minRating10.coerceIn(0, MAX_RATING_THRESHOLD).toFloat())
    }
    var maxPrep by remember(plan.date) { mutableStateOf(plan.filters.maxPrepMinutes) }
    val prepOptions = listOf<Int?>(null, 15, 30, 45, 60, 90)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Text("${plan.date.greekDayLabel()} · τι θέλεις;", style = MaterialTheme.typography.headlineMedium)
                Text("Η νέα πρόταση θα τηρήσει όλα τα φίλτρα.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                FilterSectionTitle("Κατηγορία")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AvailableCategories, key = { it.key }) { category ->
                        FilterChip(
                            selected = categoryKey == category.key,
                            onClick = { categoryKey = category.key },
                            label = { Text("${category.emoji} ${category.label}") },
                            leadingIcon = if (categoryKey == category.key) {
                                { Icon(Icons.Filled.Check, null, Modifier.size(17.dp)) }
                            } else null,
                        )
                    }
                }
            }
            item {
                FilterSectionTitle("Δείκτης ευκολίας")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectableEaseOptions.forEach { option ->
                        FilterChip(
                            selected = ease == option,
                            onClick = { ease = option },
                            label = { Text("${option.greekLabel} · ${option.detail}") },
                            leadingIcon = if (ease == option) {
                                { Icon(Icons.Filled.Check, null, Modifier.size(17.dp)) }
                            } else null,
                        )
                    }
                }
            }
            item {
                FilterSectionTitle("Ελάχιστη βαθμολογία")
                val roundedRating = rating.roundToInt()
                Text(
                    ratingThresholdLabel(roundedRating),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = rating,
                    onValueChange = { rating = it },
                    valueRange = 0f..MAX_RATING_THRESHOLD.toFloat(),
                    steps = MAX_RATING_THRESHOLD - 1,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Όλες", style = MaterialTheme.typography.bodyMedium)
                    Text("Πάνω από 9/10", style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                FilterSectionTitle("Μέγιστη προετοιμασία")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(prepOptions) { minutes ->
                        FilterChip(
                            selected = maxPrep == minutes,
                            onClick = { maxPrep = minutes },
                            label = { Text(minutes?.let { "έως $it′" } ?: "Χωρίς όριο") },
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        onSave(
                            FiltersUi(
                                categoryKey = categoryKey,
                                ease = ease,
                                minRating10 = rating.roundToInt(),
                                maxPrepMinutes = maxPrep,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Αποθήκευση & νέα πρόταση")
                }
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}
