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
    DONE,
    REJECTED,
    FAILED_PERMANENT,
}

enum class ReviewState { PENDING, ACCEPTED, DISMISSED }

enum class CallDirection { INCOMING, OUTGOING, MISSED, REJECTED, UNKNOWN }

enum class CallState { PENDING_RECORDING, PENDING_TRANSCRIPTION, TRANSCRIBED, NO_RECORDING, FAILED, DONE }

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
