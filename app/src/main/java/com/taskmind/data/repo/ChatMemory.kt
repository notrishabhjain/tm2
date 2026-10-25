package com.taskmind.data.repo

import com.taskmind.core.ChatContext
import com.taskmind.core.FollowUp
import com.taskmind.core.Priority
import com.taskmind.core.SourceType
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.dao.ProjectDao
import com.taskmind.data.db.dao.RawCaptureDao
import com.taskmind.data.db.dao.TaskDao
import com.taskmind.data.db.entity.ProjectEntity
import com.taskmind.data.db.entity.RawCaptureEntity
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.tagging.AutoTagger
import java.util.UUID

/**
 * What the extraction pipeline remembers: the chat a message came from, the
 * open tasks with that person or group, and the matters the user tracks.
 *
 * It reads that context, and it applies what the model says back about
 * tasks that already exist - a follow-up, a hint that one is done, which
 * matter one belongs to. It never creates a task; that is still only the
 * intake funnel's job.
 */
class ChatMemory(
    private val rawCaptureDao: RawCaptureDao,
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
) {

    /** The context for one capture. Empty when nothing relevant is known. */
    suspend fun contextFor(capture: RawCaptureEntity): ChatContext.Block {
        val chat = when (capture.sourceType) {
            SourceType.NOTIFICATION -> ChatContext.parseLabel(capture.contextLabel)
            else -> ChatContext.Chat(callee(capture), null)
        }
        val recent = if (capture.sourceType == SourceType.NOTIFICATION && capture.sourceApp != null) {
            rawCaptureDao.recentFromApp(
                app = capture.sourceApp,
                from = capture.occurredAt - ChatContext.RECENT_WINDOW_MILLIS,
                to = capture.occurredAt,
                excludeId = capture.id,
                limit = RECENT_SCAN,
            )
                .filter { ChatContext.sameChat(chat, ChatContext.parseLabel(it.contextLabel)) }
                .mapNotNull { c ->
                    val text = c.rawText?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ChatContext.Line(c.occurredAt, ChatContext.parseLabel(c.contextLabel).sender, text)
                }
        } else {
            emptyList()
        }

        val projects = projectDao.all()
        val names = projects.associate { it.id to it.name }
        val tasks = taskDao.openCaptured(OPEN_SCAN)
            .filter { belongsTo(it, chat) }
            .map { ChatContext.OpenTask(it.id, it.title, it.dueAt, it.projectId?.let(names::get)) }

        return ChatContext.block(recent, tasks, projects.map { ChatContext.Matter(it.id, it.name) })
    }

    /**
     * Whether a task came from this person or group. A group's tasks are
     * that group's; a one-to-one chat or a call matches the person's
     * one-to-one tasks and calls, not everything they said in groups.
     */
    private fun belongsTo(task: TaskEntity, chat: ChatContext.Chat): Boolean {
        val parsed = AutoTagger.parseLabel(task.sourceType, task.sourceLabel)
        return if (chat.group != null) {
            parsed.group.equals(chat.group, ignoreCase = true)
        } else {
            parsed.group == null && !parsed.person.isNullOrBlank() &&
                parsed.person.equals(chat.sender, ignoreCase = true)
        }
    }

    private fun callee(capture: RawCaptureEntity): String {
        val label = capture.sourceLabel ?: capture.contextLabel
        return AutoTagger.parseLabel(SourceType.CALL, label).person ?: label.orEmpty()
    }

    // ---------------------------------------------------------------- writes

    /** Applies a follow-up to an open task. Returns what changed, or null if the task is gone. */
    suspend fun applyUpdate(
        taskId: String,
        newDueAt: Long?,
        priority: Priority,
        who: String,
        quote: String?,
        at: Long,
        projectId: String?,
    ): List<String>? {
        val task = taskDao.byId(taskId)?.takeIf { it.status == TaskStatus.ACTIVE } ?: return null
        val now = System.currentTimeMillis()
        val patch = FollowUp.update(task.current(), newDueAt, priority, who, quote, at, now)
        val project = projectId ?: task.projectId
        val changes = patch.changes + if (project != task.projectId) listOf("matter") else emptyList()
        if (changes.isEmpty()) return emptyList()
        taskDao.update(task.with(patch).copy(projectId = project, updatedAt = now))
        return changes
    }

    /** Marks an open task as possibly done. Returns what changed, or null if the task is gone. */
    suspend fun markMaybeDone(taskId: String, who: String, quote: String?, at: Long): List<String>? {
        val task = taskDao.byId(taskId)?.takeIf { it.status == TaskStatus.ACTIVE } ?: return null
        val patch = FollowUp.maybeDone(task.current(), who, quote, at)
        if (patch.changes.isEmpty()) return emptyList()
        taskDao.update(task.with(patch).copy(updatedAt = System.currentTimeMillis()))
        return patch.changes
    }

    /**
     * The project id for a matter the model chose, creating it when new.
     * A name that differs only in case from an existing matter is that matter.
     */
    suspend fun matterId(choice: ChatContext.MatterChoice): String? = when (choice) {
        is ChatContext.MatterChoice.Existing -> choice.id
        ChatContext.MatterChoice.None -> null
        is ChatContext.MatterChoice.New -> {
            val existing = projectDao.all()
            existing.firstOrNull { it.name.equals(choice.name, ignoreCase = true) }?.id ?: run {
                val id = UUID.randomUUID().toString()
                projectDao.upsert(
                    ProjectEntity(
                        id = id,
                        name = choice.name,
                        sortOrder = existing.size,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                id
            }
        }
    }

    /** Files a task the funnel just created under a matter, unless it already has one. */
    suspend fun assignMatter(taskId: String, projectId: String) {
        val task = taskDao.byId(taskId) ?: return
        if (task.projectId != null) return
        taskDao.update(task.copy(projectId = projectId, updatedAt = System.currentTimeMillis()))
    }

    private fun TaskEntity.current() = FollowUp.Current(notes, dueAt, priority, tags, reminderAt)

    private fun TaskEntity.with(p: FollowUp.Patch) =
        copy(notes = p.notes, dueAt = p.dueAt, priority = p.priority, tags = p.tags, reminderAt = p.reminderAt)

    private companion object {
        /** Wider than the context, because other chats in the same app are filtered out after. */
        const val RECENT_SCAN = 60
        const val OPEN_SCAN = 300
    }
}
