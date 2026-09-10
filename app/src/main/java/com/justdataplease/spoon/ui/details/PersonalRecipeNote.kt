package com.justdataplease.spoon.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.ui.components.OwnedTextDraft

private const val MaxRecipeNoteLength = 10_000

@Composable
internal fun PersonalRecipeNoteSection(
    recipeId: String,
    savedNote: String,
    onSaveNote: (String) -> Unit,
    dataOwnerKey: String = "guest",
) {
    val draftState = rememberSaveable(dataOwnerKey, recipeId, saver = OwnedTextDraft.saver(dataOwnerKey, recipeId)) {
        OwnedTextDraft(dataOwnerKey, recipeId, savedNote)
    }
    var draft by draftState.text
    LaunchedEffect(savedNote) { draftState.receiveSavedText(savedNote) }
    val normalizedDraft = draft.trim()
    val hasChanges = normalizedDraft != savedNote.trim()

    DetailSectionCard("Δική μου σημείωση", Icons.Outlined.NoteAlt) {
        Text(
            "Κράτησε εδώ αλλαγές, ιδέες ή ό,τι θέλεις να θυμάσαι την επόμενη φορά.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { if (it.length <= MaxRecipeNoteLength) draft = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            label = { Text("Προσωπική σημείωση") },
            supportingText = { Text("${draft.length}/$MaxRecipeNoteLength") },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!hasChanges && normalizedDraft.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Αποθηκευμένη", modifier = Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.primary)
                }
            } else {
                androidx.compose.foundation.layout.Spacer(Modifier)
            }
            Button(onClick = { onSaveNote(normalizedDraft) }, enabled = hasChanges) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Text(if (normalizedDraft.isBlank()) "Διαγραφή" else "Αποθήκευση", modifier = Modifier.padding(start = 7.dp))
            }
        }
    }
}
