package com.justdataplease.spoon.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodPreferencesLogicTest {
    private val categories = listOf("legumes", "fish", "meat", "poultry", "vegetables")

    @Test
    fun `default exclusions present every category as All`() {
        assertEquals(categories.toSet(), includedCategoryKeys(categories, emptySet()))
        assertEquals(categories.toSet(), includedCategoryKeys(categories, categories.toSet()))
    }

    @Test
    fun `first category chosen from All becomes the only included category`() {
        val exclusions = toggleCategoryInclusions(categories, emptySet(), "poultry")

        assertEquals(categories.toSet() - "poultry", exclusions)
        assertEquals(setOf("poultry"), includedCategoryKeys(categories, exclusions))
    }

    @Test
    fun `category choices can be added and removing the last returns to All`() {
        val chickenOnly = toggleCategoryInclusions(categories, emptySet(), "poultry")
        val chickenAndMeat = toggleCategoryInclusions(categories, chickenOnly, "meat")
        val meatOnly = toggleCategoryInclusions(categories, chickenAndMeat, "poultry")
        val allAgain = toggleCategoryInclusions(categories, meatOnly, "meat")

        assertEquals(
            setOf("poultry", "meat"),
            includedCategoryKeys(categories, chickenAndMeat),
        )
        assertEquals(setOf("meat"), includedCategoryKeys(categories, meatOnly))
        assertEquals(emptySet<String>(), allAgain)
        assertEquals(categories.toSet(), includedCategoryKeys(categories, allAgain))
    }

    @Test
    fun `unknown category cannot mutate exclusions`() {
        val exclusions = setOf("fish")

        assertEquals(
            exclusions,
            toggleCategoryInclusions(categories, exclusions, "unknown"),
        )
    }

    @Test
    fun `vegan mode resets an animal-only category restriction to All`() {
        val chickenOnlyExclusions = categories.toSet() - "poultry"

        assertEquals(
            emptySet<String>(),
            reconcileCategoryExclusionsForVegan(
                availableCategoryKeys = categories,
                excludedCategoryKeys = chickenOnlyExclusions,
                veganOnly = true,
            ),
        )
    }

    @Test
    fun `vegan mode keeps the default All selection`() {
        assertEquals(
            emptySet<String>(),
            reconcileCategoryExclusionsForVegan(
                availableCategoryKeys = categories,
                excludedCategoryKeys = emptySet(),
                veganOnly = true,
            ),
        )
    }

    @Test
    fun `vegan mode retains compatible choices and removes animal choices`() {
        val chickenAndVegetables = categories.toSet() - setOf("poultry", "vegetables")
        val reconciled = reconcileCategoryExclusionsForVegan(
            availableCategoryKeys = categories,
            excludedCategoryKeys = chickenAndVegetables,
            veganOnly = true,
        )

        assertEquals(setOf("vegetables"), includedCategoryKeys(categories, reconciled))
        assertEquals(
            chickenAndVegetables,
            reconcileCategoryExclusionsForVegan(categories, chickenAndVegetables, veganOnly = false),
        )
    }

    @Test
    fun `ingredient suggestions ignore Greek accents and rank prefixes first`() {
        val options = listOf(
            "Σούπα με κοτόπουλο",
            "ΚΟΤΟΠΟΥΛΟ",
            "Κοτόπουλο μπούτι",
            "Μοσχάρι",
        )

        assertEquals(
            listOf("Κοτόπουλο", "Κοτόπουλο μπούτι", "Σούπα με κοτόπουλο"),
            ingredientSuggestions(options, emptySet(), "κοτοπουλο"),
        )
    }

    @Test
    fun `ingredient suggestions omit selected values and collapse canonical duplicates`() {
        val options = listOf("ΑΥΓΑ", "αυγα", "Αυγά", "ΚΟΤΟΠΟΥΛΟ", "Μοσχάρι")

        assertEquals(
            listOf("Αυγό"),
            ingredientSuggestions(options, emptySet(), "αυγα"),
        )
        assertEquals(
            emptyList<String>(),
            ingredientSuggestions(options, setOf("κοτόπουλο"), "κοτοπουλο"),
        )
    }

    @Test
    fun `ingredient suggestions are bounded`() {
        val options = (1..50).map { index -> "Υλικό $index" }

        assertEquals(
            MAX_INGREDIENT_SUGGESTIONS,
            ingredientSuggestions(options, emptySet(), "").size,
        )
    }

    @Test
    fun `only catalog values resolve as canonical ingredient selections`() {
        val options = listOf("ΚΟΤΟΠΟΥΛΟ", "Μοσχάρι")

        assertEquals("Κοτόπουλο", canonicalIngredientOption(options, "κοτόπουλο"))
        assertEquals(
            "Λεμόνι",
            canonicalIngredientOption(listOf("Λεμο\u0301νι"), "λεμόνι"),
        )
        assertNull(canonicalIngredientOption(options, "τυχαίο υλικό"))
    }

    @Test
    fun `reviewed singular plural and spelling aliases share one readable identity`() {
        val cases = listOf(
            Triple("ΑΥΓΑ", "αυγό", "Αυγό"),
            Triple("ΠΑΤΑΤΕΣ", "πατάτα", "Πατάτα"),
            Triple("ΝΤΟΜΑΤΕΣ", "ντομάτα", "Ντομάτα"),
            Triple("ΑΓΚΙΝΑΡΕΣ", "αγκινάρα", "Αγκινάρα"),
            Triple("ΚΑΡΟΤΑ", "καρότο", "Καρότο"),
            Triple("ΠΑΝΤΖΑΡΙΑ", "παντζάρι", "Παντζάρι"),
            Triple("ΠΡΑΣΑ", "πράσο", "Πράσο"),
            Triple("ΡΕΒΥΘΙΑ", "ρεβίθια", "Ρεβίθια"),
            Triple("ΦΡΑΟΥΛΕΣ", "φράουλα", "Φράουλα"),
            Triple("ΒΕΡΙΚΟΚΑ", "βερίκοκο", "Βερίκοκο"),
            Triple("ΒΑΤΟΜΟΥΡΑ", "βατόμουρο", "Βατόμουρο"),
            Triple("ΚΟΛΙΑΝΔΡΟ", "κόλιανδρος", "Κόλιανδρος"),
            Triple("ΒΛΙΤΑ", "βλήτα", "Βλήτα"),
            Triple("ΣΟΥΠΙΕΣ", "σουπιά", "Σουπιά"),
            Triple("ΕΛΙΕΣ", "ελιά", "Ελιά"),
            Triple("ΧΟΥΡΜΑΔΕΣ", "χουρμάς", "Χουρμάς"),
            Triple("ΞΕΡΑ ΦΡΟΥΤΑ", "αποξηραμένα φρούτα", "Αποξηραμένα φρούτα"),
            Triple("ΨΑΡΙΑ", "ψάρι", "Ψάρι"),
            Triple("ΦΟΥΝΤΟΥΚΙΑ", "φουντούκι", "Φουντούκι"),
        )

        cases.forEach { (catalogOption, candidateAlias, expectedLabel) ->
            assertEquals(
                expectedLabel,
                canonicalIngredientOption(listOf(catalogOption), candidateAlias),
            )
            assertEquals(
                listOf(expectedLabel),
                ingredientSuggestions(listOf(catalogOption), emptySet(), candidateAlias),
            )
        }
    }

    @Test
    fun `selecting any alias omits every equivalent dropdown option`() {
        val options = listOf("Αυγό", "ΑΥΓΑ", "Ρεβίθια", "ΡΕΒΥΘΙΑ")

        assertEquals(
            emptyList<String>(),
            ingredientSuggestions(
                ingredientOptions = options,
                excludedIngredients = setOf("Αυγά", "Ρεβύθια"),
                query = "",
            ),
        )
    }

    @Test
    fun `parent child flour and plant milk options remain distinct`() {
        val options = listOf(
            "ΑΛΕΥΡΙ",
            "ΑΛΕΥΡΙ (ΖΥΜΕΣ)",
            "Γάλα αμυγδάλου",
            "Γάλα βρώμης",
        )

        assertEquals(
            listOf("Αλεύρι", "Αλεύρι (ζύμες)", "Γάλα αμυγδάλου", "Γάλα βρώμης"),
            ingredientSuggestions(options, emptySet(), ""),
        )
    }
}
