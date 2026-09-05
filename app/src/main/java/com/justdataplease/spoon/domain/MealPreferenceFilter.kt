package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.expandedIngredientAliasTokens
import com.justdataplease.spoon.data.local.normalizedCatalogToken
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings

/** Global discovery rules. Saved recipes and history deliberately bypass these rules. */
internal fun Recipe.matchesMealPreferences(settings: MealPreferenceSettings): Boolean {
    val excludedCategories = settings.excludedCategories
        .map(String::normalizedCatalogToken)
        .filter(String::isNotBlank)
        .toSet()
    if (category.normalizedCatalogToken() in excludedCategories) return false

    if (settings.veganOnly && !isStrictlyVegan()) return false

    val excludedIngredients = settings.excludedIngredientTerms.asSequence()
        .flatMap { ingredient -> expandedIngredientAliasTokens(ingredient).asSequence() }
        .distinct()
        .toList()
    if (excludedIngredients.isEmpty()) return true

    val ingredientTexts = sequence {
        yieldAll(ingredientLabels)
        ingredientSections.forEach { section ->
            section.ingredients.forEach { ingredient ->
                yield(listOf(ingredient.title, ingredient.info).joinToString(" "))
            }
        }
    }.map(String::normalizedCatalogToken)
        .filter(String::isNotBlank)
        .toList()

    return excludedIngredients.none { excluded ->
        ingredientTexts.any { ingredient -> ingredient.contains(excluded) }
    }
}

private fun Recipe.isStrictlyVegan(): Boolean {
    if (category.normalizedCatalogToken() in ANIMAL_CATEGORIES) return false
    if (dietLabels.none(String::isRecognizedVeganDietLabel)) return false

    if (ingredientLabels.any { it.containsAnimalDerivedIngredient() }) return false
    return ingredientSections.none { section ->
        section.ingredients.any { ingredient ->
            ingredient.title.containsAnimalDerivedIngredient(ingredient.info)
        }
    }
}

internal fun String.isRecognizedVeganDietLabel(): Boolean =
    normalizedCatalogToken() in RECOGNIZED_VEGAN_LABELS

internal fun String.containsAnimalDerivedIngredient(info: String = ""): Boolean {
    val ingredientText = listOf(this, info)
        .filter(String::isNotBlank)
        .joinToString(" ")
    return ALTERNATIVE_SEPARATOR.split(ingredientText).any(String::containsAnimalDerivedClause)
}

/** Mirrors build_local_catalog.py; CatalogContractDeviceTest checks every bundled row. */
private fun String.containsAnimalDerivedClause(): Boolean {
    val words = normalizedCatalogToken().split(' ').filter(String::isNotBlank)
    return words.indices.any { index ->
        val kind = animalKind(words[index], words.getOrNull(index - 1)) ?: return@any false
        val nearby = words.subList(maxOf(0, index - 3), minOf(words.size, index + 4))
        val directlyNegated = words.getOrNull(index - 1)?.hasStem(NEGATION_STEMS) == true
        val negatedKind = if (kind == "pesto") "cheese" else kind
        val relevantKindNegated = words.indices.any { other ->
            other > 0 && words[other - 1].hasStem(NEGATION_STEMS) &&
                animalKind(words[other], words[other - 1]) == negatedKind
        }
        val explicitlyVegan = nearby.any { it.hasStem(UNIVERSAL_VEGAN_QUALIFIER_STEMS) }
        val plantBases = when (kind) {
            "milk", "cheese", "yogurt", "dairy" -> PLANT_DAIRY_BASE_STEMS
            "butter" -> PLANT_BUTTER_BASE_STEMS
            "broth" -> PLANT_BROTH_BASE_STEMS
            else -> emptySet()
        }
        val hasPlantBase = nearby.any { it.hasStem(plantBases) }
        !(directlyNegated || relevantKindNegated || explicitlyVegan || hasPlantBase)
    }
}

private fun String.hasStem(stems: Set<String>): Boolean = stems.any(::startsWith)

private fun animalKind(token: String, previous: String?): String? =
    ANIMAL_EXACT_TOKENS.entries.firstOrNull { (kind, exact) ->
        // "σε φέτα" describes a slice, rather than feta cheese.
        !(kind == "cheese" && token in setOf("φετα", "φετασ") && previous == "σε") &&
            (token in exact || token.hasStem(ANIMAL_TOKEN_STEMS[kind].orEmpty()))
    }?.key

