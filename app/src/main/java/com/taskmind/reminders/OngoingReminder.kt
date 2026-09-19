package com.taskmind.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.taskmind.MainActivity
import com.taskmind.R
import com.taskmind.Routes
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
import com.taskmind.prefs.UiPreferences
import kotlinx.coroutines.flow.first

/**
 * A notification that stays put, listing what is overdue or due today.
 *
 * Separate from [com.taskmind.notify.Notifier] deliberately. That one posts
 * events - a task was captured, something needs review - and those are meant
 * to be read once and dismissed. This is a standing summary that should still
 * be there tomorrow morning, so it has its own channel with its own
 * importance, and a person can silence one without silencing the other.
 *
 * HONEST LIMIT
 *
 * `setOngoing(true)` stops it being swiped away on Android 13 and below. From
 * Android 14 the system lets a notification be dismissed whatever the app
 * asks, unless the app is running a foreground service - and running one all
 * day to hold a reminder in place would cost battery and a permanent "app is
 * running" entry. So on a recent phone this can be swiped off; it comes back
 * on the next refresh, which is hourly and whenever tasks change.
 */
object OngoingReminder {

    private const val CHANNEL = "taskmind.ongoing"
    private const val NOTIFICATION_ID = 42_001

    /** Rebuilds or removes the notification to match the current state. */
    suspend fun refresh(context: Context) {
        val prefs = UiPreferences(context)
        if (!prefs.current().ongoingReminder) {
            cancel(context)
            return
        }

        val container = AppContainer.get(context)
        val now = System.currentTimeMillis()
        val endOfToday = endOfDay(now)

        val due = container.database.taskDao().observeAll().first()
            .asSequence()
            .filter { it.status == TaskStatus.ACTIVE }
            .filter { it.dueAt != null && it.dueAt!! <= endOfToday }
            .sortedBy { it.dueAt }
            .toList()

        // Nothing due is worth saying nothing about. A permanent "0 tasks"
        // notification is the kind of thing people turn off entirely.
        if (due.isEmpty()) {
            cancel(context)
            return
        }

        post(context, due, now)
    }

    private fun post(context: Context, due: List<TaskEntity>, now: Long) {
        ensureChannel(context)

        val overdue = due.count { it.dueAt!! < now }
        val title = when {
            overdue > 0 && overdue == due.size -> "$overdue overdue"
            overdue > 0 -> "$overdue overdue, ${due.size - overdue} due today"
            else -> "${due.size} due today"
        }

        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        for (task in due.take(MAX_LINES)) {
            val marker = if (task.dueAt!! < now) "! " else "• "
            style.addLine(marker + task.title)
        }
        if (due.size > MAX_LINES) style.setSummaryText("and ${due.size - MAX_LINES} more")

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_taskmind)
            .setContentTitle(title)
            .setContentText(due.first().title)
            .setStyle(style)
            .setContentIntent(openTasks(context))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Posting without POST_NOTIFICATIONS throws on API 33+. The app asks
        // for it elsewhere; if it was refused, a reminder silently not
        // appearing is better than a crash.
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /**
     * IMPORTANCE_LOW on purpose: this is a thing to glance at, not an
     * interruption. It has no sound and does not push itself in front of
     * anything - which is what makes it tolerable as something permanent.
     */
    private fun ensureChannel(context: Context) {
        val system = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        system.createNotificationChannel(
            NotificationChannel(CHANNEL, "Today's tasks", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A standing list of what is overdue or due today."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    private fun openTasks(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_ROUTE, Routes.TASKS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun endOfDay(now: Long): Long {
        val zone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        return java.util.Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59)
            set(java.util.Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    /** More than this and it stops being glanceable. */
    private const val MAX_LINES = 6
}
