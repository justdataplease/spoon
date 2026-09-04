package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `ownerless preferences survive the first authenticated owner transition`() {
        val pending = MealPreferenceSettings(
            excludedCategories = setOf("poultry", "meat"),
            updatedAtEpochMillis = 200L,
        )

        assertEquals(
            pending,
            ownerlessMealPreferencesToPreserve(
                previousOwnerUid = null,
                nextOwnerUid = "owner-a",
                settings = pending,
            ),
        )
    }

    @Test
    fun `real owner changes and sign-out never preserve preferences`() {
        val ownerA = MealPreferenceSettings(veganOnly = true, updatedAtEpochMillis = 100L)

        assertNull(ownerlessMealPreferencesToPreserve("owner-a", "owner-b", ownerA))
        assertNull(ownerlessMealPreferencesToPreserve("owner-a", null, ownerA))
        assertNull(ownerlessMealPreferencesToPreserve(null, null, ownerA))
    }
}
