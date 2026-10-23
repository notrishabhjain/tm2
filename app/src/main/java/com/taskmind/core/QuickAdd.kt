package com.taskmind.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Reads a date and time out of a typed task, the way people write them.
 *
 * "Call Rahul tomorrow 5pm" becomes "Call Rahul", due tomorrow at 17:00.
 * "Send invoice by friday" becomes "Send invoice", due Friday with no time.
 * Hinglish day words are understood too: aaj, kal, parso.
 *
 * Deliberately local and rule-based rather than another model call: it runs
 * on every keystroke, offline, for free, and a rule that misses a phrasing
 * just leaves the date for the person to pick - nothing is lost.
 *
 * "kal" means both yesterday and tomorrow in Hindi. Nobody writes a new task
 * for yesterday, so here it is always tomorrow.
 */
object QuickAdd {

    data class Result(
        /** The title with the date words taken out. */
        val title: String,
        /** Null when no date or time was found. */
        val dueAt: Long?,
        /** The words that were read as the date, for showing back. */
        val matched: String?,
    )

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        // No "sat" or "sun": "sat with Rahul" and "sun screen" are not dates.
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    // Day phrases. Longest alternatives first so "day after tomorrow" is not
    // read as "tomorrow".
    private val DAY = Regex(
        "\\b(?:(?:by|on|before|till|until)\\s+)?" +
            "(day after tomorrow|tomorrow|tmrw|tmr|today|tonight|aaj|kal|parso|" +
            "(?:next\\s+|this\\s+)?(?:" + WEEKDAYS.keys.sortedByDescending { it.length }.joinToString("|") + ")" +
            "|in\\s+(\\d{1,2})\\s+(days?|weeks?))\\b",
        RegexOption.IGNORE_CASE,
    )

    // Times: "5pm", "5 pm", "5:30pm", "at 5", "at 17:30", "17:30", "5 baje",
    // and the parts of the day.
    private val TIME = Regex(
        "\\b(?:(?:at|by|@)\\s*)?(?:" +
            "(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje)" +
            "|(?<=\\bat\\s|@)(\\d{1,2})(?::(\\d{2}))?" +
            "|(\\d{1,2}):(\\d{2})" +
            "|(morning|subah|noon|afternoon|evening|shaam|sham|night|raat)" +
            ")\\b",
        RegexOption.IGNORE_CASE,
    )

    private val IN_HOURS = Regex(
        "\\bin\\s+(\\d{1,3})\\s*(hours?|hrs?|h|minutes?|mins?|m)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String, now: Long): Result {
        var rest = text
        val matchedParts = mutableListOf<String>()

        // "in 2 hours" is a complete answer by itself.
        IN_HOURS.find(rest)?.let { m ->
            val n = m.groupValues[1].toLong()
            val unit = m.groupValues[2].lowercase()
            val millis = if (unit.startsWith("h")) n * 3_600_000L else n * 60_000L
            return Result(clean(rest.removeRange(m.range)), now + millis, m.value.trim())
        }

        val today = Instant.ofEpochMilli(now).atZone(DateResolver.IST).toLocalDate()
        var day: LocalDate? = null
        var impliedHour: Int? = null

        DAY.find(rest)?.let { m ->
            val word = m.groupValues[1].lowercase().replace(Regex("\\s+"), " ")
            day = when {
                word == "today" || word == "aaj" -> today
                word == "tonight" -> today.also { impliedHour = 21 }
                word == "tomorrow" || word == "tmrw" || word == "tmr" || word == "kal" -> today.plusDays(1)
                word == "day after tomorrow" || word == "parso" -> today.plusDays(2)
                word.startsWith("in ") -> {
                    val n = m.groupValues[2].toLong()
                    if (m.groupValues[3].lowercase().startsWith("week")) today.plusWeeks(n) else today.plusDays(n)
                }
                else -> {
                    val next = word.startsWith("next ")
                    val name = word.removePrefix("next ").removePrefix("this ").trim()
                    val dow = WEEKDAYS[name] ?: return@let
                    // "friday" on a Friday means today; "next friday" means a week on.
                    val candidate = today.with(TemporalAdjusters.nextOrSame(dow))
                    if (next && candidate == today) candidate.plusWeeks(1) else candidate
                }
            }
            matchedParts += m.value.trim()
            rest = rest.removeRange(m.range)
        }

        var hour: Int? = null
        var minute = 0
        TIME.find(rest)?.let { m ->
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> {
                    var h = g[1].toInt()
                    minute = g[2].toIntOrNull() ?: 0
                    when (g[3].lowercase()) {
                        "pm" -> if (h < 12) h += 12
                        "am" -> if (h == 12) h = 0
                        // "5 baje" carries no am/pm: working hours decide.
                        else -> h = assumeWorkingHours(h)
                    }
                    hour = h
                }
                g[4].isNotEmpty() -> {
                    hour = assumeWorkingHours(g[4].toInt())
                    minute = g[5].toIntOrNull() ?: 0
                }
                g[6].isNotEmpty() -> {
                    hour = g[6].toInt()
                    minute = g[7].toInt()
                }
                else -> hour = when (g[8].lowercase()) {
                    "morning", "subah" -> 9
                    "noon" -> 12
                    "afternoon" -> 15
                    "evening", "shaam", "sham" -> 18
                    else -> 21
                }
            }
            val h = hour
            if (h == null || h !in 0..23 || minute !in 0..59) {
                hour = null
                return@let
            }
            matchedParts += m.value.trim()
            rest = rest.removeRange(m.range)
        }
        if (hour == null) hour = impliedHour

        if (day == null && hour == null) return Result(text.trim(), null, null)

        val dueAt = when {
            day != null && hour == null -> SmartReminder.withoutTime(startOf(day!!))
            day != null -> SmartReminder.timed(startOf(day!!), hour!!, minute)
            else -> {
                // A time with no day: today if it is still ahead, else tomorrow.
                val todayAt = SmartReminder.timed(startOf(today), hour!!, minute)
                if (todayAt > now) todayAt else SmartReminder.timed(startOf(today.plusDays(1)), hour!!, minute)
            }
        }
        return Result(clean(rest), dueAt, matchedParts.joinToString(" "))
    }

    /** 1-7 is afternoon, 8-11 morning, 12 noon - how people mean a bare hour at work. */
    private fun assumeWorkingHours(h: Int): Int = when (h) {
        in 1..7 -> h + 12
        else -> h
    }

    private fun startOf(day: LocalDate): Long =
        day.atStartOfDay(DateResolver.IST).toInstant().toEpochMilli()

    /** Tidies what is left once the date words are gone. */
    private fun clean(text: String): String =
        text.replace(Regex("\\s+"), " ")
            .trim()
            // "Send invoice by" -> "Send invoice"
            .replace(Regex("\\s+(by|on|at|before|till|until|for)$", RegexOption.IGNORE_CASE), "")
            .trim(' ', ',', '-', '.')
}
