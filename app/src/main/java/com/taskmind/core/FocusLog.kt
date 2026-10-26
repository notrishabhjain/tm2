package com.taskmind.core

import kotlin.math.roundToInt

/**
 * Whether the ranking is right: of the things the morning picked, how many
 * were done that same day.
 *
 * The picks are written down when the morning brief goes out - day and task
 * ids, nothing else - and judged later against when each task was completed.
 * A rising number means the top of the list is the part that gets done.
 * Pure; storing the entries is the caller's job.
 */
object FocusLog {

    data class Day(val startOfDay: Long, val ids: List<String>)

    /** How many days are kept and judged. */
    const val KEEP_DAYS = 14

    /** Fewer picks than this and a percentage would mislead. */
    const val MIN_PICKS = 5

    fun encode(day: Day): String = day.startOfDay.toString() + ":" + day.ids.joinToString(",")

    fun decode(raw: String): Day? {
        val start = raw.substringBefore(':').toLongOrNull() ?: return null
        val ids = raw.substringAfter(':', "").split(',').filter { it.isNotBlank() }
        return Day(start, ids)
    }

    /** Adds today's picks, replacing any earlier entry for the same day, keeping the newest [KEEP_DAYS]. */
    fun record(existing: List<Day>, today: Day): List<Day> =
        (existing.filter { it.startOfDay != today.startOfDay } + today)
            .sortedBy { it.startOfDay }
            .takeLast(KEEP_DAYS)

    /**
     * 0-100: picks completed on the day they were picked. Today is left out
     * until it is over. Null when there are too few picks to judge.
     */
    fun completion(days: List<Day>, completedAt: (String) -> Long?, now: Long): Int? {
        val judged = days.filter { DateResolver.nextMidnight(it.startOfDay) <= now }
        val picks = judged.sumOf { it.ids.size }
        if (picks < MIN_PICKS) return null
        val done = judged.sumOf { day ->
            val end = DateResolver.nextMidnight(day.startOfDay)
            day.ids.count { id -> completedAt(id)?.let { it in day.startOfDay until end } == true }
        }
        return (100.0 * done / picks).roundToInt()
    }
}
