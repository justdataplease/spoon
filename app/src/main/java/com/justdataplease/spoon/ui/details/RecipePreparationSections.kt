package com.justdataplease.spoon.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalDining
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.data.model.RecipeIngredient
import com.justdataplease.spoon.ui.model.RecipeDetailUi
import com.justdataplease.spoon.ui.shopping.ShoppingIngredientDraftUi

@Composable
internal fun IngredientsSection(
    recipe: RecipeDetailUi,
    onAddIngredients: (List<ShoppingIngredientDraftUi>) -> Unit,
) {
    val sections = recipe.ingredientSections.filter { it.ingredients.isNotEmpty() }
    if (sections.isEmpty()) return
    val shoppingIngredients = recipe.shoppingIngredientDrafts()
    DetailSectionCard("Υλικά", Icons.Outlined.Restaurant) {
        if (shoppingIngredients.isNotEmpty()) {
            Button(
                onClick = { onAddIngredients(shoppingIngredients) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.ShoppingBasket, contentDescription = null)
                Text("Προσθήκη όλων στη λίστα", modifier = Modifier.padding(start = 8.dp))
            }
        }
        sections.forEachIndexed { sectionIndex, section ->
            if (section.title.isNotBlank()) {
                Text(section.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            section.ingredients.forEachIndexed { ingredientIndex, ingredient ->
                val draft = ingredient.toShoppingDraft(recipe.recipeId, recipe.title)
                IngredientRow(
                    ingredient = ingredient,
                    onAdd = draft?.let { { onAddIngredients(listOf(it)) } },
                )
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
    onAdd: (() -> Unit)?,
) {
    val primaryLabel = ingredient.primaryLabel()
    val uk = listOf(ingredient.ukQuantity, ingredient.ukUnit).filter(String::isNotBlank).joinToString(" ")
    val us = listOf(ingredient.usQuantity, ingredient.usUnit).filter(String::isNotBlank).joinToString(" ")

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
            if (primaryLabel.isNotBlank()) {
                Text(
                    primaryLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
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
        }
        if (onAdd != null) {
            androidx.compose.material3.IconButton(onClick = onAdd) {
                Icon(
                    Icons.Outlined.ShoppingBasket,
                    contentDescription = "Προσθήκη ${ingredient.title} στη λίστα αγορών",
                )
            }
        }
    }
}

internal fun RecipeIngredient.primaryLabel(): String = buildList {
    listOf(quantity.trim(), unit.trim())
        .filter(String::isNotBlank)
        .joinToString(" ")
        .takeIf(String::isNotBlank)
        ?.let(::add)
    title.trim().takeIf(String::isNotBlank)?.let(::add)
}.joinToString("  ")

internal fun RecipeDetailUi.shoppingIngredientDrafts(): List<ShoppingIngredientDraftUi> =
    ingredientSections.flatMap { section ->
        section.ingredients.mapNotNull { ingredient -> ingredient.toShoppingDraft(recipeId, title) }
    }

private fun RecipeIngredient.toShoppingDraft(
    recipeId: String,
    recipeTitle: String,
): ShoppingIngredientDraftUi? = title.trim().takeIf(String::isNotBlank)?.let { safeTitle ->
    ShoppingIngredientDraftUi(
        recipeId = recipeId,
        recipeTitle = recipeTitle,
        title = safeTitle,
        quantity = quantity.trim(),
        unit = unit.trim(),
        info = info.trim(),
    )
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
