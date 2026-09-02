package com.justdataplease.spoon.data.model

import kotlinx.serialization.Serializable

/** Difficulty is derived only from the number of preparation steps. */
@Serializable
enum class EaseLevel(
    val key: String,
    val greekLabel: String,
) {
    EASY("easy", "Εύκολο"),
    MODERATE("moderate", "Μέτριο"),
    INVOLVED("involved", "Πιο απαιτητικό"),
    UNKNOWN("unknown", "Χωρίς εκτίμηση"),
    ;

    companion object {
        fun fromKey(key: String): EaseLevel? = entries.firstOrNull { it.key == key }

        fun fromStepCount(stepCount: Int): EaseLevel = when {
            stepCount <= 0 -> UNKNOWN
            stepCount <= 5 -> EASY
            stepCount <= 9 -> MODERATE
            else -> INVOLVED
        }

        fun fromWorkload(preparationCount: Int, stepCount: Int): EaseLevel {
            val preparations = preparationCount.coerceAtLeast(0)
            val steps = stepCount.coerceAtLeast(0)
            return when {
                preparations == 0 && steps == 0 -> UNKNOWN
                preparations >= 3 || steps >= 10 -> INVOLVED
                preparations <= 1 && steps in 1..5 -> EASY
                else -> MODERATE
            }
        }
    }
}