private val ALTERNATIVE_SEPARATOR = Regex("""(?<![\p{L}\p{N}_])(?:ή|or)(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)

private val RECOGNIZED_VEGAN_LABELS = setOf("vegan", "αυστηρα χορτοφαγικη vegan")
private val ANIMAL_CATEGORIES = setOf("fish", "meat", "poultry")

private val ANIMAL_EXACT_TOKENS = mapOf(
    "meat" to setOf(
        "bacon", "beef", "chicken", "duck", "goat", "ham", "lamb", "meat", "pork", "sausage",
        "turkey", "veal", "κοτα", "κοτασ", "κοτεσ", "κοτων", "κρεασ", "κρεατα", "κρεατοσ",
    ),
    "fish" to setOf(
        "anchovies", "anchovy", "cod", "crab", "fish", "herring", "lobster", "mussel", "mussels",
        "octopus", "oyster", "oysters", "prawn", "prawns", "salmon", "sardine", "sardines",
        "seafood", "shrimp", "shrimps", "squid", "trout", "tuna", "καβουρι", "καβουρια",
        "καβουριου", "μυδι", "μυδια", "μυδιων", "σουπια", "τονο", "τονοσ", "τονου", "τσιπουρα",
        "ψαρι", "ψαρια", "ψαριου", "ψαριων",
    ),
    "egg" to setOf(
        "egg", "eggs", "αυγα", "αυγο", "αυγου", "αυγων",
    ),
    "milk" to setOf(
        "casein", "dairy", "milk", "whey", "γαλα", "γαλακτοσ", "γαλατα",
    ),
    "cheese" to setOf(
        "brie", "cheddar", "cheese", "cheeses", "feta", "gouda", "halloumi", "mascarpone",
        "mozzarella", "parmesan", "ricotta", "τυρι", "τυρια", "τυριου", "τυριων", "φετα", "φετασ",
    ),
    "butter" to setOf(
        "butter", "βουτυρα", "βουτυρο", "βουτυρου",
    ),
    "yogurt" to setOf(
        "yoghurt", "yogurt",
    ),
    "dairy" to setOf(
        "cream", "creme", "smetana", "κεφιρ", "κρεμα", "κρεμασ", "ξινοκρεμα", "σαντιγι",
    ),
    "honey" to setOf(
        "honey", "μελι", "μελιου",
    ),
    "broth" to setOf(
        "bouillon", "broth", "stock",
    ),
    "pesto" to setOf(
        "pesto", "πεστο",
    ),
    "other_animal_product" to setOf(
        "gelatin", "gelatine", "lard",
    ),
)

private val ANIMAL_TOKEN_STEMS = mapOf(
    "meat" to setOf(
        "αρνι", "βοδιν", "γαλοπουλ", "ζαμπον", "κατσικ", "κοκορ", "κοτοπουλ", "κουνελ", "λαρδι",
        "λουκανικ", "μορταδελ", "μοσχαρ", "πανσετ", "παπι", "προσιουτ", "προσουτ", "σαλαμι",
        "χοιριν",
    ),
    "fish" to setOf(
        "worcester", "αθεριν", "αντζουγ", "αστακ", "αυγοταραχ", "γαριδ", "γαυρ", "καλαμαρ",
        "καραβιδ", "κουτσομουρ", "λαβρακ", "μπακαλιαρ", "παλαμιδ", "πεστροφ", "ρεγγ", "σαρδελ",
        "σκουμπρ", "σολομ", "σουριμ", "στρειδ", "συναγριδ", "ταραμ", "χαβιαρ", "χταποδ",
    ),
    "egg" to setOf(
        "aioli", "mayo", "mayonnaise", "αβγ", "αγιολι", "αυγ", "μαγιονεζ",
    ),
    "cheese" to setOf(
        "ανθοτυρ", "γαλοτυρ", "γκουντ", "γραβιερ", "κασερ", "κασσερ", "κεφαλοτυρ", "μανουρ",
        "μασκαρπον", "μοτσαρελ", "μυζηθρ", "ξινομυζηθρ", "παρμεζ", "πεκοριν", "ρικοτ", "ροκφορ",
        "χαλουμ",
    ),
    "yogurt" to setOf(
        "γιαουρτ",
    ),
    "broth" to setOf(
        "ζωμ",
    ),
    "other_animal_product" to setOf(
        "ζελατιν", "λαρδι",
    ),
)

private val UNIVERSAL_VEGAN_QUALIFIER_STEMS = setOf(
    "vegan", "νηστει", "νηστισιμ", "φυτικ",
)

private val NEGATION_STEMS = setOf(
    "no", "without", "διχωσ", "χωρισ",
)

private val PLANT_DAIRY_BASE_STEMS = setOf(
    "almond", "cashew", "coconut", "hazelnut", "hemp", "macadamia", "oat", "pea", "rice", "soy",
    "soya", "αμυγδαλ", "βρωμη", "κανναβ", "καρυδ", "κασι", "μακανταμ", "μπιζελ", "ρυζ", "σογι",
    "φουντουκ",
)

private val PLANT_BUTTER_BASE_STEMS = setOf(
    "almond", "cashew", "cocoa", "coconut", "hazelnut", "peanut", "sesame", "tahini", "αμυγδαλ",
    "ελαιολ", "κακαο", "καρυδ", "κασι", "ξηρων", "σησαμ", "ταχιν", "φιστικ", "φουντουκ", "φυστικ",
)

private val PLANT_BROTH_BASE_STEMS = setOf(
    "mushroom", "vegetable", "veggie", "λαχανικ", "μανιταρ",
)
