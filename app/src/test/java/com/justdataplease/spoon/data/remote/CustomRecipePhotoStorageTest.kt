package com.justdataplease.spoon.data.remote

import com.justdataplease.spoon.data.model.CustomRecipe
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CustomRecipePhotoStorageTest {
    private val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, 3, -1, -39)
    private val recipe = CustomRecipe(
        id = "custom_00000000-0000-4000-8000-000000000000",
        photoDataUri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg),
    )

    @Test
    fun `an uploaded photo leaves only its URL in the synced recipe`() = runTest {
        val storage = RecordingPhotoStorage(url = "https://firebasestorage.googleapis.com/v0/b/b/o/x.jpg?alt=media&token=t")

        val synced = recipe.withUploadedPhoto("uid-1", storage)

        assertEquals("", synced.photoDataUri)
        assertEquals(storage.url, synced.photoUrl)
        assertEquals("uid-1" to recipe.id, storage.uploadedFor)
        assertArrayEquals(jpeg, storage.uploaded)
    }

    @Test
    fun `without a ready bucket the photo stays inline`() = runTest {
        assertSame(recipe, recipe.withUploadedPhoto("uid-1", RecordingPhotoStorage(url = null)))
        assertSame(recipe, recipe.withUploadedPhoto("uid-1", null))
    }

    @Test
    fun `a recipe without a new photo uploads nothing`() = runTest {
        val storage = RecordingPhotoStorage(url = "unused")
        val kept = recipe.copy(photoDataUri = "", photoUrl = "https://firebasestorage.googleapis.com/v0/b/b/o/y")

        assertSame(kept, kept.withUploadedPhoto("uid-1", storage))
        assertEquals(null, storage.uploaded)
    }

    private class RecordingPhotoStorage(val url: String?) : CustomRecipePhotoStorage {
        var uploaded: ByteArray? = null
        var uploadedFor: Pair<String, String>? = null

        override suspend fun upload(ownerUid: String, recipeId: String, jpeg: ByteArray): String? {
            uploaded = jpeg
            uploadedFor = ownerUid to recipeId
            return url
        }

        override suspend fun deleteReplaced(previousUrl: String, currentUrl: String) = Unit
    }
}
