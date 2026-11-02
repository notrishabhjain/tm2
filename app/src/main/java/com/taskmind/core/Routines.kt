package com.taskmind.core

/**
 * Tasks that come back on their own: rent, bills, renewals, refills.
 *
 * A routine is a recurring task with a lead time. The task is due on the day
 * (rent on the 1st); the reminder comes [leadDays] earlier, at 09:00, so the
 * money is there in time. Completing it rolls it forward through
 * [Recurrence], which keeps the same gap between reminder and due date.
 */
object Routines {

    enum class Every(val label: String, val rule: String) {
        WEEK("Every week", "WEEKLY"),
        MONTH("Every month", "MONTHLY"),
        QUARTER("Every 3 months", "CUSTOM:3m"),
        YEAR("Every year", "YEARLY"),
    }

    data class Preset(val title: String, val every: Every, val area: String, val leadDays: Int)

    /** Starting points; every field can be changed before saving. */
    val PRESETS: List<Preset> = listOf(
        Preset("Pay rent", Every.MONTH, "Home", 3),
        Preset("Pay electricity bill", Every.MONTH, "Money", 3),
        Preset("Pay credit card bill", Every.MONTH, "Money", 5),
        Preset("Refill medicines", Every.MONTH, "Health", 5),
        Preset("Renew car insurance", Every.YEAR, "Money", 30),
        Preset("Submit expense claims", Every.MONTH, "Work", 2),
    )

    val LEAD_CHOICES: List<Int> = listOf(0, 1, 3, 7, 30)

    /** 09:00 on the day [leadDays] before [dueAt]; on the day itself when 0, but never after the due time. */
    fun reminderFor(dueAt: Long, leadDays: Int): Long {
        val day = dueAt - leadDays.coerceAtLeast(0) * DAY
        return minOf(SmartReminder.morningOf(day), dueAt)
    }

    fun describeLead(leadDays: Int): String = when (leadDays) {
        0 -> "On the day"
        1 -> "1 day before"
        else -> "$leadDays days before"
    }

    private const val DAY = 24L * 60 * 60 * 1000
}
