package com.justdataplease.spoon.ui.custom

import androidx.lifecycle.SavedStateHandle
import com.justdataplease.spoon.data.model.RecipeIngredient
import com.justdataplease.spoon.data.model.RecipeIngredientSection
import com.justdataplease.spoon.data.model.RecipeMethodSection
import com.justdataplease.spoon.ui.model.RecipeDetailUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomRecipeEditorStateTest {
    @Test
    fun complete_draft_session_restores_after_viewmodel_process_recreation() {
        val firstHandle = SavedStateHandle()
        val firstStore = CustomRecipeEditorStore(firstHandle)
        val state = CustomRecipeEditorState(
            mode = CustomRecipeEditorMode.EDIT,
            recipeId = "custom_01234567-89ab-4def-8123-456789abcdef",
            title = "Η πίτα μου",
            description = "Οικογενειακή",
            categoryKey = "vegetarian",
            prepMinutes = "25",
            cookMinutes = "45",
            servings = "6",
            ingredients = listOf(CustomIngredientDraftUi("Σπανάκι", "500", "γρ.")),
            steps = listOf("Ανακατεύουμε."),
            selectedPhotoPath = "C:\\app\\files\\custom_recipe_drafts\\draft.jpg",
            retainExistingPhoto = false,
            ingredientTitle = "Φέτα",
            ingredientQuantity = "200",
            ingredientUnit = "γρ.",
            stepText = "Ψήνουμε.",
            formMessage = "Δοκιμή",
        )
        firstStore.set(state)

        val persisted = firstHandle.get<String>(SAVED_CUSTOM_RECIPE_EDITOR_KEY)
        val restoredStore = CustomRecipeEditorStore(
            SavedStateHandle(mapOf(SAVED_CUSTOM_RECIPE_EDITOR_KEY to persisted)),
        )

        assertEquals(state, restoredStore.state.value)
        assertTrue(restoredStore.state.value.isEditing)
        assertFalse(persisted.orEmpty().contains("data:image", ignoreCase = true))
    }

    @Test
    fun editing_an_existing_photo_stores_only_a_retention_marker() {
        val editor = CustomRecipeEditorState.edit(
            RecipeDetailUi(
                recipeId = "custom_01234567-89ab-4def-8123-456789abcdef",
                title = "Συνταγή",
                categoryKey = "fish",
                rating10 = 0.0,
                prepMinutes = 10,
                cookMinutes = 20,
                stepCount = 1,
                imageUrl = "data:image/jpeg;base64," + "A".repeat(500_000),
                sourceUrl = "",
                sourceName = "Προσωπική συνταγή",
                tags = emptyList(),
                isFavorite = false,
                ingredientSections = listOf(
                    RecipeIngredientSection(
                        ingredients = listOf(RecipeIngredient(title = "Ψάρι")),
                    ),
                ),
                methodSections = listOf(RecipeMethodSection(steps = listOf("Ψήνουμε."))),
            ),
        )
        val encoded = encodeCustomRecipeEditorState(editor)

        assertTrue(editor.retainExistingPhoto)
        assertEquals("", editor.selectedPhotoPath)
        assertFalse(encoded.contains("data:image", ignoreCase = true))
        assertTrue(encoded.length < 2_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun data_uri_is_rejected_from_saved_editor_photo_state() {
        CustomRecipeEditorStore(SavedStateHandle()).set(
            CustomRecipeEditorState.create().copy(
                selectedPhotoPath = "data:image/jpeg;base64,YWJj",
            ),
        )
    }
}
