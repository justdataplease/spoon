package com.justdataplease.spoon.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalDining
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.data.model.RecipeIngredient
import com.justdataplease.spoon.ui.model.RecipeDetailUi

@Composable
internal fun IngredientsSection(
    recipe: RecipeDetailUi,
    onOpenExternal: (String) -> Unit,
) {
    val sections = recipe.ingredientSections.filter { it.ingredients.isNotEmpty() }
    if (sections.isEmpty()) return
    DetailSectionCard("Υλικά", Icons.Outlined.Restaurant) {
        sections.forEachIndexed { sectionIndex, section ->
            if (section.title.isNotBlank()) {
                Text(section.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            section.ingredients.forEachIndexed { ingredientIndex, ingredient ->
                IngredientRow(ingredient, onOpenExternal)
                if (ingredientIndex < section.ingredients.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (sectionIndex < sections.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun IngredientRow(
    ingredient: RecipeIngredient,
    onOpenExternal: (String) -> Unit,
) {
    val amount = listOf(ingredient.quantity, ingredient.unit).filter(String::isNotBlank).joinToString(" ")
    val uk = listOf(ingredient.ukQuantity, ingredient.ukUnit).filter(String::isNotBlank).joinToString(" ")
    val us = listOf(ingredient.usQuantity, ingredient.usUnit).filter(String::isNotBlank).joinToString(" ")
    val links = listOf(
        "Σχετικό" to ingredient.internalLink,
        "Εξωτερικό" to ingredient.externalLink,
    ).filter { (_, link) -> normalizeRecipeLink(link) != null }.distinctBy { it.second }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (amount.isNotBlank()) {
                Text(amount, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (ingredient.title.isNotBlank()) {
                Text(ingredient.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            if (ingredient.info.isNotBlank()) {
                Text(
                    ingredient.info,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val conversions = buildList {
                if (uk.isNotBlank()) add("Ην. Βασίλειο: $uk")
                if (us.isNotBlank()) add("ΗΠΑ: $us")
            }
            if (conversions.isNotEmpty()) {
                Text(
                    conversions.joinToString("  •  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (links.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    links.forEach { (label, link) ->
                        TextButton(
                            onClick = { onOpenExternal(link) },
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        ) {
                            Text(label)
                            Icon(
                                Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 4.dp).size(15.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MethodSection(recipe: RecipeDetailUi) {
    val sections = recipe.methodSections.filter { it.steps.any(String::isNotBlank) }
    if (sections.isEmpty()) return
    DetailSectionCard("Εκτέλεση", Icons.AutoMirrored.Outlined.MenuBook) {
        var runningStep = 1
        sections.forEachIndexed { sectionIndex, section ->
            if (section.title.isNotBlank()) {
                Text(section.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            section.steps.filter(String::isNotBlank).forEach { step ->
                MethodStep(number = runningStep++, text = step)
            }
            if (sectionIndex < sections.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MethodStep(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = CircleShape,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(top = 4.dp),
        )
    }
}

@Composable
internal fun AdviceSections(recipe: RecipeDetailUi) {
    BulletSection("Μυστικά επιτυχίας", Icons.Outlined.Lightbulb, recipe.tips)
    BulletSection("Διατροφικές συμβουλές", Icons.Outlined.LocalDining, recipe.nutritionTips)
    BulletSection("Σημειώσεις", Icons.Outlined.Info, recipe.notes)
}

@Composable
internal fun BulletSection(title: String, icon: ImageVector, entries: List<String>) {
    val content = entries.filter(String::isNotBlank).distinct()
    if (content.isEmpty()) return
    DetailSectionCard(title, icon) {
        content.forEach { text ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text("•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}
