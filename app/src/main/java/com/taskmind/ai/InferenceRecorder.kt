package com.taskmind.ai

/**
 * Records what was actually sent to a cloud model and what came back.
 *
 * The point is auditability. TaskMind's privacy statement says it sends message
 * text and call audio to a provider the user configures; this is what lets them
 * check that claim against the bytes instead of believing it.
 */
interface InferenceRecorder {

    suspend fun record(call: RecordedCall)

    /** A recorder that drops everything, for tests and for the no-op path. */
    object None : InferenceRecorder {
        override suspend fun record(call: RecordedCall) = Unit
    }
}

data class RecordedCall(
    /** extract-message | extract-transcript | verify | asr | connection-test */
    val kind: String,
    val baseUrl: String,
    val model: String,
    val startedAt: Long,
    val durationMillis: Long,
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val httpStatus: Int? = null,
    val ok: Boolean,
    val responseBody: String? = null,
    val totalTokens: Int? = null,
    val errorText: String? = null,
    val rawCaptureId: String? = null,
    val sourceLabel: String? = null,
) {
    companion object {
        const val KIND_MESSAGE = "extract-message"
        const val KIND_TRANSCRIPT = "extract-transcript"
        const val KIND_VERIFY = "verify"
        const val KIND_ASR = "asr"
        const val KIND_TEST = "connection-test"

        /**
         * Prompts and replies are kilobytes each and the table keeps a hundred
         * of them. Truncation is marked so a clipped body is never mistaken for
         * the model returning something short.
         */
        const val MAX_STORED_CHARS = 12_000

        fun clip(text: String?): String? {
            if (text == null) return null
            if (text.length <= MAX_STORED_CHARS) return text
            return text.take(MAX_STORED_CHARS) +
                "\n\n[... truncated, ${text.length - MAX_STORED_CHARS} more characters]"
        }
    }
}

/** Identifies which capture a call belongs to, so a task can be traced back. */
data class TraceContext(
    val kind: String,
    val rawCaptureId: String? = null,
    val sourceLabel: String? = null,
)
