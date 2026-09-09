package com.justdataplease.spoon.ui.components

import com.justdataplease.spoon.R
import java.net.URI
import java.text.Normalizer
import java.util.Locale

internal fun publisherIconResource(
    sourceKey: String,
    sourceName: String,
    sourceUrl: String,
): Int? {
    // Prefer the catalog identity; recipe titles in a URL may mention another publisher.
    publisherKeyIcon(sourceKey.trim().lowercase(Locale.ROOT))?.let { return it }
    val host = runCatching {
        val url = sourceUrl.trim()
        URI(if ("://" in url || url.startsWith("//")) url else "//$url")
            .host?.lowercase(Locale.ROOT)
    }.getOrNull()
    when {
        host.isPublisherDomain("akispetretzikis.com") -> return R.drawable.source_akis
        host.isPublisherDomain("argiro.gr") -> return R.drawable.source_argiro
        host.isPublisherDomain("gastronomos.gr") -> return R.drawable.source_gastronomos
    }

    val name = Normalizer.normalize(sourceName, Normalizer.Form.NFD)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .lowercase(Locale.ROOT)
        .replace('ς', 'σ')
    publisherKeyIcon(name.trim())?.let { return it }
    val words = name.split(PublisherWordSeparator).toSet()
    return when {
        words.any { it in setOf("argiro", "αργυρω") } -> R.drawable.source_argiro
        words.any { it in setOf("gastronomos", "γαστρονομοσ") } -> R.drawable.source_gastronomos
        words.any { it in setOf("akis", "petretzikis", "ακισ", "πετρετζικησ") } -> R.drawable.source_akis
        else -> null
    }
}

private fun publisherKeyIcon(key: String): Int? = when (key.removePrefix("www.")) {
    "akis", "akispetretzikis.com" -> R.drawable.source_akis
    "argiro", "argiro.gr" -> R.drawable.source_argiro
    "gastronomos", "gastronomos.gr" -> R.drawable.source_gastronomos
    else -> null
}

private fun String?.isPublisherDomain(domain: String): Boolean =
    this == domain || this?.endsWith(".$domain") == true

private val PublisherWordSeparator = Regex("[^\\p{L}]+")
