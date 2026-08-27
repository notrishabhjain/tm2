package com.taskmind.data.repo

import com.taskmind.core.CaptureState
import com.taskmind.data.db.dao.RawCaptureDao
import com.taskmind.data.db.dao.ReviewItemDao
import com.taskmind.data.db.dao.TaskDao
import com.taskmind.data.db.entity.ReviewItemEntity
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.intake.CaptureMarker
import com.taskmind.intake.NewTask
import com.taskmind.intake.ReviewProposal
import com.taskmind.intake.ReviewSink
import com.taskmind.intake.TaskSink

/**
 * The Room end of the intake funnel's ports.
 *
 * This is the ONLY class in the app that calls
 * `TaskDao.insertIgnoringDuplicates`, and it is reachable only from
 * `IntakeFunnel`. `ArchitectureTest` fails the build if either of those two
 * statements stops being true - that is failure mode 1.
 */
class RoomIntakePorts(
    private val taskDao: TaskDao,
    private val reviewItemDao: ReviewItemDao,
    private val rawCaptureDao: RawCaptureDao,
) : TaskSink, ReviewSink, CaptureMarker {

    /**
     * Insert with IGNORE; a zero row-count return means the unique index on
     * (sourceType, sourceRef, titleKey) already had it. Dedup is enforced by
     * the database, not by trusting the caller to check first (spec 6).
     */
    override suspend fun insertIfAbsent(task: NewTask): Boolean {
        val rowId = taskDao.insertIgnoringDuplicates(task.toEntity())
        return rowId != -1L
    }

    override suspend fun propose(proposal: ReviewProposal) {
        reviewItemDao.insert(proposal.toEntity())
    }

    /**
     * Called only AFTER the candidate has been persisted. Marking first and
     * persisting second is failure mode 5: a transient failure then erased the
     * recording from the search path permanently.
     */
    override suspend fun markProcessed(rawCaptureId: String) {
        rawCaptureDao.setState(rawCaptureId, CaptureState.DONE)
    }

    private fun NewTask.toEntity(): TaskEntity = TaskEntity(
        id = id,
        title = title,
        titleKey = titleKey,
        notes = notes,
        dueAt = dueAt,
        priority = priority,
        projectId = projectId,
        tags = tags,
        recurrenceRule = recurrenceRule,
        reminderAt = reminderAt,
        parentTaskId = parentTaskId,
        sourceType = sourceType,
        sourceRef = sourceRef,
        sourceLabel = sourceLabel,
        sourceApp = sourceApp,
        evidence = evidence,
        confidence = confidence,
        inferenceOrigin = inferenceOrigin,
        rawCaptureId = rawCaptureId,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun ReviewProposal.toEntity(): ReviewItemEntity = ReviewItemEntity(
        id = id,
        title = title,
        notes = notes,
        dueAt = dueAt,
        priority = priority,
        evidence = evidence,
        confidence = confidence,
        reasoning = reasoning,
        sourceText = sourceText,
        sourceType = sourceType,
        sourceRef = sourceRef,
        sourceLabel = sourceLabel,
        sourceApp = sourceApp,
        rawCaptureId = rawCaptureId,
        inferenceOrigin = inferenceOrigin,
        occurredAt = occurredAt,
        createdAt = createdAt,
    )
}
