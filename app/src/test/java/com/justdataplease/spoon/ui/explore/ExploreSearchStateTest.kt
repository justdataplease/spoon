package com.justdataplease.spoon.ui.explore

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
}
