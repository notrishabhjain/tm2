package com.taskmind.core

/**
 * One order for everything: how much a task matters right now, and why.
 *
 * The model's priority is one opinion about one message. What the user needs
 * is an order across everything open, which depends on things the model
 * never saw: how close the deadline is today, how many times the person has
 * chased it since, whether the person is someone they said matters, and how
 * long it has sat untouched. Those are rules, so they live here, in tested
 * code, and every score comes with the reasons that made it.
 *
 * The Focus view, the morning's "3 things today" and the matter summaries all
 * read from this. Nothing is stored; the score is recomputed whenever the list
 * is, so it moves with the clock.
 */
object Importance {

    data class Input(
        val id: String,
        val title: String,
        val priority: Priority,
        val dueAt: Long?,
        val createdAt: Long,
        val updatedAt: Long,
        val tags: List<String>,
        val notes: String?,
        /** Who asked, from the source label. Null for typed tasks and unknown senders. */
        val person: String?,
        val waitingOn: Boolean,
    ) {
        val maybeDone: Boolean get() = FollowUp.MAYBE_DONE_TAG in tags
    }

    data class Score(val points: Int, val reasons: List<String>) {
        /** The one line shown with the task: the biggest reason it is here. */
        val why: String? get() = reasons.firstOrNull()
    }

    /** Most tasks a Focus list shows. Short on purpose: it is the list you actually do. */
    const val FOCUS_SIZE = 5

    /** Below this a task is real but not pressing, and Focus leaves it for Later. */
    const val FOCUS_MIN = 25

    private const val DAY = 24L * 60 * 60 * 1000

    /** Dated follow-up lines in the notes, as [FollowUp] writes them. */
    private val FOLLOW_UP = Regex("^\\[[A-Z][a-z]{2} \\d{1,2} [A-Z][a-z]{2}] .+")

    fun score(task: Input, now: Long, keyPeople: Set<String> = emptySet()): Score {
        // Each part is (points, reason). Reasons are shown largest first, so
        // the line under a task names what actually put it there.
        val parts = mutableListOf<Pair<Int, String?>>()

        parts += when (task.priority) {
            Priority.URGENT -> 40 to "Marked urgent"
            Priority.HIGH -> 28 to "High priority"
            Priority.MEDIUM -> 15 to null
            Priority.LOW -> 5 to null
        }

        task.dueAt?.let { due ->
            val today = DateResolver.startOfDay(now)
            val tomorrow = DateResolver.nextMidnight(now)
            parts += when {
                due < today -> 35 to "Overdue"
                due < now && !SmartReminder.isDateOnly(due) -> 35 to "Overdue"
                due < tomorrow -> 30 to "Due today"
                due < tomorrow + DAY -> 18 to "Due tomorrow"
                due < today + 7 * DAY -> 8 to null
                else -> 0 to null
            }
        }

        val nudges = nudges(task.notes)
        if (nudges > 0) {
            val who = task.person?.substringBefore(' ') ?: "They"
            parts += minOf(nudges, 3) * 10 to if (nudges == 1) "$who followed up" else "$who followed up $nudges times"
        }

        if (task.person != null && keyPeople.any { it.equals(task.person, ignoreCase = true) }) {
            parts += 20 to "${task.person} is a key person"
        }

        // Untouched for weeks, no date, nothing pressing: it is sinking, and
        // it should sink. The weekly review offers to drop these.
        if (task.dueAt == null && task.priority.ordinal >= Priority.MEDIUM.ordinal) {
            val idle = (now - maxOf(task.createdAt, task.updatedAt)) / DAY
            if (idle >= 30) parts += -20 to null else if (idle >= 14) parts += -10 to null
        }

        if (task.waitingOn) parts += -25 to null
        if (task.maybeDone) parts += -30 to null

        val reasons = parts.filter { it.first > 0 && it.second != null }
            .sortedByDescending { it.first }
            .map { it.second!! }
        return Score(parts.sumOf { it.first }.coerceAtLeast(0), reasons)
    }

    /** How many times someone has followed up on this task, from its notes. */
    fun nudges(notes: String?): Int =
        notes?.lines()?.count { line -> FOLLOW_UP.matches(line.trim()) && !line.trimEnd().endsWith("maybe done?") } ?: 0

    /**
     * The tasks worth doing now, best first. Somebody else's move and
     * anything that may already be done are left out: Focus is your list.
     */
    fun focus(
        tasks: List<Input>,
        now: Long,
        keyPeople: Set<String> = emptySet(),
        limit: Int = FOCUS_SIZE,
    ): List<Pair<Input, Score>> =
        tasks.asSequence()
            .filter { !it.waitingOn && !it.maybeDone }
            .map { it to score(it, now, keyPeople) }
            .filter { it.second.points >= FOCUS_MIN }
            .sortedWith(
                compareByDescending<Pair<Input, Score>> { it.second.points }
                    .thenBy { it.first.dueAt ?: Long.MAX_VALUE },
            )
            .take(limit)
            .toList()

    /**
     * The people the user named in About me, among those who have sent tasks.
     *
     * Matched on whole words, case-insensitively, so "Priya" in "Priya
     * (finance) approves all POs" counts and "Ram" inside "programme" does not.
     * A one-word first name is enough: About me is written the way people
     * talk, and nobody writes surnames there.
     */
    fun keyPeople(aboutMe: String, senders: Collection<String>): Set<String> {
        if (aboutMe.isBlank()) return emptySet()
        val text = aboutMe.lowercase()
        return senders.filter { name ->
            val first = name.trim().substringBefore(' ').lowercase()
            first.length >= 3 && Regex("(^|[^\\p{L}])${Regex.escape(first)}([^\\p{L}]|$)").containsMatchIn(text)
        }.toSet()
    }
}
