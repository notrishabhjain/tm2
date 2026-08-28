package com.taskmind.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.taskmind.capture.ReminderReceiver
import com.taskmind.capture.WatchdogReceiver
import com.taskmind.di.AppContainer
import java.util.concurrent.TimeUnit

/**
 * All background scheduling in one place.
 *
 * Spec 17.3: never start a service on a timer just to poll. Work is enqueued in
 * response to something actually happening; the periodic jobs exist only as a
 * safety net for the events HyperOS silently swallows.
 */
object Scheduler {

    private const val WORK_EXTRACTION = "taskmind.extraction"
    private const val WORK_TRANSCRIPTION = "taskmind.transcription"
    private const val WORK_CALL_DISCOVERY = "taskmind.call_discovery"
    private const val WORK_CALL_SWEEP_DELAYED = "taskmind.call_sweep_delayed"
    private const val WORK_MAINTENANCE = "taskmind.maintenance"
    private const val WORK_RETENTION = "taskmind.retention"
    private const val WORK_UPDATE = "taskmind.update_check"

    private fun wm(context: Context): WorkManager = WorkManager.getInstance(context.applicationContext)

    private val connected = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private val unmetered = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .build()

    fun enqueueExtraction(context: Context) {
        val request = OneTimeWorkRequestBuilder<ExtractionWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm(context).enqueueUniqueWork(WORK_EXTRACTION, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Spec 9: the Wi-Fi-only ASR toggle. Call audio is the expensive upload, so
     * the constraint is chosen from settings rather than hard-coded.
     */
    fun enqueueTranscription(context: Context, wifiOnly: Boolean? = null) {
        val useUnmetered = wifiOnly ?: AppContainer.get(context).cachedSettings.wifiOnlyAsr
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setConstraints(if (useUnmetered) unmetered else connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .build()
        wm(context).enqueueUniqueWork(WORK_TRANSCRIPTION, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun enqueueCallDiscovery(context: Context) {
        val request = OneTimeWorkRequestBuilder<CallDiscoveryWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        wm(context).enqueueUniqueWork(WORK_CALL_DISCOVERY, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * The dialer writes the call-log row a moment after the call ends. When the
     * immediate sweep found nothing, come back once rather than giving up.
     */
    fun enqueueDelayedCallSweep(context: Context) {
        val request = OneTimeWorkRequestBuilder<CallDiscoveryWorker>()
            .setInitialDelay(20, TimeUnit.SECONDS)
            .build()
        wm(context).enqueueUniqueWork(WORK_CALL_SWEEP_DELAYED, ExistingWorkPolicy.REPLACE, request)
    }

    fun ensurePeriodicWork(context: Context) {
        wm(context).enqueueUniquePeriodicWork(
            WORK_MAINTENANCE,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(30, TimeUnit.MINUTES).build(),
        )
        wm(context).enqueueUniquePeriodicWork(
            WORK_RETENTION,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build(),
        )
        wm(context).enqueueUniquePeriodicWork(
            WORK_UPDATE,
            ExistingPeriodicWorkPolicy.KEEP,
            // Spec 19: unmetered and not urgent.
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(unmetered)
                .build(),
        )
    }

    fun ensureWatchdog(context: Context) {
        WatchdogReceiver.schedule(context)
    }

    /**
     * The periodic work that is actually registered right now.
     *
     * WorkManager silently drops periodic work when the OEM's battery manager
     * decides to, and on HyperOS that is the difference between an app that
     * drains its queue in the background and one that only works while open.
     * The diagnostics screen reports what it finds rather than what was asked
     * for.
     */
    fun scheduledWorkNames(context: Context): List<String> =
        listOf(WORK_MAINTENANCE, WORK_RETENTION, WORK_UPDATE).filter { name ->
            runCatching {
                wm(context).getWorkInfosForUniqueWork(name).get()
                    .any { !it.state.isFinished }
            }.getOrDefault(false)
        }.map { it.removePrefix("taskmind.") }

    fun cancelAll(context: Context) {
        wm(context).cancelUniqueWork(WORK_MAINTENANCE)
        wm(context).cancelUniqueWork(WORK_RETENTION)
        wm(context).cancelUniqueWork(WORK_UPDATE)
        WatchdogReceiver.cancel(context)
    }

    // ------------------------------------------------------------ reminders

    fun enqueueReminderCheck(context: Context) {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION)
        context.sendBroadcast(intent)
    }

    /**
     * Schedules an exact alarm for the next reminder only. One alarm at a time
     * keeps us inside the exact-alarm budget and inside spec 17.3's rule that
     * the app never wakes up just to look around.
     */
    suspend fun scheduleNextReminder(context: Context, container: AppContainer) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val next = container.database.taskDao().nextReminder(System.currentTimeMillis()) ?: return
        val at = next.reminderAt ?: return

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REMINDER_REQUEST,
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        runCatching {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
            } else {
                // Without the exact-alarm permission an inexact alarm is
                // honest: late is better than a permission prompt the user
                // cannot grant from here.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
            }
        }
    }

    private const val REMINDER_REQUEST = 7001
}
