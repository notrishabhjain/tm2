package com.taskmind.reminders

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * What is left of the standing "due today" notification.
 *
 * It listed overdue and due-today tasks in the shade all day. The morning
 * brief now says the same thing once, at 9:00, and the evening wrap-up offers
 * to move what slipped - so two notifications answering "what do I owe
 * today?" became one. Anyone who had it switched on still has it posted from
 * an older version; this takes it down.
 */
object OngoingReminder {

    private const val NOTIFICATION_ID = 42_001

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}
