package com.justdataplease.spoon.data.remote

import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreOfflineCacheTest {
    @Test
    fun `personal data uses a generous bounded persistent disk cache`() {
        val settings = persistentPersonalCacheSettings(
            FirebaseFirestoreSettings.Builder().build(),
        )

        assertTrue(settings.cacheSettings is PersistentCacheSettings)
        assertEquals(
            PERSONAL_FIRESTORE_CACHE_SIZE_BYTES,
            (settings.cacheSettings as PersistentCacheSettings).sizeBytes,
        )
    }
}
