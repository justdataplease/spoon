package com.justdataplease.spoon.data.local

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeListStoreTest {
    @Test
    fun `a recipe is placed in exactly the chosen lists`() {
        val lists = listOf(
            RecipeList("a", "Γιορτινά", listOf("r1")),
            RecipeList("b", "Βραδινά", listOf("r2")),
        )

        val moved = lists.withRecipeIn("r1", setOf("b"))

        assertEquals(emptyList<String>(), moved[0].recipeIds)
        assertEquals(listOf("r2", "r1"), moved[1].recipeIds)
        assertEquals(lists, lists.withRecipeIn("r1", setOf("a")))
    }

    @Test
    fun `list names are unique ignoring case and accents and favorites is reserved`() {
        val existing = listOf("Γιορτινά")

        assertNull(recipeListNameProblem("Βραδινά", existing))
        assertNotNull(recipeListNameProblem("ΓΙΟΡΤΙΝΑ", existing))
        assertNotNull(recipeListNameProblem("αγαπημενα", existing))
        assertNotNull(recipeListNameProblem("   ", existing))
    }

    @Test
    fun `lists persist per owner and a first account login adopts guest lists`() {
        val preferences = memoryPreferences()
        val store = RecipeListStore(preferences)
        store.selectOwner("guest")
        val list = checkNotNull(store.create("Γιορτινά"))
        store.setRecipeLists("r1", setOf(list.id))

        val reopened = RecipeListStore(preferences)
        reopened.selectOwner("account:uid-1")
        assertEquals(listOf("r1"), reopened.lists.value.single().recipeIds)

        reopened.selectOwner("guest")
        assertTrue(reopened.lists.value.isEmpty())
    }

    @Test
    fun `removing a recipe from the collection clears it from every list`() {
        val store = RecipeListStore(memoryPreferences())
        store.selectOwner("guest")
        val first = checkNotNull(store.create("Α"))
        val second = checkNotNull(store.create("Β"))
        store.setRecipeLists("r1", setOf(first.id, second.id))

        store.removeRecipe("r1")

        assertTrue(store.lists.value.all { it.recipeIds.isEmpty() })
        assertNull(store.create("α"))
    }

    private fun memoryPreferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = proxy(SharedPreferences.Editor::class.java) { method, args ->
            when (method) {
                "putString" -> editor.also { values[args[0] as String] = args[1] }
                "apply" -> null
                "commit" -> true
                else -> error("Unexpected editor call: $method")
            }
        }
        return proxy(SharedPreferences::class.java) { method, args ->
            when (method) {
                "getString" -> values[args[0] as String] as? String ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences call: $method")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
            handler(method.name, arguments.orEmpty())
        } as T
}
