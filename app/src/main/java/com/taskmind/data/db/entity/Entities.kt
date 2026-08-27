package com.taskmind.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.taskmind.core.CallDirection
import com.taskmind.core.CallState
import com.taskmind.core.CaptureState
import com.taskmind.core.LogLevel
import com.taskmind.core.Priority
import com.taskmind.core.ReviewState
import com.taskmind.core.SourceType
import com.taskmind.core.TaskStatus

/**
 * Spec 6 - the data model. Everything is local; there is no server.
 */

/**
 * The primary object.
 *
 * The unique index on (sourceType, sourceRef, titleKey) IS the dedup mechanism.
 * SQLite treats NULLs as distinct in a unique index, so manual tasks
 * (sourceRef = NULL) may legitimately repeat while automated ones cannot.
 * Dedup is enforced by the database, not by trusting callers to check first.
 */
@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["sourceType", "sourceRef", "titleKey"], unique = true),
        Index(value = ["status", "dueAt"]),
        Index(value = ["projectId"]),
        Index(value = ["parentTaskId"]),
        Index(value = ["rawCaptureId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = RawCaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["rawCaptureId"],
            // Spec 6.3: purging raw text must never delete the derived task.
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** Normalised for dedup - see spec 7.4. */
    val titleKey: String,
    val notes: String? = null,
    val dueAt: Long? = null,
    val priority: Priority = Priority.MEDIUM,
    val status: TaskStatus = TaskStatus.ACTIVE,
    val projectId: String? = null,
    val tags: List<String> = emptyList(),
    val recurrenceRule: String? = null,
    val reminderAt: Long? = null,
    val parentTaskId: String? = null,
    val sortOrder: Int = 0,

    val sourceType: SourceType = SourceType.MANUAL,
    val sourceRef: String? = null,
    /** "Sharma Ji - WhatsApp" / "Call with +9198... - 14:32". Denormalised so it
     *  survives the retention purge (spec 6.3). */
    val sourceLabel: String? = null,
    val sourceApp: String? = null,
    /** The verbatim quote that justified this task. Also denormalised. */
    val evidence: String? = null,
    val confidence: Double? = null,
    val inferenceOrigin: String? = null,
    val rawCaptureId: String? = null,

    val completedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Every capture path writes one of these and NOTHING else. Capture never writes
 * to the task table (spec 5).
 */
@Entity(
    tableName = "raw_captures",
    indices = [
        Index(value = ["state", "nextAttemptAt"]),
        Index(value = ["sourceType", "sourceRef"]),
        Index(value = ["capturedAt"]),
    ],
)
data class RawCaptureEntity(
    @PrimaryKey val id: String,
    val sourceType: SourceType,
    val sourceRef: String?,
    val sourceApp: String? = null,
    val sourceLabel: String? = null,
    /** Message text or transcript. Null while awaiting ASR. */
    val rawText: String? = null,
    val audioPath: String? = null,
    val capturedAt: Long,
    /**
     * When the message arrived or the call took place. THIS, not capturedAt, is
     * what relative dates resolve against (spec 6.2, 14.1).
     */
    val occurredAt: Long,
    val state: CaptureState,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val nextAttemptAt: Long? = null,
    /** Spec 12.3: per-chunk checkpoint so a killed process resumes. */
    val chunkIndex: Int = 0,
    val chunkTotal: Int = 0,
    val partialText: String? = null,
    /** Extra prompt context: sender, group name, contact label. */
    val contextLabel: String? = null,
)

@Entity(
    tableName = "review_items",
    indices = [Index(value = ["state", "createdAt"])],
)
data class ReviewItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String? = null,
    val dueAt: Long? = null,
    val priority: Priority = Priority.MEDIUM,
    val evidence: String? = null,
    val confidence: Double? = null,
    val reasoning: String? = null,
    val sourceText: String = "",
    val sourceType: SourceType,
    val sourceRef: String? = null,
    val sourceLabel: String? = null,
    val sourceApp: String? = null,
    val rawCaptureId: String? = null,
    val inferenceOrigin: String? = null,
    val occurredAt: Long,
    val state: ReviewState = ReviewState.PENDING,
    val createdAt: Long,
)

@Entity(
    tableName = "call_records",
    indices = [
        Index(value = ["startTime"]),
        Index(value = ["state"]),
        Index(value = ["callLogId"], unique = true),
    ],
)
data class CallRecordEntity(
    @PrimaryKey val id: String,
    val callLogId: Long? = null,
    val contactName: String? = null,
    val phoneNumber: String? = null,
    val direction: CallDirection = CallDirection.UNKNOWN,
    val startTime: Long,
    /** Nullable on purpose: the call log frequently has no duration yet.
     *  Every query over it must handle NULL explicitly (failure mode 4). */
    val durationSeconds: Long? = null,
    val recordingPath: String? = null,
    val transcript: String? = null,
    val summary: String? = null,
    val state: CallState = CallState.PENDING_RECORDING,
    val rawCaptureId: String? = null,
    val lastError: String? = null,
    val discoveryAttempts: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Cheap pre-inference reject only (spec 10.3), with a 7-day TTL.
 * NOT the dedup mechanism - the unique index on tasks is. This exists purely to
 * avoid spending money re-inferring an Android notification re-delivery.
 */
@Entity(tableName = "fingerprints", indices = [Index(value = ["seenAt"])])
data class FingerprintEntity(
    @PrimaryKey val hash: String,
    val seenAt: Long,
)

/**
 * The primary debugging instrument, because there is no local machine
 * (spec 15). Newest 500 kept, trimmed on insert.
 */
@Entity(tableName = "activity_log", indices = [Index(value = ["timestamp"]), Index(value = ["level"])])
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val stage: String,
    val level: LogLevel,
    val message: String,
    val detail: String? = null,
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Int? = null,
    val sortOrder: Int = 0,
    val createdAt: Long,
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val name: String,
    val createdAt: Long,
)

/**
 * Every package that has posted a notification since install, so the settings
 * screen can offer the allow-list from real data rather than a guess (spec 10.3).
 */
@Entity(tableName = "seen_packages")
data class SeenPackageEntity(
    @PrimaryKey val packageName: String,
    val label: String? = null,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val notificationCount: Int = 0,
)

/**
 * Every call TaskMind makes to a cloud model, with the exact prompt it sent and
 * the raw reply it got back.
 *
 * This exists because "the app sends your messages to an AI" is a claim the
 * user has to take on trust unless they can see the actual bytes. They can:
 * Settings -> Model calls shows this table, and each row expands to the full
 * system prompt, the user message, and the unedited response.
 *
 * It is capped and is cleared by "Erase all captured content", because these
 * rows contain the message text and transcripts themselves.
 */
@Entity(tableName = "inference_calls", indices = [Index(value = ["startedAt"])])
data class InferenceCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val durationMillis: Long,
    /** extract-message | extract-transcript | verify | asr | connection-test */
    val kind: String,
    val baseUrl: String,
    val model: String,
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val httpStatus: Int? = null,
    val ok: Boolean,
    val responseBody: String? = null,
    val totalTokens: Int? = null,
    val errorText: String? = null,
    /** A plain-language diagnosis when the failure has a known cause. */
    val diagnosis: String? = null,
    /** Which capture this call was for, so a task can be traced back to it. */
    val rawCaptureId: String? = null,
    val sourceLabel: String? = null,
)
