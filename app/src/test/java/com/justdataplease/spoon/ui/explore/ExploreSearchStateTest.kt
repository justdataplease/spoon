package com.justdataplease.spoon.ui.explore

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreSearchStateTest {
    @Test
    fun `typed query is visible immediately but filtering waits 275 milliseconds`() = runTest {
        val search = ExploreSearchState(backgroundScope)
        runCurrent()

        search.update("ψάρι")

        assertEquals("ψάρι", search.visibleQuery.value)
        assertEquals("", search.filterQuery.value)
        advanceTimeBy(EXPLORE_SEARCH_DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertEquals("", search.filterQuery.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("ψάρι", search.filterQuery.value)
    }

    @Test
    fun `each keystroke cancels the previous pending filter query`() = runTest {
        val search = ExploreSearchState(backgroundScope)
        runCurrent()

        search.update("κ")
        runCurrent()
        advanceTimeBy(100)
        search.update("κο")
        runCurrent()
        advanceTimeBy(100)
        search.update("κοτ")
        runCurrent()

        advanceTimeBy(EXPLORE_SEARCH_DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertEquals("", search.filterQuery.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("κοτ", search.filterQuery.value)
    }

    @Test
    fun `duplicate settled query is not emitted twice`() = runTest {
        val source = MutableSharedFlow<String>(extraBufferCapacity = 2)
        val settled = mutableListOf<String>()
        backgroundScope.launch {
            source.debouncedDistinctExploreQueries().collect(settled::add)
        }
        runCurrent()

        source.emit("  όσπρια  ")
        advanceTimeBy(EXPLORE_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        source.emit("όσπρια")
        advanceTimeBy(EXPLORE_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(listOf("όσπρια"), settled)
    }

    @Test
    fun `non-search filter changes emit immediately with latest settled query`() = runTest {
        val search = ExploreSearchState(backgroundScope)
        val filters = MutableStateFlow(ExploreFiltersUi())
        val inputs = mutableListOf<ExploreFilterInput>()
        backgroundScope.launch {
            exploreFilterInputs(search.filterQuery, filters).collect(inputs::add)
        }
        runCurrent()
        assertEquals(1, inputs.size)

        search.update("ψάρι")
        runCurrent()
        val fish = ExploreFiltersUi(categoryKey = "fish")
        filters.value = fish
        runCurrent()

        assertEquals(ExploreFilterInput(query = "", filters = fish), inputs.last())
        advanceTimeBy(EXPLORE_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(ExploreFilterInput(query = "ψάρι", filters = fish), inputs.last())
    }
    @Test
    fun `rapid Greek composition stays current before asynchronous filtering runs`() = runTest {
        val search = ExploreSearchState(backgroundScope)
        runCurrent()
        for (text in listOf("κ", "κο", "κοτ", "κοτα", "κότα")) {
            val edit = TextFieldValue(text, TextRange(text.length), TextRange(0, text.length))
            search.update(edit)
            assertEquals(edit, search.editorValue)
        }
        assertEquals("", search.filterQuery.value)
        runCurrent()
        advanceTimeBy(EXPLORE_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals("κότα", search.filterQuery.value)
        assertEquals(TextRange(0, 4), search.editorValue.composition)
        assertEquals(TextRange(4), search.editorValue.selection)
    }

    @Test
    fun `settled older query cannot overwrite new text or middle cursor edits`() = runTest {
        val search = ExploreSearchState(backgroundScope)
        search.update("κοτα")
        runCurrent()
        advanceTimeBy(EXPLORE_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals("κοτα", search.filterQuery.value)

        search.update(TextFieldValue("κοτα", TextRange(2)))
        val insertion = TextFieldValue("κοτότα", TextRange(4), TextRange(2, 4))
        search.update(insertion)
        assertEquals("κοτα", search.filterQuery.value)
        assertEquals(insertion, search.editorValue)
        runCurrent()
        assertEquals(insertion, search.editorValue)
        advanceTimeBy(EXPLORE_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals("κοτότα", search.filterQuery.value)
        assertEquals(insertion, search.editorValue)
    }

    @Test
    fun `cursor and composition changes do not rerun the same search`() = runTest {
        val search = ExploreSearchState(backgroundScope)
        val queries = mutableListOf<String>()
        backgroundScope.launch { search.filterQuery.collect(queries::add) }
        runCurrent()
        search.update("ψάρι")
        runCurrent()
        advanceTimeBy(EXPLORE_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        val before = queries.toList()
        search.update(TextFieldValue("ψάρι", TextRange(1), TextRange(0, 2)))
        search.update(TextFieldValue("ψάρι", TextRange(1)))
        runCurrent()
        advanceTimeBy(EXPLORE_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(before, queries)
        assertEquals(TextFieldValue("ψάρι", TextRange(1)), search.editorValue)
    }

    @Test
    fun `clearing during composition stays empty after pending debounce completes`() = runTest {
        val search = ExploreSearchState(backgroundScope)
        runCurrent()
        search.update(TextFieldValue("κοτό", TextRange(4), TextRange(0, 4)))
        runCurrent()
        advanceTimeBy(100)
        search.update(TextFieldValue())
        assertEquals(TextFieldValue(), search.editorValue)
        runCurrent()
        advanceTimeBy(EXPLORE_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals("", search.filterQuery.value)
        assertEquals(TextFieldValue(), search.editorValue)
    }

    @Test
    fun `input limit keeps selection and composition within accepted text`() = runTest {
        val search = ExploreSearchState(backgroundScope)
        search.update(TextFieldValue("α".repeat(161), TextRange(161), TextRange(158, 161)))
        assertEquals("α".repeat(160), search.editorValue.text)
        assertEquals(TextRange(160), search.editorValue.selection)
        assertEquals(TextRange(158, 160), search.editorValue.composition)
        assertEquals(search.editorValue.text, search.visibleQuery.value)
    }

}
