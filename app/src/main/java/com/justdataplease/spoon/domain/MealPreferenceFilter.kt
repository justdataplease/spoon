package com.justdataplease.spoon.domain

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

    val excludedIngredients = settings.excludedIngredientTerms
        .map(String::normalizedCatalogToken)
        .filter(String::isNotBlank)
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

private fun String.containsAnimalDerivedClause(): Boolean {
    val localText = normalizedCatalogToken()
    val words = localText.split(' ').filter(String::isNotBlank)
    if (words.isEmpty()) return false

    fun hasAnyPrefix(prefixes: Set<String>): Boolean =
        words.any { word -> prefixes.any(word::startsWith) }

    val hasVeganQualifier = hasAnyPrefix(VEGAN_QUALIFIER_PREFIXES)
    if (hasAnyPrefix(FLESH_AND_SEAFOOD_PREFIXES)) return true

    val hasEggProduct = hasAnyPrefix(GREEK_EGG_PREFIXES) ||
        hasAnyPrefix(MAYONNAISE_PREFIXES) ||
        words.any { it == "egg" || it == "eggs" }
    if (hasEggProduct &&
        !hasVeganQualifier &&
        !localText.containsAnyPhrase(WITHOUT_EGG_PHRASES)
    ) return true

    if (hasAnyPrefix(MILK_PREFIXES) &&
        !localText.containsAnyPhrase(PLANT_MILK_PHRASES)
    ) return true
    if ((hasAnyPrefix(CHEESE_PREFIXES) || words.any(DIRECT_CHEESE_WORDS::contains)) &&
        !localText.containsAnyPhrase(PLANT_CHEESE_PHRASES) &&
        !localText.containsAnyPhrase(WITHOUT_CHEESE_PHRASES)
    ) return true
    if (hasAnyPrefix(BUTTER_PREFIXES) &&
        !localText.containsAnyPhrase(PLANT_BUTTER_PHRASES)
    ) return true
    if (hasAnyPrefix(YOGURT_PREFIXES) &&
        !localText.containsAnyPhrase(PLANT_YOGURT_PHRASES)
    ) return true
    if (hasAnyPrefix(CREAM_PREFIXES) &&
        !localText.containsAnyPhrase(PLANT_CREAM_PHRASES)
    ) return true

    if (hasAnyPrefix(BROTH_PREFIXES) &&
        !hasVeganQualifier &&
        !hasAnyPrefix(PLANT_BROTH_PREFIXES)
    ) return true
    if (hasAnyPrefix(PESTO_PREFIXES) &&
        !hasVeganQualifier &&
        !localText.containsAnyPhrase(WITHOUT_CHEESE_PHRASES)
    ) return true

    return words.any { it == "μελι" || it == "μελιου" || it == "honey" } ||
        hasAnyPrefix(OTHER_ANIMAL_PRODUCT_PREFIXES)
}

private fun String.containsAnyPhrase(phrases: Set<String>): Boolean {
    val padded = " $this "
    return phrases.any { phrase -> " $phrase " in padded }
}

private val RECOGNIZED_VEGAN_LABELS = setOf(
    "vegan",
    "αυστηρα χορτοφαγικη vegan",
)

private val ANIMAL_CATEGORIES = setOf("meat", "poultry", "fish")

private val ALTERNATIVE_SEPARATOR = Regex("""\b(?:ή|or)\b""", RegexOption.IGNORE_CASE)

private val FLESH_AND_SEAFOOD_PREFIXES = setOf(
    "κρεασ", "κρεατ", "μοσχαρ", "βοδιν", "χοιριν", "αρνι", "κατσικ",
    "κοτοπουλ", "κοτα", "γαλοπουλ", "παπια", "μπεικον", "ζαμπον", "λουκανικ",
    "ψαρ", "σολομ", "τονοσ", "τονου", "μπακαλιαρ", "γαυρ", "σαρδελ", "αντζουγ", "πεστροφ", "ρεγγ",
    "χταποδ", "καλαμαρ", "γαριδ", "καβουρ", "μυδι", "στρειδ", "σουπι", "αστακ",
    "θαλασσιν", "meat", "beef", "veal", "pork", "lamb", "goat", "chicken",
    "turkey", "duck", "bacon", "ham", "sausage", "fish", "salmon", "tuna",
    "cod", "anchov", "sardine", "trout", "octopus", "squid", "shrimp", "prawn",
    "crab", "mussel", "oyster", "lobster", "seafood", "herring", "worcester",
)

