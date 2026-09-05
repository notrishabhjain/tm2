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

    /**
     * Reset when the review screen is opened, so the count reflects what is
     * actually waiting rather than everything ever proposed.
     */
    private val pendingReviewNotices = AtomicInteger(0)

    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return

        system.createNotificationChannel(
            NotificationChannel(CHANNEL_TASKS, "Tasks captured", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A commitment was found in a message or a call and added to your list."
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_REVIEW, "Needs review", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Something was found that is not certain enough to add without you seeing it."
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

    /**
     * Why notifications are or are not reaching the phone.
     *
     * "I get no notifications" has at least four causes that look identical
     * from inside the app: the runtime permission, the app-level toggle, a
     * per-channel block, and a channel the OEM launcher silenced. HyperOS in
     * particular lets a channel be switched off in a screen the app never sees.
     * Guessing between them wasted a round trip, so the app now reads all four.
     */
    data class Diagnosis(
        val permissionGranted: Boolean,
        val notificationsEnabled: Boolean,
        val channelImportance: Map<String, Int>,
        val blockedChannels: List<String>,
    ) {
        val healthy: Boolean
            get() = permissionGranted && notificationsEnabled && blockedChannels.isEmpty()

        fun explain(): String = when {
            !permissionGranted ->
                "The notification permission is not granted. Grant it in Android settings."
            !notificationsEnabled ->
                "Notifications are switched off for TaskMind as a whole, in Android settings."
            blockedChannels.isNotEmpty() ->
                "These notification categories are switched off: ${blockedChannels.joinToString()}. " +
                    "On Xiaomi/HyperOS this is a per-category switch inside the app's notification " +
                    "settings, separate from the main toggle."
            else -> "Notifications are enabled and no category is blocked."
        }
    }

    fun diagnose(): Diagnosis {
        val system = context.getSystemService(NotificationManager::class.java)
        val importance = mutableMapOf<String, Int>()
        val blocked = mutableListOf<String>()
        for (id in listOf(CHANNEL_TASKS, CHANNEL_REVIEW, CHANNEL_REMINDERS)) {
            val channel = runCatching { system?.getNotificationChannel(id) }.getOrNull()
            val value = channel?.importance ?: NotificationManager.IMPORTANCE_UNSPECIFIED
            importance[id] = value
            if (channel != null && value == NotificationManager.IMPORTANCE_NONE) blocked += id
        }
        return Diagnosis(
            permissionGranted = canPost(),
            notificationsEnabled = runCatching { manager.areNotificationsEnabled() }.getOrDefault(false),
            channelImportance = importance,
            blockedChannels = blocked,
        )
    }

    /** Called when the review inbox is opened: the backlog has been seen. */
    fun clearReviewNotice() {
        pendingReviewNotices.set(0)
        runCatching { manager.cancel(ID_REVIEW_SUMMARY) }
    }

    /** Posts one notification on demand, and says whether the system took it. */
    fun postTest(): String {
        val diagnosis = diagnose()
        if (!diagnosis.healthy) return diagnosis.explain()
        val notification = NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_stat_taskmind)
            .setContentTitle("TaskMind test notification")
            .setContentText("If you can see this, task notifications will reach you.")
            .setContentIntent(contentIntent(ROUTE_TASKS))
            .setAutoCancel(true)
            .build()
        return runCatching {
            manager.notify(ID_TEST, notification)
            "Posted. If nothing appeared, the block is outside the app - check TaskMind's " +
                "notification categories in Android settings."
        }.getOrElse { "The system refused it: ${it.message ?: it.toString()}" }
    }

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

    /**
     * Something was found but is not certain enough to assert.
     *
     * Without this the review inbox was invisible: an item landed there and the
     * only way to discover it was to open the app and look, which defeats the
     * point of an app that is supposed to notice things for you.
     */
    override suspend fun onReviewProposed(reviewId: String, title: String) {
        if (!canPost()) return
        val count = pendingReviewNotices.incrementAndGet()
        val notification = NotificationCompat.Builder(context, CHANNEL_REVIEW)
            .setSmallIcon(R.drawable.ic_stat_taskmind)
            .setContentTitle(
                if (count > 1) "$count items need a quick look" else "Something needs a quick look",
            )
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(contentIntent(ROUTE_REVIEW))
            .setAutoCancel(true)
            .build()
        postSafely(ID_REVIEW_SUMMARY, notification)
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
        const val CHANNEL_REVIEW = "review"
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_UPDATES = "updates"

        const val ID_TASK_SUMMARY = 1001
        const val ID_REVIEW_SUMMARY = 1003
        const val ID_TEST = 1004
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
