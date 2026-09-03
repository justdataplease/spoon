package com.justdataplease.spoon.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.justdataplease.spoon.ui.components.ProviderLabelKind
import com.justdataplease.spoon.ui.components.greekProviderLabel
import com.justdataplease.spoon.ui.model.RecipeDetailUi

@Composable
internal fun EquipmentSection(recipe: RecipeDetailUi) {
    BulletSection("Εξοπλισμός", Icons.Outlined.Kitchen, recipe.equipment)
}

private data class TaxonomyGroup(
    val label: String,
    val values: List<String>,
    val kind: ProviderLabelKind,
)

@Composable
internal fun TaxonomySection(recipe: RecipeDetailUi) {
    val groups = buildList {
        add(TaxonomyGroup("Ειδική διατροφή", recipe.dietLabels, ProviderLabelKind.DIET))
        add(TaxonomyGroup("Είδος γεύματος", recipe.mealTypeLabels, ProviderLabelKind.MEAL_TYPE))
        add(TaxonomyGroup("Περίσταση", recipe.occasionLabels, ProviderLabelKind.OCCASION))
        add(TaxonomyGroup("Τρόπος μαγειρέματος", recipe.methodLabels, ProviderLabelKind.METHOD))
        add(TaxonomyGroup("Κουζίνα / χώρα", recipe.cuisineLabels, ProviderLabelKind.CUISINE))
        add(TaxonomyGroup("Κύριο υλικό", recipe.ingredientLabels, ProviderLabelKind.INGREDIENT))
        add(TaxonomyGroup("Ετικέτες", recipe.tags.filterNot { it == "demo" }, ProviderLabelKind.TAG))
        if (recipe.quickRecipe) {
            add(TaxonomyGroup("Χρόνος", listOf("Γρήγορη συνταγή"), ProviderLabelKind.TAG))
        }
    }.map { group ->
        group.copy(
            values = group.values
                .filter(String::isNotBlank)
                .map { greekProviderLabel(it, group.kind) }
                .filter(String::isNotBlank)
                .distinct(),
        )
    }
        .filter { it.values.isNotEmpty() }
    if (groups.isEmpty()) return

    DetailSectionCard("Κατηγορίες & φίλτρα", Icons.Outlined.Public) {
        groups.forEach { group ->
            Text(group.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(group.values, key = { "${group.label}:$it" }) { label ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = CircleShape,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun CatalogDetailsSection(
    recipe: RecipeDetailUi,
    onOpenExternal: (String) -> Unit,
) {
    val entries = buildList {
        if (recipe.sourceRecipeId > 0) add("Κωδικός συνταγής" to recipe.sourceRecipeId.toString())
        if (recipe.slug.isNotBlank()) add("Σύντομο όνομα" to recipe.slug)
        if (recipe.categorySourceId > 0) add("Κωδικός κατηγορίας" to recipe.categorySourceId.toString())
        if (recipe.language.isNotBlank()) {
            add("Γλώσσα" to if (recipe.language == "el") "Ελληνικά" else recipe.language)
        }
        if (recipe.authorName.isNotBlank()) add("Δημιουργός" to recipe.authorName)
        if (recipe.publishedAt.isNotBlank()) add("Δημοσίευση" to recipe.publishedAt)
        add("Κατάσταση πηγής" to if (recipe.published) "Δημοσιευμένη" else "Μη δημοσιευμένη")
        if (recipe.createdAt.isNotBlank()) add("Δημιουργία" to recipe.createdAt)
        if (recipe.sourceUpdatedAt.isNotBlank()) add("Τελευταία ενημέρωση πηγής" to recipe.sourceUpdatedAt)
        if (recipe.shares > 0) add("Κοινοποιήσεις" to recipe.shares.toString())
    }
    val showSeoTitle = recipe.seoTitle.isNotBlank() && recipe.seoTitle != recipe.title
    val showSeoDescription = recipe.seoDescription.isNotBlank() && recipe.seoDescription != recipe.description
    val safeSponsorLogoUrl = normalizeRecipeLink(recipe.sponsorLogoUrl)
    if (entries.isEmpty() && !showSeoTitle && !showSeoDescription && safeSponsorLogoUrl == null) return

    DetailSectionCard("Στοιχεία συνταγής", Icons.Outlined.Badge) {
        entries.forEach { (label, value) -> MetadataRow(label, value) }
        if (showSeoTitle) MetadataRow("Τίτλος για μηχανές αναζήτησης", recipe.seoTitle)
        if (showSeoDescription) MetadataRow("Περιγραφή για μηχανές αναζήτησης", recipe.seoDescription)
        if (safeSponsorLogoUrl != null) {
            Text("Χορηγός", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AsyncImage(
                model = safeSponsorLogoUrl,
                contentDescription = "Λογότυπο χορηγού",
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )
            TextButton(onClick = { onOpenExternal(safeSponsorLogoUrl) }) {
                Text("Άνοιγμα λογοτύπου")
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp).size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.9f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.3f))
    }
}

@Composable
internal fun SourceSection(
    recipe: RecipeDetailUi,
    onOpenSource: () -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    val hasSafeSource = normalizeRecipeLink(recipe.sourceUrl) != null
    DetailSectionCard("Πηγή", Icons.Outlined.Info) {
        Text(
            recipe.sourceName.ifBlank { "Αρχική σελίδα συνταγής" },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Άνοιξε την αρχική δημοσίευση για επιπλέον πληροφορίες και ενημερώσεις της συνταγής.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onOpenSource,
            enabled = hasSafeSource,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text(if (hasSafeSource) "Άνοιγμα αρχικής συνταγής" else "Δεν υπάρχει ασφαλής διαθέσιμη πηγή")
            Spacer(Modifier.size(8.dp))
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
        }
        if (
            recipe.shortUrl.isNotBlank() &&
            recipe.shortUrl != recipe.sourceUrl &&
            normalizeRecipeLink(recipe.shortUrl) != null
        ) {
            TextButton(
                onClick = { onOpenExternal(recipe.shortUrl) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Άνοιγμα σύντομου συνδέσμου")
            }
        }
    }
}

@Composable
internal fun DetailSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = CircleShape,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.padding(8.dp).size(20.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            content()
        }
    }
}
