package com.taskmind.capture

import com.taskmind.core.ChatContext
import com.taskmind.core.Priority

/**
 * The phone's first reads, as the model's read of the same message sees them
 * (see InstantCapture and FirstReadMatch).
 *
 * The extraction pipeline asks which first reads are waiting on the captures
 * it is about to read, then confirms the ones the model agrees with and
 * withdraws the rest. An interface, so the pipeline does not need to know how
 * tasks are stored or how notifications are kept in step.
 */
interface FirstReads {

    /** Tasks the phone made from these captures that the model has not read yet, oldest first. */
    suspend fun pending(captureIds: Collection<String>): List<FirstRead>

    /**
     * The model agrees: the task takes the model's words and date - unless the
     * user has already changed it, in which case it is left exactly as they
     * made it. Either way no second task is made for the same ask.
     */
    suspend fun confirm(read: FirstRead, update: FirstReadUpdate)

    /** The model found no task there: take it back, quietly, unless the user has already acted on it. */
    suspend fun withdraw(read: FirstRead, reason: String)
}

data class FirstRead(
    val taskId: String,
    val rawCaptureId: String,
    val title: String,
    val evidence: String?,
)

/** What the model said, ready to apply to a first read. */
data class FirstReadUpdate(
    val title: String,
    val evidence: String?,
    val priority: Priority,
    val dueAtRaw: String?,
    val notes: String?,
    val confidence: Double?,
    val waitingOn: Boolean,
    val matter: ChatContext.MatterChoice,
    val area: String?,
    val origin: String,
    val occurredAt: Long,
)
