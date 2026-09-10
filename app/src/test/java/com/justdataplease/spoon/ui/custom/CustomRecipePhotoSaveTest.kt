package com.justdataplease.spoon.ui.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CustomRecipePhotoSaveTest {
    private val validEditor = CustomRecipeEditorState.create().copy(
        title = "Η συνταγή μου",
        ingredients = listOf(CustomIngredientDraftUi("Ντομάτα")),
        steps = listOf("Κόβουμε και σερβίρουμε."),
        selectedPhotoPath = "/existing-photo.jpg",
    )

    @Test
    fun `otherwise valid recipe cannot save before its replacement photo is ready`() {
        assertNull(validEditor.saveValidationMessage())
        val processing = validEditor.copy(photoPreparation = RecipePhotoPreparation.PROCESSING)
        assertNotNull(processing.saveValidationMessage())
        assertEquals("/existing-photo.jpg", processing.selectedPhotoPath)
    }

    @Test
    fun `restored interrupted processing stays blocked until retry or explicit continuation`() {
        val processing = validEditor.copy(photoPreparation = RecipePhotoPreparation.PROCESSING)
        val restored = decodeCustomRecipeEditorState(encodeCustomRecipeEditorState(processing))!!
        assertNotNull(restored.saveValidationMessage())

        val failed = restored.copy(photoPreparation = RecipePhotoPreparation.FAILED)
        assertNotNull(failed.saveValidationMessage())
        assertEquals("/existing-photo.jpg", failed.selectedPhotoPath)

        val continued = failed.copy(photoPreparation = RecipePhotoPreparation.READY)
        assertNull(continued.saveValidationMessage())
        assertEquals(validEditor.completedDraft("old-image"), continued.completedDraft("old-image"))
    }

    @Test
    fun `successful retry saves the new photo and preserves entered recipe text`() {
        val failed = validEditor.copy(photoPreparation = RecipePhotoPreparation.FAILED)
        val ready = failed.copy(photoPreparation = RecipePhotoPreparation.READY, selectedPhotoPath = "/new-photo.jpg")
        assertNull(ready.saveValidationMessage())
        assertEquals(validEditor.title, ready.title)
        assertEquals(validEditor.ingredients, ready.ingredients)
        assertEquals("new-image", ready.completedDraft("new-image").imageDataUrl)
    }
}
