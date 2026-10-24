package com.taskmind.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.taskmind.core.Brief
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.core.TaskStatus
import com.taskmind.core.WaitingOn
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
    const val EVENING_HOUR = 19

    private const val WINDOW_MILLIS = 10 * 60 * 1000L

    /** Arms (or disarms) both alarms to match the current preferences. */
    suspend fun schedule(context: Context) {
        val app = context.applicationContext
        val prefs = UiPreferences(app).current()
        arm(app, Brief.Kind.MORNING, prefs.morningBrief)
        arm(app, Brief.Kind.EVENING, prefs.eveningBrief)
    }

    private fun arm(context: Context, kind: Brief.Kind, enabled: Boolean) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, kind)
        if (!enabled) {
            runCatching { alarms.cancel(pending) }
            return
        }
        val hour = if (kind == Brief.Kind.MORNING) MORNING_HOUR else EVENING_HOUR
        val at = Brief.nextAt(hour, System.currentTimeMillis())
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
        val items = container.taskRepository.observeTasks().first()
            .filter { it.status == TaskStatus.ACTIVE && it.parentTaskId == null }
            .map { Brief.Item(it.title, it.dueAt, WaitingOn.matches(it.title, it.tags)) }
        val message = Brief.compose(kind, items, now)
        if (message == null) {
            container.logger.write(Stage.SYSTEM, LogLevel.DEBUG, "${kind.name.lowercase()} brief: nothing to say")
            return
        }
        container.notifier.postBrief(kind, message)
    }

    private const val REQUEST_MORNING = 7101
    private const val REQUEST_EVENING = 7102
}

/** Fires a brief, then arms the next one. Not exported. */
class BriefReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { name -> Brief.Kind.entries.firstOrNull { it.name == name } }
            ?: return
        val app = context.applicationContext
        val container = AppContainer.get(app)
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                val prefs = UiPreferences(app).current()
                val enabled = if (kind == Brief.Kind.MORNING) prefs.morningBrief else prefs.eveningBrief
                if (enabled) DailyBrief.post(app, kind)
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
    }
}
