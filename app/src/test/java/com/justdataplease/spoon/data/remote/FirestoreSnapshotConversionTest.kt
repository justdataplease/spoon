package com.justdataplease.spoon.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class FirestoreSnapshotConversionTest {
    @Test
    fun `snapshot source and conversion run off caller while preserving order`() = runBlocking {
        val callerThread = Thread.currentThread()
        var sourceThread: Thread? = null
        val conversionThreads = mutableListOf<Thread>()

        val converted = convertFirestoreSnapshotOffMain(
            source = {
                sourceThread = Thread.currentThread()
                listOf(3, 1, 2, 4)
            },
            convert = { value ->
                conversionThreads += Thread.currentThread()
                value.takeUnless { it == 1 }?.toString()
            },
        )

        assertEquals(listOf("3", "2", "4"), converted)
        assertNotSame(callerThread, sourceThread)
        conversionThreads.forEach { assertSame(sourceThread, it) }
    }
}
