package com.justdataplease.spoon.sync

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogFreshnessStoreTest {
    private val catalogHash = "c".repeat(64)

    @Test
    fun `success and later failure survive a store round trip`() {
        val memory = MemoryPreferences()
        val writer = CatalogFreshnessStore(memory.preferences)

        assertTrue(
            writer.recordSuccess(
                checkedAt = 10_000L,
                catalogUpdatedAt = 9_000L,
                recipeCount = 5_365L,
                catalogVersion = "sha256:$catalogHash",
                catalogHash = catalogHash,
            ),
        )
        assertTrue(
            writer.recordFailure(
                attemptedAt = 11_000L,
                failure = CatalogCheckFailure.STATUS_MALFORMED,
            ),
        )

        val restored = CatalogFreshnessStore(memory.preferences).read()
        assertEquals(CatalogCheckState.FAILED, restored.checkStateAt(11_000L))
        assertEquals(10_000L, restored.lastCheckedAt)
        assertEquals(9_000L, restored.catalogUpdatedAt)
        assertEquals(5_365L, restored.recipeCount)
        assertEquals("sha256:$catalogHash", restored.catalogVersion)
        assertEquals(catalogHash, restored.catalogHash)
        assertEquals(CatalogCheckFailure.STATUS_MALFORMED, restored.lastFailure)
        assertEquals(1L, restored.consecutiveFailures)
    }

    @Test
    fun `persisted last success derives stale state from current time`() {
        val memory = MemoryPreferences()
        val store = CatalogFreshnessStore(memory.preferences)
        assertTrue(
            store.recordSuccess(
                checkedAt = 1_000L,
                catalogUpdatedAt = 900L,
                recipeCount = 20L,
            ),
        )

        val restored = CatalogFreshnessStore(memory.preferences).read()
        assertEquals(
            CatalogCheckState.STALE,
            restored.checkStateAt(1_000L + TimeUnit.DAYS.toMillis(90L)),
        )
        assertFalse(restored.lastAttemptFailed)
    }

    @Test
    fun `legacy successful check supplies last attempt during migration`() {
        val memory = MemoryPreferences(mutableMapOf("lastCheckedAt" to 42_000L))

        val restored = CatalogFreshnessStore(memory.preferences).read()

        assertEquals(42_000L, restored.lastCheckedAt)
        assertEquals(42_000L, restored.lastAttemptAt)
        assertEquals(CatalogCheckState.CURRENT, restored.checkStateAt(42_001L))
    }

    private class MemoryPreferences(
        private val values: MutableMap<String, Any?> = mutableMapOf(),
    ) {
        val preferences: SharedPreferences = proxy(SharedPreferences::class.java) { method, args ->
            val key = args.firstOrNull() as? String
            when (method) {
                "getAll" -> values.toMap()
                "getString" -> values[key] as? String ?: args.getOrNull(1)
                "getStringSet" -> values[key] as? Set<*> ?: args.getOrNull(1)
                "getInt" -> values[key] as? Int ?: args[1]
                "getLong" -> values[key] as? Long ?: args[1]
                "getFloat" -> values[key] as? Float ?: args[1]
                "getBoolean" -> values[key] as? Boolean ?: args[1]
                "contains" -> values.containsKey(key)
                "edit" -> editor()
                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener",
                -> null
                else -> objectMethod(method, args)
            }
        }

        private fun editor(): SharedPreferences.Editor =
            proxy(SharedPreferences.Editor::class.java) { method, args ->
                val key = args.firstOrNull() as? String
                when (method) {
                    "putString", "putStringSet", "putInt", "putLong", "putFloat", "putBoolean" -> {
                        values[key.orEmpty()] = args.getOrNull(1)
                        editor()
                    }
                    "remove" -> {
                        values.remove(key)
                        editor()
                    }
                    "clear" -> {
                        values.clear()
                        editor()
                    }
                    "commit" -> true
                    "apply" -> null
                    else -> objectMethod(method, args)
                }
            }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(
            type: Class<T>,
            handler: (String, Array<out Any?>) -> Any?,
        ): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
            handler(method.name, arguments.orEmpty())
        } as T

        private fun objectMethod(method: String, args: Array<out Any?>): Any? = when (method) {
            "toString" -> "MemoryPreferences"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> args.firstOrNull() === this
            else -> error("Unexpected SharedPreferences call: $method")
        }
    }
}
