package com.taskmind.ui.tasks

import com.taskmind.core.DateResolver
import com.taskmind.core.Priority
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.entity.TaskEntity

/** Spec 16 - the views. Every one of them has an empty state. */
enum class TaskView(val label: String, val emptyTitle: String, val emptyBody: String) {
    TODAY(
        "Today",
        "Nothing due today",
        "Tasks due today appear here. Anything captured from a message or a call lands here automatically.",
    ),
    UPCOMING(
        "Upcoming",
        "Nothing scheduled",
        "Tasks with a due date later than today appear here.",
    ),
    OVERDUE(
        "Overdue",
        "Nothing overdue",
        "Tasks past their due date appear here. An empty list is the good outcome.",
    ),
    ALL(
        "All",
        "No tasks yet",
        "Add one with the button below, or let TaskMind find them in your messages and calls.",
    ),
    COMPLETED(
        "Completed",
        "Nothing completed yet",
        "Tasks you tick off appear here, newest first.",
    ),
    ARCHIVED(
        "Archived",
        "Nothing archived",
        "Archiving keeps a task out of the way without deleting it. Archived tasks appear here.",
    ),
}

enum class SortMode(val label: String) {
    DUE_DATE("Due date"),
    PRIORITY("Priority"),
    CREATED("Recently added"),
}

enum class GroupMode(val label: String) {
    NONE("No grouping"),
    DATE("By date"),
    PROJECT("By project"),
}

object TaskFilters {

    fun apply(
        tasks: List<TaskEntity>,
        view: TaskView,
        query: String,
        projectId: String?,
        tag: String?,
        sort: SortMode,
        now: Long,
    ): List<TaskEntity> {
        val startOfToday = DateResolver.startOfDay(now)
        val endOfToday = DateResolver.endOfDay(now)

        val byView = tasks.filter { task ->
            when (view) {
                TaskView.TODAY ->
                    task.status == TaskStatus.ACTIVE &&
                        task.dueAt != null && task.dueAt in startOfToday..endOfToday
                TaskView.UPCOMING ->
                    task.status == TaskStatus.ACTIVE && task.dueAt != null && task.dueAt > endOfToday
                TaskView.OVERDUE ->
                    task.status == TaskStatus.ACTIVE && task.dueAt != null && task.dueAt < startOfToday
                TaskView.ALL -> task.status == TaskStatus.ACTIVE
                TaskView.COMPLETED -> task.status == TaskStatus.COMPLETED
                TaskView.ARCHIVED -> task.status == TaskStatus.ARCHIVED
            }
        }

        val byProject = if (projectId == null) byView else byView.filter { it.projectId == projectId }
        val byTag = if (tag == null) byProject else byProject.filter { tag in it.tags }

        val trimmed = query.trim()
        val bySearch = if (trimmed.isEmpty()) {
            byTag
        } else {
            byTag.filter { task ->
                task.title.contains(trimmed, ignoreCase = true) ||
                    task.notes?.contains(trimmed, ignoreCase = true) == true ||
                    task.sourceLabel?.contains(trimmed, ignoreCase = true) == true
            }
        }

        // Sub-tasks are shown under their parent, not as top-level rows.
        val topLevel = bySearch.filter { it.parentTaskId == null }

        return when (sort) {
            // Tasks with no date sort last: "no date" is not "due at zero".
            SortMode.DUE_DATE -> topLevel.sortedWith(
                compareBy<TaskEntity> { it.dueAt ?: Long.MAX_VALUE }.thenBy { priorityRank(it.priority) },
            )
            SortMode.PRIORITY -> topLevel.sortedWith(
                compareBy<TaskEntity> { priorityRank(it.priority) }.thenBy { it.dueAt ?: Long.MAX_VALUE },
            )
            SortMode.CREATED -> topLevel.sortedByDescending {
                if (view == TaskView.COMPLETED) it.completedAt ?: it.updatedAt else it.createdAt
            }
        }
    }

    fun priorityRank(priority: Priority): Int = when (priority) {
        Priority.URGENT -> 0
        Priority.HIGH -> 1
        Priority.MEDIUM -> 2
        Priority.LOW -> 3
    }

    fun counts(tasks: List<TaskEntity>, now: Long): Map<TaskView, Int> =
        TaskView.entries.associateWith { view ->
            apply(tasks, view, "", null, null, SortMode.DUE_DATE, now).size
        }
}
