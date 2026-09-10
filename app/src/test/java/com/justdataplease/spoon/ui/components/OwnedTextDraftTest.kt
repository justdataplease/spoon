package com.justdataplease.spoon.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnedTextDraftTest {
    @Test
    fun `a delayed save acknowledgement keeps text entered after save`() {
        val draft = OwnedTextDraft("account:a", "recipe", "old note")
        draft.text.value = "submitted note"
        draft.text.value = "submitted note plus newer typing"

        draft.receiveSavedText("submitted note")

        assertEquals("submitted note plus newer typing", draft.text.value)
    }

    @Test
    fun `an untouched note follows a remote edit`() {
        val draft = OwnedTextDraft("account:a", "recipe", "old note")
        draft.receiveSavedText("remote note")
        assertEquals("remote note", draft.text.value)
    }

    @Test
    fun `restoration retains the edit and its saved baseline`() {
        val original = OwnedTextDraft("account:a", "recipe", "saved")
        original.text.value = "unfinished edit"
        val restored = OwnedTextDraft.restore(original.encode(), "account:a", "recipe")!!

        restored.receiveSavedText("remote saved")

        assertEquals("unfinished edit", restored.text.value)
    }

    @Test
    fun `shopping input restores without requiring a saved server value`() {
        val original = OwnedTextDraft("guest", "shopping-manual")
        original.text.value = "Φρέσκο γάλα"
        assertEquals("Φρέσκο γάλα", OwnedTextDraft.restore(original.encode(), "guest", "shopping-manual")!!.text.value)
    }

    @Test
    fun `another owner or recipe cannot restore the saved text`() {
        val original = OwnedTextDraft("account:a", "recipe-a", "private note")
        val encoded = original.encode()
        assertNull(OwnedTextDraft.restore(encoded, "account:b", "recipe-a"))
        assertNull(OwnedTextDraft.restore(encoded, "guest", "recipe-a"))
        assertNull(OwnedTextDraft.restore(encoded, "account:a", "recipe-b"))
        assertNull(OwnedTextDraft.restore("broken", "account:a", "recipe-a"))
    }
}
