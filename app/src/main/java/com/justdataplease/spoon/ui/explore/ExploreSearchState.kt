package com.justdataplease.spoon.ui.explore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal const val EXPLORE_SEARCH_DEBOUNCE_MILLIS = 275L
private const val MAX_EXPLORE_QUERY_LENGTH = 160

internal data class ExploreFilterInput(
    val query: String,
    val filters: ExploreFiltersUi,
)

/**
 * Keeps text input synchronous while exposing a quieter query for the 20k+ recipe scan.
 * Clearing the field is immediate; non-blank typeahead waits for the user's short pause.
 */
internal class ExploreSearchState(
    scope: CoroutineScope,
    debounceMillis: Long = EXPLORE_SEARCH_DEBOUNCE_MILLIS,
) {
    private val mutableVisibleQuery = MutableStateFlow("")

    val visibleQuery: StateFlow<String> = mutableVisibleQuery.asStateFlow()
    val filterQuery: StateFlow<String> = mutableVisibleQuery
        .debouncedDistinctExploreQueries(debounceMillis)
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = mutableVisibleQuery.value,
        )

    fun update(query: String) {
        mutableVisibleQuery.value = query.take(MAX_EXPLORE_QUERY_LENGTH)
    }
}

@OptIn(FlowPreview::class)
internal fun Flow<String>.debouncedDistinctExploreQueries(
    debounceMillis: Long = EXPLORE_SEARCH_DEBOUNCE_MILLIS,
): Flow<String> {
    require(debounceMillis >= 0L)
    return map(String::trim)
        .debounce { query -> if (query.isEmpty()) 0L else debounceMillis }
        .distinctUntilChanged()
}

/** Filter chips remain immediate and combine with the latest settled text query. */
internal fun exploreFilterInputs(
    filterQuery: Flow<String>,
    filters: Flow<ExploreFiltersUi>,
): Flow<ExploreFilterInput> = combine(filterQuery, filters, ::ExploreFilterInput)
    .distinctUntilChanged()
