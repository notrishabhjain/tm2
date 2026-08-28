package com.taskmind.ai

/**
 * Spec 9: HTTP 429 and 5xx retry; 400 and 401 do not - they are configuration
 * errors and must be surfaced on the status screen instead of being retried
 * forever against a wrong base URL.
 */
sealed interface AiResult<out T> {

    data class Ok<T>(val value: T) : AiResult<T>

    data class HttpError(
        val code: Int,
        val message: String,
        /** The response's `Retry-After`, when it sent one. Only 429s use it. */
        val retryAfter: String? = null,
    ) : AiResult<Nothing> {
        /** A transport or capacity problem: the same request may work later. */
        val transient: Boolean get() = code == 429 || code == 408 || code in 500..599

        /** The request itself is wrong. Retrying cannot fix it. */
        val configuration: Boolean get() = code == 400 || code == 401 || code == 403 || code == 404

        /**
         * Out of quota. Distinct from [transient] because it is not the
         * capture's fault and applies to every other queued capture equally:
         * the caller holds the whole provider rather than retrying this one.
         */
        val rateLimited: Boolean get() = code == 429
    }

    data class NetworkError(val message: String) : AiResult<Nothing>

    /** A 2xx whose body we could not use. Not retryable: it will not improve. */
    data class BadResponse(val message: String) : AiResult<Nothing>
}

val AiResult<*>.retryable: Boolean
    get() = when (this) {
        is AiResult.Ok -> false
        is AiResult.HttpError -> transient
        is AiResult.NetworkError -> true
        is AiResult.BadResponse -> false
    }

val AiResult<*>.errorText: String?
    get() = when (this) {
        is AiResult.Ok -> null
        is AiResult.HttpError -> "HTTP $code: $message"
        is AiResult.NetworkError -> "network: $message"
        is AiResult.BadResponse -> "bad response: $message"
    }

inline fun <T, R> AiResult<T>.map(transform: (T) -> R): AiResult<R> = when (this) {
    is AiResult.Ok -> AiResult.Ok(transform(value))
    is AiResult.HttpError -> this
    is AiResult.NetworkError -> this
    is AiResult.BadResponse -> this
}
