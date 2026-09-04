package com.justdataplease.spoon.ui.shopping

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.ui.components.GradientHeroCard

@Composable
fun ShoppingScreen(
    items: List<ShoppingListItemUi>,
    onToggle: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearChecked: () -> Unit,
    onAddManual: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var manualItem by remember { mutableStateOf("") }
    val pending = items.count { !it.isChecked }
    val checked = items.size - pending
    val grouped = remember(items) {
        items.groupBy { it.recipeTitle.ifBlank { "Χωρίς συνταγή" } }
            .mapValues { (_, recipeItems) -> recipeItems.sortedWith(compareBy(ShoppingListItemUi::isChecked).thenBy { it.title }) }
            .toSortedMap(compareBy<String> { it == "Χωρίς συνταγή" }.thenBy { it })
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item { ShoppingHero(pending = pending, total = items.size) }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(22.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = manualItem,
                        onValueChange = { manualItem = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Νέο προϊόν") },
                        placeholder = { Text("π.χ. γάλα") },
                        shape = RoundedCornerShape(16.dp),
                    )
                    Button(
                        onClick = {
                            val value = manualItem.trim()
                            if (value.isNotBlank()) {
                                onAddManual(value)
                                manualItem = ""
                            }
                        },
                        enabled = manualItem.isNotBlank(),
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "Προσθήκη προϊόντος")
                    }
                }
            }
        }

        if (items.isEmpty()) {
            item { EmptyShoppingList() }
        } else {
            grouped.forEach { (recipeTitle, recipeItems) ->
                item(key = "header-$recipeTitle") {
                    Text(
                        recipeTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp, start = 4.dp),
                    )
                }
                items(recipeItems, key = ShoppingListItemUi::id) { item ->
                    ShoppingRow(item = item, onToggle = { onToggle(item.id) }, onRemove = { onRemove(item.id) })
                }
            }
        }

        if (checked > 0) {
            item {
                OutlinedButton(
                    onClick = onClearChecked,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (checked == 1) "Διαγραφή αγορασμένου" else "Διαγραφή $checked αγορασμένων")
                }
            }
        }
    }
}

@Composable
private fun ShoppingHero(pending: Int, total: Int) {
    GradientHeroCard(
        icon = Icons.Outlined.ShoppingBasket,
        title = "Λίστα αγορών",
        subtitle = when {
            total == 0 -> "Πρόσθεσε υλικά από μια συνταγή ή γράψε κάτι δικό σου."
            pending == 0 -> "Τα πήρες όλα — μπράβο!"
            pending == 1 -> "Απομένει 1 προϊόν."
            else -> "Απομένουν $pending προϊόντα."
        },
        gradient = listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.primaryContainer),
    )
}

@Composable
private fun ShoppingRow(
    item: ShoppingListItemUi,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (item.isChecked) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 7.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = item.isChecked,
                onCheckedChange = { onToggle() },
            )
            Column(modifier = Modifier.weight(1f).padding(vertical = 7.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (item.isChecked) TextDecoration.LineThrough else TextDecoration.None,
                )
                val details = listOf(item.amountLabel(), item.info.trim()).filter(String::isNotBlank).joinToString(" · ")
                if (details.isNotBlank()) {
                    Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Αφαίρεση ${item.title}")
            }
        }
    }
}

@Composable
private fun EmptyShoppingList() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 46.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(Modifier.padding(21.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(36.dp))
            }
        }
        Text("Η λίστα είναι άδεια", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Άνοιξε μια συνταγή και πάτησε «Προσθήκη στη λίστα» στα υλικά.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
