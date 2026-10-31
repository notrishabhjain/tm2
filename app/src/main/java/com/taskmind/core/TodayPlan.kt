package com.taskmind.core

/**
 * The Today list: what the user chose for today, what they chose earlier and
 * did not finish, and what TaskMind suggests adding.
 *
 * Inbox-first means nothing reaches Today on its own. Focus becomes a source
 * of suggestions; the user decides. Pure - the screen and the morning brief
 * both read it.
 */
object TodayPlan {

    data class Task(
        val id: String,
        val plannedFor: String?,
        val inbox: Boolean,
        val input: Importance.Input,
    )

    data class Plan(
        /** Planned for today, in the order they were ranked. */
        val today: List<Task>,
        /** Planned for an earlier day and still open: offered to move to today. */
        val carried: List<Task>,
        /** Not planned, but worth a look: Focus picks, and anything due today or late. */
        val suggested: List<Pair<Task, String?>>,
    )

    /** Most suggestions offered at once; a plan of fifteen is not a plan. */
    const val MAX_SUGGESTED = 6

    fun plan(open: List<Task>, todayKey: String, now: Long, keyPeople: Set<String> = emptySet()): Plan {
        val byId = open.associateBy { it.id }
        val ranked = { list: List<Task> ->
            list.sortedByDescending { Importance.score(it.input, now, keyPeople).points }
        }
        val today = ranked(open.filter { it.plannedFor == todayKey })
        val carried = ranked(open.filter { it.plannedFor != null && it.plannedFor < todayKey })

        val unplanned = open.filter { it.plannedFor == null && !it.inbox }
        val focus = Importance.focus(unplanned.map { it.input }, now, keyPeople, MAX_SUGGESTED)
            .mapNotNull { (input, score) -> byId[input.id]?.let { it to score.why } }
        val endOfToday = DateResolver.endOfDay(now)
        val due = unplanned
            .filter { t -> t.input.dueAt != null && t.input.dueAt <= endOfToday && focus.none { it.first.id == t.id } }
            .sortedBy { it.input.dueAt }
            .map { t -> t to (if (t.input.dueAt!! < DateResolver.startOfDay(now)) "Overdue" else "Due today") }

        return Plan(today, carried, (focus + due).take(MAX_SUGGESTED))
    }
}
