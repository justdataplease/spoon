package com.justdataplease.spoon.ui.explore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorePagingTest {
    @Test
    fun requestsNextPageInPrefetchWindow() {
        assertTrue(
            shouldLoadNextExplorePage(
                lastVisibleItemIndex = 25,
                totalItemCount = 30,
                recipeCount = 24,
                hasMore = true,
                isLoadingPage = false,
            ),
        )
    }

    @Test
    fun doesNotRequestWhilePageIsLoading() {
        assertFalse(
            shouldLoadNextExplorePage(
                lastVisibleItemIndex = 29,
                totalItemCount = 30,
                recipeCount = 24,
                hasMore = true,
                isLoadingPage = true,
            ),
        )
    }

    @Test
    fun doesNotRequestAfterFinalPageOrBeforeFirstResults() {
        assertFalse(
            shouldLoadNextExplorePage(
                lastVisibleItemIndex = 29,
                totalItemCount = 30,
                recipeCount = 24,
                hasMore = false,
                isLoadingPage = false,
            ),
        )
        assertFalse(
            shouldLoadNextExplorePage(
                lastVisibleItemIndex = 2,
                totalItemCount = 3,
                recipeCount = 0,
                hasMore = true,
                isLoadingPage = false,
            ),
        )
    }

    @Test
    fun doesNotRequestAwayFromEnd() {
        assertFalse(
            shouldLoadNextExplorePage(
                lastVisibleItemIndex = 12,
                totalItemCount = 30,
                recipeCount = 24,
                hasMore = true,
                isLoadingPage = false,
            ),
        )
    }
}
