package com.taskmind.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.taskmind.MainActivity
import com.taskmind.R
import com.taskmind.intake.TaskCreatedNotifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spec 17.3 - notifications the app itself posts.
 *
 * SILENT BY DEFAULT. Failure mode 3 was two services posting a progress
 * notification on every short-timer invocation, and the app buzzing all day
 * until it was uninstalled.
 *
 * The only notifications with sound are: a task was created (grouped, at most
 * one summary per 5 minutes) and a reminder fired. There is no notification for
 * "processing", "syncing", "checking", or any failure that will be retried -
 * those live in the activity log and on the status screen.
 */
class Notifier(private val context: Context) : TaskCreatedNotifier {

    private val manager = NotificationManagerCompat.from(context)
    private val nextId = AtomicInteger(2000)

    @Volatile private var lastTaskSummaryAt = 0L
    @Volatile private var tasksSinceSummary = 0

    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return

        system.createNotificationChannel(
            NotificationChannel(CHANNEL_TASKS, "Tasks captured", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A commitment was found in a message or a call and added to your list."
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders you set on a task."
            },
        )
        // Low importance and no sound: a foreground service notification is a
        // legal requirement, not a message to the user.
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Background activity", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown only while TaskMind is doing bounded background work."
                setShowBadge(false)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A new version of TaskMind is available."
                setShowBadge(false)
            },
        )
    }

    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun contentIntent(route: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(
            context,
            route.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Spec 7.7 / 17.3: notify if and only if a task was actually created, and
     * collapse a burst into one summary per five minutes.
     */
    override suspend fun onTaskCreated(taskId: String, title: String) {
        if (!canPost()) return
        val now = System.currentTimeMillis()
        val withinWindow = now - lastTaskSummaryAt < SUMMARY_WINDOW_MILLIS

        if (withinWindow) {
            tasksSinceSummary++
            val summary = NotificationCompat.Builder(context, CHANNEL_TASKS)
                .setSmallIcon(R.drawable.ic_stat_taskmind)
                .setContentTitle("$tasksSinceSummary new tasks captured")
                .setContentText(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(title))
                .setContentIntent(contentIntent(ROUTE_TASKS))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build()
            postSafely(ID_TASK_SUMMARY, summary)
            return
        }

        lastTaskSummaryAt = now
        tasksSinceSummary = 1
        val notification = NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_stat_taskmind)
            .setContentTitle("Task captured")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(contentIntent(ROUTE_TASKS))
            .setAutoCancel(true)
            .build()
        postSafely(ID_TASK_SUMMARY, notification)
    }

    fun postReminder(taskId: String, title: String, notes: String?) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_taskmind)
            .setContentTitle(title)
            .setContentText(notes ?: "Reminder")
            .setStyle(NotificationCompat.BigTextStyle().bigText(notes ?: title))
            .setContentIntent(contentIntent("$ROUTE_TASK_DETAIL/$taskId"))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        postSafely(nextId.incrementAndGet(), notification)
    }

    fun postMandatoryUpdate(versionName: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_taskmind)
            .setContentTitle("TaskMind $versionName is required")
            .setContentText("Tap to install the update.")
            .setContentIntent(contentIntent(ROUTE_STATUS))
            .setAutoCancel(true)
            .build()
        postSafely(ID_UPDATE, notification)
    }

    /**
     * The foreground-service notification. Deferred so short runs never draw at
     * all, on a minimum-importance channel so they never make a sound.
     */
    fun foregroundNotification(text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_taskmind)
            .setContentTitle("TaskMind")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setContentIntent(contentIntent(ROUTE_STATUS))
            .build()

    private fun postSafely(id: Int, notification: Notification) {
        runCatching { manager.notify(id, notification) }
    }

    companion object {
        const val CHANNEL_TASKS = "tasks"
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_UPDATES = "updates"

        const val ID_TASK_SUMMARY = 1001
        const val ID_UPDATE = 1002
        const val ID_FOREGROUND_WORKER = 1101
        const val ID_FOREGROUND_RESIDENCY = 1102

        const val ROUTE_TASKS = "tasks"
        const val ROUTE_STATUS = "status"
        const val ROUTE_REVIEW = "review"
        const val ROUTE_TASK_DETAIL = "task"

        private const val SUMMARY_WINDOW_MILLIS = 5 * 60 * 1000L
    }
}
