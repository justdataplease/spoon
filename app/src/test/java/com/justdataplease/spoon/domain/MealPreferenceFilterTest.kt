package com.justdataplease.spoon.domain

import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeIngredient
import com.justdataplease.spoon.data.model.RecipeIngredientSection
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPreferenceFilterTest {
    private val veganOnly = MealPreferenceSettings(veganOnly = true)

    private val veganRecipe = Recipe(
        id = "vegan",
        category = MealCategory.VEGETABLES.key,
        dietLabels = listOf("Αυστηρά χορτοφαγική (Vegan)"),
        ingredientSections = listOf(
            RecipeIngredientSection(
                ingredients = listOf(
                    RecipeIngredient(title = "Γάλα καρύδας", info = "χωρίς ζάχαρη"),
                ),
            ),
        ),
    )

    @Test
    fun `empty preferences include everything`() {
        assertTrue(veganRecipe.matchesMealPreferences(MealPreferenceSettings()))
    }

    @Test
    fun `vegan aliases are accepted and excluded categories still win`() {
        assertTrue(veganRecipe.matchesMealPreferences(veganOnly))
        assertTrue(
            veganRecipe.copy(dietLabels = listOf("vegan"))
                .matchesMealPreferences(veganOnly),
        )
        assertFalse(
            veganRecipe.matchesMealPreferences(
                MealPreferenceSettings(
                    excludedCategories = setOf(MealCategory.VEGETABLES.key),
                    veganOnly = true,
                ),
            ),
        )
    }

    @Test
    fun `ingredient phrase matching ignores Greek accents case punctuation and final sigma`() {
        assertFalse(
            veganRecipe.matchesMealPreferences(
                MealPreferenceSettings(excludedIngredientTerms = setOf("ΓΑΛΑ-ΚΑΡΥΔΑΣ")),
            ),
        )
        assertTrue(
            veganRecipe.matchesMealPreferences(
                MealPreferenceSettings(excludedIngredientTerms = setOf("γάλα αμυγδάλου")),
            ),
        )
    }

    @Test
    fun `untagged recipe is not assumed vegan`() {
        assertFalse(
            veganRecipe.copy(dietLabels = emptyList())
                .matchesMealPreferences(veganOnly),
        )
    }

    @Test
    fun `vegan label must be one recognized exact alias`() {
        listOf("Vegan friendly", "not vegan", "vegan diet").forEach { label ->
            assertFalse(
                label,
                veganRecipe.copy(dietLabels = listOf(label)).matchesMealPreferences(veganOnly),
            )
        }
    }

    @Test
    fun `animal recipe categories are never vegan eligible`() {
        listOf(MealCategory.MEAT, MealCategory.POULTRY, MealCategory.FISH).forEach { category ->
            assertFalse(
                category.key,
                veganRecipe.copy(category = category.key).matchesMealPreferences(veganOnly),
            )
        }
    }

    @Test
    fun `vegan-tagged recipes with animal ingredients are rejected`() {
        listOf(
            "μοσχαρίσιο κρέας",
            "φιλέτο κοτόπουλο",
            "καπνιστός σολομός",
            "γαρίδες καθαρισμένες",
            "2 αυγά",
            "γάλα πλήρες",
            "τυρί φέτα",
            "βούτυρο",
            "στραγγιστό γιαούρτι",
            "μέλι",
            "ζελατίνη",
            "ρέγγα",
            "σάλτσα Worcestershire",
            "μαγιονέζα",
            "mayo",
            "aioli",
            "ζωμός",
            "stock",
            "πέστο βασιλικού",
        ).forEach { ingredient ->
            assertFalse(
                ingredient,
                veganRecipe.withIngredient(ingredient).matchesMealPreferences(veganOnly),
            )
        }
    }

    @Test
    fun `plant dairy alternatives and without-egg ingredients remain vegan eligible`() {
        listOf(
            "γάλα καρύδας",
            "γάλα σόγιας",
            "γάλα βρώμης",
            "γάλα αμυγδάλου",
            "φυτικό τυρί",
            "νηστίσιμο τυρί",
            "φιστικοβούτυρο",
            "βούτυρο κακάο",
            "βούτυρο καρύδας",
            "μαγιονέζα χωρίς αυγό",
            "vegan mayonnaise",
            "φυτική μαγιονέζα",
            "ζωμός λαχανικών",
            "mushroom broth",
            "vegan pesto",
            "πέστο χωρίς τυρί",
        ).forEach { ingredient ->
            assertTrue(
                ingredient,
                veganRecipe.withIngredient(ingredient).matchesMealPreferences(veganOnly),
            )
        }
    }

    @Test
    fun `animal products in ingredient info and alternative branches are rejected`() {
        listOf(
            "λαχανικά" to "σερβίρονται με κοτόπουλο",
            "γλυκόζη" to "ή μέλι",
            "ζωμός" to "κοτόπουλου",
            "λάδι" to "ή βούτυρο",
            "γάλα" to "φρέσκο ή φυτικό",
            "κατσικίσιο τυρί — ή νηστίσιμο" to "",
            "ζωμός λαχανικών (ή κότας)" to "",
        ).forEach { (title, info) ->
            assertFalse(
                "$title $info",
                veganRecipe.withIngredient(title, info).matchesMealPreferences(veganOnly),
            )
        }
    }

    private fun Recipe.withIngredient(title: String, info: String = ""): Recipe = copy(
        ingredientLabels = emptyList(),
        ingredientSections = listOf(
            RecipeIngredientSection(
                ingredients = listOf(RecipeIngredient(title = title, info = info)),
            ),
        ),
    )
}
