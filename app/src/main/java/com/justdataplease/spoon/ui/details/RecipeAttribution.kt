package com.justdataplease.spoon.ui.details

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.justdataplease.spoon.ui.model.RecipeDetailUi

@Composable
internal fun RecipeAttribution(recipe: RecipeDetailUi) {
    val sourceUrl = normalizeRecipeLink(recipe.sourceUrl)
    val notice = buildAnnotatedString {
        append("Η παρούσα εφαρμογή προορίζεται για προσωπική χρήση. Η πνευματική ιδιοκτησία της συνταγής ανήκει στους δημιουργούς και δικαιούχους της.")
        if (sourceUrl != null) {
            append(" Αν σου άρεσε η συνταγή, ")
            withLink(LinkAnnotation.Url(sourceUrl, TextLinkStyles(style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            )))) { append("επισκέψου τη σελίδα του δημιουργού") }
            append(".")
        }
    }
    Text(notice, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
