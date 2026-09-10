package com.justdataplease.spoon.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Keeps an edited value when an acknowledgement arrives, and isolates saved drafts by owner. */
internal class OwnedTextDraft(
    val ownerKey: String,
    val subjectKey: String,
    private var baseline: String = "",
    initialText: String = baseline,
) {
    val text = mutableStateOf(initialText)

    fun receiveSavedText(savedText: String) {
        if (savedText == baseline) return
        if (text.value.trim() == baseline.trim()) text.value = savedText
        baseline = savedText
    }

    fun encode(): String = Json.encodeToString(
        TextDraftSnapshot.serializer(),
        TextDraftSnapshot(ownerKey, subjectKey, baseline, text.value),
    )

    companion object {
        fun restore(value: String, ownerKey: String, subjectKey: String): OwnedTextDraft? =
            runCatching { Json.decodeFromString(TextDraftSnapshot.serializer(), value) }
                .getOrNull()
                ?.takeIf { it.ownerKey == ownerKey && it.subjectKey == subjectKey }
                ?.let { OwnedTextDraft(it.ownerKey, it.subjectKey, it.baseline, it.text) }

        fun saver(ownerKey: String, subjectKey: String) = Saver<OwnedTextDraft, String>(
            save = { it.encode() },
            restore = { restore(it, ownerKey, subjectKey) },
        )
    }
}

@Serializable
private data class TextDraftSnapshot(
    val ownerKey: String,
    val subjectKey: String,
    val baseline: String,
    val text: String,
)
