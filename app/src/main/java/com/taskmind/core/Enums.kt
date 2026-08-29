package com.taskmind.core

/**
 * Shared vocabulary. These live in `core` (no `android.*` imports) so that the
 * intake funnel and its unit tests can use them without an Android runtime.
 */

enum class Priority { URGENT, HIGH, MEDIUM, LOW;

    companion object {
        fun parse(raw: String?): Priority =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: MEDIUM
    }
}

enum class TaskStatus { ACTIVE, COMPLETED, ARCHIVED, DELETED }

enum class SourceType { NOTIFICATION, CALL, CLIPBOARD, MANUAL, REVIEW }

/**
 * Spec 6.2. A capture never leaves the pipeline by being deleted; it moves
 * between these states and stays recoverable. Principle 1: never silently lose
 * a capture.
 */
enum class CaptureState {
    PENDING_TRANSCRIPTION,
    PENDING_EXTRACTION,
    BUDGET_HELD,

    /**
     * The provider rejected the request itself - wrong model, blocked model,
     * rejected key. Retrying cannot fix it, and retrying anyway is expensive:
     * these captures previously parked in PENDING_EXTRACTION with no next
     * attempt time, which the work queue reads as "due now", so every drain
     * re-sent them. On the device that spent an entire day's request quota on a
     * model that could never answer.
     *
     * Nothing here is retried until the provider settings actually change, or
     * the user asks for a retry.
     */
    BLOCKED_CONFIG,

    /**
     * A recording exists but nobody asked for it to be transcribed.
     *
     * The device that prompted this had 6463 recordings on it. Uploading all of
     * them is minutes of audio per call against a daily budget, for calls the
     * user may have no interest in. When automatic transcription is off, calls
     * park here and the Recordings screen is where they get picked.
     */
    AWAITING_SELECTION,

    DONE,
    REJECTED,
    FAILED_PERMANENT,
}

enum class ReviewState { PENDING, ACCEPTED, DISMISSED }

enum class CallDirection { INCOMING, OUTGOING, MISSED, REJECTED, UNKNOWN }

enum class CallState {
    PENDING_RECORDING,
    PENDING_TRANSCRIPTION,

    /**
     * The recording was found, but automatic transcription is off so nobody
     * has asked for it yet. Distinct from PENDING_TRANSCRIPTION so the Calls
     * screen stops reporting fifteen calls queued when the queue holds one.
     */
    AWAITING_SELECTION,

    TRANSCRIBED,
    NO_RECORDING,
    FAILED,
    DONE,
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

enum class AsrProvider { OPENAI_COMPATIBLE, SARVAM }

/** Stage labels used by the activity log. Spec 15 wants every stage traceable. */
object Stage {
    const val CAPTURE = "capture"
    const val PREFILTER = "prefilter"
    const val TRANSCRIBE = "transcribe"
    const val EXTRACT = "extract"
    const val VERIFY = "verify"
    const val FUNNEL = "funnel"
    const val CALL = "call"
    const val BUDGET = "budget"
    const val WORKER = "worker"
    const val UPDATE = "update"
    const val SELFTEST = "selftest"
    const val SYSTEM = "system"
    const val IMPORT = "import"
}
