package com.justdataplease.spoon.ui.explore

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.justdataplease.spoon.ui.components.RecipeArtwork
import com.justdataplease.spoon.ui.model.AvailableCategories
import com.justdataplease.spoon.ui.model.EaseUi
import com.justdataplease.spoon.ui.model.SelectableEaseOptions
import java.util.Locale
import kotlin.math.roundToInt

private val GreekLocale = Locale.forLanguageTag("el-GR")

@Composable
fun ExploreScreen(
    query: String,
    recipes: List<ExploreRecipeUi>,
    totalRecipeCount: Int,
    filters: ExploreFiltersUi,
    options: ExploreFacetOptionsUi,
    onQueryChange: (String) -> Unit,
    onApplyFilters: (ExploreFiltersUi) -> Unit,
    onOpenRecipe: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onCreateRecipe: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showFilters by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            ExploreHero(resultCount = recipes.size, totalCount = totalRecipeCount, onCreateRecipe = onCreateRecipe)
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotBlank()) {
                        {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Καθαρισμός αναζήτησης")
                            }
                        }
                    } else null,
                    placeholder = { Text("Αναζήτηση συνταγής ή υλικού") },
                    label = { Text("Αναζήτηση") },
                )
                Surface(
                    onClick = { showFilters = true },
                    modifier = Modifier.size(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = if (filters.activeCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (filters.activeCount > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Φίλτρα εξερεύνησης")
                        if (filters.activeCount > 0) {
                            Surface(
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            ) {
                                Text(
                                    filters.activeCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (filters.activeCount > 0) {
            item {
                ActiveFilterSummary(
                    filters = filters,
                    onClear = { onApplyFilters(ExploreFiltersUi()) },
                )
            }
        }
        if (recipes.isEmpty()) {
            item {
                EmptyExploreState(onClear = {
                    onQueryChange("")
                    onApplyFilters(ExploreFiltersUi())
                })
            }
        } else {
            items(recipes, key = ExploreRecipeUi::recipeId) { recipe ->
                ExploreRecipeCard(
                    recipe = recipe,
                    onOpen = { onOpenRecipe(recipe.recipeId) },
                    onToggleFavorite = { onToggleFavorite(recipe.recipeId) },
                )
            }
        }
    }

    if (showFilters) {
        ExploreFilterSheet(
            current = filters,
            options = options,
            onDismiss = { showFilters = false },
            onApply = {
                onApplyFilters(it)
                showFilters = false
            },
        )
    }
}

@Composable
private fun ExploreHero(resultCount: Int, totalCount: Int, onCreateRecipe: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(30.dp)) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ),
                )
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.RestaurantMenu, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Εξερεύνηση", style = MaterialTheme.typography.displaySmall)
            }
            Text(
                "Βρες ακριβώς αυτό που θέλεις να μαγειρέψεις.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "$resultCount από $totalCount ελληνικές συνταγές",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(onClick = onCreateRecipe, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null)
                Text("Νέα δική μου συνταγή", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ExploreRecipeCard(
    recipe: ExploreRecipeUi,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            RecipeArtwork(
                imageUrl = recipe.imageUrl,
                title = recipe.title,
                modifier = Modifier.size(width = 112.dp, height = 126.dp).clip(RoundedCornerShape(17.dp)),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        recipe.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(38.dp)) {
                        Icon(
                            if (recipe.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (recipe.isFavorite) "Αφαίρεση από αγαπημένα" else "Προσθήκη στα αγαπημένα",
                            tint = if (recipe.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "${recipe.categoryEmoji} ${recipe.categoryLabel.ifBlank { "Άλλη κατηγορία" }}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SmallMetric("★ ${"%.1f".format(GreekLocale, recipe.rating10)}/10")
                    SmallMetric(if (recipe.prepMinutes > 0) "${recipe.prepMinutes}′ προετ." else "Χρόνος —")
                }
            }
        }
    }
}

@Composable
private fun SmallMetric(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

@Composable
private fun ActiveFilterSummary(filters: ExploreFiltersUi, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${filters.activeCount} ενεργά φίλτρα", style = MaterialTheme.typography.labelLarge)
        AssistChip(
            onClick = onClear,
            label = { Text("Καθαρισμός") },
            leadingIcon = { Icon(Icons.Outlined.FilterAltOff, contentDescription = null, Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun EmptyExploreState(onClear: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 54.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.padding(22.dp).size(34.dp))
        }
        Text("Δεν βρέθηκε συνταγή", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Δοκίμασε διαφορετική αναζήτηση ή λιγότερα φίλτρα.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onClear) { Text("Καθαρισμός όλων") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreFilterSheet(
    current: ExploreFiltersUi,
    options: ExploreFacetOptionsUi,
    onDismiss: () -> Unit,
    onApply: (ExploreFiltersUi) -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    var rating by remember(current) { mutableFloatStateOf(current.minRating10.toFloat()) }
    val prepOptions = listOf<Int?>(null, 15, 30, 45, 60, 90, 120)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 38.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp),
        ) {
            item {
                Text("Φίλτρα εξερεύνησης", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Τα επιλεγμένα φίλτρα εφαρμόζονται όλα μαζί.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                FilterTitle("Βασικό υλικό")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        ChoiceChip("Όλα", draft.categoryKey.isBlank()) { draft = draft.copy(categoryKey = "") }
                    }
                    items(AvailableCategories, key = { it.key }) { category ->
                        ChoiceChip("${category.emoji} ${category.label}", draft.categoryKey == category.key) {
                            draft = draft.copy(categoryKey = category.key)
                        }
                    }
                }
            }
            item {
                FilterTitle("Δυσκολία")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SelectableEaseOptions, key = { it.key }) { ease ->
                        ChoiceChip(ease.greekLabel, draft.ease == ease) { draft = draft.copy(ease = ease) }
                    }
                }
            }
            item {
                FilterTitle("Ελάχιστη βαθμολογία")
                Text(
                    if (rating.roundToInt() == 0) "Οποιαδήποτε" else "${rating.roundToInt()}+/10",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(value = rating, onValueChange = { rating = it }, valueRange = 0f..10f, steps = 9)
            }
            item {
                FilterTitle("Μέγιστος χρόνος προετοιμασίας")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(prepOptions) { minutes ->
                        ChoiceChip(
                            label = minutes?.let { "έως $it′" } ?: "Χωρίς όριο",
                            selected = draft.maxPrepMinutes == minutes,
                        ) { draft = draft.copy(maxPrepMinutes = minutes) }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Γρήγορες συνταγές", style = MaterialTheme.typography.titleMedium)
                        Text("Μόνο όσες χαρακτηρίζονται γρήγορες", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = draft.quickOnly, onCheckedChange = { draft = draft.copy(quickOnly = it) })
                }
            }
            item { FacetRow("Ειδική διατροφή", options.diets, draft.diet) { draft = draft.copy(diet = it) } }
            item { FacetRow("Είδος γεύματος", options.mealTypes, draft.mealType) { draft = draft.copy(mealType = it) } }
            item { FacetRow("Περίσταση", options.occasions, draft.occasion) { draft = draft.copy(occasion = it) } }
            item { FacetRow("Τρόπος μαγειρέματος", options.methods, draft.method) { draft = draft.copy(method = it) } }
            item { FacetRow("Χώρα / διεθνής κουζίνα", options.cuisines, draft.cuisine) { draft = draft.copy(cuisine = it) } }
            item { FacetRow("Κύριο υλικό", options.ingredients, draft.ingredient) { draft = draft.copy(ingredient = it) } }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            draft = ExploreFiltersUi()
                            rating = 0f
                        },
                        modifier = Modifier.weight(1f).height(54.dp),
                    ) { Text("Καθαρισμός") }
                    Button(
                        onClick = { onApply(draft.copy(minRating10 = rating.roundToInt())) },
                        modifier = Modifier.weight(1f).height(54.dp),
                    ) { Text("Εφαρμογή") }
                }
            }
        }
    }
}

@Composable
private fun FacetRow(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterTitle(title)
        if (options.isEmpty()) {
            Text("Δεν υπάρχουν ακόμη διαθέσιμες επιλογές.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { ChoiceChip("Όλα", selected.isBlank()) { onSelect("") } }
                items(options, key = { it }) { option ->
                    ChoiceChip(option, selected == option) { onSelect(option) }
                }
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(17.dp)) }
        } else null,
    )
}

@Composable
private fun FilterTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}
