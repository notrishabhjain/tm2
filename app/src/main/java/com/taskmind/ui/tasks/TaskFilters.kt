package com.taskmind.ui.tasks

import com.taskmind.core.DateResolver
import com.taskmind.core.Priority
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.entity.TaskEntity

/** Spec 16 - the views. Every one of them has an empty state. */
enum class TaskView(val label: String, val emptyTitle: String, val emptyBody: String) {
    /**
     * Everything that needs attention, in one scroll.
     *
     * The separate Today / Upcoming / Overdue chips each answered one question
     * and hid the other two, so knowing what was actually on meant tapping
     * through all three. This is the default now: overdue first, because that
     * is what a person opens the app to find out.
     */
    AGENDA(
        "Agenda",
        "Nothing on",
        "Overdue, today and upcoming tasks appear here together. Anything captured from a " +
            "message or a call lands here automatically.",
    ),
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
                // Everything active with a date, plus the undated, sectioned
                // by AgendaSection below rather than filtered out here.
                TaskView.AGENDA -> task.status == TaskStatus.ACTIVE
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

    /**
     * Where a task sits on the agenda.
     *
     * Ordered by how much it matters that you see it: something overdue is
     * more urgent than something due later today, and an undated task is not
     * urgent at all but must not become invisible.
     */
    enum class AgendaSection(val label: String) {
        OVERDUE("Overdue"),
        TODAY("Today"),
        TOMORROW("Tomorrow"),
        THIS_WEEK("This week"),
        LATER("Later"),
        NO_DATE("No date"),
    }

    fun sectionFor(task: TaskEntity, now: Long): AgendaSection {
        val due = task.dueAt ?: return AgendaSection.NO_DATE
        val endOfToday = DateResolver.endOfDay(now)
        val endOfTomorrow = endOfToday + DAY_MILLIS
        val endOfWeek = endOfToday + 7 * DAY_MILLIS
        return when {
            due < DateResolver.startOfDay(now) -> AgendaSection.OVERDUE
            due <= endOfToday -> AgendaSection.TODAY
            due <= endOfTomorrow -> AgendaSection.TOMORROW
            due <= endOfWeek -> AgendaSection.THIS_WEEK
            else -> AgendaSection.LATER
        }
    }

    /** The agenda, section by section, with empty sections omitted. */
    fun agenda(tasks: List<TaskEntity>, now: Long): List<Pair<AgendaSection, List<TaskEntity>>> =
        tasks.groupBy { sectionFor(it, now) }
            .toList()
            .sortedBy { it.first.ordinal }
            .map { (section, rows) ->
                section to rows.sortedWith(
                    compareBy<TaskEntity> { it.dueAt ?: Long.MAX_VALUE }
                        .thenBy { priorityRank(it.priority) },
                )
            }

    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

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
