package com.justdataplease.spoon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingBasket
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.spoon.data.model.isCustomRecipeId
import com.justdataplease.spoon.ui.account.AccountScreen
import com.justdataplease.spoon.ui.calendar.CalendarScreen
import com.justdataplease.spoon.ui.custom.CustomRecipeScreen
import com.justdataplease.spoon.ui.details.RecipeDetailsScreen
import com.justdataplease.spoon.ui.explore.ExploreScreen
import com.justdataplease.spoon.ui.favorites.FavoriteReplacementSheet
import com.justdataplease.spoon.ui.favorites.FavoritesScreen
import com.justdataplease.spoon.ui.history.HistoryScreen
import com.justdataplease.spoon.ui.model.SpoonUiState
import com.justdataplease.spoon.ui.more.MoreScreen
import com.justdataplease.spoon.ui.shopping.ShoppingScreen
import com.justdataplease.spoon.ui.settings.FoodPreferencesScreen
import com.justdataplease.spoon.ui.week.WeekScreen
import com.justdataplease.spoon.ui.week.MealMenuScreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.History

private enum class PrimaryDestination { WEEK, EXPLORE, FAVORITES, SHOPPING, HISTORY, MORE }
private enum class MorePage { HUB, CALENDAR, PREFERENCES, ACCOUNT }

