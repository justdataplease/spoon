package com.justdataplease.spoon.ui.components

import com.justdataplease.spoon.R
import com.justdataplease.spoon.data.preferences.RecipePublisherOption
import com.justdataplease.spoon.data.preferences.RecipePublisherOptions
import java.net.URI
import java.text.Normalizer
import java.util.Locale

internal fun publisherIconResource(
    sourceKey: String,
    sourceName: String,
    sourceUrl: String,
): Int? = recipePublisher(sourceKey, sourceName, sourceUrl)?.let { publisherKeyIcon(it.key) }

/** Resolve the same trusted publisher identity for its artwork and website destination. */
internal fun recipePublisher(
    sourceKey: String,
    sourceName: String,
    sourceUrl: String,
): RecipePublisherOption? {
    val key = sourceKey.trim().lowercase(Locale.ROOT)
    if (key == "personal" || key == "custom") return null
    // Prefer the catalog identity; recipe titles in a URL may mention another publisher.
    publisherForKey(key)?.let { return it }
    val host = runCatching {
        val url = sourceUrl.trim()
        URI(if ("://" in url || url.startsWith("//")) url else "//$url")
            .host?.lowercase(Locale.ROOT)
    }.getOrNull()
    RecipePublisherOptions.firstOrNull { publisher ->
        val domain = URI(publisher.websiteUrl).host.removePrefix("www.")
        host == domain || host?.endsWith(".$domain") == true
    }?.let { return it }

    val name = Normalizer.normalize(sourceName, Normalizer.Form.NFD)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .lowercase(Locale.ROOT)
        .replace('ς', 'σ')
    publisherForKey(name.trim())?.let { return it }
    val words = name.split(PublisherWordSeparator).toSet()
    val nameKey = when {
        words.any { it in setOf("argiro", "αργυρω") } -> "argiro"
        words.any { it in setOf("gastronomos", "γαστρονομοσ") } -> "gastronomos"
        words.any { it in setOf("akis", "petretzikis", "ακισ", "πετρετζικησ") } -> "akis"
        words.any { it in setOf("tsoulis", "τσουλησ") } -> "tsoulis"
        words.any { it in setOf("lucacos", "λουκακοσ") } -> "lucacos"
        "funkycook" in words || ("funky" in words && "cook" in words) -> "funkycook"
        "cookpad" in words -> "cookpad"
        else -> null
    }
    return RecipePublisherOptions.firstOrNull { it.key == nameKey }
}

private fun publisherForKey(key: String): RecipePublisherOption? =
    RecipePublisherOptions.firstOrNull {
        it.key == key || URI(it.websiteUrl).host.removePrefix("www.") == key.removePrefix("www.")
    }

private fun publisherKeyIcon(key: String): Int? = when (key) {
    "akis" -> R.drawable.source_akis
    "argiro" -> R.drawable.source_argiro
    "gastronomos" -> R.drawable.source_gastronomos
    "tsoulis" -> R.drawable.source_tsoulis
    "lucacos" -> R.drawable.source_lucacos
    "funkycook" -> R.drawable.source_funkycook
    "cookpad" -> R.drawable.source_cookpad
    else -> null
}

private val PublisherWordSeparator = Regex("[^\\p{L}]+")
