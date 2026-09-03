package com.justdataplease.spoon.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.ui.components.CategoryPill
import com.justdataplease.spoon.ui.components.greekDayLabel
import com.justdataplease.spoon.ui.model.CalendarMealUi
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GreekLocale = Locale.forLanguageTag("el-GR")
private val FullDateFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", GreekLocale)

@Composable
fun CalendarScreen(
    shownMonth: YearMonth,
    meals: List<CalendarMealUi>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onCurrentMonth: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    onToggleCompleted: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedDate by remember(shownMonth) { mutableStateOf<LocalDate?>(null) }
    val selectedMeal = selectedDate?.let { date -> meals.firstOrNull { it.date == date } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Ημερολόγιο", style = MaterialTheme.typography.displaySmall)
            Text(
                "Οι προτάσεις και όσα μαγείρεψες, σε μία ματιά.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            MonthCard(
                month = shownMonth,
                meals = meals,
                selectedDate = selectedDate,
                onSelectDate = { selectedDate = it },
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
                onCurrentMonth = onCurrentMonth,
            )
        }
        if (selectedDate != null) {
            item {
                SelectedDayCard(
                    date = selectedDate!!,
                    meal = selectedMeal,
                    onOpenRecipe = onOpenRecipe,
                    onToggleCompleted = onToggleCompleted,
                )
            }
        }
        item {
            Text("Αυτόν τον μήνα", style = MaterialTheme.typography.titleLarge)
        }
        val completed = meals.filter { it.isCompleted }.sortedByDescending { it.date }
        if (completed.isEmpty()) {
            item {
                Text(
                    "Δεν έχεις σημειώσει κάποιο γεύμα ως έτοιμο ακόμα.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(completed, key = { it.date.toString() }) { meal ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Filled.Check, null, Modifier.padding(7.dp).size(18.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(meal.recipeTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                meal.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", GreekLocale)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(meal.emoji)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCard(
    month: YearMonth,
    meals: List<CalendarMealUi>,
    selectedDate: LocalDate?,
    onSelectDate: (LocalDate) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onCurrentMonth: () -> Unit,
) {
    val first = month.atDay(1)
    val leading = first.dayOfWeek.value - 1
    val cells = List<LocalDate?>(leading) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    val rows = cells.chunked(7)
    val byDate = meals.associateBy { it.date }
    val monthLabel = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", GreekLocale))
        .replaceFirstChar { it.titlecase(GreekLocale) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPreviousMonth) { Icon(Icons.Outlined.ChevronLeft, "Προηγούμενος μήνας") }
                Text(
                    monthLabel,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            onClickLabel = "Μετάβαση στον τρέχοντα μήνα",
                            role = Role.Button,
                            onClick = onCurrentMonth,
                        ),
                )
                IconButton(onClick = onNextMonth) { Icon(Icons.Outlined.ChevronRight, "Επόμενος μήνας") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("Δ", "Τ", "Τ", "Π", "Π", "Σ", "Κ").forEach { label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            rows.forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    (0..6).forEach { index ->
                        val date = week.getOrNull(index)
                        val meal = date?.let(byDate::get)
                        DayCell(
                            date = date,
                            meal = meal,
                            selected = date == selectedDate,
                            onSelect = { if (date != null) onSelectDate(date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate?,
    meal: CalendarMealUi?,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayDescription = date?.let { calendarDayDescription(it, meal, selected) }
    Box(
        modifier = modifier
            .aspectRatio(0.82f)
            .padding(2.dp)
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    date == LocalDate.now() -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    else -> Color.Transparent
                },
                RoundedCornerShape(12.dp),
            )
            .then(
                if (date != null) {
                    Modifier
                        .semantics(mergeDescendants = true) {
                            contentDescription = checkNotNull(dayDescription)
                            this.selected = selected
                        }
                        .clickable(
                            onClickLabel = "Προβολή ημέρας",
                            role = Role.Button,
                            onClick = onSelect,
                        )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (date != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = if (date == LocalDate.now()) FontWeight.Bold else FontWeight.Normal)
                if (meal != null) {
                    Text(meal.emoji, style = MaterialTheme.typography.bodyMedium)
                    if (meal.isCompleted) {
                        Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
                    }
                }
            }
        }
    }
}

internal fun calendarDayDescription(
    date: LocalDate,
    meal: CalendarMealUi?,
    selected: Boolean,
): String = buildList {
    add(
        date.format(FullDateFormatter)
            .replaceFirstChar { it.titlecase(GreekLocale) },
    )
    if (meal == null) {
        add("Χωρίς προγραμματισμένο γεύμα")
    } else {
        add("Γεύμα: ${meal.recipeTitle}")
        add(if (meal.isCompleted) "Ολοκληρωμένο" else "Δεν έχει ολοκληρωθεί")
    }
    add(if (selected) "Επιλεγμένη ημέρα" else "Μη επιλεγμένη ημέρα")
}.joinToString(". ")

@Composable
private fun SelectedDayCard(
    date: LocalDate,
    meal: CalendarMealUi?,
    onOpenRecipe: (String) -> Unit,
    onToggleCompleted: (LocalDate) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(date.greekDayLabel(), style = MaterialTheme.typography.titleLarge)
            if (meal == null) {
                Text("Δεν υπάρχει προγραμματισμένο γεύμα.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(meal.recipeTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(meal.emoji, modifier = Modifier.padding(start = 12.dp))
                }
                OutlinedButton(
                    onClick = { onOpenRecipe(meal.recipeId) },
                    enabled = meal.recipeId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Προβολή λεπτομερειών")
                }
                Button(
                    onClick = { onToggleCompleted(date) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (meal.isCompleted) {
                            "Αναίρεση ολοκλήρωσης"
                        } else {
                            "Το έφτιαξα"
                        },
                    )
                }
                Text(if (meal.isCompleted) "✓ Το έφτιαξες" else "Προγραμματισμένο", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