private data class Destination(
    val key: PrimaryDestination,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val Destinations = listOf(
    Destination(PrimaryDestination.WEEK, "Πλάνο", Icons.Filled.RestaurantMenu, Icons.Outlined.RestaurantMenu),
    Destination(PrimaryDestination.EXPLORE, "Βρες", Icons.Filled.Search, Icons.Outlined.Search),
    Destination(PrimaryDestination.FAVORITES, "Αγαπημένα", Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
    Destination(PrimaryDestination.SHOPPING, "Αγορές", Icons.Filled.ShoppingBasket, Icons.Outlined.ShoppingBasket),
    Destination(PrimaryDestination.HISTORY, "Ιστορικό", Icons.Filled.History, Icons.Outlined.History),
    Destination(PrimaryDestination.MORE, "Μενού", Icons.Filled.MoreHoriz, Icons.Outlined.Menu),
)

@Composable
fun SpoonApp(
    state: SpoonUiState,
    viewModel: SpoonViewModel,
    onOpenRecipe: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedDestination by rememberSaveable { mutableIntStateOf(0) }
    var morePage by rememberSaveable { mutableStateOf(MorePage.HUB) }
    val snackbarHostState = remember { SnackbarHostState() }
    val selectedRecipe = state.selectedRecipe
    val customRecipeEditor by viewModel.customRecipeEditor.collectAsStateWithLifecycle()
    val retainedCustomRecipePhoto by viewModel.customRecipeEditorRetainedPhoto.collectAsStateWithLifecycle()
    val mealMenu by viewModel.mealMenu.collectAsStateWithLifecycle()
    val favoritesSearch by viewModel.favoritesSearchUiState.collectAsStateWithLifecycle()
    val mealPreferenceSettings by viewModel.mealPreferenceSettings.collectAsStateWithLifecycle()

    BackHandler(
        enabled = mealMenu != null || selectedRecipe != null ||
            state.favoriteReplacementDate != null ||
            customRecipeEditor.isOpen ||
            (Destinations[selectedDestination].key == PrimaryDestination.MORE && morePage != MorePage.HUB) ||
            Destinations[selectedDestination].key != PrimaryDestination.WEEK,
    ) {
        when {
            customRecipeEditor.isOpen -> viewModel.dismissCustomRecipeEditor()
            selectedRecipe != null -> viewModel.dismissRecipeDetails()
            state.favoriteReplacementDate != null -> viewModel.dismissFavoriteReplacement()
            mealMenu != null -> viewModel.dismissMealMenu()
            Destinations[selectedDestination].key == PrimaryDestination.MORE && morePage != MorePage.HUB -> {
                morePage = MorePage.HUB
            }
            else -> {
                selectedDestination = Destinations.indexOfFirst { it.key == PrimaryDestination.WEEK }
                morePage = MorePage.HUB
            }
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
            if (selectedRecipe == null && !customRecipeEditor.isOpen && mealMenu == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Destinations.forEachIndexed { index, destination ->
                        val selected = selectedDestination == index
                        NavigationBarItem(
                            selected = selected,
                            alwaysShowLabel = false,
                            onClick = {
                                selectedDestination = index
                                if (destination.key == PrimaryDestination.MORE) morePage = MorePage.HUB
                            },
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
        when {
            customRecipeEditor.isOpen -> CustomRecipeScreen(
                editorState = customRecipeEditor,
                retainedImageUrl = retainedCustomRecipePhoto,
                isSaving = state.isSavingCustomRecipe,
                onBack = viewModel::dismissCustomRecipeEditor,
                onStateChange = viewModel::updateCustomRecipeEditor,
                onSave = viewModel::saveCustomRecipeEditor,
                modifier = Modifier.padding(padding),
            )

            selectedRecipe != null -> RecipeDetailsScreen(
                recipe = selectedRecipe,
                onBack = viewModel::dismissRecipeDetails,
                onToggleFavorite = { viewModel.toggleFavorite(selectedRecipe.recipeId) },
                onOpenSource = { onOpenRecipe(selectedRecipe.sourceUrl) },
                recipeNote = state.selectedRecipeNote,
                onSaveNote = viewModel::saveRecipeNote,
                onAddIngredients = viewModel::addIngredientsToShopping,
                onEdit = if (selectedRecipe.recipeId.isCustomRecipeId()) {
                    { viewModel.editCustomRecipe(selectedRecipe) }
                } else {
                    null
                },
                isLoadingDetails = state.isRecipeDetailsLoading,
                modifier = Modifier.padding(padding),
            )

            mealMenu != null -> MealMenuScreen(
                state = mealMenu!!,
                onBack = viewModel::dismissMealMenu,
                onOpenRecipe = viewModel::showRecipeDetails,
                onRetry = { viewModel.showMealMenu(mealMenu!!.date) },
                onReroll = viewModel::rerollMenuCourse,
                onToggleFavorite = viewModel::toggleFavorite,
                onChooseFavorite = { viewModel.showFavoriteReplacement(mealMenu!!.date, it) },
                onToggleLock = viewModel::toggleMenuLock,
                onToggleCompleted = viewModel::toggleMenuCompleted,
                onSaveFilters = viewModel::saveMenuFilters,
                modifier = Modifier.padding(padding),
            )

            else -> when (Destinations[selectedDestination].key) {
                PrimaryDestination.WEEK -> WeekScreen(
                    state = state,
                    onPreviousWeek = viewModel::previousWeek,
                    onNextWeek = viewModel::nextWeek,
                    onCurrentWeek = viewModel::currentWeek,
                    favoritesOnly = mealPreferenceSettings.favoritesOnly,
                    onOpenMenu = viewModel::showMealMenu,
                    onToggleLock = viewModel::toggleLocked,
                    onReroll = viewModel::reroll,
                    onEdit = viewModel::editFilters,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onToggleCompleted = viewModel::toggleCompleted,
                    onOpenRecipe = viewModel::showRecipeDetails,
                    onShuffleWeek = viewModel::shuffleWeek,
                    onSaveFilters = viewModel::saveFilters,
                    onDismissEditor = viewModel::dismissFilters,
                    onChooseFavorite = { viewModel.showFavoriteReplacement(it) },
                    modifier = Modifier.padding(padding),
                )

                PrimaryDestination.EXPLORE -> ExploreScreen(
                    query = state.exploreQuery,
                    recipes = state.exploreRecipes,
                    totalRecipeCount = state.exploreTotalRecipeCount,
                    resultGeneration = state.exploreResultGeneration,
                    isLoadingPage = state.exploreIsLoadingPage,
                    hasMore = state.exploreHasMore,
                    filters = state.exploreFilters,
                    options = state.exploreOptions,
                    onQueryChange = viewModel::updateExploreQuery,
                    onApplyFilters = viewModel::applyExploreFilters,
                    onOpenRecipe = viewModel::showRecipeDetails,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onLoadMore = viewModel::loadMoreExplore,
                    onCreateRecipe = viewModel::createCustomRecipe,
                    modifier = Modifier.padding(padding),
                )

                PrimaryDestination.FAVORITES -> FavoritesScreen(
                    favorites = favoritesSearch.favorites,
                    query = favoritesSearch.query,
                    filters = favoritesSearch.filters,
                    options = state.exploreOptions,
                    totalFavoriteCount = favoritesSearch.totalCount,
                    onQueryChange = viewModel::updateFavoritesQuery,
                    onApplyFilters = viewModel::applyFavoritesFilters,
                    onOpenRecipe = viewModel::showRecipeDetails,
                    onRemoveFavorite = viewModel::toggleFavorite,
                    modifier = Modifier.padding(padding),
                )

                PrimaryDestination.SHOPPING -> ShoppingScreen(
                    items = state.shoppingItems,
                    onToggle = viewModel::toggleShoppingItem,
                    onRemove = viewModel::removeShoppingItem,
                    onClearChecked = viewModel::clearCheckedShoppingItems,
                    onAddManual = viewModel::addManualShoppingItem,
                    modifier = Modifier.padding(padding),
                )

                PrimaryDestination.HISTORY -> HistoryScreen(
                    entries = state.historyEntries,
                    onOpenRecipe = viewModel::showRecipeDetails,
                    onRemoveEntry = viewModel::removeCookedHistoryEntry,
                    modifier = Modifier.padding(padding),
                )

                PrimaryDestination.MORE -> when (morePage) {
                    MorePage.HUB -> MoreScreen(
                        onOpenCalendar = { morePage = MorePage.CALENDAR },
                        onOpenHistory = { selectedDestination = Destinations.indexOfFirst { it.key == PrimaryDestination.HISTORY } },
                        onOpenPreferences = { morePage = MorePage.PREFERENCES },
                        onOpenAccount = { morePage = MorePage.ACCOUNT },
                        onCreateRecipe = viewModel::createCustomRecipe,
                        modifier = Modifier.padding(padding),
                    )
                    MorePage.CALENDAR -> CalendarScreen(
                        shownMonth = state.shownMonth,
                        meals = state.calendarMeals,
                        onPreviousMonth = viewModel::previousMonth,
                        onNextMonth = viewModel::nextMonth,
                        onCurrentMonth = viewModel::currentMonth,
                        onOpenRecipe = viewModel::showRecipeDetails,
                        onToggleCompleted = viewModel::toggleCompleted,
                        modifier = Modifier.padding(padding),
                    )
                    MorePage.PREFERENCES -> FoodPreferencesScreen(
                        settings = mealPreferenceSettings,
                        ingredientOptions = state.exploreOptions.ingredients,
                        onBack = { morePage = MorePage.HUB },
                        onSave = { settings ->
                            viewModel.saveMealPreferenceSettings(settings) { morePage = MorePage.HUB }
                        },
                        modifier = Modifier.padding(padding),
                    )
                    MorePage.ACCOUNT -> AccountScreen(
                        state = state.account,
                        onBack = {
                            viewModel.clearAccountError()
                            morePage = MorePage.HUB
                        },
                        onCreateOrLinkAccount = viewModel::createOrLinkAccount,
                        onSignIn = viewModel::signInWithEmail,
                        onResetPassword = viewModel::resetPassword,
                        onSignOut = viewModel::signOut,
                        onClearError = viewModel::clearAccountError,
                        modifier = Modifier.padding(padding),
                    )
                }
            }
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
