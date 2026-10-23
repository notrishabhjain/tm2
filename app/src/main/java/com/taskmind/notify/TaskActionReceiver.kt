package com.taskmind.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.taskmind.core.DateResolver
import com.taskmind.core.LogLevel
import com.taskmind.core.PreFilter
import com.taskmind.core.SmartReminder
import com.taskmind.core.Stage
import com.taskmind.core.TaskStatus
import com.taskmind.core.WaitingOn
import com.taskmind.di.AppContainer
import com.taskmind.tagging.AutoTagger
import com.taskmind.work.Scheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The buttons on TaskMind's notifications.
 *
 * Before these existed, every notification was a dead end: the only thing it
 * could do was open the app, so finishing a task you were just reminded of
 * meant unlocking, finding it, and ticking it. Now the common answers - done,
 * later, not mine - are one tap from the shade.
 *
 * Not exported. A PendingIntent runs with this app's identity, so the
 * notification can reach it and nothing else on the phone can.
 */
class TaskActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val action = intent.action ?: return

        val pending = goAsync()
        val container = AppContainer.get(app)
        container.applicationScope.launch {
            try {
                handle(app, container, action, taskId, notificationId, intent)
            } catch (t: Throwable) {
                container.logger.write(Stage.SYSTEM, LogLevel.ERROR, "notification button failed: $action", t.toString())
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(
        app: Context,
        container: AppContainer,
        action: String,
        taskId: String?,
        notificationId: Int,
        intent: Intent,
    ) {
        val repo = container.taskRepository
        val now = System.currentTimeMillis()

        when (action) {
            ACTION_DONE -> {
                taskId ?: return
                repo.complete(taskId)
                cancel(app, notificationId)
            }

            ACTION_SNOOZE_HOUR -> {
                val task = taskId?.let { repo.byId(it) } ?: return
                val at = now + HOUR
                repo.setReminder(task.id, at)
                // A deadline that has already gone moves with the reminder,
                // or the task would come back an hour from now still overdue.
                if (task.dueAt != null && task.dueAt < at) repo.setDueAt(task.id, at)
                Scheduler.scheduleNextReminder(app, container)
                cancel(app, notificationId)
            }

            ACTION_TOMORROW -> {
                val task = taskId?.let { repo.byId(it) } ?: return
                val tomorrowMorning = SmartReminder.morningOf(DateResolver.nextMidnight(now))
                repo.setReminder(task.id, tomorrowMorning)
                val due = task.dueAt
                if (due == null || due < tomorrowMorning) {
                    // Same time of day, tomorrow; a task with no time keeps
                    // having none.
                    val moved = if (due == null || SmartReminder.isDateOnly(due)) {
                        SmartReminder.withoutTime(tomorrowMorning)
                    } else {
                        val local = java.time.Instant.ofEpochMilli(due).atZone(DateResolver.IST)
                        SmartReminder.timed(tomorrowMorning, local.hour, local.minute)
                    }
                    repo.setDueAt(task.id, moved)
                }
                Scheduler.scheduleNextReminder(app, container)
                cancel(app, notificationId)
            }

            ACTION_MOVE_SLIPPED -> {
                // The evening wrap-up's button: everything of yours still open
                // today goes to tomorrow, at the same time of day, with a
                // fresh automatic reminder. Waiting-on tasks stay put - the
                // brief leaves them out for the same reason.
                val endOfToday = DateResolver.endOfDay(now)
                val tomorrowStart = DateResolver.nextMidnight(now)
                val slipped = repo.observeTasks().first().filter {
                    it.status == TaskStatus.ACTIVE && it.parentTaskId == null &&
                        it.dueAt != null && it.dueAt <= endOfToday && !WaitingOn.matches(it.title, it.tags)
                }
                slipped.forEach { task ->
                    val due = task.dueAt ?: return@forEach
                    val moved = if (SmartReminder.isDateOnly(due)) {
                        SmartReminder.withoutTime(tomorrowStart)
                    } else {
                        val local = java.time.Instant.ofEpochMilli(due).atZone(DateResolver.IST)
                        SmartReminder.timed(tomorrowStart, local.hour, local.minute)
                    }
                    repo.update(task.copy(dueAt = moved, reminderAt = SmartReminder.forDue(moved, now)))
                }
                container.logger.write(Stage.SYSTEM, LogLevel.INFO, "moved ${slipped.size} open tasks to tomorrow")
                Scheduler.scheduleNextReminder(app, container)
                cancel(app, notificationId)
            }

            ACTION_NOT_MINE -> {
                val task = taskId?.let { repo.byId(it) } ?: return
                repo.delete(task.id)
                container.logger.write(Stage.FUNNEL, LogLevel.INFO, "marked not mine from a notification", task.title)
                val group = AutoTagger.parseLabel(task.sourceType, task.sourceLabel).group
                if (group.isNullOrBlank()) {
                    cancel(app, notificationId)
                } else {
                    // The second half of "not mine": one tap to stop the group
                    // that produced it. Asked, never assumed - one wrong
                    // reading does not mean the whole group is noise.
                    container.notifier.offerGroupMute(notificationId, group)
                }
            }

            ACTION_MUTE_GROUP -> {
                val group = intent.getStringExtra(EXTRA_GROUP)?.trim().orEmpty()
                if (group.isNotEmpty()) {
                    container.settingsRepository.setGroupOverride(group, PreFilter.GroupPolicy.NEVER)
                    container.logger.write(Stage.PREFILTER, LogLevel.INFO, "stopped watching group from a notification", group)
                }
                cancel(app, notificationId)
            }
        }
    }

    private fun cancel(context: Context, id: Int) {
        if (id != 0) runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    companion object {
        const val ACTION_DONE = "com.taskmind.action.TASK_DONE"
        const val ACTION_SNOOZE_HOUR = "com.taskmind.action.TASK_SNOOZE_HOUR"
        const val ACTION_TOMORROW = "com.taskmind.action.TASK_TOMORROW"
        const val ACTION_NOT_MINE = "com.taskmind.action.TASK_NOT_MINE"
        const val ACTION_MUTE_GROUP = "com.taskmind.action.MUTE_GROUP"
        const val ACTION_MOVE_SLIPPED = "com.taskmind.action.MOVE_SLIPPED"

        const val EXTRA_TASK_ID = "com.taskmind.extra.TASK_ID"
        const val EXTRA_NOTIFICATION_ID = "com.taskmind.extra.NOTIFICATION_ID"
        const val EXTRA_GROUP = "com.taskmind.extra.GROUP"

        private const val HOUR = 60 * 60 * 1000L
    }
}