private val GREEK_EGG_PREFIXES = setOf("αυγ", "αβγ")
private val MAYONNAISE_PREFIXES = setOf("μαγιονεζ", "mayonnaise", "mayo", "αγιολι", "aioli")
private val VEGAN_QUALIFIER_PREFIXES = setOf("φυτικ", "vegan", "νηστισιμ", "νηστει")
private val WITHOUT_EGG_PHRASES = setOf(
    "χωρισ αυγο", "χωρισ αυγα", "χωρισ αβγο", "χωρισ αβγα", "without egg", "without eggs",
    "eggless",
)

private val MILK_PREFIXES = setOf("γαλα", "γαλακτ", "milk", "dairy")
private val CHEESE_PREFIXES = setOf(
    "τυρ", "παρμεζαν", "μοτσαρελ", "γραβιερα", "κασερι", "κεφαλοτυρ", "μανουρ",
    "ανθοτυρ", "μυζηθρ", "ρικοτα", "cheese", "parmesan", "mozzarella", "cheddar",
    "ricotta",
)
private val DIRECT_CHEESE_WORDS = setOf("φετα", "φετασ", "feta")
private val BUTTER_PREFIXES = setOf("βουτυρ", "butter")
private val YOGURT_PREFIXES = setOf("γιαουρτ", "yogurt", "yoghurt")
private val CREAM_PREFIXES = setOf("κρεμα", "cream", "whey", "casein")
private val BROTH_PREFIXES = setOf("ζωμ", "broth", "stock", "bouillon")
private val PLANT_BROTH_PREFIXES = setOf(
    "λαχανικ", "μανιταρ", "vegetable", "veggie", "mushroom",
)
private val PESTO_PREFIXES = setOf("πεστο", "pesto")
private val WITHOUT_CHEESE_PHRASES = setOf(
    "χωρισ τυρι", "χωρισ παρμεζανα", "διχωσ τυρι", "without cheese", "no cheese",
)

private val PLANT_MILK_PHRASES = setOf(
    "γαλα καρυδασ", "γαλα σογιασ", "γαλα βρωμησ", "γαλα αμυγδαλου", "γαλα ρυζιου",
    "φυτικο γαλα", "νηστισιμο γαλα", "vegan γαλα", "coconut milk", "soy milk",
    "soya milk", "oat milk", "almond milk", "rice milk", "cashew milk", "plant milk",
    "vegan milk", "non dairy milk", "dairy free milk",
)
private val PLANT_CHEESE_PHRASES = setOf(
    "φυτικο τυρι", "τυρι φυτικο", "νηστισιμο τυρι", "τυρι νηστισιμο", "vegan τυρι",
    "plant cheese", "vegan cheese", "non dairy cheese", "dairy free cheese",
)
private val PLANT_BUTTER_PHRASES = setOf(
    "βουτυρο κακαο", "βουτυρο καρυδασ", "βουτυρο φιστικιου", "βουτυρο αμυγδαλου",
    "φυτικο βουτυρο", "νηστισιμο βουτυρο", "vegan βουτυρο", "cocoa butter",
    "coconut butter", "peanut butter", "almond butter", "plant butter", "vegan butter",
)
private val PLANT_YOGURT_PHRASES = setOf(
    "γιαουρτι καρυδασ", "γιαουρτι σογιασ", "γιαουρτι αμυγδαλου", "φυτικο γιαουρτι",
    "νηστισιμο γιαουρτι", "vegan γιαουρτι", "coconut yogurt", "soy yogurt",
    "almond yogurt", "plant yogurt", "vegan yogurt",
)
private val PLANT_CREAM_PHRASES = setOf(
    "κρεμα καρυδασ", "κρεμα σογιασ", "κρεμα βρωμησ", "φυτικη κρεμα", "νηστισιμη κρεμα",
    "vegan κρεμα", "coconut cream", "soy cream", "oat cream", "plant cream", "vegan cream",
)

private val OTHER_ANIMAL_PRODUCT_PREFIXES = setOf(
    "ζελατιν", "λαρδι", "gelatin", "gelatine", "lard",
)
