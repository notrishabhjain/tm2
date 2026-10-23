package com.taskmind.core

import java.time.Instant

/**
 * When to tap you on the shoulder about a task, without being asked.
 *
 * Before this, a task found in a message got a due date and nothing else: no
 * reminder was ever set, so "send the file by 6 pm" was written down and then
 * never mentioned again. A reminder is the difference between a list you have
 * to remember to look at and one that comes to you.
 *
 * The rules, in the order they are tried - the first one still in the future
 * wins:
 *
 *  - A task due at a real time gets a reminder [LEAD_MINUTES] before it, or at
 *    the time itself if that is too close.
 *  - A task with only a day (stored at [DateResolver.DEFAULT_HOUR], which is
 *    how a bare date is represented) gets a reminder at [MORNING_HOUR] that
 *    morning - the useful moment to hear about something due "today" - and
 *    falls back to shortly before the end-of-day default if the morning has
 *    already gone.
 *  - Anything already past gets nothing. A reminder for a deadline that has
 *    gone is an overdue badge, and the list already shows those.
 *
 * Pure, so it can be tested without a device.
 */
object SmartReminder {

    const val LEAD_MINUTES = 30L
    const val MORNING_HOUR = 9

    private const val MINUTE = 60_000L

    /** A reminder must be at least this far ahead to be worth scheduling. */
    private const val MIN_AHEAD = MINUTE

    /** True when [dueAt] carries no time of its own, only a day. */
    fun isDateOnly(dueAt: Long): Boolean {
        val local = Instant.ofEpochMilli(dueAt).atZone(DateResolver.IST)
        return local.hour == DateResolver.DEFAULT_HOUR && local.minute == 0 &&
            local.second == 0 && local.nano == 0
    }

    /** The reminder a task due at [dueAt] should get, or null for none. */
    fun forDue(dueAt: Long?, now: Long): Long? {
        if (dueAt == null) return null
        val lead = dueAt - LEAD_MINUTES * MINUTE
        val candidates = if (isDateOnly(dueAt)) {
            listOf(morningOf(dueAt), lead)
        } else {
            listOf(lead, dueAt)
        }
        return candidates.firstOrNull { it >= now + MIN_AHEAD }
    }

    /** [MORNING_HOUR]:00 IST on the day [millis] falls on. */
    fun morningOf(millis: Long): Long =
        Instant.ofEpochMilli(millis)
            .atZone(DateResolver.IST)
            .toLocalDate()
            .atTime(MORNING_HOUR, 0)
            .atZone(DateResolver.IST)
            .toInstant()
            .toEpochMilli()

    /**
     * What the person picked in a reminder control.
     *
     * Kept separate from the instant it produces because the instant depends
     * on the due date, and the due date can change after the choice is made.
     */
    enum class Choice(val label: String) {
        SMART("Automatic"),
        AT_TIME("At the time"),
        HOUR_BEFORE("1 hour before"),
        MORNING("9 am that day"),
        NONE("No reminder"),
    }

    /** The instant [choice] means for a task due at [dueAt], or null. */
    fun resolve(choice: Choice, dueAt: Long?, now: Long): Long? {
        if (dueAt == null) return null
        val at = when (choice) {
            Choice.SMART -> return forDue(dueAt, now)
            Choice.AT_TIME -> dueAt
            Choice.HOUR_BEFORE -> dueAt - 60 * MINUTE
            Choice.MORNING -> morningOf(dueAt)
            Choice.NONE -> return null
        }
        return at.takeIf { it >= now + MIN_AHEAD }
    }

    /**
     * The choice that best describes an existing reminder, so an editor opens
     * showing what is actually set rather than resetting it.
     */
    fun describe(reminderAt: Long?, dueAt: Long?, now: Long): Choice {
        if (reminderAt == null) return Choice.NONE
        if (dueAt == null) return Choice.SMART
        return when (reminderAt) {
            dueAt -> Choice.AT_TIME
            dueAt - 60 * MINUTE -> Choice.HOUR_BEFORE
            morningOf(dueAt) -> if (isDateOnly(dueAt)) Choice.SMART else Choice.MORNING
            else -> Choice.SMART
        }
    }

    /** [dueAt]'s day at [hour]:[minute] IST. */
    fun withTime(dueAt: Long, hour: Int, minute: Int): Long =
        Instant.ofEpochMilli(dueAt)
            .atZone(DateResolver.IST)
            .toLocalDate()
            .atTime(hour, minute)
            .atZone(DateResolver.IST)
            .toInstant()
            .toEpochMilli()

    /**
     * [day]'s date at [hour]:[minute] IST, as a real time.
     *
     * 18:00:00 exactly is how "no time" is stored, so a task someone actually
     * wants at six in the evening is nudged one second - invisible, but no
     * longer read as "any time".
     */
    fun timed(day: Long, hour: Int, minute: Int): Long {
        val at = withTime(day, hour, minute)
        return if (isDateOnly(at)) at + 1_000L else at
    }

    /** [dueAt]'s day with no time of its own (the end-of-day default). */
    fun withoutTime(dueAt: Long): Long = withTime(dueAt, DateResolver.DEFAULT_HOUR, 0)
}
