package com.justdataplease.spoon.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.TextButton
import com.justdataplease.spoon.ui.explore.ExploreFiltersUi
import com.justdataplease.spoon.ui.explore.ExploreFacetOptionsUi
import com.justdataplease.spoon.ui.explore.ExploreFilterSheet
import com.justdataplease.spoon.ui.explore.ActiveFilterSummary
import com.justdataplease.spoon.ui.explore.RecipeSearchControls
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.ui.components.CompactFavoriteCard
import com.justdataplease.spoon.ui.model.FavoriteUi

@Composable
fun FavoritesScreen(
    favorites: List<FavoriteUi>,
    onOpenRecipe: (String) -> Unit,
    onRemoveFavorite: (String) -> Unit,
    modifier: Modifier = Modifier,
    query: String = "",
    filters: ExploreFiltersUi = ExploreFiltersUi(),
    options: ExploreFacetOptionsUi = ExploreFacetOptionsUi(),
    totalFavoriteCount: Int = favorites.size,
    onQueryChange: (String) -> Unit = {},
    onApplyFilters: (ExploreFiltersUi) -> Unit = {},
) {
    var showFilters by remember { mutableStateOf(false) }
    if (totalFavoriteCount == 0 && query.isBlank() && filters.activeCount == 0) {
        Box(modifier = modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Icon(
                        Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.padding(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text("Η συλλογή σου", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Πάτησε την καρδιά σε μια πρόταση και θα τη βρεις εδώ.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Συλλογή", style = MaterialTheme.typography.displaySmall)
            Text(
                favoriteRecipeSummary(favorites.size),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
        }
        item {
            RecipeSearchControls(
                query = query,
                filters = filters,
                onQueryChange = onQueryChange,
                onShowFilters = { showFilters = true },
                filterDescription = "Φίλτρα συλλογής",
            )
        }
        if (filters.activeCount > 0) {
            item { ActiveFilterSummary(filters, onClear = { onApplyFilters(ExploreFiltersUi()) }) }
        }
        if (favorites.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Δεν βρέθηκαν συνταγές στη συλλογή", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Δοκίμασε άλλη αναζήτηση ή άλλα φίλτρα. Ισχύουν και οι προτιμήσεις φαγητού σου.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        onQueryChange("")
                        onApplyFilters(ExploreFiltersUi())
                    }) { Text("Καθαρισμός αναζήτησης και φίλτρων") }
                }
            }
        }
        items(favorites, key = { it.recipeId }) { favorite ->
            CompactFavoriteCard(
                emoji = favorite.category.emoji,
                title = favorite.title,
                category = favorite.category.label,
                rating10 = favorite.rating10,
                prepMinutes = favorite.prepMinutes,
                imageUrl = favorite.imageUrl,
                onOpen = { onOpenRecipe(favorite.recipeId) },
                onRemove = { onRemoveFavorite(favorite.recipeId) },
                modifier = Modifier.fillMaxWidth(),
            )
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

data class FavoritesSearchUiState(
    val query: String = "",
    val filters: ExploreFiltersUi = ExploreFiltersUi(),
    val favorites: List<FavoriteUi> = emptyList(),
    val totalCount: Int = 0,
)

internal fun favoriteRecipeSummary(recipeCount: Int): String =
    if (recipeCount == 1) {
        "1 συνταγή που αξίζει να ξαναφτιάξεις"
    } else {
        "$recipeCount συνταγές που αξίζει να ξαναφτιάξεις"
    }
