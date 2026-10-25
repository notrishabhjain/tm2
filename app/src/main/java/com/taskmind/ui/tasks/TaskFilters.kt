package com.taskmind.ui.tasks

import com.taskmind.core.DateResolver
import com.taskmind.core.WaitingOn
import com.taskmind.core.Priority
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.tagging.AutoTagger

/** Spec 16 - the views. Every one of them has an empty state. */
enum class TaskView(val label: String, val emptyTitle: String, val emptyBody: String) {
    AGENDA(
        "Agenda",
        "Nothing on",
        "Overdue, today and upcoming tasks appear here together. Anything captured from a " +
            "message or a call lands here automatically.",
    ),
    ALL(
        "All",
        "No tasks yet",
        "Add one with the button below, or let TaskMind find them in your messages and calls.",
    ),

    /** Somebody else's move: promises made to you, things to chase. */
    WAITING(
        "Waiting on",
        "Nobody owes you anything",
        "When someone promises you something - \"I'll confirm by tomorrow\" - it lands here, " +
            "so you know who to chase.",
    ),

    /**
     * Completed and archived together.
     *
     * Archive used to be its own state with its own view, sitting between
     * Done and Delete. Nobody needs three ways to get a task off the list, so
     * it is no longer offered - but tasks archived before that still exist,
     * and they live here rather than nowhere.
     */
    COMPLETED(
        "Done",
        "Nothing done yet",
        "Tasks you tick off appear here, newest first.",
    ),
}

enum class SortMode(val label: String) {
    DUE_DATE("Due date"),
    PRIORITY("Priority"),
    CREATED("Recently added"),
}

/**
 * How the list is arranged beyond sorting.
 *
 * By-date and by-project used to be listed here and were never implemented -
 * the agenda already groups by date and projects have their own filter, so
 * both would have been a second way to do something the screen already does.
 * Bundling is the one grouping that shows something a sort cannot.
 */
enum class GroupMode(val label: String) {
    /** The default: work the model filed under a matter, then everything else as usual. */
    MATTER("By matter"),
    NONE("Flat list"),
    RELATED("Group related"),
}

object TaskFilters {

    /**
     * The tasks a view could show, before any search, project or tag filter.
     *
     * Pulled out of [apply] because the filter row needs it: the tags on
     * offer have to come from the same set of tasks the view is drawing from,
     * or a tag outlives the last task carrying it.
     */
    fun scope(tasks: List<TaskEntity>, view: TaskView, now: Long): List<TaskEntity> {
        return tasks.filter { task ->
            when (view) {
                // Everything active with a date, plus the undated, sectioned
                // by AgendaSection below rather than filtered out here.
                TaskView.AGENDA -> task.status == TaskStatus.ACTIVE
                TaskView.ALL -> task.status == TaskStatus.ACTIVE
                TaskView.WAITING -> task.status == TaskStatus.ACTIVE && WaitingOn.matches(task.title, task.tags)
                TaskView.COMPLETED ->
                    task.status == TaskStatus.COMPLETED || task.status == TaskStatus.ARCHIVED
            }
        }
    }

    fun apply(
        tasks: List<TaskEntity>,
        view: TaskView,
        query: String,
        projectId: String?,
        tag: String?,
        sort: SortMode,
        now: Long,
    ): List<TaskEntity> {
        val byView = scope(tasks, view, now)

        val byProject = if (projectId == null) byView else byView.filter { it.projectId == projectId }

        // A tag filter matches tags you typed AND tags the app worked out, so
        // tapping "whatsapp" or "Sharma Ji" on a task filters by it without
        // anyone having had to tag anything by hand.
        val byTag = if (tag == null) {
            byProject
        } else {
            val wanted = tag.lowercase()
            byProject.filter { task ->
                task.tags.any { it.equals(tag, ignoreCase = true) } || wanted in autoKeys(task)
            }
        }

        val trimmed = query.trim()
        val bySearch = if (trimmed.isEmpty()) byTag else byTag.filter { matches(it, trimmed) }

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
     * Everything one task can be found by.
     *
     * The old version searched the title, the notes and the source label. That
     * missed the two things most worth searching for: the evidence quote,
     * which is the actual sentence somebody said, and the tags - so looking
     * for "whatsapp" or a person's name found nothing unless their name
     * happened to be in the title.
     */
    fun matches(task: TaskEntity, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return task.title.contains(q, ignoreCase = true) ||
            task.notes?.contains(q, ignoreCase = true) == true ||
            task.sourceLabel?.contains(q, ignoreCase = true) == true ||
            task.evidence?.contains(q, ignoreCase = true) == true ||
            task.tags.any { it.contains(q, ignoreCase = true) } ||
            autoKeys(task).any { it.contains(q.lowercase()) }
    }

    /** The derived tags for a task, as lower-case strings. */
    fun autoKeys(task: TaskEntity): List<String> = AutoTagger.keys(
        sourceType = task.sourceType,
        sourceApp = task.sourceApp,
        sourceLabel = task.sourceLabel,
        title = task.title,
        evidence = task.evidence,
    )

    /** The derived tags for a task, with their kinds, for display. */
    fun autoTags(task: TaskEntity): List<AutoTagger.Tag> = AutoTagger.tags(
        sourceType = task.sourceType,
        sourceApp = task.sourceApp,
        sourceLabel = task.sourceLabel,
        title = task.title,
        evidence = task.evidence,
    )

    /**
     * The tags across a set of tasks, most common first.
     *
     * What the filter row offers. Ordering by frequency means the people and
     * apps you actually deal with are the ones in reach, rather than whatever
     * sorts first alphabetically.
     *
     * These are derived, never stored, so a tag exists exactly as long as a
     * task carrying it does. Pass the tasks the current view can show and the
     * chip for "Sharma Ji" disappears the moment his last task is ticked off,
     * which is the only behaviour that makes sense for a tag nobody typed.
     *
     * [selected] is kept in the list even if it falls outside [limit], because
     * a filter you cannot see is a filter you cannot turn off.
     */
    fun tagCloud(
        tasks: List<TaskEntity>,
        selected: String? = null,
        limit: Int = 12,
    ): List<AutoTagger.Tag> {
        val ranked = tasks.flatMap { autoTags(it) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<AutoTagger.Tag, Int>> { it.value }.thenBy { it.key.value })
            .map { it.key }
        val shown = ranked.take(limit)
        if (selected == null || shown.any { it.value.equals(selected, ignoreCase = true) }) return shown
        val pinned = ranked.firstOrNull { it.value.equals(selected, ignoreCase = true) } ?: return shown
        return listOf(pinned) + shown.dropLast(1)
    }

    /**
     * Whether any of these tasks still carries this tag, typed or derived.
     *
     * The test behind clearing a filter that has run out of tasks.
     */
    fun hasTag(tasks: List<TaskEntity>, tag: String): Boolean {
        val wanted = tag.lowercase()
        return tasks.any { task ->
            task.tags.any { it.equals(tag, ignoreCase = true) } || wanted in autoKeys(task)
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
