package com.justdataplease.spoon.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.TextButton
import com.justdataplease.spoon.data.local.FAVORITES_LIST_NAME
import com.justdataplease.spoon.ui.explore.ExploreFiltersUi
import com.justdataplease.spoon.ui.explore.ExploreFacetOptionsUi
import com.justdataplease.spoon.ui.explore.ExploreFilterSheet
import com.justdataplease.spoon.ui.explore.ActiveFilterSummary
import com.justdataplease.spoon.ui.explore.RecipeSearchControls
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.ui.components.CompactFavoriteCard
import com.justdataplease.spoon.ui.model.FavoriteUi

@Composable
fun FavoritesScreen(
    favorites: List<FavoriteUi>,
    onOpenRecipe: (String) -> Unit,
    onOpenListPicker: (String) -> Unit,
    onSetIncludedInWeeklyPlan: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    query: TextFieldValue = TextFieldValue(),
    filters: ExploreFiltersUi = ExploreFiltersUi(),
    options: ExploreFacetOptionsUi = ExploreFacetOptionsUi(),
    totalFavoriteCount: Int = favorites.size,
    onQueryChange: (TextFieldValue) -> Unit = {},
    onApplyFilters: (ExploreFiltersUi) -> Unit = {},
    wholeWords: Boolean = false,
    onWholeWordsChange: (Boolean) -> Unit = {},
    lists: List<RecipeListUi> = emptyList(),
    selectedListId: String? = null,
    onSelectList: (String?) -> Unit = {},
    onCreateList: (String) -> String? = { null },
    onRenameList: (String, String) -> Unit = { _, _ -> },
    onDeleteList: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    var showFilters by remember { mutableStateOf(false) }
    var showCreateList by remember { mutableStateOf(false) }
    var renamingList by remember { mutableStateOf<RecipeListUi?>(null) }
    var deletingList by remember { mutableStateOf<RecipeListUi?>(null) }
    val selectedList = lists.firstOrNull { it.id == selectedListId }
    val searching = query.text.isNotBlank() || filters.activeCount > 0

    if (totalFavoriteCount == 0 && !searching) {
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
        state = listState,
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
            RecipeListChips(
                lists = lists,
                selectedListId = selectedList?.id,
                favoritesCount = totalFavoriteCount,
                onSelectList = onSelectList,
                onCreateList = { showCreateList = true },
            )
        }
        if (selectedList != null) {
            item {
                SelectedListActions(
                    list = selectedList,
                    onRename = { renamingList = selectedList },
                    onDelete = { deletingList = selectedList },
                )
            }
        }
        item {
            RecipeSearchControls(
                query = query,
                filters = filters,
                onQueryChange = onQueryChange,
                onShowFilters = { showFilters = true },
                filterDescription = "Φίλτρα συλλογής",
                wholeWords = wholeWords,
                onWholeWordsChange = onWholeWordsChange,
            )
        }
        if (filters.activeCount > 0) {
            item { ActiveFilterSummary(filters, onClear = { onApplyFilters(ExploreFiltersUi()) }) }
        }
        if (favorites.isEmpty()) {
            item {
                if (selectedList != null && !searching) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Η λίστα «${selectedList.name}» είναι άδεια", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Πάτησε την καρδιά σε μια συνταγή και διάλεξε αυτή τη λίστα.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Δεν βρέθηκαν συνταγές στη συλλογή", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (wholeWords) {
                                "Δοκίμασε χωρίς «Ολόκληρες λέξεις», άλλη αναζήτηση ή άλλα φίλτρα."
                            } else {
                                "Δοκίμασε άλλη αναζήτηση ή άλλα φίλτρα. Ισχύουν και οι προτιμήσεις φαγητού σου."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            onQueryChange(TextFieldValue())
                            onApplyFilters(ExploreFiltersUi())
                        }) { Text("Καθαρισμός αναζήτησης και φίλτρων") }
                    }
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
                onRemove = { onOpenListPicker(favorite.recipeId) },
                includedInWeeklyPlan = favorite.includedInWeeklyPlan,
                onSetIncludedInWeeklyPlan = { onSetIncludedInWeeklyPlan(favorite.recipeId, it) },
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
    if (showCreateList) {
        RecipeListNameDialog(
            title = "Νέα λίστα",
            initialName = "",
            existingNames = lists.map(RecipeListUi::name),
            confirmLabel = "Δημιουργία",
            onConfirm = { name ->
                onCreateList(name)?.let(onSelectList)
                showCreateList = false
            },
            onDismiss = { showCreateList = false },
        )
    }
    renamingList?.let { list ->
        RecipeListNameDialog(
            title = "Μετονομασία λίστας",
            initialName = list.name,
            existingNames = lists.filterNot { it.id == list.id }.map(RecipeListUi::name),
            confirmLabel = "Αποθήκευση",
            onConfirm = { name ->
                onRenameList(list.id, name)
                renamingList = null
            },
            onDismiss = { renamingList = null },
        )
    }
    deletingList?.let { list ->
        AlertDialog(
            onDismissRequest = { deletingList = null },
            title = { Text("Διαγραφή λίστας «${list.name}»;") },
            text = { Text("Οι συνταγές της δεν διαγράφονται· μένουν στα $FAVORITES_LIST_NAME.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteList(list.id)
                    deletingList = null
                }) { Text("Διαγραφή", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingList = null }) { Text("Άκυρο") } },
        )
    }
}

@Composable
private fun RecipeListChips(
    lists: List<RecipeListUi>,
    selectedListId: String?,
    favoritesCount: Int,
    onSelectList: (String?) -> Unit,
    onCreateList: () -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "favorites") {
            ListChip("$FAVORITES_LIST_NAME · $favoritesCount", selectedListId == null) { onSelectList(null) }
        }
        items(lists, key = RecipeListUi::id) { list ->
            ListChip("${list.name} · ${list.recipeCount}", list.id == selectedListId) { onSelectList(list.id) }
        }
        item(key = "new-list") {
            AssistChip(
                onClick = onCreateList,
                label = { Text("Νέα λίστα") },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun ListChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(17.dp)) }
        } else null,
    )
}

@Composable
private fun SelectedListActions(list: RecipeListUi, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Λίστα «${list.name}»",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRename) {
            Icon(Icons.Outlined.Edit, contentDescription = "Μετονομασία λίστας")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Διαγραφή λίστας")
        }
    }
}

data class FavoritesSearchUiState(
    val filters: ExploreFiltersUi = ExploreFiltersUi(),
    val favorites: List<FavoriteUi> = emptyList(),
    val totalCount: Int = 0,
    val lists: List<RecipeListUi> = emptyList(),
    /** Null means «Αγαπημένα», every saved recipe. */
    val selectedListId: String? = null,
)

internal fun favoriteRecipeSummary(recipeCount: Int): String =
    if (recipeCount == 1) {
        "1 συνταγή που αξίζει να ξαναφτιάξεις"
    } else {
        "$recipeCount συνταγές που αξίζει να ξαναφτιάξεις"
    }
