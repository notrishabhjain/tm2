package com.taskmind.data.repo

import com.taskmind.core.Importance
import com.taskmind.core.Standing
import com.taskmind.core.TaskStatus
import com.taskmind.core.WaitingOn
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.tagging.AutoTagger

/**
 * The bridge from the task table to the pure ranking rules in [Importance]
 * and [Standing]. The task list, the morning brief and the People view all
 * convert through here, so they rank and describe tasks the same way.
 */
object TaskRanking {

    fun person(task: TaskEntity): String? = AutoTagger.parseLabel(task.sourceType, task.sourceLabel).person

    fun input(task: TaskEntity): Importance.Input = Importance.Input(
        id = task.id,
        title = task.title,
        priority = task.priority,
        dueAt = task.dueAt,
        createdAt = task.createdAt,
        updatedAt = task.updatedAt,
        tags = task.tags,
        notes = task.notes,
        person = person(task),
        waitingOn = WaitingOn.matches(task.title, task.tags),
    )

    fun standing(task: TaskEntity): Standing.Task = Standing.Task(
        title = task.title,
        dueAt = task.dueAt,
        waitingOn = WaitingOn.matches(task.title, task.tags),
        person = person(task),
        updatedAt = task.updatedAt,
    )

    /** Active, top-level: what can be ranked. */
    fun rankable(tasks: List<TaskEntity>): List<TaskEntity> =
        tasks.filter { it.status == TaskStatus.ACTIVE && it.parentTaskId == null }

    /** The senders the user named in About me, plus those approved as key people in Rules. */
    fun keyPeople(tasks: List<TaskEntity>, aboutMe: String, approved: Set<String> = emptySet()): Set<String> =
        Importance.keyPeople(aboutMe, tasks.mapNotNull { person(it) }.distinct()) + approved

    /** Focus, best first, as tasks with the line that explains each. */
    fun focus(
        tasks: List<TaskEntity>,
        aboutMe: String,
        now: Long,
        limit: Int = Importance.FOCUS_SIZE,
        approvedKeyPeople: Set<String> = emptySet(),
    ): List<Pair<TaskEntity, String?>> {
        val open = rankable(tasks)
        val byId = open.associateBy { it.id }
        return Importance.focus(open.map(::input), now, keyPeople(open, aboutMe, approvedKeyPeople), limit)
            .mapNotNull { (input, score) -> byId[input.id]?.let { it to score.why } }
    }
}
