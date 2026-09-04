package com.justdataplease.spoon.ui.details

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.data.model.RecipeNutritionSection
import com.justdataplease.spoon.ui.model.RecipeDetailUi

private data class NutritionRow(
    val label: String,
    val portion: String,
    val portionPercent: String,
    val per100g: String,
    val per100gPercent: String,
)

@Composable
internal fun NutritionSection(recipe: RecipeDetailUi) {
    val sections = recipe.nutritionSections
        .map { it.title to nutritionRows(it) }
        .filter { (_, rows) -> rows.isNotEmpty() }
    if (sections.isEmpty() && recipe.nutritionPer.isBlank()) return
    DetailSectionCard("Διατροφική αξία", Icons.Outlined.Equalizer) {
        if (recipe.nutritionPer.isNotBlank()) {
            Text(
                "Οι τιμές αφορούν ${recipe.nutritionPer}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sections.forEach { (title, rows) ->
            if (title.isNotBlank()) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            NutritionTable(rows)
        }
    }
}

@Composable
private fun NutritionTable(rows: List<NutritionRow>) {
    Row(Modifier.fillMaxWidth()) {
        Text("Στοιχείο", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1.2f))
        Text("Ανά μερίδα", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text("Ανά 100 γρ.", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
    }
    HorizontalDivider()
    rows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(row.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.2f))
            Text(
                nutritionValue(row.portion, row.portionPercent),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                nutritionValue(row.per100g, row.per100gPercent),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun nutritionValue(value: String, percent: String): String = when {
    value.isNotBlank() && percent.isNotBlank() -> "$value  ($percent)"
    value.isNotBlank() -> value
    percent.isNotBlank() -> percent
    else -> "—"
}

private fun nutritionRows(section: RecipeNutritionSection): List<NutritionRow> = listOf(
    NutritionRow("Ενέργεια", section.kcalPortion, section.kcalPortionPercent, section.kcal100g, section.kcal100gPercent),
    NutritionRow("Λιπαρά", section.fatPortion, section.fatPortionPercent, section.fat100g, section.fat100gPercent),
    NutritionRow(
        "Κορεσμένα λιπαρά",
        section.saturatedFatPortion,
        section.saturatedFatPortionPercent,
        section.saturatedFat100g,
        section.saturatedFat100gPercent,
    ),
    NutritionRow("Υδατάνθρακες", section.carbsPortion, section.carbsPortionPercent, section.carbs100g, section.carbs100gPercent),
    NutritionRow("Σάκχαρα", section.sugarsPortion, section.sugarsPortionPercent, section.sugars100g, section.sugars100gPercent),
    NutritionRow("Πρωτεΐνη", section.proteinPortion, section.proteinPortionPercent, section.protein100g, section.protein100gPercent),
    NutritionRow("Φυτικές ίνες", section.fiberPortion, section.fiberPortionPercent, section.fiber100g, section.fiber100gPercent),
    NutritionRow("Νάτριο", section.sodiumPortion, section.sodiumPortionPercent, section.sodium100g, section.sodium100gPercent),
).filter { row ->
    listOf(row.portion, row.portionPercent, row.per100g, row.per100gPercent).any(String::isNotBlank)
}
