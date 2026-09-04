package com.justdataplease.spoon.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalCacheReadinessTest {
    @Test
    fun `established account starts from local snapshots without server`() {
        assertTrue(
            personalCacheCanInitialize(
                allLocalSnapshotsReady = true,
                ownerBootstrapComplete = true,
                establishedCachedPersonalData = false,
            ),
        )
    }

    @Test
    fun `upgrade can trust non-empty established Firestore cache`() {
        assertTrue(
            personalCacheCanInitialize(
                allLocalSnapshotsReady = true,
                ownerBootstrapComplete = false,
                establishedCachedPersonalData = true,
            ),
        )
    }

    @Test
    fun `new empty phone waits for first complete server bootstrap`() {
        assertFalse(
            personalCacheCanInitialize(
                allLocalSnapshotsReady = true,
                ownerBootstrapComplete = false,
                establishedCachedPersonalData = false,
            ),
        )
    }

    @Test
    fun `partial local snapshot set is never considered ready`() {
        assertFalse(
            personalCacheCanInitialize(
                allLocalSnapshotsReady = false,
                ownerBootstrapComplete = true,
                establishedCachedPersonalData = true,
            ),
        )
    }

    @Test
    fun `bootstrap marker requires every owner query from server`() {
        val required = setOf("plans", "favorites", "history")

        assertFalse(ownerBootstrapCanBeMarked(setOf("plans", "favorites"), required))
        assertTrue(ownerBootstrapCanBeMarked(required, required))
        assertFalse(ownerBootstrapCanBeMarked(required, emptySet()))
    }
}
