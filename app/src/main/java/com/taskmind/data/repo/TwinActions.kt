package com.taskmind.data.repo

import com.taskmind.core.Journal
import com.taskmind.core.LogLevel
import com.taskmind.core.Priority
import com.taskmind.core.SmartReminder
import com.taskmind.core.Stage
import com.taskmind.core.TaskStatus
import com.taskmind.core.Twin
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
import com.taskmind.intake.IntakeResult

/**
 * Carries out what the user accepted from the twin, and takes it back.
 *
 * Every change goes through [TaskRepository] (a new task through the intake
 * funnel, as everywhere), and every applied batch is one [Journal] entry
 * with each task's before and after, so "Undo" can put it back exactly.
 */
class TwinActions(private val container: AppContainer) {

    data class Applied(val count: Int, val failed: Int, val entryId: String?, val created: List<String>)

    private val repo get() = container.taskRepository
    private val journal get() = container.twinJournal

    suspend fun apply(actions: List<Twin.Action>, summary: String, source: String = TwinJournal.SOURCE_TWIN): Applied {
        val now = System.currentTimeMillis()
        val changes = mutableListOf<Journal.Change>()
        val created = mutableListOf<String>()
        var failed = 0
        for (action in actions) {
            val ok = runCatching { applyOne(action, now, changes, created) }
                .onFailure { container.logger.write(Stage.SYSTEM, LogLevel.WARN, "twin action failed", it.toString()) }
                .getOrDefault(false)
            if (!ok) failed++
        }
        val entry = journal.record(source, summary, changes)
        container.logger.write(Stage.SYSTEM, LogLevel.INFO, "twin applied ${actions.size - failed} change(s)", summary)
        return Applied(actions.size - failed, failed, entry, created)
    }

    private suspend fun applyOne(
        action: Twin.Action,
        now: Long,
        changes: MutableList<Journal.Change>,
        created: MutableList<String>,
    ): Boolean {
        when (action) {
            is Twin.Action.Add -> {
                val result = repo.createManualTask(
                    title = action.title,
                    notes = action.notes,
                    dueAt = action.dueAt,
                    priority = action.priority,
                    reminderAt = SmartReminder.forDue(action.dueAt, now),
                    area = action.area,
                    plannedFor = action.day,
                )
                val id = (result as? IntakeResult.Created)?.taskId ?: return false
                created += id
                changes += Journal.Change(id, Journal.Kind.CREATED, null, repo.byId(id)?.let(TwinJournal::snapshot))
            }
            is Twin.Action.Plan -> edit(action.taskId, changes) { t ->
                t.copy(
                    plannedFor = action.day,
                    inbox = false,
                    // A start time in today's plan becomes the reminder for it.
                    reminderAt = action.at?.takeIf { it > now } ?: t.reminderAt,
                )
            } ?: return false
            is Twin.Action.Edit -> edit(action.taskId, changes) { t ->
                val due = if (action.clearDue) null else action.dueAt ?: t.dueAt
                t.copy(
                    title = action.title ?: t.title,
                    dueAt = due,
                    reminderAt = if (due != t.dueAt) SmartReminder.forDue(due, now) else t.reminderAt,
                    priority = action.priority ?: t.priority,
                    area = action.area ?: t.area,
                )
            } ?: return false
            is Twin.Action.Remind -> edit(action.taskId, changes) { t -> t.copy(reminderAt = action.at) } ?: return false
            is Twin.Action.Done -> {
                val before = repo.byId(action.taskId)?.takeIf { it.status == TaskStatus.ACTIVE } ?: return false
                val next = repo.complete(before.id)
                repo.byId(before.id)?.let { changes += TwinJournal.changed(before, it) }
                // A recurring task rolls forward; undo should take the new one back too.
                next?.let { id -> changes += Journal.Change(id, Journal.Kind.CREATED, null, repo.byId(id)?.let(TwinJournal::snapshot)) }
            }
            is Twin.Action.Drop -> {
                val before = repo.byId(action.taskId)?.takeIf { it.status == TaskStatus.ACTIVE } ?: return false
                // Archived, not deleted: dropped by the twin is easy to find again.
                repo.archive(before.id)
                repo.byId(before.id)?.let { changes += TwinJournal.changed(before, it) }
            }
            // A nudge changes nothing; the screen opens the draft instead.
            is Twin.Action.Nudge -> return true
        }
        return true
    }

    private suspend fun edit(id: String, changes: MutableList<Journal.Change>, change: (TaskEntity) -> TaskEntity): Unit? {
        val before = repo.byId(id)?.takeIf { it.status == TaskStatus.ACTIVE } ?: return null
        val after = change(before)
        if (after == before) return Unit
        repo.update(after)
        changes += TwinJournal.changed(before, repo.byId(id) ?: after)
        return Unit
    }

    /**
     * Takes back one journal entry. Returns a line for the user: what was put
     * back, and what was left because they had changed it since.
     */
    suspend fun undo(entryId: String): String {
        val entry = journal.byId(entryId) ?: return "That change is no longer in the history."
        if (entry.undone) return "Already undone."
        val current = mutableMapOf<String, TaskEntity?>()
        for (c in entry.changes) current[c.taskId] = repo.byId(c.taskId)
        val reverts = Journal.undo(entry) { id -> current[id]?.let(TwinJournal::snapshot) }
        var restored = 0
        var kept = 0
        val now = System.currentTimeMillis()
        for (r in reverts) {
            val task = current[r.taskId] ?: continue
            when (r) {
                is Journal.Revert.Remove -> {
                    // Straight to the table: an undo is not the user rejecting a
                    // task, and must not teach the rule ideas that it was.
                    container.database.taskDao().setStatus(task.id, TaskStatus.DELETED, null, now)
                    restored++
                }
                is Journal.Revert.Restore -> {
                    val to = r.to
                    val status = runCatching { TaskStatus.valueOf(to.status) }.getOrDefault(task.status)
                    if (status != task.status) {
                        when (status) {
                            TaskStatus.ACTIVE -> repo.reopen(task.id)
                            TaskStatus.ARCHIVED -> repo.archive(task.id)
                            TaskStatus.DELETED -> container.database.taskDao().setStatus(task.id, TaskStatus.DELETED, null, now)
                            TaskStatus.COMPLETED -> container.database.taskDao().setStatus(task.id, TaskStatus.COMPLETED, now, now)
                        }
                    }
                    val fresh = repo.byId(task.id) ?: task
                    val patched = fresh.copy(
                        title = to.title,
                        dueAt = to.dueAt,
                        reminderAt = to.reminderAt,
                        priority = runCatching { Priority.valueOf(to.priority) }.getOrDefault(fresh.priority),
                        area = to.area,
                        plannedFor = to.plannedFor,
                        inbox = to.inbox,
                        notes = to.notes,
                        tags = to.tags,
                        projectId = to.projectId,
                    )
                    if (patched != fresh) repo.update(patched)
                    restored++
                    kept += r.kept
                }
                is Journal.Revert.Nothing -> Unit
            }
        }
        journal.markUndone(entryId)
        container.logger.write(Stage.SYSTEM, LogLevel.INFO, "undid: ${entry.summary}", "restored=$restored kept=$kept")
        return buildString {
            append(if (restored == 0) "Nothing to undo - already as it was." else "Undone.")
            if (kept > 0) append(" $kept change(s) you made since were kept.")
        }
    }
}
