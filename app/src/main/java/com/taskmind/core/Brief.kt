package com.taskmind.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The two moments a day TaskMind speaks first.
 *
 * Morning: what is on today, what is already late, who you are waiting on.
 * Evening: what slipped, so it can be moved to tomorrow in one tap instead of
 * turning into tomorrow's overdue pile.
 *
 * Silence is a valid answer. A brief with nothing in it is not sent - a
 * notification that says "nothing to do" every day is one people learn to
 * swipe away unread, and then the day it matters it is swiped away too.
 *
 * Pure, so what it says can be tested without a phone.
 */
object Brief {

    enum class Kind { MORNING, EVENING }

    /** The few facts about a task a brief needs. */
    data class Item(val title: String, val dueAt: Long?, val waitingOn: Boolean)

    data class Message(
        val title: String,
        /** One line per task, most urgent first, already capped. */
        val lines: List<String>,
        /** Tasks the evening "move to tomorrow" button would move. Empty in the morning. */
        val slipped: Int,
    )

    private const val MAX_LINES = 5
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /** A calendar event today. [start] is null for an all-day event. */
    data class Meeting(val start: Long?, val title: String)

    /**
     * [active] must be the active tasks only. [meetings] are today's calendar
     * events, read only when the person turned that on; the morning uses
     * them, the evening does not.
     */
    fun compose(kind: Kind, active: List<Item>, now: Long, meetings: List<Meeting> = emptyList()): Message? =
        when (kind) {
            Kind.MORNING -> morning(active, now, meetings)
            Kind.EVENING -> evening(active, now)
        }

    private fun morning(active: List<Item>, now: Long, meetings: List<Meeting>): Message? {
        val startOfToday = DateResolver.startOfDay(now)
        val endOfToday = DateResolver.endOfDay(now)
        val overdue = active.filter { it.dueAt != null && it.dueAt < startOfToday }
        val today = active.filter { it.dueAt != null && it.dueAt in startOfToday..endOfToday }
            .sortedBy { it.dueAt }
        val waiting = active.count { it.waitingOn }
        if (overdue.isEmpty() && today.isEmpty() && waiting == 0 && meetings.isEmpty()) return null

        val title = listOfNotNull(
            meetings.size.takeIf { it > 0 }?.let { if (it == 1) "1 meeting" else "$it meetings" },
            today.size.takeIf { it > 0 }?.let { "$it due today" },
            overdue.size.takeIf { it > 0 }?.let { "$it overdue" },
            waiting.takeIf { it > 0 }?.let { "waiting on $it" },
        ).joinToString(", ").replaceFirstChar { it.uppercase() }

        // Meetings first: they are fixed in time, so the day is planned
        // around them.
        val meetingLines = meetings.sortedBy { it.start ?: 0L }.map { m ->
            val at = m.start?.let { Instant.ofEpochMilli(it).atZone(DateResolver.IST).format(TIME) } ?: "All day"
            "$at  Meeting: ${m.title}"
        }
        val lines = (meetingLines + overdue.sortedBy { it.dueAt }.map { "Late: ${it.title}" } + today.map { line(it) })
            .take(MAX_LINES + 2)
        return Message(title, lines, slipped = 0)
    }

    private fun evening(active: List<Item>, now: Long): Message? {
        val endOfToday = DateResolver.endOfDay(now)
        // Everything due by the end of today that is still open: whatever was
        // due earlier today and the backlog from before. Waiting-on tasks are
        // left out - they slip because someone else has not done something,
        // and moving them does not chase anybody.
        val slipped = active.filter { !it.waitingOn && it.dueAt != null && it.dueAt <= endOfToday }
            .sortedBy { it.dueAt }
        if (slipped.isEmpty()) return null
        val title = if (slipped.size == 1) "1 thing is still open today" else "${slipped.size} things are still open today"
        return Message(title, slipped.take(MAX_LINES).map { it.title }, slipped = slipped.size)
    }

    private fun line(item: Item): String {
        val due = item.dueAt ?: return item.title
        if (SmartReminder.isDateOnly(due)) return item.title
        return "${Instant.ofEpochMilli(due).atZone(DateResolver.IST).format(TIME)}  ${item.title}"
    }

    /** The next [hour]:00 IST strictly after [now]. */
    fun nextAt(hour: Int, now: Long): Long {
        val today = SmartReminder.withTime(DateResolver.startOfDay(now), hour, 0)
        return if (today > now) today else SmartReminder.withTime(DateResolver.nextMidnight(now), hour, 0)
    }
}
