package com.justdataplease.spoon.ui.week

import com.justdataplease.spoon.domain.repository.BackendFailure
import com.justdataplease.spoon.domain.repository.BackendFailureKind
import com.justdataplease.spoon.domain.repository.BackendState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekLoadingBannerTest {
    @Test
    fun `meal search banner is visible only during usable backend work`() {
        assertTrue(shouldShowMealSearchBanner(isWorking = true, BackendState.Cloud))
        assertTrue(shouldShowMealSearchBanner(isWorking = true, BackendState.Local))
        assertFalse(shouldShowMealSearchBanner(isWorking = false, BackendState.Cloud))
    }

    @Test
    fun `backend connection and errors use status banner without search overlay`() {
        val offline = BackendState.Error(
            BackendFailure(BackendFailureKind.NETWORK, isRetryable = true, message = "offline"),
        )

        assertFalse(shouldShowMealSearchBanner(isWorking = true, BackendState.Connecting))
        assertFalse(shouldShowMealSearchBanner(isWorking = true, offline))
    }
}
