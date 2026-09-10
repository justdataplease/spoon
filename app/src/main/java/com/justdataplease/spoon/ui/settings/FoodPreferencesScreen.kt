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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.data.preferences.RecipePublisherOptions
import com.justdataplease.spoon.data.preferences.AllowedRecipePublisherKeys
import com.justdataplease.spoon.ui.components.PublisherBadge
import com.justdataplease.spoon.data.model.MealCourse
import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.domain.WeeklyPlanDefaults
import com.justdataplease.spoon.ui.components.GreekLocale
import java.time.DayOfWeek
import java.time.format.TextStyle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FavoriteBorder
import com.justdataplease.spoon.ui.components.ProviderLabelKind
import com.justdataplease.spoon.ui.components.greekProviderLabel
import com.justdataplease.spoon.ui.model.AvailableCategories
import com.justdataplease.spoon.ui.model.toDomainCategoryKey

private const val MAX_EXCLUDED_INGREDIENTS = 40

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FoodPreferencesScreen(
    settings: MealPreferenceSettings,
    ingredientOptions: List<String>,
    onBack: () -> Unit,
    onSave: (MealPreferenceSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var draft by remember(settings) { mutableStateOf(settings) }
    var defaultCourse by rememberSaveable { mutableStateOf(MealCourse.MAIN) }
    var ingredientInput by rememberSaveable { mutableStateOf("") }
    var ingredientMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val availableCategoryKeys = remember {
        AvailableCategories.map { category -> category.key.toDomainCategoryKey() }
    }

    fun selectIngredient(candidate: String) {
        if (draft.excludedIngredientTerms.size >= MAX_EXCLUDED_INGREDIENTS) return
        val canonicalIngredient = canonicalIngredientOption(ingredientOptions, candidate) ?: return
        draft = draft.copy(excludedIngredientTerms = draft.excludedIngredientTerms + canonicalIngredient)
        ingredientInput = ""
        ingredientMenuExpanded = false
        keyboardController?.hide()
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
                            Text("Το «Άλλο» εξαιρείται αρχικά", fontWeight = FontWeight.Bold)
                            Text(
                                "Οι αποκλεισμοί ισχύουν στην Εξερεύνηση, στη Συλλογή και στις νέες προτάσεις εβδομάδας.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            item {
                PreferenceCard(
                    title = "Πηγές συνταγών",
                    subtitle = "Όλες οι πηγές είναι αρχικά ενεργές. Αποεπίλεξε όσες δεν θέλεις στις αναζητήσεις και στις νέες προτάσεις σου.",
                    icon = Icons.Outlined.Public,
                ) {
                    val selectedCount = RecipePublisherOptions.count { it.key !in draft.excludedSourceKeys }
                    Text("$selectedCount από ${RecipePublisherOptions.size} ενεργές", style = MaterialTheme.typography.labelLarge)
                    FilterChip(
                        selected = draft.excludedSourceKeys.isEmpty(),
                        onClick = { draft = draft.copy(excludedSourceKeys = emptySet()) },
                        label = { Text("Όλες οι πηγές") },
                        leadingIcon = if (draft.excludedSourceKeys.isEmpty()) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                    )
                    RecipePublisherOptions.forEach { publisher ->
                        val selected = publisher.key !in draft.excludedSourceKeys
                        FilterChip(
                            selected = selected,
                            onClick = {
                                draft = draft.copy(excludedSourceKeys = if (selected) {
                                    draft.excludedSourceKeys + publisher.key
                                } else draft.excludedSourceKeys - publisher.key)
                            },
                            label = { Text(publisher.name) },
                            leadingIcon = {
                                PublisherBadge(sourceKey = publisher.key, sourceName = publisher.name,
                                    modifier = Modifier.size(24.dp))
                            },
                            trailingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (draft.excludedSourceKeys.containsAll(AllowedRecipePublisherKeys)) {
                        Text("Δεν έχεις επιλέξει καμία πηγή. Θα εμφανίζονται μόνο οι προσωπικές σου συνταγές, εφόσον ταιριάζουν στα υπόλοιπα φίλτρα.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                PreferenceCard(
                    title = "Προτάσεις εβδομάδας",
                    subtitle = "Διάλεξε από πού θα βρίσκουμε τις συνταγές σου.",
                    icon = Icons.Outlined.FavoriteBorder,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Μόνο από τη συλλογή", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Οι συνταγές μπορούν να επαναλαμβάνονται. Αν καμία δεν ταιριάζει στην κατηγορία και στα φίλτρα της ημέρας, θα εμφανίζεται μη διαθέσιμη συνταγή.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.favoritesOnly,
                            onCheckedChange = { draft = draft.copy(favoritesOnly = it) },
                        )
                    }
                }
            }

            item {
                PreferenceCard(
                    title = "Κατηγορία ανά ημέρα",
                    subtitle = "Διάλεξε προεπιλογές για κυρίως, συνοδευτικό και γλυκό. Η αποθήκευση ενημερώνει τα αντίστοιχα πιάτα της επιλεγμένης εβδομάδας, εκτός αν είναι κλειδωμένα ή μαγειρεμένα.",
                    icon = Icons.Outlined.CalendarMonth,
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MealCourse.entries.forEach { course ->
                            FilterChip(
                                selected = defaultCourse == course,
                                onClick = { defaultCourse = course },
                                label = { Text(course.label) },
                            )
                        }
                    }
                    DayOfWeek.entries.forEach { day ->
                        var expanded by remember { mutableStateOf(false) }
                        val category = WeeklyPlanDefaults.categoryFor(day, draft, defaultCourse)
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                        ) {
                            OutlinedTextField(
                                value = category.greekLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(day.getDisplayName(TextStyle.FULL, GreekLocale).replaceFirstChar { it.titlecase(GreekLocale) }) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                MealCategory.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.greekLabel) },
                                        onClick = {
                                            val overrides = when (defaultCourse) {
                                                MealCourse.MAIN -> draft.weekdayCategories
                                                MealCourse.SIDE -> draft.sideWeekdayCategories
                                                MealCourse.DESSERT -> draft.dessertWeekdayCategories
                                            } - day.name
                                            val updated = if (option == WeeklyPlanDefaults.categoryFor(day, course = defaultCourse)) {
                                                overrides
                                            } else overrides + (day.name to option.key)
                                            draft = when (defaultCourse) {
                                                MealCourse.MAIN -> draft.copy(weekdayCategories = updated)
                                                MealCourse.SIDE -> draft.copy(sideWeekdayCategories = updated)
                                                MealCourse.DESSERT -> draft.copy(dessertWeekdayCategories = updated)
                                            }
                                            expanded = false
                                        },
                                    )
                                }
                            }
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
                            onCheckedChange = { veganOnly ->
                                draft = draft.copy(
                                    veganOnly = veganOnly,
                                    excludedCategories = reconcileCategoryExclusionsForVegan(
                                        availableCategoryKeys = availableCategoryKeys,
                                        excludedCategoryKeys = draft.excludedCategories,
                                        veganOnly = veganOnly,
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            item {
                val includedKeys = includedCategoryKeys(
                    availableCategoryKeys = availableCategoryKeys,
                    excludedCategoryKeys = draft.excludedCategories,
                )
                val allCategoriesSelected = includedKeys == availableCategoryKeys.toSet()
                val compatibleCategoryKeys = if (draft.veganOnly) {
                    availableCategoryKeys.toSet() - VEGAN_INCOMPATIBLE_CATEGORY_KEYS
                } else {
                    availableCategoryKeys.toSet()
                }
                val includedCount = if (allCategoriesSelected) {
                    compatibleCategoryKeys.size
                } else {
                    includedKeys.count(compatibleCategoryKeys::contains)
                }
                PreferenceCard(
                    title = "Κατηγορίες που θέλεις",
                    subtitle = when {
                        draft.veganOnly && allCategoriesSelected ->
                            "Όλες οι συμβατές vegan κατηγορίες είναι ενεργές."
                        allCategoriesSelected -> "Όλες οι κατηγορίες είναι ενεργές."
                        else -> "$includedCount από ${compatibleCategoryKeys.size} ενεργές."
                    },
                    icon = Icons.Outlined.Category,
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        FilterChip(
                            selected = allCategoriesSelected,
                            onClick = {
                                draft = draft.copy(excludedCategories = emptySet())
                            },
                            label = { Text("Όλες") },
                            leadingIcon = if (allCategoriesSelected) {
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
                        AvailableCategories.forEach { category ->
                            val domainKey = category.key.toDomainCategoryKey()
                            val incompatibleWithVegan = draft.veganOnly &&
                                domainKey in VEGAN_INCOMPATIBLE_CATEGORY_KEYS
                            val included = !allCategoriesSelected && domainKey in includedKeys
                            FilterChip(
                                selected = included,
                                onClick = {
                                    draft = draft.copy(
                                        excludedCategories = toggleCategoryInclusions(
                                            availableCategoryKeys = availableCategoryKeys,
                                            excludedCategoryKeys = draft.excludedCategories,
                                            categoryKey = domainKey,
                                        ),
                                    )
                                },
                                enabled = !incompatibleWithVegan,
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
                    if (draft.veganOnly) {
                        Text(
                            "Κρέας, κοτόπουλο και ψάρι δεν είναι διαθέσιμα στη vegan επιλογή.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                PreferenceCard(
                    title = "Αποκλεισμένα υλικά",
                    subtitle = "Διάλεξε υλικά από τον υπάρχοντα κατάλογο συνταγών.",
                    icon = Icons.Outlined.Restaurant,
                ) {
                    val suggestions = ingredientSuggestions(
                        ingredientOptions = ingredientOptions,
                        excludedIngredients = draft.excludedIngredientTerms,
                        query = ingredientInput,
                    )
                    val canAddIngredient = ingredientOptions.isNotEmpty() &&
                        draft.excludedIngredientTerms.size < MAX_EXCLUDED_INGREDIENTS
                    val showIngredientMenu = ingredientMenuExpanded && suggestions.isNotEmpty()
                    ExposedDropdownMenuBox(
                        expanded = showIngredientMenu,
                        onExpandedChange = { expanded ->
                            if (canAddIngredient) ingredientMenuExpanded = expanded
                        },
                    ) {
                        OutlinedTextField(
                            value = ingredientInput,
                            onValueChange = { query ->
                                ingredientInput = query
                                ingredientMenuExpanded = true
                            },
                            modifier = Modifier
                                .menuAnchor(
                                    type = MenuAnchorType.PrimaryEditable,
                                    enabled = canAddIngredient,
                                )
                                .fillMaxWidth(),
                            enabled = canAddIngredient,
                            label = { Text("Αναζήτησε υλικό") },
                            placeholder = { Text("π.χ. κοτόπουλο") },
                            supportingText = {
                                Text(
                                    when {
                                        ingredientOptions.isEmpty() ->
                                            "Δεν υπάρχουν διαθέσιμα υλικά στον κατάλογο."
                                        !canAddIngredient ->
                                            "Έχεις φτάσει το όριο των $MAX_EXCLUDED_INGREDIENTS υλικών."
                                        else ->
                                            "${draft.excludedIngredientTerms.size}/$MAX_EXCLUDED_INGREDIENTS υλικά"
                                    },
                                )
                            },
                            singleLine = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = showIngredientMenu,
                                )
                            },
                        )
                        ExposedDropdownMenu(
                            expanded = showIngredientMenu,
                            onDismissRequest = { ingredientMenuExpanded = false },
                        ) {
                            suggestions.forEach { rawIngredient ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            greekProviderLabel(
                                                rawIngredient,
                                                ProviderLabelKind.INGREDIENT,
                                            ),
                                        )
                                    },
                                    onClick = { selectIngredient(rawIngredient) },
                                )
                            }
                        }
                    }

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
                                val displayIngredient = greekProviderLabel(
                                    ingredient,
                                    ProviderLabelKind.INGREDIENT,
                                )
                                InputChip(
                                    selected = true,
                                    onClick = {
                                        draft = draft.copy(
                                            excludedIngredientTerms = draft.excludedIngredientTerms - ingredient,
                                        )
                                    },
                                    label = { Text(displayIngredient) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = "Αφαίρεση $displayIngredient",
                                            modifier = Modifier.size(17.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item { AboutAppButton(modifier = Modifier.fillMaxWidth()) }
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
                        ingredientMenuExpanded = false
                    },
                    enabled = draft.hasActiveSelections() || ingredientInput.isNotBlank(),
                    modifier = Modifier.weight(1f).height(54.dp),
                ) {
                    Text("Επαναφορά όλων")
                }
                Button(
                    onClick = { onSave(draft) },
                    enabled = draft != settings,
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
