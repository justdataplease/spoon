package com.justdataplease.spoon.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OwnerBootstrapStoreTest {
    @Test
    fun `marker filename is stable opaque and safe`() {
        val ownerUid = "owner.with-sensitive-identity"
        val marker = ownerBootstrapMarkerName(ownerUid)

        assertEquals(marker, ownerBootstrapMarkerName(ownerUid))
        assertFalse(marker.contains(ownerUid))
        assertEquals(64, marker.removePrefix("owner_bootstrap_v2_").length)
        assertEquals(
            setOf(true),
            marker.removePrefix("owner_bootstrap_v2_")
                .map { it in '0'..'9' || it in 'a'..'f' }
                .toSet(),
        )
    }
}
