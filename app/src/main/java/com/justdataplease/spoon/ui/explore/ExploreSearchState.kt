package com.justdataplease.spoon.ui.explore

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
 * Owns the synchronous editor, including selection and IME composition.
 * Only the filtering query travels through asynchronous flows and debounce.
 * Clearing the field is immediate; non-blank typeahead waits for the user's short pause.
 */
internal class ExploreSearchState(
    scope: CoroutineScope,
    debounceMillis: Long = EXPLORE_SEARCH_DEBOUNCE_MILLIS,
) {
    private var mutableEditorValue by mutableStateOf(TextFieldValue())
    private val mutableVisibleQuery = MutableStateFlow("")

    // Read directly from Compose; result snapshots must never feed text back into the editor.
    val editorValue: TextFieldValue get() = mutableEditorValue

    val visibleQuery: StateFlow<String> = mutableVisibleQuery.asStateFlow()
    val filterQuery: StateFlow<String> = mutableVisibleQuery
        .debouncedDistinctExploreQueries(debounceMillis)
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = mutableVisibleQuery.value,
        )

    fun update(value: TextFieldValue) {
        val accepted = if (value.text.length > MAX_EXPLORE_QUERY_LENGTH) {
            value.copy(text = value.text.take(MAX_EXPLORE_QUERY_LENGTH))
        } else value
        mutableEditorValue = accepted
        mutableVisibleQuery.value = accepted.text
    }

    fun update(query: String) {
        val text = query.take(MAX_EXPLORE_QUERY_LENGTH)
        update(TextFieldValue(text, selection = TextRange(text.length)))
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
