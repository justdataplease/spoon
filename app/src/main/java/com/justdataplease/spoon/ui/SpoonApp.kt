package com.justdataplease.spoon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.justdataplease.spoon.ui.calendar.CalendarScreen
import com.justdataplease.spoon.ui.details.RecipeDetailsScreen
import com.justdataplease.spoon.ui.explore.ExploreScreen
import com.justdataplease.spoon.ui.favorites.FavoriteReplacementSheet
import com.justdataplease.spoon.ui.favorites.FavoritesScreen
import com.justdataplease.spoon.ui.model.SpoonUiState
import com.justdataplease.spoon.ui.week.WeekScreen

private data class Destination(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val Destinations = listOf(
    Destination("Εβδομάδα", Icons.Filled.RestaurantMenu, Icons.Outlined.RestaurantMenu),
    Destination("Εξερεύνηση", Icons.Filled.Search, Icons.Outlined.Search),
    Destination("Αγαπημένα", Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
    Destination("Ημερολόγιο", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
)

@Composable
fun SpoonApp(
    state: SpoonUiState,
    viewModel: SpoonViewModel,
    onOpenRecipe: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedDestination by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val selectedRecipe = state.selectedRecipe

    BackHandler(enabled = selectedRecipe != null || state.favoriteReplacementDate != null) {
        if (selectedRecipe != null) {
            viewModel.dismissRecipeDetails()
        } else {
            viewModel.dismissFavoriteReplacement()
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selectedRecipe == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Destinations.forEachIndexed { index, destination ->
                        val selected = selectedDestination == index
                        NavigationBarItem(
                            selected = selected,
                            onClick = { selectedDestination = index },
                            icon = {
                                Icon(
                                    if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (selectedRecipe != null) {
            RecipeDetailsScreen(
                recipe = selectedRecipe,
                onBack = viewModel::dismissRecipeDetails,
                onToggleFavorite = { viewModel.toggleFavorite(selectedRecipe.recipeId) },
                onOpenSource = { onOpenRecipe(selectedRecipe.sourceUrl) },
                isLoadingDetails = state.isRecipeDetailsLoading,
                modifier = Modifier.padding(padding),
            )
        } else when (selectedDestination) {
            0 -> WeekScreen(
                state = state,
                onPreviousWeek = viewModel::previousWeek,
                onNextWeek = viewModel::nextWeek,
                onCurrentWeek = viewModel::currentWeek,
                onReroll = viewModel::reroll,
                onEdit = viewModel::editFilters,
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleCompleted = viewModel::toggleCompleted,
                onOpenRecipe = viewModel::showRecipeDetails,
                onShuffleWeek = viewModel::shuffleWeek,
                onSaveFilters = viewModel::saveFilters,
                onDismissEditor = viewModel::dismissFilters,
                onChooseFavorite = viewModel::showFavoriteReplacement,
                modifier = Modifier.padding(padding),
            )
            1 -> ExploreScreen(
                query = state.exploreQuery,
                recipes = state.exploreRecipes,
                totalRecipeCount = state.exploreTotalRecipeCount,
                filters = state.exploreFilters,
                options = state.exploreOptions,
                onQueryChange = viewModel::updateExploreQuery,
                onApplyFilters = viewModel::applyExploreFilters,
                onOpenRecipe = viewModel::showRecipeDetails,
                onToggleFavorite = viewModel::toggleFavorite,
                modifier = Modifier.padding(padding),
            )
            2 -> FavoritesScreen(
                favorites = state.favorites,
                onOpenRecipe = viewModel::showRecipeDetails,
                onRemoveFavorite = viewModel::toggleFavorite,
                modifier = Modifier.padding(padding),
            )
            else -> CalendarScreen(
                shownMonth = state.shownMonth,
                meals = state.calendarMeals,
                onPreviousMonth = viewModel::previousMonth,
                onNextMonth = viewModel::nextMonth,
                onCurrentMonth = viewModel::currentMonth,
                onOpenRecipe = viewModel::showRecipeDetails,
                onToggleCompleted = viewModel::toggleCompleted,
                modifier = Modifier.padding(padding),
            )
        }
    }

    state.favoriteReplacementDate?.let { date ->
        FavoriteReplacementSheet(
            date = date,
            favorites = state.favorites,
            onSelect = viewModel::replaceWithFavorite,
            onDismiss = viewModel::dismissFavoriteReplacement,
        )
    }
}
