package com.taskmind.intake

import com.taskmind.core.LogLevel
import com.taskmind.core.Priority
import com.taskmind.core.SourceType

/**
 * The single input type of the intake funnel. Every source - notification,
 * call, clipboard, manual entry, an accepted review item - builds one of these
 * and hands it to [IntakeFunnel.submit]. Nothing else may create a task.
 */
data class TaskCandidate(
    val title: String,
    val evidence: String?,
    val priority: Priority = Priority.MEDIUM,
    val dueAtRaw: String? = null,
    val notes: String? = null,
    val confidence: Double? = null,
    val sourceType: SourceType,
    val sourceRef: String?,
    val sourceLabel: String? = null,
    val sourceApp: String? = null,
    val rawCaptureId: String? = null,
    val inferenceOrigin: String? = null,
    /** The text the evidence is checked against. Empty for MANUAL. */
    val sourceText: String = "",
    /**
     * When the message arrived or the call took place. Relative dates resolve
     * against this, not against now (spec 14.1).
     */
    val occurredAt: Long,
    val tags: List<String> = emptyList(),
    val projectId: String? = null,
    val reminderAt: Long? = null,
    val recurrenceRule: String? = null,
    val parentTaskId: String? = null,
    /** Model reasoning, carried to the review inbox so the user can judge it. */
    val reasoning: String? = null,
)

/** What the funnel decided to persist. Mapped to the Room entity by the sink. */
data class NewTask(
    val id: String,
    val title: String,
    val titleKey: String,
    val notes: String?,
    val dueAt: Long?,
    val priority: Priority,
    val projectId: String?,
    val tags: List<String>,
    val recurrenceRule: String?,
    val reminderAt: Long?,
    val parentTaskId: String?,
    val sourceType: SourceType,
    val sourceRef: String?,
    val sourceLabel: String?,
    val sourceApp: String?,
    val evidence: String?,
    val confidence: Double?,
    val inferenceOrigin: String?,
    val rawCaptureId: String?,
    val createdAt: Long,
)

/** A candidate the funnel was not confident enough to create outright. */
data class ReviewProposal(
    val id: String,
    val title: String,
    val notes: String?,
    val dueAt: Long?,
    val priority: Priority,
    val evidence: String?,
    val confidence: Double?,
    val reasoning: String?,
    val sourceText: String,
    val sourceType: SourceType,
    val sourceRef: String?,
    val sourceLabel: String?,
    val sourceApp: String?,
    val rawCaptureId: String?,
    val inferenceOrigin: String?,
    val occurredAt: Long,
    val createdAt: Long,
)

sealed interface IntakeResult {
    val summary: String

    data class Created(val taskId: String, val title: String) : IntakeResult {
        override val summary get() = "created"
    }

    data class Duplicate(val titleKey: String) : IntakeResult {
        override val summary get() = "duplicate"
    }

    data class SentToReview(val reviewId: String, val confidence: Double?) : IntakeResult {
        override val summary get() = "review"
    }

    data class Discarded(val reason: String) : IntakeResult {
        override val summary get() = "discarded"
    }

    data class Invalid(val reason: String) : IntakeResult {
        override val summary get() = "invalid"
    }
}

/**
 * Spec 14.2 and 13. Every threshold here is settings-tunable with a reset to
 * these defaults.
 */
data class FunnelConfig(
    val autoCreateThreshold: Double = 0.75,
    val reviewThreshold: Double = 0.40,
    val notificationTolerance: Double = 0.90,
    val clipboardTolerance: Double = 0.85,
    val callTolerance: Double = 0.75,
) {
    fun toleranceFor(sourceType: SourceType): Double = when (sourceType) {
        SourceType.NOTIFICATION -> notificationTolerance
        SourceType.CLIPBOARD -> clipboardTolerance
        SourceType.CALL -> callTolerance
        // A review item inherits its original source's text; be no stricter
        // than the loosest, because the user has already eyeballed it.
        SourceType.REVIEW -> callTolerance
        SourceType.MANUAL -> 0.0
    }

    companion object {
        val DEFAULT = FunnelConfig()
    }
}

// ---------------------------------------------------------------------------
// Ports. The funnel depends only on these, which is what makes it a pure
// function testable on a JVM with no Android runtime - and there is no local
// machine on which to test it any other way (spec 3).
// ---------------------------------------------------------------------------

interface TaskSink {
    /**
     * Inserts with OnConflictStrategy.IGNORE against the unique index on
     * (sourceType, sourceRef, titleKey).
     *
     * @return true if a row was written, false if the database already had it.
     */
    suspend fun insertIfAbsent(task: NewTask): Boolean
}

interface ReviewSink {
    suspend fun propose(proposal: ReviewProposal)
}

interface CaptureMarker {
    /** Called only AFTER a candidate has been persisted (failure mode 5). */
    suspend fun markProcessed(rawCaptureId: String)
}

interface FunnelLog {
    suspend fun log(stage: String, level: LogLevel, message: String, detail: String? = null)
}

interface TaskCreatedNotifier {
    /** Spec 17.3: the only automatic notification with sound. */
    suspend fun onTaskCreated(taskId: String, title: String)
}

interface IdGenerator {
    fun newId(): String
}

interface Clock {
    fun now(): Long
}
