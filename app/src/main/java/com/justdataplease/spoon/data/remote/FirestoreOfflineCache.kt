package com.justdataplease.spoon.data.remote

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings

// The public catalog lives in bundled SQLite, so this generous bound is reserved for personal
// plans, history, favorites, shopping, notes, and hundreds of maximum-size custom recipe photos.
internal const val PERSONAL_FIRESTORE_CACHE_SIZE_BYTES: Long = 256L * 1024L * 1024L

/** Preserves host/SSL settings while making the on-disk personal-data cache explicit. */
internal fun persistentPersonalCacheSettings(
    current: FirebaseFirestoreSettings,
): FirebaseFirestoreSettings = FirebaseFirestoreSettings.Builder(current)
    .setLocalCacheSettings(
        PersistentCacheSettings.newBuilder()
            .setSizeBytes(PERSONAL_FIRESTORE_CACHE_SIZE_BYTES)
            .build(),
    )
    .build()

/**
 * Must run before the first Firestore read. It is idempotent so both Application startup and the
 * repository provider can safely enforce it.
 */
@Synchronized
internal fun configurePersistentPersonalCache(
    firestore: FirebaseFirestore,
): FirebaseFirestore {
    val currentCache = firestore.firestoreSettings.cacheSettings
    if (
        currentCache is PersistentCacheSettings &&
        currentCache.sizeBytes == PERSONAL_FIRESTORE_CACHE_SIZE_BYTES
    ) {
        return firestore
    }
    firestore.firestoreSettings = persistentPersonalCacheSettings(firestore.firestoreSettings)
    return firestore
}
