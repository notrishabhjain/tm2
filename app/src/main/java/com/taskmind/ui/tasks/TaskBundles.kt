package com.taskmind.ui.tasks

import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.relate.TaskRelations
import com.taskmind.tagging.AutoTagger

/**
 * The bridge between the task table and the pure relating rules.
 *
 * [TaskRelations] knows nothing about Room or about the auto-tagger; it takes
 * a small data class and returns groups of ids. This is the only place that
 * converts between the two, which keeps the rules testable on a machine with
 * no Android toolchain - which is the only kind of machine this project has.
 */
object TaskBundles {

    /** A bundle plus the tasks themselves, ready to draw. */
    data class Group(val bundle: TaskRelations.Bundle, val tasks: List<TaskEntity>)

    /**
     * The person, group and topics come from the auto-tagger rather than from
     * new columns, so this works backwards over every task already captured.
     */
    fun relatable(task: TaskEntity): TaskRelations.Relatable {
        val tags = TaskFilters.autoTags(task)
        return TaskRelations.Relatable(
            id = task.id,
            title = task.title,
            dueAt = task.dueAt,
            projectId = task.projectId,
            person = tags.firstOrNull { it.kind == AutoTagger.Kind.PERSON }?.value,
            group = tags.firstOrNull { it.kind == AutoTagger.Kind.GROUP }?.value,
            topics = tags.filter { it.kind == AutoTagger.Kind.TOPIC }.map { it.value },
        )
    }

    /**
     * The task list split into bundles, with everything unrelated left over.
     *
     * The leftovers are returned separately rather than as one-task bundles:
     * a heading above a single row is noise, and the point of this view is to
     * show what can be dealt with in one go.
     */
    fun grouped(tasks: List<TaskEntity>): Pair<List<Group>, List<TaskEntity>> {
        if (tasks.size < 2) return emptyList<Group>() to tasks
        val byId = tasks.associateBy { it.id }
        val bundles = TaskRelations.bundles(tasks.map { relatable(it) })
        val groups = bundles.mapNotNull { bundle ->
            val rows = bundle.ids.mapNotNull { byId[it] }
            if (rows.size < 2) null else Group(bundle, rows)
        }
        val taken = groups.flatMap { it.tasks }.map { it.id }.toSet()
        return groups to tasks.filter { it.id !in taken }
    }

    /**
     * The other tasks in this task's bundle.
     *
     * [pool] is everything the comparison may consider - active tasks, in
     * practice, since offering to complete something already completed is
     * not an offer.
     */
    fun relatedTo(taskId: String, pool: List<TaskEntity>): Group? {
        if (pool.size < 2) return null
        val byId = pool.associateBy { it.id }
        val bundle = TaskRelations.bundleFor(taskId, pool.map { relatable(it) }) ?: return null
        val rows = bundle.ids.mapNotNull { byId[it] }
        return if (rows.size < 2) null else Group(bundle, rows)
    }

    /**
     * Tasks that look like this one captured a second time.
     *
     * Shown as a question, never acted on automatically: the app has been
     * wrong about what a message meant before, and silently merging two tasks
     * would hide the evidence that says which reading was right.
     */
    fun duplicatesOf(taskId: String, pool: List<TaskEntity>): List<TaskEntity> {
        val byId = pool.associateBy { it.id }
        return TaskRelations.duplicates(pool.map { relatable(it) })
            .filter { it.keepId == taskId || it.otherId == taskId }
            .mapNotNull { byId[if (it.keepId == taskId) it.otherId else it.keepId] }
            .distinctBy { it.id }
    }
}
