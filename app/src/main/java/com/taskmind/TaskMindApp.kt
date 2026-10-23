package com.taskmind

import android.app.Application
import com.taskmind.capture.ResidencyService
import com.taskmind.core.LogLevel
import com.taskmind.core.SmartReminder
import com.taskmind.core.SourceType
import com.taskmind.core.TaskStatus
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import com.taskmind.reminders.DailyBrief
import com.taskmind.work.Scheduler
import com.taskmind.work.TaskChangeWatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TaskMindApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
        container.start()
        container.logger.post(
            Stage.SYSTEM,
            LogLevel.INFO,
            "TaskMind ${BuildConfig.APP_VERSION_NAME} (${BuildConfig.APP_VERSION_CODE}) started",
        )

        // Spec 17.4: re-establish everything on every start. A revoked
        // permission or a killed service must recover without the user having
        // to know that it happened.
        Scheduler.ensurePeriodicWork(this)
        Scheduler.ensureWatchdog(this)

        // The widget, the web mirror, the standing notification and the next
        // reminder alarm all follow the task list from here on, whatever
        // changed it.
        TaskChangeWatcher.start(this, container)

        // cachedSettings is still at its defaults this early - the collector
        // that fills it has not run yet - so read the store directly.
        container.applicationScope.launch {
            // Draw the line between the recordings that already existed and the
            // calls this app is responsible for, before anything can scan. Once
            // set it never moves on its own.
            val cutoff = container.settingsRepository.ensureRecordingCutoff(System.currentTimeMillis())

            backfillReminders()
            // Re-armed on every start, which includes after a reboot: the
            // boot receiver starts this process.
            runCatching { DailyBrief.schedule(this@TaskMindApp) }

            val settings = container.settingsRepository.current()
            if (settings.cloudConsent && settings.captureCalls) {
                ResidencyService.start(this@TaskMindApp)
            }
            container.logger.write(
                Stage.CALL,
                LogLevel.INFO,
                "watching for new call recordings",
                "Anything recorded before " +
                    java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata") }
                        .format(java.util.Date(cutoff)) +
                    " is ignored; everything after it is transcribed automatically.",
            )
        }
    }

    /**
     * Gives tasks captured before automatic reminders existed the reminder
     * they would get today. Runs once; afterwards the funnel does it at
     * creation. Manual tasks are left exactly as their owner set them.
     */
    private suspend fun backfillReminders() {
        val prefs = getSharedPreferences(ONCE_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REMINDER_BACKFILL, false)) return
        val now = System.currentTimeMillis()
        var set = 0
        container.taskRepository.observeTasks().first()
            .filter {
                it.status == TaskStatus.ACTIVE && it.reminderAt == null &&
                    it.sourceType != SourceType.MANUAL && it.dueAt != null
            }
            .forEach { task ->
                SmartReminder.forDue(task.dueAt, now)?.let { at ->
                    container.taskRepository.setReminder(task.id, at)
                    set++
                }
            }
        prefs.edit().putBoolean(KEY_REMINDER_BACKFILL, true).apply()
        if (set > 0) {
            container.logger.write(Stage.SYSTEM, LogLevel.INFO, "gave $set existing tasks an automatic reminder")
            Scheduler.scheduleNextReminder(this, container)
        }
    }

    private companion object {
        const val ONCE_PREFS = "taskmind_once"
        const val KEY_REMINDER_BACKFILL = "reminder_backfill_v1"
    }
}
