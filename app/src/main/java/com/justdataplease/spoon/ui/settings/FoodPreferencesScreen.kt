package com.justdataplease.spoon.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.ui.model.AvailableCategories
import com.justdataplease.spoon.ui.model.toDomainCategoryKey

private const val MIN_INGREDIENT_LENGTH = 2
private const val MAX_INGREDIENT_LENGTH = 60
private const val MAX_EXCLUDED_INGREDIENTS = 40

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoodPreferencesScreen(
    settings: MealPreferenceSettings,
    onBack: () -> Unit,
    onSave: (MealPreferenceSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var draft by remember(settings) { mutableStateOf(settings) }
    var ingredientInput by rememberSaveable { mutableStateOf("") }
    var ingredientError by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun addIngredient(): MealPreferenceSettings? {
        val ingredient = ingredientInput.trim().replace(Whitespace, " ")
        val validationError = when {
            ingredient.length < MIN_INGREDIENT_LENGTH -> "Γράψε τουλάχιστον 2 χαρακτήρες."
            ingredient.length > MAX_INGREDIENT_LENGTH -> "Το υλικό μπορεί να έχει έως 60 χαρακτήρες."
            draft.excludedIngredientTerms.size >= MAX_EXCLUDED_INGREDIENTS ->
                "Μπορείς να αποκλείσεις έως 40 υλικά."
            draft.excludedIngredientTerms.any { it.equals(ingredient, ignoreCase = true) } ->
                "Αυτό το υλικό υπάρχει ήδη."
            else -> null
        }
        ingredientError = validationError
        if (validationError != null) return null

        val updatedDraft = draft.copy(excludedIngredientTerms = draft.excludedIngredientTerms + ingredient)
        draft = updatedDraft
        ingredientInput = ""
        keyboardController?.hide()
        return updatedDraft
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Πίσω")
                    }
                    Column {
                        Text("Προτιμήσεις φαγητού", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Διάλεξε τι θέλεις να εμφανίζεται στις προτάσεις σου.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                            Icon(
                                Icons.Outlined.Tune,
                                contentDescription = null,
                                modifier = Modifier.padding(11.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Όλα περιλαμβάνονται αρχικά", fontWeight = FontWeight.Bold)
                            Text(
                                "Οι αποκλεισμοί ισχύουν στην Εξερεύνηση και στις νέες προτάσεις εβδομάδας.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            item {
                PreferenceCard(
                    title = "Τρόπος διατροφής",
                    subtitle = "Περιόρισε όλες τις νέες προτάσεις σε vegan επιλογές.",
                    icon = Icons.Outlined.Spa,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Μόνο vegan συνταγές", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (draft.veganOnly) "Ενεργό" else "Όλες οι διατροφικές επιλογές",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.veganOnly,
                            onCheckedChange = { draft = draft.copy(veganOnly = it) },
                        )
                    }
                }
            }

            item {
                val includedCount = AvailableCategories.count { category ->
                    category.key.toDomainCategoryKey() !in draft.excludedCategories
                }
                PreferenceCard(
                    title = "Κατηγορίες που επιτρέπονται",
                    subtitle = "Αποεπίλεξε όσες θέλεις να αποκλείσεις · $includedCount από ${AvailableCategories.size} ενεργές.",
                    icon = Icons.Outlined.Category,
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        AvailableCategories.forEach { category ->
                            val domainKey = category.key.toDomainCategoryKey()
                            val included = domainKey !in draft.excludedCategories
                            FilterChip(
                                selected = included,
                                onClick = {
                                    val exclusions = if (included) {
                                        draft.excludedCategories + domainKey
                                    } else {
                                        draft.excludedCategories - domainKey
                                    }
                                    draft = draft.copy(excludedCategories = exclusions)
                                },
                                label = { Text("${category.emoji} ${category.label}") },
                                leadingIcon = if (included) {
                                    {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            item {
                PreferenceCard(
                    title = "Αποκλεισμένα υλικά",
                    subtitle = "Δεν θα εμφανίζονται συνταγές που περιέχουν κάποιο από αυτά.",
                    icon = Icons.Outlined.Restaurant,
                ) {
                    OutlinedTextField(
                        value = ingredientInput,
                        onValueChange = {
                            ingredientInput = it
                            ingredientError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Υλικό προς αποκλεισμό") },
                        placeholder = { Text("π.χ. μανιτάρια") },
                        supportingText = {
                            Text(
                                ingredientError
                                    ?: "${draft.excludedIngredientTerms.size}/$MAX_EXCLUDED_INGREDIENTS υλικά",
                            )
                        },
                        isError = ingredientError != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addIngredient() }),
                        trailingIcon = {
                            IconButton(
                                onClick = { addIngredient() },
                                enabled = ingredientInput.isNotBlank(),
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = "Προσθήκη υλικού")
                            }
                        },
                    )

                    if (draft.excludedIngredientTerms.isEmpty()) {
                        Text(
                            "Δεν έχεις αποκλείσει κανένα υλικό.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        FlowRow(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            draft.excludedIngredientTerms.sorted().forEach { ingredient ->
                                InputChip(
                                    selected = true,
                                    onClick = {
                                        draft = draft.copy(
                                            excludedIngredientTerms = draft.excludedIngredientTerms - ingredient,
                                        )
                                    },
                                    label = { Text(ingredient) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = "Αφαίρεση $ingredient",
                                            modifier = Modifier.size(17.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 5.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        draft = MealPreferenceSettings()
                        ingredientInput = ""
                        ingredientError = null
                    },
                    enabled = draft.hasActiveSelections() || ingredientInput.isNotBlank(),
                    modifier = Modifier.weight(1f).height(54.dp),
                ) {
                    Text("Επαναφορά όλων")
                }
                Button(
                    onClick = {
                        if (ingredientInput.isBlank()) {
                            onSave(draft)
                        } else {
                            addIngredient()?.let(onSave)
                        }
                    },
                    enabled = draft != settings || ingredientInput.isNotBlank(),
                    modifier = Modifier.weight(1f).height(54.dp),
                ) {
                    Text("Αποθήκευση")
                }
            }
        }
    }
}

@Composable
private fun PreferenceCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(24.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

private val Whitespace = Regex("\\s+")
