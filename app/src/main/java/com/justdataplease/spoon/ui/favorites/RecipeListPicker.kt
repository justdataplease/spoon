package com.justdataplease.spoon.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.data.local.FAVORITES_LIST_NAME
import com.justdataplease.spoon.data.local.MAX_RECIPE_LIST_NAME_LENGTH
import com.justdataplease.spoon.data.local.recipeListNameProblem

data class RecipeListUi(
    val id: String,
    val name: String,
    val recipeCount: Int = 0,
)

data class RecipeListPickerUi(
    val recipeId: String,
    val recipeTitle: String,
    val isFavorite: Boolean,
    val lists: List<RecipeListUi>,
    /** The extra lists that already hold this recipe; «Αγαπημένα» always does once saved. */
    val listIdsWithRecipe: Set<String>,
)

/** Chooses the lists a saved recipe belongs to. «Αγαπημένα» is fixed: it holds every saved recipe. */
@Composable
fun SaveToListDialog(
    picker: RecipeListPickerUi,
    onCreateList: (String) -> String?,
    onSave: (Set<String>) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(picker.recipeId) { mutableStateOf(picker.listIdsWithRecipe) }
    var creating by rememberSaveable(picker.recipeId) { mutableStateOf(false) }
    var newName by rememberSaveable(picker.recipeId) { mutableStateOf("") }
    val nameProblem = recipeListNameProblem(newName, picker.lists.map(RecipeListUi::name))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (picker.isFavorite) "Λίστες συνταγής" else "Αποθήκευση στη συλλογή") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (picker.recipeTitle.isNotBlank()) {
                    Text(
                        picker.recipeTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                ListChoiceRow(
                    name = FAVORITES_LIST_NAME,
                    supporting = "Κάθε αποθηκευμένη συνταγή",
                    checked = true,
                    enabled = false,
                    onCheckedChange = {},
                )
                picker.lists.forEach { list ->
                    ListChoiceRow(
                        name = list.name,
                        supporting = recipeCountLabel(list.recipeCount),
                        checked = list.id in selected,
                        onCheckedChange = { checked -> selected = if (checked) selected + list.id else selected - list.id },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(MAX_RECIPE_LIST_NAME_LENGTH) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Όνομα νέας λίστας") },
                        placeholder = { Text("π.χ. Γιορτινά") },
                        singleLine = true,
                        isError = newName.isNotBlank() && nameProblem != null,
                        supportingText = nameProblem?.takeIf { newName.isNotBlank() }?.let { problem -> { Text(problem) } },
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            creating = false
                            newName = ""
                        }) { Text("Άκυρο") }
                        TextButton(
                            enabled = nameProblem == null,
                            onClick = {
                                onCreateList(newName)?.let { id -> selected = selected + id }
                                creating = false
                                newName = ""
                            },
                        ) { Text("Δημιουργία") }
                    }
                } else {
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Νέα λίστα", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (picker.isFavorite) {
                    TextButton(onClick = onRemove) {
                        Text("Αφαίρεση από τη συλλογή", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(selected) }) { Text("Αποθήκευση") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Άκυρο") }
        },
    )
}

/** Names a new list or renames one; the save button waits for a valid, unused name. */
@Composable
fun RecipeListNameDialog(
    title: String,
    initialName: String,
    existingNames: List<String>,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val problem = recipeListNameProblem(name, existingNames)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_RECIPE_LIST_NAME_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Όνομα λίστας") },
                singleLine = true,
                isError = name.isNotBlank() && problem != null,
                supportingText = problem?.takeIf { name.isNotBlank() }?.let { message -> { Text(message) } },
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = problem == null) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Άκυρο") }
        },
    )
}

@Composable
private fun ListChoiceRow(
    name: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun recipeCountLabel(count: Int): String = if (count == 1) "1 συνταγή" else "$count συνταγές"
