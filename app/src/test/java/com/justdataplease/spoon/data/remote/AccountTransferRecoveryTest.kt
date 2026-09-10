package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.local.AccountTransferStorage
import com.justdataplease.spoon.data.local.PersistedAccountTransferStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTransferRecoveryTest {
    @Test
    fun `malformed optional transfer metadata preserves the original and never touches source data`() {
        val disk = Disk().apply { value = "{broken" }
        val source = mutableSetOf("history-one", "history-two")
        val result = recover(disk) { _, _ -> source.clear() }
        assertTrue(result is AccountTransferRecoveryOutcome.Failed)
        assertFalse((result as AccountTransferRecoveryOutcome.Failed).claimCompleted)
        assertEquals("{broken", disk.value)
        assertEquals(setOf("history-one", "history-two"), source)
        assertEquals(0, disk.clears)
    }

    @Test
    fun `unreadable metadata remains a recoverable status instead of escaping into local readiness`() {
        val disk = Disk().apply { failReads = true }
        var claimed = false
        val result = recover(disk) { _, _ -> claimed = true }
        assertTrue(result is AccountTransferRecoveryOutcome.Failed)
        assertFalse(claimed)
        assertEquals(0, disk.clears)
    }

    @Test
    fun `clear failure keeps claimed data durable and retries the retained intent without duplicates`() {
        val disk = Disk()
        PersistedAccountTransferStore(disk).prepare("anonymous", "member@example.com")
        val original = disk.value
        val source = mutableSetOf("history-one", "history-two")
        val destination = mutableSetOf("existing")
        val claim: (String, String) -> Unit = { from, to ->
            assertEquals("anonymous", from)
            assertEquals("member", to)
            destination += source
            source.clear()
        }
        disk.failClear = true
        val failed = recover(disk, claim) as AccountTransferRecoveryOutcome.Failed
        assertTrue(failed.claimCompleted)
        assertEquals(original, disk.value)
        assertEquals(setOf("existing", "history-one", "history-two"), destination)
        assertTrue(source.isEmpty())
        disk.failClear = false
        assertEquals(AccountTransferRecoveryOutcome.Recovered, recover(disk, claim))
        assertEquals(setOf("existing", "history-one", "history-two"), destination)
        assertNull(disk.value)
    }

    @Test
    fun `a different authenticated account cannot claim or clear the pending transfer`() {
        val disk = Disk()
        PersistedAccountTransferStore(disk).prepare("anonymous", "someone-else@example.com")
        val original = disk.value
        assertEquals(AccountTransferRecoveryOutcome.NotNeeded, recover(disk) { _, _ -> error("Wrong owner") })
        assertEquals(original, disk.value)
        assertEquals(0, disk.clears)
    }

    @Test
    fun `cancellation is propagated without clearing the intent`() {
        val disk = Disk()
        PersistedAccountTransferStore(disk).prepare("anonymous", "member@example.com")
        assertThrows(CancellationException::class.java) {
            recover(disk) { _, _ -> throw CancellationException("cancel") }
        }
        assertEquals(0, disk.clears)
    }

    private fun recover(disk: Disk, claim: (String, String) -> Unit) = recoverPendingAccountTransfer(
        store = PersistedAccountTransferStore(disk), ownerUid = "member", email = "member@example.com",
        isAnonymous = false, claim = claim,
    )

    private class Disk : AccountTransferStorage {
        var value: String? = null
        var failReads = false
        var failClear = false
        var clears = 0
        override fun read(): String? {
            if (failReads) throw IOException("simulated legacy read failure")
            return value
        }
        override fun write(value: String) { this.value = value }
        override fun clear() {
            clears++
            if (failClear) throw IOException("simulated clear failure")
            value = null
        }
    }
}
