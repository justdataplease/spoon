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
    @Test
    fun `all publishers are enabled by default and exclusions intersect explicit explore filters`() {
        val keys = com.justdataplease.spoon.data.preferences.AllowedRecipePublisherKeys
        val recipes = keys.map { Recipe(id = "${it}_one", sourceKey = it, title = it, category = "meat") }
        assertTrue(recipes.all { it.matchesMealPreferences(MealPreferenceSettings()) })
        val disabled = MealPreferenceSettings(excludedSourceKeys = setOf("akis"))
        assertFalse(recipes.first { it.sourceKey == "akis" }.matchesMealPreferences(disabled))
        assertTrue(recipes.filter { it.sourceKey != "akis" }.all { it.matchesMealPreferences(disabled) })
        assertTrue(ExploreRecipeFilter.filter(recipes, ExploreCriteria(sourceKeys = setOf("akis")), disabled).isEmpty())
        assertTrue(ExploreRecipeFilter.filter(recipes, preferences = MealPreferenceSettings(excludedSourceKeys = keys)).isEmpty())
    }

    @Test
    fun `source exclusions resolve legacy domains and leave personal and future sources available`() {
        val disabled = MealPreferenceSettings(excludedSourceKeys = setOf(" TSoulis "))
        assertFalse(Recipe(source = "www.giorgostsoulis.com", category = "meat").matchesMealPreferences(disabled))
        val allDisabled = MealPreferenceSettings(excludedSourceKeys = com.justdataplease.spoon.data.preferences.AllowedRecipePublisherKeys)
        assertTrue(Recipe(id = "custom_recipe", sourceKey = "personal", category = "meat").matchesMealPreferences(allDisabled))
        assertTrue(Recipe(sourceKey = "future_publisher", category = "meat").matchesMealPreferences(allDisabled))
    }

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
    fun `persisted ingredient aliases match equivalent recipe ingredient forms`() {
        listOf(
            Triple("1 αυγό", "ΑΥΓΑ", "singular to plural"),
            Triple("2 αυγά", "Αυγό", "plural to singular"),
            Triple("ρεβίθια βρασμένα", "ΡΕΒΥΘΙΑ", "alternate spelling"),
            Triple("ξερά φρούτα", "Αποξηραμένα φρούτα", "semantic synonym"),
            Triple("πατάτες baby", "Πατάτα", "number variant"),
        ).forEach { (ingredient, excludedAlias, description) ->
            assertFalse(
                description,
                veganRecipe.withIngredient(ingredient).matchesMealPreferences(
                    MealPreferenceSettings(excludedIngredientTerms = setOf(excludedAlias)),
                ),
            )
        }
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
            "αβγά",
            "κρέμα",
            "tuna",
            "duck",
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

    @Test
    fun `local plant qualifiers and slice descriptions do not reject vegan recipes`() {
        listOf(
            "τυρί" to "φυτικό προϊόν",
            "γάλα" to "από αμύγδαλα",
            "ψωμί" to "σε φέτα",
        ).forEach { (title, info) ->
            assertTrue("$title $info", veganRecipe.withIngredient(title, info).matchesMealPreferences(veganOnly))
        }
    }

    @Test
    fun `animal ingredient tags veto vegan eligibility even with plant raw ingredients`() {
        assertFalse(veganRecipe.copy(ingredientLabels = listOf("Αυγά")).matchesMealPreferences(veganOnly))
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
