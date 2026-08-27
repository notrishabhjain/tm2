package com.taskmind.core

import java.time.Instant

/**
 * Spec 16 - recurring tasks. Rule grammar: DAILY | WEEKLY | MONTHLY |
 * CUSTOM:<n><unit>, where unit is one of d, w, m.
 *
 * On completion the next instance is created from the rule.
 */
object Recurrence {

    private val CUSTOM = Regex("^CUSTOM:(\\d{1,3})([dwmDWM])$")

    fun isValid(rule: String?): Boolean {
        if (rule.isNullOrBlank()) return false
        val r = rule.trim().uppercase()
        return r == "DAILY" || r == "WEEKLY" || r == "MONTHLY" || CUSTOM.matches(rule.trim())
    }

    fun describe(rule: String?): String? {
        if (!isValid(rule)) return null
        val r = rule!!.trim()
        return when (r.uppercase()) {
            "DAILY" -> "Repeats daily"
            "WEEKLY" -> "Repeats weekly"
            "MONTHLY" -> "Repeats monthly"
            else -> {
                val m = CUSTOM.find(r)!!
                val n = m.groupValues[1]
                val unit = when (m.groupValues[2].lowercase()) {
                    "d" -> if (n == "1") "day" else "days"
                    "w" -> if (n == "1") "week" else "weeks"
                    else -> if (n == "1") "month" else "months"
                }
                "Repeats every $n $unit"
            }
        }
    }

    /**
     * @param from the anchor to advance from - the completed instance's due
     *   date when it had one, otherwise the completion moment.
     * @return the next occurrence in epoch millis, or null when the rule is
     *   not a rule.
     */
    fun next(rule: String?, from: Long): Long? {
        if (!isValid(rule)) return null
        val r = rule!!.trim()
        val zoned = Instant.ofEpochMilli(from).atZone(DateResolver.IST)
        val advanced = when (r.uppercase()) {
            "DAILY" -> zoned.plusDays(1)
            "WEEKLY" -> zoned.plusWeeks(1)
            "MONTHLY" -> zoned.plusMonths(1)
            else -> {
                val m = CUSTOM.find(r)!!
                val n = m.groupValues[1].toLong()
                when (m.groupValues[2].lowercase()) {
                    "d" -> zoned.plusDays(n)
                    "w" -> zoned.plusWeeks(n)
                    else -> zoned.plusMonths(n)
                }
            }
        }
        return advanced.toInstant().toEpochMilli()
    }
}
