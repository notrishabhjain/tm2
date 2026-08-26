package com.taskmind.core

/**
 * Spec 9: retries use exponential backoff (30 s, 2 m, 10 m, 1 h, 6 h) capped at
 * 5 attempts, then FAILED_PERMANENT with the error visible in the log.
 *
 * Nothing is ever discarded for failing - a FAILED_PERMANENT capture keeps its
 * text and its audio path and can be retried by hand from the status screen.
 */
object Backoff {

    val DELAYS_MILLIS: List<Long> = listOf(
        30_000L,
        2 * 60_000L,
        10 * 60_000L,
        60 * 60_000L,
        6 * 60 * 60_000L,
    )

    const val MAX_ATTEMPTS = 5

    /**
     * @param attemptCount how many attempts have already been made, including
     *   the one that just failed.
     * @return when to try again, or null when the cap has been reached.
     */
    fun nextAttemptAt(attemptCount: Int, now: Long): Long? {
        if (attemptCount >= MAX_ATTEMPTS) return null
        val delay = DELAYS_MILLIS.getOrElse(attemptCount - 1) { DELAYS_MILLIS.last() }
        return now + delay
    }

    fun isExhausted(attemptCount: Int): Boolean = attemptCount >= MAX_ATTEMPTS
}
