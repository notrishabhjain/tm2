package com.taskmind.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.taskmind.core.Brief
import com.taskmind.core.DateResolver
import com.taskmind.core.FocusLog
import com.taskmind.data.repo.TaskRanking
import com.taskmind.diagnostics.CaptureHealth
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.core.TaskStatus
import com.taskmind.core.WaitingOn
import com.taskmind.core.WeeklyReview
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.tagging.AutoTagger
import com.taskmind.di.AppContainer
import com.taskmind.prefs.UiPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The morning brief and the evening wrap-up.
 *
 * Two alarms a day, each re-armed when it fires and whenever the app starts.
 * They are window alarms, not exact ones: a brief that arrives at 9:04 is
 * fine, and exact alarms need a permission this should not have to ask for.
 *
 * What each says is decided by [Brief], which is pure and tested; this file
 * is only the clock and the notification.
 */
object DailyBrief {

    const val MORNING_HOUR = 9
    /** 18:30: the wrap-up and the digest of maybes, in one notification. */
    const val EVENING_HOUR = 18
    const val EVENING_MINUTE = 30

    private const val WINDOW_MILLIS = 10 * 60 * 1000L

    /** Arms (or disarms) both alarms to match the current preferences. */
    suspend fun schedule(context: Context) {
        val app = context.applicationContext
        val prefs = UiPreferences(app).current()
        arm(app, Brief.Kind.MORNING, prefs.morningBrief)
        arm(app, Brief.Kind.EVENING, prefs.eveningBrief)
        armWeekly(app, prefs.weeklyReview)
    }

    private fun armWeekly(context: Context, enabled: Boolean) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_WEEKLY,
            Intent(context, BriefReceiver::class.java)
                .setAction(BriefReceiver.ACTION)
                .putExtra(BriefReceiver.EXTRA_KIND, BriefReceiver.KIND_WEEKLY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (!enabled) {
            runCatching { alarms.cancel(pending) }
            return
        }
        val at = WeeklyReview.nextSunday(WEEKLY_HOUR, System.currentTimeMillis())
        runCatching { alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MILLIS, pending) }
    }

    /** Composes and posts the Sunday review now, if the week had anything in it. */
    suspend fun postWeekly(context: Context) {
        val container = AppContainer.get(context)
        val now = System.currentTimeMillis()
        val summary = WeeklyReview.summarise(weeklyItems(container), now) ?: return
        container.notifier.postWeekly(summary)
    }

    /** The task list as the weekly review reads it. Also used by the Done view. */
    suspend fun weeklyItems(container: AppContainer): List<WeeklyReview.Item> =
        container.taskRepository.observeTasks().first()
            .filter { it.parentTaskId == null && it.status != TaskStatus.DELETED }
            .map { it.toWeeklyItem() }

    private fun arm(context: Context, kind: Brief.Kind, enabled: Boolean) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, kind)
        if (!enabled) {
            runCatching { alarms.cancel(pending) }
            return
        }
        val at = if (kind == Brief.Kind.MORNING) {
            Brief.nextAt(MORNING_HOUR, System.currentTimeMillis())
        } else {
            Brief.nextAt(EVENING_HOUR, System.currentTimeMillis(), EVENING_MINUTE)
        }
        runCatching { alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MILLIS, pending) }
    }

    private fun pendingIntent(context: Context, kind: Brief.Kind): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            if (kind == Brief.Kind.MORNING) REQUEST_MORNING else REQUEST_EVENING,
            Intent(context, BriefReceiver::class.java)
                .setAction(BriefReceiver.ACTION)
                .putExtra(BriefReceiver.EXTRA_KIND, kind.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Composes and posts one brief now, if there is anything to say. */
    suspend fun post(context: Context, kind: Brief.Kind) {
        val container = AppContainer.get(context)
        val now = System.currentTimeMillis()
        val all = container.taskRepository.observeTasks().first()
        val items = all
            .filter { it.status == TaskStatus.ACTIVE && it.parentTaskId == null }
            .map { Brief.Item(it.title, it.dueAt, WaitingOn.matches(it.title, it.tags)) }
        // The morning leads with the day's top things, and writes down which
        // they were so Health can say later whether they got done.
        val picks = if (kind == Brief.Kind.MORNING) {
            TaskRanking.focus(all, container.settingsRepository.current().aboutMe, now, Brief.PICKS)
        } else {
            emptyList()
        }
        if (picks.isNotEmpty()) {
            CaptureHealth.recordFocus(context, FocusLog.Day(DateResolver.startOfDay(now), picks.map { it.first.id }))
        }
        val meetings = if (kind == Brief.Kind.MORNING && UiPreferences(context).current().calendarInBrief) {
            CalendarToday.read(context, now)
        } else {
            emptyList()
        }
        val maybes = if (kind == Brief.Kind.EVENING) {
            container.taskRepository.observePendingReviewCount().first()
        } else {
            0
        }
        val message = Brief.compose(kind, items, now, meetings, maybes, picks.map { (t, why) -> Brief.Pick(t.title, why) })
        if (message == null) {
            container.logger.write(Stage.SYSTEM, LogLevel.DEBUG, "${kind.name.lowercase()} brief: nothing to say")
            return
        }
        container.notifier.postBrief(kind, message)
    }

    private const val REQUEST_MORNING = 7101
    private const val REQUEST_EVENING = 7102
    private const val REQUEST_WEEKLY = 7103

    const val WEEKLY_HOUR = 18
}

/** One task as the weekly review sees it. */
fun TaskEntity.toWeeklyItem(): WeeklyReview.Item {
    val parsed = AutoTagger.parseLabel(sourceType, sourceLabel)
    return WeeklyReview.Item(
        title = title,
        open = status == TaskStatus.ACTIVE,
        createdAt = createdAt,
        completedAt = completedAt,
        from = parsed.group ?: parsed.person,
    )
}

/** Fires a brief, then arms the next one. Not exported. */
class BriefReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_KIND) ?: return
        val kind = Brief.Kind.entries.firstOrNull { it.name == name }
        if (kind == null && name != KIND_WEEKLY) return
        val app = context.applicationContext
        val container = AppContainer.get(app)
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                val prefs = UiPreferences(app).current()
                when (kind) {
                    null -> if (prefs.weeklyReview) DailyBrief.postWeekly(app)
                    Brief.Kind.MORNING -> if (prefs.morningBrief) DailyBrief.post(app, kind)
                    Brief.Kind.EVENING -> if (prefs.eveningBrief) DailyBrief.post(app, kind)
                }
            } catch (t: Throwable) {
                container.logger.write(Stage.SYSTEM, LogLevel.ERROR, "brief failed", t.toString())
            } finally {
                runCatching { DailyBrief.schedule(app) }
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.taskmind.action.BRIEF"
        const val EXTRA_KIND = "com.taskmind.extra.BRIEF_KIND"
        const val KIND_WEEKLY = "WEEKLY"
    }
}
