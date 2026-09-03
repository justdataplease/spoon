package com.justdataplease.spoon.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import kotlinx.serialization.Serializable

/** A private note. The recipe id is also the document id, so there is one note per recipe. */
@Serializable
@IgnoreExtraProperties
data class RecipeNote(
    @set:DocumentId var id: String = "",
    val recipeId: String = "",
    val text: String = "",
    val updatedAtEpochMillis: Long = 0L,
)
