package com.taskmind.core

/**
 * Sunday evening: what the week looked like, so the list can be pruned.
 *
 * Three questions, each answered with a number and a few names:
 *  - How much got done, against how much arrived?
 *  - What has been sitting longest? Old open tasks are usually either
 *    forgotten commitments or things that no longer matter - both worth a
 *    decision.
 *  - Who asks the most? Patterns in who your work comes from are invisible
 *    one task at a time.
 *
 * Pure, so what it says can be tested.
 */
object WeeklyReview {

    data class Item(
        val title: String,
        val open: Boolean,
        val createdAt: Long,
        val completedAt: Long?,
        /** The person or group it came from, or null for your own tasks. */
        val from: String?,
    )

    data class Summary(
        val done: Int,
        val arrived: Int,
        val open: Int,
        /** Up to three, oldest first, with their age in days. */
        val oldest: List<Pair<String, Int>>,
        /** Up to three, by how many open tasks came from them. */
        val topAskers: List<Pair<String, Int>>,
    ) {
        val headline: String
            get() = "$done done, $arrived new, $open still open"

        /** The notification's lines. */
        fun lines(): List<String> = buildList {
            oldest.forEach { (title, days) -> add("Open $days days: $title") }
            if (topAskers.isNotEmpty()) {
                add("Most asks: " + topAskers.joinToString(", ") { (who, n) -> "$who ($n)" })
            }
        }
    }

    private const val DAY = 24L * 60 * 60 * 1000
    private const val WEEK = 7 * DAY

    /** Null when the week had nothing in it at all. */
    fun summarise(items: List<Item>, now: Long): Summary? {
        val since = now - WEEK
        val done = items.count { !it.open && (it.completedAt ?: 0) >= since }
        val arrived = items.count { it.createdAt >= since }
        val open = items.filter { it.open }
        if (done == 0 && arrived == 0 && open.isEmpty()) return null

        val oldest = open
            .filter { now - it.createdAt >= 3 * DAY }
            .sortedBy { it.createdAt }
            .take(3)
            .map { it.title to ((now - it.createdAt) / DAY).toInt() }

        val askers = open
            .mapNotNull { it.from?.trim()?.takeIf { name -> name.isNotEmpty() } }
            .groupingBy { it }
            .eachCount()
            .filter { it.value >= 2 }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(3)
            .map { it.key to it.value }

        return Summary(done, arrived, open.size, oldest, askers)
    }

    /** The next Sunday at [hour]:00 IST strictly after [now]. */
    fun nextSunday(hour: Int, now: Long): Long {
        var day = java.time.Instant.ofEpochMilli(now).atZone(DateResolver.IST).toLocalDate()
        while (true) {
            if (day.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
                val at = day.atTime(hour, 0).atZone(DateResolver.IST).toInstant().toEpochMilli()
                if (at > now) return at
            }
            day = day.plusDays(1)
        }
    }
}
