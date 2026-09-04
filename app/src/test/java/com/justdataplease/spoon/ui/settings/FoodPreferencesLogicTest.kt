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
            listOf("ΚΟΤΟΠΟΥΛΟ", "Κοτόπουλο μπούτι", "Σούπα με κοτόπουλο"),
            ingredientSuggestions(options, emptySet(), "κοτοπουλο"),
        )
    }

    @Test
    fun `ingredient suggestions omit selected values and collapse canonical duplicates`() {
        val options = listOf("ΑΥΓΑ", "αυγα", "Αυγά", "ΚΟΤΟΠΟΥΛΟ", "Μοσχάρι")

        assertEquals(
            listOf("ΑΥΓΑ"),
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

        assertEquals("ΚΟΤΟΠΟΥΛΟ", canonicalIngredientOption(options, "κοτόπουλο"))
        assertNull(canonicalIngredientOption(options, "τυχαίο υλικό"))
    }
}
