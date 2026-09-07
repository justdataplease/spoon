package com.justdataplease.spoon.ui.week

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.data.model.MealCourse
import com.justdataplease.spoon.ui.components.EmptyRecipeCard
import com.justdataplease.spoon.ui.components.greekDayLabel
import com.justdataplease.spoon.ui.components.greekShortDate
import com.justdataplease.spoon.ui.model.DayPlanUi
import com.justdataplease.spoon.ui.model.FiltersUi
import java.time.LocalDate

data class MealMenuUiState(
    val date: LocalDate,
    val isLoading: Boolean = true,
    val plans: Map<MealCourse, DayPlanUi> = emptyMap(),
    val favoritesOnly: Boolean = false,
    val error: String? = null,
)

@Composable
fun MealMenuScreen(
    state: MealMenuUiState,
    onBack: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    onRetry: () -> Unit,
    onReroll: (MealCourse) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onChooseFavorite: (MealCourse) -> Unit,
    onToggleLock: (MealCourse) -> Unit,
    onToggleCompleted: (MealCourse) -> Unit,
    onSaveFilters: (MealCourse, FiltersUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingCourse by rememberSaveable(state.date) { mutableStateOf<MealCourse?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Πίσω στο πλάνο") }
                Column {
                    Text("Πλήρες μενού", style = MaterialTheme.typography.headlineMedium)
                    Text("${state.date.greekDayLabel()} · ${state.date.greekShortDate()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "Οι επιλογές σου αποθηκεύονται. Άλλαξε κάθε πιάτο ξεχωριστά όποτε θέλεις.",
                modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.isLoading) {
            item { CircularProgressIndicator(Modifier.padding(24.dp)) }
        }
        if (state.error != null) {
            item {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Δοκίμασε ξανά") }
            }
        }
        MealCourse.entries.forEach { course ->
            state.plans[course]?.let { plan ->
                item(key = course.name) {
                    if (plan.recipeId.isBlank()) {
                        Column {
                            EmptyRecipeCard(plan = plan, onPick = { onReroll(course) },
                                onEdit = { editingCourse = course }, favoritesOnly = state.favoritesOnly,
                                title = course.label)
                            TextButton(onClick = { onChooseFavorite(course) }) { Text("Από τη συλλογή") }
                        }
                    } else {
                        DayRecipeCard(
                            plan = plan, isToday = false, courseTitle = course.label,
                            onReroll = { onReroll(course) }, onEdit = { editingCourse = course },
                            onToggleFavorite = { onToggleFavorite(plan.recipeId) },
                            onToggleCompleted = { onToggleCompleted(course) },
                            onToggleLock = { onToggleLock(course) },
                            onOpenRecipe = { onOpenRecipe(plan.recipeId) },
                            onChooseFavorite = { onChooseFavorite(course) },
                        )
                    }
                }
            }
        }
    }
    editingCourse?.let { course ->
        state.plans[course]?.let { plan ->
            FilterEditorSheet(plan = plan, onDismiss = { editingCourse = null }, onSave = {
                onSaveFilters(course, it)
                editingCourse = null
            })
        }
    }
}
