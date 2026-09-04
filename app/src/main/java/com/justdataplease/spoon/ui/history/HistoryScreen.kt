package com.justdataplease.spoon.ui.history

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.ui.components.GradientHeroCard
import com.justdataplease.spoon.ui.components.GreekLocale
import com.justdataplease.spoon.ui.components.RecipeArtwork
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

data class HistoryEntryUi(
    val id: String,
    val date: LocalDate,
    val recipeId: String,
    val title: String,
    val categoryLabel: String,
    val categoryEmoji: String,
    val imageUrl: String = "",
    val completedAtEpochMillis: Long = 0L,
)

@Composable
fun HistoryScreen(
    entries: List<HistoryEntryUi>,
    onOpenRecipe: (String) -> Unit,
    onRemoveEntry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = remember(entries) {
        entries.sortedWith(
            compareByDescending<HistoryEntryUi> { it.date }
                .thenByDescending { it.completedAtEpochMillis },
        )
    }
    val grouped = remember(ordered) { ordered.groupBy { YearMonth.from(it.date) } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item { HistoryHero(count = ordered.size) }
        if (ordered.isEmpty()) {
            item { EmptyHistory() }
        } else {
            grouped.forEach { (month, monthEntries) ->
                item(key = "month-$month") {
                    Text(
                        month.format(DateTimeFormatter.ofPattern("LLLL yyyy", GreekLocale))
                            .replaceFirstChar { it.titlecase(GreekLocale) },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 7.dp, start = 4.dp),
                    )
                }
                items(monthEntries, key = HistoryEntryUi::id) { entry ->
                    HistoryCard(
                        entry = entry,
                        onOpen = { onOpenRecipe(entry.recipeId) },
                        onUndo = { onRemoveEntry(entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryHero(count: Int) {
    GradientHeroCard(
        icon = Icons.Outlined.History,
        title = "Ιστορικό",
        subtitle = when (count) {
            0 -> "Εδώ θα εμφανίζονται όσα σημειώνεις ως «Το έφτιαξα»."
            1 -> "Έχεις μαγειρέψει 1 προγραμματισμένη συνταγή."
            else -> "Έχεις μαγειρέψει $count προγραμματισμένες συνταγές."
        },
        gradient = listOf(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.secondaryContainer),
    )
}

@Composable
private fun HistoryCard(
    entry: HistoryEntryUi,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(21.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(11.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecipeArtwork(
                imageUrl = entry.imageUrl,
                title = entry.title,
                modifier = Modifier.size(78.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${entry.categoryEmoji} ${entry.categoryLabel}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    entry.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", GreekLocale))
                        .replaceFirstChar { it.titlecase(GreekLocale) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUndo) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Αφαίρεση από το ιστορικό")
            }
        }
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 50.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.padding(22.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(38.dp))
            }
        }
        Text("Δεν υπάρχει ιστορικό ακόμα", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Όταν μαγειρέψεις κάτι, πάτησε «Το έφτιαξα» στην εβδομάδα ή στο ημερολόγιο.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
