package com.taskmind.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import com.taskmind.core.LlmJson.str

/**
 * The tidy-up of tasks that pre-date the second brain: filing open tasks
 * under matters, and offering to drop the ones that have gone stale.
 *
 * Both are proposals. Filing is one model call that suggests a matter per
 * task; the user sees every suggestion and applies the ones they want.
 * Dropping is a rule - untouched for weeks, nothing pressing - and a
 * dropped task is archived, so it stays under Done and can be reopened.
 */
object Cleanup {

    // -- stale -------------------------------------------------------------

    /** Untouched this long, and nothing pressing about it, and it is offered for dropping. */
    const val STALE_DAYS = 21

    /** A due date this far in the past no longer means anything. */
    const val LONG_OVERDUE_DAYS = 14

    private const val DAY = 24L * 60 * 60 * 1000

    /**
     * Open tasks nobody has touched in [STALE_DAYS] days that are not urgent
     * or high and are not due soon, oldest first. A follow-up from the other
     * person counts as a touch, so a task someone is still chasing is never
     * here.
     */
    fun stale(tasks: List<Importance.Input>, now: Long): List<Importance.Input> =
        tasks.filter { t ->
            val idle = now - maxOf(t.createdAt, t.updatedAt)
            val dueOk = t.dueAt == null || t.dueAt < now - LONG_OVERDUE_DAYS * DAY
            idle >= STALE_DAYS * DAY && dueOk && t.priority.ordinal >= Priority.MEDIUM.ordinal
        }.sortedBy { maxOf(it.createdAt, it.updatedAt) }

    // -- filing ------------------------------------------------------------

    /** Most tasks sent in one call. More than this and the model starts losing track. */
    const val BATCH = 60

    data class Task(val id: String, val title: String, val from: String?)

    /** One suggestion: this task under this matter. [matterId] is null for a new matter. */
    data class Assignment(val taskId: String, val title: String, val matter: String, val matterId: String?)

    val SYSTEM: String = """
You sort a person's open to-do items into MATTERS. A matter is an ongoing piece of work with a
person, group or goal - a project, a deal, a hire, a case, a household thing ("Vendor PO",
"SRE hiring", "Flat rent"). Not a category like "work" or "emails".

You get the open tasks, numbered T1, T2, with who each came from, and the matters the person
already has, numbered M1, M2.

For each task:
- If it belongs to an existing matter, give that matter's number ("M2").
- If several tasks plainly belong to the same ongoing work that has no matter yet, give them
  the SAME new name, 2-4 words, in English ("Office move").
- If it is a one-off errand with nothing to group it with, give null. Most lists have many of
  these, and leaving them unfiled is correct.

Never invent a matter for a single task. Prefer an existing matter over a new one when either fits.

Return ONLY this JSON, no markdown:
{"assign":[{"task":"T1","matter":"M2"},{"task":"T2","matter":"Office move"},{"task":"T3","matter":null}]}
""".trim()

    fun userPrompt(tasks: List<Task>, matters: List<ChatContext.Matter>): String = buildString {
        if (matters.isNotEmpty()) {
            append("Matters the person already has:\n")
            matters.forEachIndexed { i, m -> append('M').append(i + 1).append(". ").append(m.name).append('\n') }
            append('\n')
        }
        append("Open tasks:\n")
        tasks.forEachIndexed { i, t ->
            append('T').append(i + 1).append(". ").append(t.title.replace('\n', ' '))
            t.from?.let { append(" (from ").append(it).append(')') }
            append('\n')
        }
    }

    /**
     * The model's answer as suggestions. A new matter name that only one task
     * got is dropped - a matter of one is clutter - and names that differ only
     * in case are one matter.
     */
    fun parse(content: String, tasks: List<Task>, matters: List<ChatContext.Matter>): List<Assignment>? {
        val obj = LlmJson.parseObject(content) ?: return null
        val rows = obj["assign"] as? JsonArray ?: return null
        val block = ChatContext.Block(
            recent = emptyList(),
            tasks = tasks.map { ChatContext.OpenTask(it.id, it.title, null, null) },
            matters = matters,
        )
        val byId = tasks.associateBy { it.id }
        val names = matters.associate { it.id to it.name }
        val raw = rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val taskId = ChatContext.resolveTask(row.str("task"), block) ?: return@mapNotNull null
            when (val choice = ChatContext.resolveMatter(row.str("matter"), block)) {
                is ChatContext.MatterChoice.Existing ->
                    Assignment(taskId, byId.getValue(taskId).title, names.getValue(choice.id), choice.id)
                is ChatContext.MatterChoice.New -> Assignment(taskId, byId.getValue(taskId).title, choice.name, null)
                ChatContext.MatterChoice.None -> null
            }
        }.distinctBy { it.taskId }

        val newCounts = raw.filter { it.matterId == null }.groupingBy { it.matter.lowercase() }.eachCount()
        val canonical = raw.filter { it.matterId == null }.groupBy { it.matter.lowercase() }.mapValues { it.value.first().matter }
        return raw.mapNotNull { a ->
            if (a.matterId != null) return@mapNotNull a
            val key = a.matter.lowercase()
            if ((newCounts[key] ?: 0) < 2) null else a.copy(matter = canonical.getValue(key))
        }
    }
}
