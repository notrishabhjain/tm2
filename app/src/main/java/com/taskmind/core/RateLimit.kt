package com.taskmind.core

/**
 * Reading "when may I ask again?" out of a 429.
 *
 * The device log that prompted this showed 250 requests spent against a model
 * that could never answer, then:
 *
 *     HTTP 429: Rate limit reached for model `groq/compound` ... on requests
 *     per day (RPD): Limit 250, Used 250, Requested 1. Please try again in
 *     5m45.6s.
 *
 * A rate limit is not a property of the capture that happened to hit it, so it
 * must not consume that capture's retry budget, and every other capture in the
 * queue is going to get the same answer. The whole provider goes quiet until
 * the reset instead.
 */
object RateLimit {

    /** Providers occasionally suggest a wait longer than any sane hold. */
    const val MAX_COOLDOWN_MILLIS = 6L * 60 * 60 * 1000

    /** Nothing useful in the body: wait long enough not to spend the quota. */
    const val DEFAULT_COOLDOWN_MILLIS = 15L * 60 * 1000

    /** "try again in 5m45.6s", "in 1h2m3s", "in 30s", "in 0.5s". */
    private val DURATION = Regex(
        "try again in\\s+(?:(\\d+(?:\\.\\d+)?)h)?(?:(\\d+(?:\\.\\d+)?)m(?!s))?(?:(\\d+(?:\\.\\d+)?)s)?",
        RegexOption.IGNORE_CASE,
    )

    /** A bare `Retry-After: 120`, which is seconds by RFC 9110. */
    private val RETRY_AFTER_SECONDS = Regex("^\\s*(\\d+)\\s*$")

    /**
     * How long to hold off, in milliseconds.
     *
     * [retryAfterHeader] is the response's `Retry-After` if it had one; the
     * body is parsed only when the header is absent or unusable, because the
     * header is the contractual answer and the prose is a courtesy.
     */
    fun cooldownMillis(retryAfterHeader: String?, body: String?): Long {
        fromHeader(retryAfterHeader)?.let { return it.coerceIn(1_000, MAX_COOLDOWN_MILLIS) }
        fromBody(body)?.let { return it.coerceIn(1_000, MAX_COOLDOWN_MILLIS) }
        return DEFAULT_COOLDOWN_MILLIS
    }

    fun fromHeader(header: String?): Long? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        val seconds = RETRY_AFTER_SECONDS.find(value)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return seconds * 1000
    }

    fun fromBody(body: String?): Long? {
        if (body.isNullOrBlank()) return null
        val match = DURATION.find(body) ?: return null
        val (h, m, s) = match.destructured
        val hours = h.toDoubleOrNull() ?: 0.0
        val minutes = m.toDoubleOrNull() ?: 0.0
        val seconds = s.toDoubleOrNull() ?: 0.0
        val total = (hours * 3_600 + minutes * 60 + seconds) * 1000
        // A match on "try again in" with no numbers at all is not a duration.
        return if (total <= 0.0) null else total.toLong()
    }

    /**
     * Whether the limit that was hit resets daily. A per-minute limit is worth
     * waiting out; a daily one means the app should say so plainly rather than
     * implying the wait is short.
     */
    fun isDailyLimit(body: String?): Boolean =
        body != null && Regex("per day|\\bRPD\\b|\\bTPD\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)
}
