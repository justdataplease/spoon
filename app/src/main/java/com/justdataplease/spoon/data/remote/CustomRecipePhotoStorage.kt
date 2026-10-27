package com.justdataplease.spoon.data.remote

import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.justdataplease.spoon.data.model.CustomRecipe
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.tasks.await

/**
 * Keeps personal recipe photos out of the synced Firestore document. The device keeps its own
 * JPEG data URI; a signed-in sync uploads it here and stores only the download URL.
 */
internal interface CustomRecipePhotoStorage {
    /** Returns the download URL, or null when Storage is not set up and the photo stays inline. */
    suspend fun upload(ownerUid: String, recipeId: String, jpeg: ByteArray): String?

    /** Best-effort removal of a photo that a newer one (or a deleted recipe) replaced. */
    suspend fun deleteReplaced(previousUrl: String, currentUrl: String)
}

/** `spoon/{uid}/customRecipes/{recipeId}/{content hash}.jpg`, readable only by its owner. */
internal class FirebaseCustomRecipePhotoStorage(
    private val storage: FirebaseStorage,
) : CustomRecipePhotoStorage {
    override suspend fun upload(ownerUid: String, recipeId: String, jpeg: ByteArray): String? {
        val name = MessageDigest.getInstance("SHA-256").digest(jpeg)
            .take(16).joinToString("") { "%02x".format(it) } + ".jpg"
        val reference = storage.reference.child("spoon/$ownerUid/customRecipes/$recipeId/$name")
        return try {
            reference.putBytes(jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
            reference.downloadUrl.await().toString()
        } catch (error: StorageException) {
            // A project without a bucket or deployed rules must not block recipe sync forever.
            if (error.errorCode !in StorageNotReadyCodes) throw error
            Log.w(STORAGE_TAG, "Cloud Storage is not ready; the photo stays in the recipe document", error)
            null
        }
    }

    override suspend fun deleteReplaced(previousUrl: String, currentUrl: String) {
        runCatching {
            val previous = storage.getReferenceFromUrl(previousUrl)
            // Re-uploading identical bytes reuses the object path under a fresh token.
            val current = currentUrl.takeIf(String::isNotBlank)?.let(storage::getReferenceFromUrl)
            if (previous.path != current?.path) previous.delete().await()
        }.onFailure { Log.w(STORAGE_TAG, "A replaced recipe photo could not be removed", it) }
    }

    private companion object {
        val StorageNotReadyCodes = setOf(
            StorageException.ERROR_BUCKET_NOT_FOUND,
            StorageException.ERROR_PROJECT_NOT_FOUND,
            StorageException.ERROR_NOT_AUTHORIZED,
        )
        const val STORAGE_TAG = "SpoonPhotoStorage"
    }
}

/** Swaps an inline photo for its Cloud Storage URL; without Storage the recipe is unchanged. */
internal suspend fun CustomRecipe.withUploadedPhoto(
    ownerUid: String,
    photoStorage: CustomRecipePhotoStorage?,
): CustomRecipe {
    if (photoStorage == null || photoDataUri.isBlank()) return this
    val jpeg = Base64.getDecoder().decode(photoDataUri.substringAfter(','))
    val url = photoStorage.upload(ownerUid, id, jpeg) ?: return this
    return copy(photoDataUri = "", photoUrl = url)
}
