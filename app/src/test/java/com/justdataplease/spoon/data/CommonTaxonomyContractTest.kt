package com.justdataplease.spoon.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CommonTaxonomyContractTest {
    private fun vocabulary(name: String) = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "tools/recipe_importer/$name") }.first(File::isFile).readText(Charsets.UTF_8)

    @Test fun `Android ingredients match every reviewed importer identity`() {
        Json.parseToJsonElement(vocabulary("ingredient_aliases.json")).jsonArray.forEach { value ->
            val group = value.jsonArray.map { it.jsonPrimitive.content }
            group.forEach { alias ->
                assertEquals(alias, canonicalIngredientIdentity(group.first()), canonicalIngredientIdentity(alias))
            }
        }
    }

    @Test fun `Android filter tags match every reviewed importer identity`() {
        Json.parseToJsonElement(vocabulary("facet_aliases.json")).jsonObject.forEach { (facet, value) ->
            value.jsonArray.forEach { aliases ->
                val group = aliases.jsonArray.map { it.jsonPrimitive.content }
                group.forEach { alias ->
                    assertEquals("$facet: $alias", listOf(group.first()), canonicalFacetLabels(facet, listOf(alias)))
                }
            }
        }
    }

    @Test fun `different dietary restrictions and ingredient types remain distinct`() {
        assertEquals(4, canonicalFacetTokens("diet", listOf("Dairy Free", "LactoseFreeDiet", "Sugar Free", "Χαμηλή σε ζάχαρη")).size)
        assertEquals(3, listOf("Γάλα", "Γάλα αμυγδάλου", "Γάλα βρώμης").map(::canonicalIngredientIdentity).toSet().size)
    }
}
