package com.taskmind.data.repo

import com.taskmind.core.LogLevel
import com.taskmind.core.Priority
import com.taskmind.core.Recurrence
import com.taskmind.core.ReviewState
import com.taskmind.core.SourceType
import com.taskmind.core.Stage
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.dao.CallRecordDao
import com.taskmind.data.db.dao.ProjectDao
import com.taskmind.data.db.dao.RawCaptureDao
import com.taskmind.data.db.dao.ReviewItemDao
import com.taskmind.data.db.dao.TagDao
import com.taskmind.data.db.dao.TaskDao
import com.taskmind.data.db.entity.ProjectEntity
import com.taskmind.data.db.entity.TagEntity
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.intake.IntakeFunnel
import com.taskmind.intake.IntakeResult
import com.taskmind.intake.TaskCandidate
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * The task manager (spec 16). Creation always goes through the intake funnel;
 * everything else - edits, completion, archiving - operates on rows that
 * already exist and is safe to do directly.
 */
class TaskRepository(
    private val taskDao: TaskDao,
    private val reviewItemDao: ReviewItemDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val rawCaptureDao: RawCaptureDao,
    private val callRecordDao: CallRecordDao,
    private val funnel: IntakeFunnel,
    private val logger: ActivityLogger,
) {

    // ----------------------------------------------------------- observation

    fun observeTasks(): Flow<List<TaskEntity>> = taskDao.observeAll()
    fun observeTask(id: String): Flow<TaskEntity?> = taskDao.observeById(id)
    fun observeSubTasks(parentId: String): Flow<List<TaskEntity>> = taskDao.observeSubTasks(parentId)
    fun observeProjects(): Flow<List<ProjectEntity>> = projectDao.observeAll()
    fun observeTags(): Flow<List<TagEntity>> = tagDao.observeAll()
    fun observePendingReview() = reviewItemDao.observePending()
    fun observePendingReviewCount() = reviewItemDao.observePendingCount()
    fun observeCalls(limit: Int = 100) = callRecordDao.observeRecent(limit)

    suspend fun byId(id: String): TaskEntity? = taskDao.byId(id)

    // -------------------------------------------------------------- creation

    /**
     * Manual entry. Goes through the funnel like every other source - that is
     * the point of spec 5, and `IntakeFunnelTest` asserts all five sources
     * converge here.
     */
    suspend fun createManualTask(
        title: String,
        notes: String? = null,
        dueAt: Long? = null,
        priority: Priority = Priority.MEDIUM,
        projectId: String? = null,
        tags: List<String> = emptyList(),
        reminderAt: Long? = null,
        recurrenceRule: String? = null,
        parentTaskId: String? = null,
    ): IntakeResult {
        tags.forEach { tagDao.insert(TagEntity(it, System.currentTimeMillis())) }
        return funnel.submit(
            TaskCandidate(
                title = title,
                evidence = null,
                priority = priority,
                dueAtRaw = null,
                notes = notes,
                confidence = null,
                sourceType = SourceType.MANUAL,
                sourceRef = null,
                sourceLabel = null,
                sourceApp = null,
                rawCaptureId = null,
                inferenceOrigin = "manual",
                sourceText = "",
                occurredAt = System.currentTimeMillis(),
                tags = tags,
                projectId = projectId,
                reminderAt = reminderAt,
                recurrenceRule = recurrenceRule,
                parentTaskId = parentTaskId,
            ),
        ).also { result ->
            // A manual task has no dueAtRaw string, so set the date afterwards.
            if (result is IntakeResult.Created && dueAt != null) {
                taskDao.setDueAt(result.taskId, dueAt, System.currentTimeMillis())
            }
        }
    }

    // -------------------------------------------------------------- mutation

    suspend fun update(task: TaskEntity) {
        taskDao.update(task.copy(updatedAt = System.currentTimeMillis()))
    }

    /**
     * Completing a recurring task creates its next instance from the rule
     * (spec 16), through the funnel like everything else.
     */
    suspend fun complete(id: String): String? {
        val now = System.currentTimeMillis()
        val task = taskDao.byId(id) ?: return null
        taskDao.setStatus(id, TaskStatus.COMPLETED, now, now)

        val rule = task.recurrenceRule
        if (!Recurrence.isValid(rule)) return null

        val nextDue = Recurrence.next(rule, task.dueAt ?: now) ?: return null
        val result = funnel.submit(
            TaskCandidate(
                title = task.title,
                // A recurring instance is not a fresh claim about a source
                // text - it is the user's own repetition of a task they already
                // accepted. It is submitted as MANUAL so that it skips the
                // evidence check and so that the unique index does not read it
                // as a duplicate of the instance just completed. The original
                // label and evidence are carried for provenance display.
                evidence = task.evidence,
                priority = task.priority,
                dueAtRaw = null,
                notes = task.notes,
                confidence = null,
                sourceType = SourceType.MANUAL,
                sourceRef = null,
                sourceLabel = task.sourceLabel,
                sourceApp = task.sourceApp,
                rawCaptureId = null,
                inferenceOrigin = "recurrence:$rule",
                sourceText = "",
                occurredAt = now,
                tags = task.tags,
                projectId = task.projectId,
                recurrenceRule = rule,
                parentTaskId = task.parentTaskId,
            ),
        )
        val newId = (result as? IntakeResult.Created)?.taskId
        if (newId != null) {
            taskDao.setDueAt(newId, nextDue, now)
            if (task.reminderAt != null && task.dueAt != null) {
                taskDao.setReminder(newId, nextDue - (task.dueAt - task.reminderAt), now)
            }
            logger.write(Stage.SYSTEM, LogLevel.INFO, "recurring task rolled forward", task.title)
        }
        return newId
    }

    suspend fun reopen(id: String) {
        val now = System.currentTimeMillis()
        taskDao.setStatus(id, TaskStatus.ACTIVE, null, now)
    }

    suspend fun archive(id: String) {
        taskDao.setStatus(id, TaskStatus.ARCHIVED, null, System.currentTimeMillis())
    }

    suspend fun unarchive(id: String) = reopen(id)

    /** Soft delete: recoverable until the user empties it (spec 16). */
    suspend fun delete(id: String) {
        taskDao.setStatus(id, TaskStatus.DELETED, null, System.currentTimeMillis())
    }

    suspend fun restore(id: String) = reopen(id)

    suspend fun bulkSetStatus(ids: List<String>, status: TaskStatus) {
        val now = System.currentTimeMillis()
        taskDao.setStatusBulk(ids, status, if (status == TaskStatus.COMPLETED) now else null, now)
    }

    suspend fun bulkSetPriority(ids: List<String>, priority: Priority) {
        taskDao.setPriorityBulk(ids, priority.name, System.currentTimeMillis())
    }

    suspend fun setReminder(id: String, reminderAt: Long?) {
        taskDao.setReminder(id, reminderAt, System.currentTimeMillis())
    }

    suspend fun setDueAt(id: String, dueAt: Long?) {
        taskDao.setDueAt(id, dueAt, System.currentTimeMillis())
    }

    suspend fun purgeDeleted() {
        val deleted = taskDao.allForExport().filter { it.status == TaskStatus.DELETED }.map { it.id }
        if (deleted.isNotEmpty()) taskDao.hardDelete(deleted)
    }

    // ------------------------------------------------------------ projects

    suspend fun createProject(name: String): String {
        val id = UUID.randomUUID().toString()
        projectDao.upsert(ProjectEntity(id = id, name = name.trim(), createdAt = System.currentTimeMillis()))
        return id
    }

    suspend fun deleteProject(id: String) = projectDao.delete(id)

    suspend fun createTag(name: String) {
        tagDao.insert(TagEntity(name.trim(), System.currentTimeMillis()))
    }

    // -------------------------------------------------------- review inbox

    /**
     * Spec 14.2: accepting a review item calls IntakeFunnel.submit() with
     * sourceType = REVIEW. It does NOT insert directly.
     */
    suspend fun acceptReviewItem(id: String): IntakeResult? {
        val item = reviewItemDao.byId(id) ?: return null
        val result = funnel.submit(
            TaskCandidate(
                title = item.title,
                evidence = item.evidence,
                priority = item.priority,
                dueAtRaw = null,
                notes = item.notes,
                // The user has looked at the source text and said yes. That is
                // a stronger signal than any model confidence.
                confidence = 1.0,
                sourceType = SourceType.REVIEW,
                sourceRef = item.sourceRef,
                sourceLabel = item.sourceLabel,
                sourceApp = item.sourceApp,
                rawCaptureId = item.rawCaptureId,
                inferenceOrigin = item.inferenceOrigin,
                sourceText = item.sourceText,
                occurredAt = item.occurredAt,
            ),
        )
        if (result is IntakeResult.Created) {
            item.dueAt?.let { taskDao.setDueAt(result.taskId, it, System.currentTimeMillis()) }
        }
        reviewItemDao.setState(id, ReviewState.ACCEPTED)
        return result
    }

    suspend fun dismissReviewItem(id: String) {
        reviewItemDao.setState(id, ReviewState.DISMISSED)
        logger.write(Stage.FUNNEL, LogLevel.INFO, "review item dismissed", id)
    }

    // -------------------------------------------------------------- context

    suspend fun rawCaptureFor(task: TaskEntity) = task.rawCaptureId?.let { rawCaptureDao.byId(it) }

    suspend fun allForExport(): List<TaskEntity> = taskDao.allForExport()

    suspend fun totalTasks(): Int = taskDao.total()
}
