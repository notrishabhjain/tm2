package com.taskmind.core

import java.time.Instant

/**
 * Plans without a model: today's free time, a day's list with start times,
 * and a week spread over its days.
 *
 * The twin asks the model to plan when it can. This is what it does when it
 * cannot - no key, no consent, no budget left - and it is also where the
 * model's prompt gets its "free today" line from. A plan of rules is plainer
 * than the model's, but it is never missing.
 *
 * Pure: tested on fixed clocks and calendars.
 */
object DayPlanner {

    data class Slot(val start: Long, val end: Long)

    data class Pick(val taskId: String, val at: Long?, val why: String?)

    const val DAY_START_HOUR = 9
    const val DAY_END_HOUR = 19
    private const val MINUTE = 60_000L
    private const val MIN_GAP = 30 * MINUTE
    /** How long a planned task is given. A guess, but one that makes the times honest. */
    const val TASK_MINUTES = 45L
    private const val TASK = TASK_MINUTES * MINUTE

    /**
     * Free time left today, between [DAY_START_HOUR] (or now, if later) and
     * [DAY_END_HOUR], around the timed meetings. Gaps under half an hour do
     * not count; all-day events do not block time.
     */
    fun gaps(meetings: List<Twin.Meeting>, now: Long): List<Slot> {
        val midnight = DateResolver.startOfDay(now)
        val dayStart = midnight + DAY_START_HOUR * 60 * MINUTE
        val dayEnd = midnight + DAY_END_HOUR * 60 * MINUTE
        var cursor = maxOf(roundUp(now, 15 * MINUTE), dayStart)
        if (cursor >= dayEnd) return emptyList()
        val busy = meetings.mapNotNull { m ->
            val start = m.start ?: return@mapNotNull null
            start to (m.end?.takeIf { it > start } ?: (start + 30 * MINUTE))
        }.sortedBy { it.first }
        val out = mutableListOf<Slot>()
        for ((start, end) in busy) {
            if (end <= cursor) continue
            if (start > cursor) {
                val slotEnd = minOf(start, dayEnd)
                if (slotEnd - cursor >= MIN_GAP) out += Slot(cursor, slotEnd)
            }
            cursor = maxOf(cursor, end)
            if (cursor >= dayEnd) break
        }
        if (dayEnd - cursor >= MIN_GAP) out += Slot(cursor, dayEnd)
        return out
    }

    /**
     * Today's list as [TodayPlan] would build it - what is already on today,
     * what was planned earlier and is still open, then the suggestions - capped
     * at [max] (but never dropping what the user put on today themselves), with
     * start times laid into the free time in order.
     */
    fun planDay(
        open: List<TodayPlan.Task>,
        meetings: List<Twin.Meeting>,
        now: Long,
        keyPeople: Set<String> = emptySet(),
        max: Int = 5,
    ): List<Pick> {
        val plan = TodayPlan.plan(open, DateResolver.dayKey(now), now, keyPeople)
        val chosen = mutableListOf<Pair<TodayPlan.Task, String?>>()
        plan.today.forEach { chosen += it to "Already on today" }
        for (t in plan.carried) if (chosen.size < max) chosen += t to "Planned earlier, still open"
        for ((t, why) in plan.suggested) if (chosen.size < max && chosen.none { it.first.id == t.id }) chosen += t to why

        val slots = gaps(meetings, now)
        var index = 0
        var cursor = slots.firstOrNull()?.start
        return chosen.map { (task, why) ->
            var at: Long? = null
            while (index < slots.size) {
                val slot = slots[index]
                val start = maxOf(cursor ?: slot.start, slot.start)
                if (start + TASK <= slot.end) {
                    at = start
                    cursor = start + TASK
                    break
                }
                index++
                cursor = slots.getOrNull(index)?.start
            }
            Pick(task.id, at, why)
        }
    }

    /**
     * Spreads open tasks over the next [days] days, at most [perDay] a day
     * counting what is already planned there. A dated task goes on the day
     * before it is due (or the day itself, when that is today or tomorrow);
     * an undated one on the first day with room - but only when it matters
     * enough to be this week's at all. [meetingsByDay] makes a busy day
     * lighter: every two meetings take one task's place.
     */
    fun planWeek(
        open: List<TodayPlan.Task>,
        now: Long,
        keyPeople: Set<String> = emptySet(),
        perDay: Int = 4,
        days: Int = 7,
        meetingsByDay: Map<String, Int> = emptyMap(),
    ): List<Pair<String, String>> {
        val today = Instant.ofEpochMilli(now).atZone(DateResolver.IST).toLocalDate()
        val keys = (0 until days).map { today.plusDays(it.toLong()).toString() }
        val load = keys.associateWith { key ->
            open.count { it.plannedFor == key } + (meetingsByDay[key] ?: 0) / 2
        }.toMutableMap()

        val candidates = open
            .filter { !it.inbox && (it.plannedFor == null || it.plannedFor < keys.first()) }
            .map { it to Importance.score(it.input, now, keyPeople).points }
            .sortedWith(compareBy<Pair<TodayPlan.Task, Int>> { it.first.input.dueAt ?: Long.MAX_VALUE }.thenByDescending { it.second })

        val out = mutableListOf<Pair<String, String>>()
        for ((task, points) in candidates) {
            val due = task.input.dueAt
            val dueKey = due?.let { DateResolver.dayKey(it) }
            if (task.input.waitingOn) continue
            if (dueKey == null && points < Importance.FOCUS_MIN) continue
            if (dueKey != null && dueKey > keys.last() && points < Importance.FOCUS_MIN) continue
            val target = when {
                dueKey == null -> null
                dueKey <= keys.first() -> keys.first()
                dueKey > keys.last() -> keys.last()
                dueKey == keys[1] -> keys[1]
                else -> keys[keys.indexOf(dueKey) - 1]
            }
            val day = (if (target != null && (load[target] ?: 0) < perDay) target else null)
                ?: keys.filter { target == null || it <= (dueKey?.takeIf { d -> d <= keys.last() } ?: keys.last()) }
                    .firstOrNull { (load[it] ?: 0) < perDay }
                ?: continue
            load[day] = (load[day] ?: 0) + 1
            out += task.id to day
        }
        return out
    }

    private fun roundUp(at: Long, step: Long): Long = ((at + step - 1) / step) * step
}
