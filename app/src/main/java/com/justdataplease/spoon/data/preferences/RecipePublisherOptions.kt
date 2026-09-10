package com.justdataplease.spoon.data.preferences

import java.util.Locale

/** Public publisher choices; absence from the exclusions keeps new sources enabled on upgrade. */
internal data class RecipePublisherOption(val key: String, val name: String, val websiteUrl: String)

internal val RecipePublisherOptions = listOf(
    RecipePublisherOption("akis", "Άκης Πετρετζίκης", "https://akispetretzikis.com/"),
    RecipePublisherOption("argiro", "Αργυρώ Μπαρμπαρίγου", "https://www.argiro.gr/"),
    RecipePublisherOption("gastronomos", "Γαστρονόμος", "https://www.gastronomos.gr/"),
    RecipePublisherOption("tsoulis", "Γιώργος Τσούλης", "https://www.giorgostsoulis.com/"),
    RecipePublisherOption("cookpad", "Cookpad", "https://cookpad.com/gr"),
    RecipePublisherOption("lucacos", "Γιάννης Λουκάκος", "https://www.yiannislucacos.gr/"),
    RecipePublisherOption("funkycook", "Funky Cook", "https://funkycook.gr/"),
)

internal val AllowedRecipePublisherKeys = RecipePublisherOptions.mapTo(linkedSetOf()) { it.key }

internal fun Set<String>.canonicalExcludedSourceKeys(): Set<String> = asSequence()
    .map { it.trim().lowercase(Locale.ROOT) }
    .filter(AllowedRecipePublisherKeys::contains)
    .toCollection(linkedSetOf())
