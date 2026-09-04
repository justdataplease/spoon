package com.justdataplease.spoon.ui.custom

import androidx.lifecycle.SavedStateHandle
import com.justdataplease.spoon.ui.model.AvailableCategories
import com.justdataplease.spoon.ui.model.RecipeDetailUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class CustomRecipeEditorMode {
    CLOSED,
    CREATE,
    EDIT,
}

/**
 * Complete, small editor session persisted through [SavedStateHandle].
 *
 * Photos deliberately live outside this payload. [selectedPhotoPath] points to an app-owned,
 * compressed JPEG; an existing Firestore data URI is represented only by
 * [retainExistingPhoto] and is resolved from the recipe catalog when needed.
 */
@Serializable
data class CustomRecipeEditorState(
    val mode: CustomRecipeEditorMode = CustomRecipeEditorMode.CLOSED,
    val recipeId: String = "",
    val title: String = "",
    val description: String = "",
    val categoryKey: String = AvailableCategories.first().key,
    val prepMinutes: String = "",
    val cookMinutes: String = "",
    val servings: String = "",
    val ingredients: List<CustomIngredientDraftUi> = emptyList(),
    val steps: List<String> = emptyList(),
    val selectedPhotoPath: String = "",
    val retainExistingPhoto: Boolean = false,
    val ingredientTitle: String = "",
    val ingredientQuantity: String = "",
    val ingredientUnit: String = "",
    val stepText: String = "",
    val formMessage: String? = null,
) {
    val isOpen: Boolean get() = mode != CustomRecipeEditorMode.CLOSED
    val isEditing: Boolean get() = mode == CustomRecipeEditorMode.EDIT

    fun completedDraft(photoDataUri: String): CustomRecipeDraftUi = CustomRecipeDraftUi(
        recipeId = recipeId,
        title = title.trim(),
        description = description.trim(),
        categoryKey = categoryKey,
        prepMinutes = prepMinutes.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        cookMinutes = cookMinutes.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        servings = servings.trim(),
        ingredients = ingredients + ingredientTitle.trim().takeIf(String::isNotBlank)?.let {
            CustomIngredientDraftUi(
                title = it,
                quantity = ingredientQuantity.trim(),
                unit = ingredientUnit.trim(),
            )
        }.let(::listOfNotNull),
        steps = steps + stepText.trim().takeIf(String::isNotBlank).let(::listOfNotNull),
        imageDataUrl = photoDataUri,
    )

    companion object {
        fun create(): CustomRecipeEditorState = CustomRecipeEditorState(
            mode = CustomRecipeEditorMode.CREATE,
        )

        fun edit(recipe: RecipeDetailUi): CustomRecipeEditorState {
            val draft = recipe.toCustomRecipeDraftUi()
            return CustomRecipeEditorState(
                mode = CustomRecipeEditorMode.EDIT,
                recipeId = draft.recipeId,
                title = draft.title,
                description = draft.description,
                categoryKey = draft.categoryKey,
                prepMinutes = draft.prepMinutes.takeIf { it > 0 }?.toString().orEmpty(),
                cookMinutes = draft.cookMinutes.takeIf { it > 0 }?.toString().orEmpty(),
                servings = draft.servings,
                ingredients = draft.ingredients,
                steps = draft.steps,
                // Never put recipe.imageUrl in saved state: custom photos are large data URIs.
                retainExistingPhoto = draft.imageDataUrl.isNotBlank(),
            )
        }
    }
}

internal class CustomRecipeEditorStore(
    private val savedStateHandle: SavedStateHandle,
) {
    private val mutableState = MutableStateFlow(
        savedStateHandle.get<String>(SAVED_CUSTOM_RECIPE_EDITOR_KEY)
            ?.let(::decodeCustomRecipeEditorState)
            ?: CustomRecipeEditorState(),
    )

    val state: StateFlow<CustomRecipeEditorState> = mutableState.asStateFlow()

    fun set(value: CustomRecipeEditorState) {
        require(!value.selectedPhotoPath.startsWith("data:", ignoreCase = true)) {
            "A draft photo must be stored as a file path, never as a data URI"
        }
        mutableState.value = value
        if (value.isOpen) {
            savedStateHandle[SAVED_CUSTOM_RECIPE_EDITOR_KEY] = encodeCustomRecipeEditorState(value)
        } else {
            savedStateHandle.remove<String>(SAVED_CUSTOM_RECIPE_EDITOR_KEY)
        }
    }

    fun clear() = set(CustomRecipeEditorState())
}

internal fun encodeCustomRecipeEditorState(state: CustomRecipeEditorState): String =
    CustomRecipeEditorJson.encodeToString(CustomRecipeEditorState.serializer(), state)

internal fun decodeCustomRecipeEditorState(value: String): CustomRecipeEditorState? =
    runCatching {
        CustomRecipeEditorJson.decodeFromString(CustomRecipeEditorState.serializer(), value)
    }.getOrNull()?.takeUnless { state ->
        state.selectedPhotoPath.startsWith("data:", ignoreCase = true)
    }

internal const val SAVED_CUSTOM_RECIPE_EDITOR_KEY = "custom_recipe_editor_v1"

private val CustomRecipeEditorJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
