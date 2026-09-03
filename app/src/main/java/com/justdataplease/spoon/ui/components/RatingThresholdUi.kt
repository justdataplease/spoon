package com.justdataplease.spoon.ui.components

internal const val MAX_RATING_THRESHOLD = 9

internal fun ratingThresholdLabel(threshold: Int): String {
    require(threshold in 0..MAX_RATING_THRESHOLD)
    return if (threshold == 0) "Οποιαδήποτε" else "Πάνω από $threshold/10"
}
