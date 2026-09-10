package com.justdataplease.spoon.data.local

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTransferStoreDeviceTest {
    @Test
    fun transferIntentSurvivesAtomicFileReopenAndClearWithoutUsingFirebase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "account-transfer-test-${UUID.randomUUID()}.json")
        try {
            NoBackupAccountTransferStore(context, file).prepare("anonymous-test-owner", "  Member@Example.com ")
            val recovered = checkNotNull(NoBackupAccountTransferStore(context, file).read())
            assertEquals("anonymous-test-owner", recovered.anonymousUid)
            assertTrue(recovered.matchesAuthenticatedAccount("member@example.com", false))
            assertFalse(recovered.matchesAuthenticatedAccount("other@example.com", false))
            NoBackupAccountTransferStore(context, file).clear()
            assertNull(NoBackupAccountTransferStore(context, file).read())
            assertFalse(file.exists())
            file.writeText("{truncated", Charsets.UTF_8)
            assertThrows(IllegalArgumentException::class.java) { NoBackupAccountTransferStore(context, file).read() }
            assertEquals("{truncated", file.readText(Charsets.UTF_8))
        } finally {
            // Only this test's uniquely named intent and AtomicFile sidecars are removed.
            NoBackupAccountTransferStore(context, file).clear()
        }
    }
}
