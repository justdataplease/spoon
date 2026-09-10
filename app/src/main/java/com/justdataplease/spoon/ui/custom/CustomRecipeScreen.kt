package com.justdataplease.spoon.ui.custom

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.ui.model.AvailableCategories

@Composable
fun CustomRecipeScreen(
    editorState: CustomRecipeEditorState,
    retainedImageUrl: String,
    isSaving: Boolean,
    onBack: () -> Unit,
    onStateChange: (CustomRecipeEditorState) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 42.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Πίσω")
                }
                Column {
                    Text(
                        if (editorState.isEditing) "Επεξεργασία συνταγής" else "Δική μου συνταγή",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        if (editorState.isEditing) {
                            "Άλλαξε κατηγορία ή στοιχεία και αποθήκευσέ την ξανά"
                        } else {
                            "Γράψε την όπως ακριβώς τη μαγειρεύεις"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading("Βασικά στοιχεία", Icons.Outlined.EditNote)
                    OutlinedTextField(
                        value = editorState.title,
                        onValueChange = {
                            onStateChange(editorState.copy(title = it.take(300), formMessage = null))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Τίτλος *") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = editorState.description,
                        onValueChange = {
                            onStateChange(editorState.copy(description = it.take(10_000), formMessage = null))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Περιγραφή") },
                        minLines = 3,
                    )
                    Text("Βασική κατηγορία", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(AvailableCategories, key = { it.key }) { category ->
                            FilterChip(
                                selected = editorState.categoryKey == category.key,
                                onClick = {
                                    onStateChange(editorState.copy(categoryKey = category.key, formMessage = null))
                                },
                                label = { Text("${category.emoji} ${category.label}") },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MinuteField(
                            "Προετοιμασία",
                            editorState.prepMinutes,
                            { onStateChange(editorState.copy(prepMinutes = it, formMessage = null)) },
                            Modifier.weight(1f),
                        )
                        MinuteField(
                            "Μαγείρεμα",
                            editorState.cookMinutes,
                            { onStateChange(editorState.copy(cookMinutes = it, formMessage = null)) },
                            Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = editorState.servings,
                        onValueChange = {
                            onStateChange(editorState.copy(servings = it.take(100), formMessage = null))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Μερίδες / ποσότητα") },
                        singleLine = true,
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading("Φωτογραφία", Icons.Outlined.Restaurant)
                    RecipePhotoInput(
                        selectedPhotoPath = editorState.selectedPhotoPath,
                        retainedImageUrl = retainedImageUrl,
                        preparation = editorState.photoPreparation,
                        enabled = !isSaving,
                        onProcessingStarted = {
                            onStateChange(editorState.copy(photoPreparation = RecipePhotoPreparation.PROCESSING, formMessage = null))
                        },
                        onCancelPendingPhoto = {
                            onStateChange(editorState.copy(photoPreparation = RecipePhotoPreparation.READY, formMessage = null))
                        },
                        onImageChanged = { path ->
                            onStateChange(
                                editorState.copy(
                                    selectedPhotoPath = path,
                                    photoPreparation = RecipePhotoPreparation.READY,
                                    retainExistingPhoto = false,
                                    formMessage = null,
                                ),
                            )
                        },
                        onError = { onStateChange(editorState.copy(photoPreparation = RecipePhotoPreparation.FAILED, formMessage = it)) },
                    )
                }
            }
        }
        item {
            EditorCard(title = "Υλικά", count = editorState.ingredients.size) {
                editorState.ingredients.forEachIndexed { index, ingredient ->
                    DraftRow(
                        title = ingredient.title,
                        subtitle = listOf(ingredient.quantity, ingredient.unit).filter(String::isNotBlank).joinToString(" "),
                        onRemove = {
                            onStateChange(
                                editorState.copy(
                                    ingredients = editorState.ingredients.filterIndexed { current, _ ->
                                        current != index
                                    },
                                ),
                            )
                        },
                    )
                }
                OutlinedTextField(
                    value = editorState.ingredientTitle,
                    onValueChange = {
                        onStateChange(editorState.copy(ingredientTitle = it.take(200), formMessage = null))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Υλικό *") },
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editorState.ingredientQuantity,
                        onValueChange = {
                            onStateChange(editorState.copy(ingredientQuantity = it.take(100)))
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Ποσότητα") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = editorState.ingredientUnit,
                        onValueChange = {
                            onStateChange(editorState.copy(ingredientUnit = it.take(100)))
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Μονάδα") },
                        singleLine = true,
                    )
                }
                OutlinedButton(
                    onClick = {
                        val ingredient = editorState.ingredientTitle.trim()
                        if (ingredient.isNotBlank() && editorState.ingredients.size < 200) {
                            onStateChange(
                                editorState.copy(
                                    ingredients = editorState.ingredients + CustomIngredientDraftUi(
                                        title = ingredient,
                                        quantity = editorState.ingredientQuantity.trim(),
                                        unit = editorState.ingredientUnit.trim(),
                                    ),
                                    ingredientTitle = "",
                                    ingredientQuantity = "",
                                    ingredientUnit = "",
                                    formMessage = null,
                                ),
                            )
                        }
                    },
                    enabled = editorState.ingredientTitle.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("Προσθήκη υλικού", modifier = Modifier.padding(start = 7.dp))
                }
            }
        }
        item {
            EditorCard(title = "Εκτέλεση", count = editorState.steps.size) {
                editorState.steps.forEachIndexed { index, step ->
                    DraftRow(
                        title = "Βήμα ${index + 1}",
                        subtitle = step,
                        onRemove = {
                            onStateChange(
                                editorState.copy(
                                    steps = editorState.steps.filterIndexed { current, _ -> current != index },
                                ),
                            )
                        },
                    )
                }
                OutlinedTextField(
                    value = editorState.stepText,
                    onValueChange = {
                        onStateChange(editorState.copy(stepText = it.take(5_000), formMessage = null))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Νέο βήμα *") },
                    minLines = 3,
                )
                OutlinedButton(
                    onClick = {
                        val step = editorState.stepText.trim()
                        if (step.isNotBlank() && editorState.steps.size < 100) {
                            onStateChange(
                                editorState.copy(
                                    steps = editorState.steps + step,
                                    stepText = "",
                                    formMessage = null,
                                ),
                            )
                        }
                    },
                    enabled = editorState.stepText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("Προσθήκη βήματος", modifier = Modifier.padding(start = 7.dp))
                }
            }
        }
        item {
            editorState.formMessage?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(message, modifier = Modifier.fillMaxWidth().padding(14.dp))
                }
            }
        }
        item {
            Button(
                onClick = {
                    editorState.saveValidationMessage()?.let { validationMessage ->
                        onStateChange(editorState.copy(formMessage = validationMessage))
                    } ?: onSave()
                },
                enabled = !isSaving && editorState.photoPreparation == RecipePhotoPreparation.READY,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Text(if (isSaving) "Αποθήκευση…" else "Αποθήκευση συνταγής", modifier = Modifier.padding(start = 9.dp))
            }
        }
    }
}

@Composable
private fun EditorCard(
    title: String,
    count: Int,
    content: @Composable () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Text(count.toString(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
            content()
        }
    }
}

@Composable
private fun DraftRow(title: String, subtitle: String, onRemove: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(15.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 13.dp, top = 7.dp, bottom = 7.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Αφαίρεση $title")
            }
        }
    }
}

@Composable
private fun MinuteField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> if (input.all(Char::isDigit) && input.length <= 4) onValueChange(input) },
        modifier = modifier,
        label = { Text("$label (λεπτά)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun SectionHeading(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
