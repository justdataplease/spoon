package com.justdataplease.spoon.ui.week

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.ui.components.RecipeArtwork
import com.justdataplease.spoon.ui.components.greekDayLabel
import com.justdataplease.spoon.ui.components.greekShortDate
import com.justdataplease.spoon.ui.explore.ExploreRecipeUi
import java.time.LocalDate

data class MealMenuUiState(
    val date: LocalDate,
    val isLoading: Boolean = true,
    val main: ExploreRecipeUi? = null,
    val side: ExploreRecipeUi? = null,
    val dessert: ExploreRecipeUi? = null,
    val favoritesOnly: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealMenuSheet(
    state: MealMenuUiState,
    onDismiss: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    onRetry: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("Πλήρες μενού", style = MaterialTheme.typography.headlineMedium)
                Text("${state.date.greekDayLabel()} · ${state.date.greekShortDate()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Το κυρίως είναι η πρόταση της ημέρας. Συνοδευτικό και γλυκό προτείνονται μόνο αν θέλεις κάτι παραπάνω.",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when {
                state.isLoading -> item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("Ετοιμάζουμε το μενού σου…")
                    }
                }
                state.error != null -> item {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("Δοκίμασε ξανά") }
                }
                else -> {
                    item { MenuCourseCard("Κυρίως", state.main, state.favoritesOnly, onOpenRecipe) }
                    item { MenuCourseCard("Συνοδευτικό · προαιρετικό", state.side, state.favoritesOnly, onOpenRecipe) }
                    item { MenuCourseCard("Γλυκό · προαιρετικό", state.dessert, state.favoritesOnly, onOpenRecipe) }
                }
            }
        }
    }
}

@Composable
private fun MenuCourseCard(
    title: String,
    recipe: ExploreRecipeUi?,
    favoritesOnly: Boolean,
    onOpenRecipe: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (recipe == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Μη διαθέσιμη συνταγή", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (favoritesOnly) "Δεν υπάρχει αγαπημένη συνταγή που να ταιριάζει σε αυτό το μέρος του μενού και στα φίλτρα σου."
                        else "Δεν βρέθηκε συνταγή που να ταιριάζει σε αυτό το μέρος του μενού και στα φίλτρα σου.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            Card(onClick = { onOpenRecipe(recipe.recipeId) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RecipeArtwork(recipe.imageUrl, recipe.title, modifier = Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(recipe.title, style = MaterialTheme.typography.titleMedium)
                        Text(recipe.categoryLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "Λεπτομέρειες συνταγής")
                }
            }
        }
    }
}
