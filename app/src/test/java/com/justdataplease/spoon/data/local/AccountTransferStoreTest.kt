package com.justdataplease.spoon.data.local

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTransferStoreTest {
    @Test
    fun `prepared intent survives instance restart and only matches its actual signed in account`() {
        val disk = MemoryStorage()
        PersistedAccountTransferStore(disk).prepare("anonymous-owner", "  Member@Example.COM  ")
        val recovered = checkNotNull(PersistedAccountTransferStore(disk).read())
        assertEquals("anonymous-owner", recovered.anonymousUid)
        assertEquals("member@example.com", recovered.email)
        assertTrue(recovered.matchesAuthenticatedAccount("MEMBER@example.com", isAnonymous = false))
        assertFalse(recovered.matchesAuthenticatedAccount("other@example.com", isAnonymous = false))
        assertFalse(recovered.matchesAuthenticatedAccount("member@example.com", isAnonymous = true))
        assertFalse(recovered.matchesAuthenticatedAccount(null, isAnonymous = false))
        assertEquals("{\"anonymousUid\":\"anonymous-owner\",\"email\":\"member@example.com\"}", disk.value)
    }

    @Test
    fun `clearing a completed claim is durable and an absent intent is distinct from corruption`() {
        val disk = MemoryStorage()
        val store = PersistedAccountTransferStore(disk)
        assertNull(store.read())
        store.prepare("anonymous-owner", "member@example.com")
        store.clear()
        assertNull(PersistedAccountTransferStore(disk).read())
        disk.value = "{truncated"
        assertThrows(IllegalArgumentException::class.java) { store.read() }
        assertEquals("{truncated", disk.value)
    }

    @Test
    fun `failed durable prepare and clear surface failure without discarding the previous recovery intent`() {
        val disk = MemoryStorage()
        val store = PersistedAccountTransferStore(disk)
        store.prepare("anonymous-owner", "member@example.com")
        val original = store.read()
        disk.failWrites = true
        assertThrows(IOException::class.java) { store.prepare("another-owner", "other@example.com") }
        assertEquals(original, PersistedAccountTransferStore(disk).read())
        assertThrows(IOException::class.java) { store.clear() }
        assertEquals(original, PersistedAccountTransferStore(disk).read())
    }

    @Test
    fun `malformed semantic intent is rejected instead of authorizing another owner claim`() {
        val disk = MemoryStorage()
        val store = PersistedAccountTransferStore(disk)
        disk.value = """{"anonymousUid":"", "email":"member@example.com"}"""
        assertThrows(IllegalArgumentException::class.java) { store.read() }
        disk.value = """{"anonymousUid":"anonymous-owner", "email":" MEMBER@example.com "}"""
        assertThrows(IllegalArgumentException::class.java) { store.read() }
        assertThrows(IllegalArgumentException::class.java) { store.prepare("", "member@example.com") }
        assertThrows(IllegalArgumentException::class.java) { store.prepare("anonymous-owner", " ") }
    }

    private class MemoryStorage : AccountTransferStorage {
        var value: String? = null
        var failWrites = false
        override fun read() = value
        override fun write(value: String) {
            if (failWrites) throw IOException("simulated disk failure")
            this.value = value
        }
        override fun clear() {
            if (failWrites) throw IOException("simulated disk failure")
            value = null
        }
    }
}
