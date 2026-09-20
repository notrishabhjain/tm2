package com.taskmind.widget

/** Shared by both widget modes so the two never render a date differently. */
object WidgetFormat {

    /** "Overdue", "Today", "Tue" or "3 Oct" - whichever is shortest and clearest. */
    fun shortDate(millis: Long, now: Long): String {
        val zone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        val day = 24L * 60 * 60 * 1000
        val today = java.util.Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return when {
            millis < today -> "Overdue"
            millis < today + day -> "Today"
            millis < today + 2 * day -> "Tomorrow"
            millis < today + 7 * day ->
                java.text.SimpleDateFormat("EEE", java.util.Locale.ENGLISH)
                    .apply { timeZone = zone }.format(java.util.Date(millis))
            else ->
                java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH)
                    .apply { timeZone = zone }.format(java.util.Date(millis))
        }
    }
}
