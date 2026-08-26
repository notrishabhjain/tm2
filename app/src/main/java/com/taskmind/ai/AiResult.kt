package com.taskmind.ai

/**
 * Spec 9: HTTP 429 and 5xx retry; 400 and 401 do not - they are configuration
 * errors and must be surfaced on the status screen instead of being retried
 * forever against a wrong base URL.
 */
sealed interface AiResult<out T> {

    data class Ok<T>(val value: T) : AiResult<T>

    data class HttpError(val code: Int, val message: String) : AiResult<Nothing> {
        val retryable: Boolean get() = code == 429 || code in 500..599 || code == 408
        val configuration: Boolean get() = code == 400 || code == 401 || code == 403 || code == 404
    }

    data class NetworkError(val message: String) : AiResult<Nothing>

    /** A 2xx whose body we could not use. Not retryable: it will not improve. */
    data class BadResponse(val message: String) : AiResult<Nothing>

    val retryable: Boolean
        get() = when (this) {
            is Ok -> false
            is HttpError -> retryable
            is NetworkError -> true
            is BadResponse -> false
        }

    val errorText: String?
        get() = when (this) {
            is Ok -> null
            is HttpError -> "HTTP $code: $message"
            is NetworkError -> "network: $message"
            is BadResponse -> "bad response: $message"
        }
}

inline fun <T, R> AiResult<T>.map(transform: (T) -> R): AiResult<R> = when (this) {
    is AiResult.Ok -> AiResult.Ok(transform(value))
    is AiResult.HttpError -> this
    is AiResult.NetworkError -> this
    is AiResult.BadResponse -> this
}
