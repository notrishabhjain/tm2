package com.taskmind.capture

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.launch

/**
 * Spec 11.2, trigger 3 of 3: a static PHONE_STATE receiver.
 *
 * This one fails silently when MIUI Autostart is off, which is exactly why
 * there are three triggers and two recovery paths. Onboarding deep-links the
 * user to the Autostart screen (spec 17.2).
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val container = AppContainer.get(context)

            if (state == TelephonyManager.EXTRA_STATE_IDLE && wasOffHook) {
                wasOffHook = false
                container.logger.post(Stage.CALL, LogLevel.INFO, "call ended (PHONE_STATE receiver)")
                val pending = goAsync()
                container.applicationScope.launch {
                    try {
                        container.callPipeline.sweepCallLog("PHONE_STATE receiver")
                        Scheduler.enqueueCallDiscovery(context)
                        Scheduler.enqueueDelayedCallSweep(context)
                    } finally {
                        pending.finish()
                    }
                }
            } else if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                wasOffHook = true
            }
        } catch (t: Throwable) {
            AppContainer.get(context).logger.post(Stage.CALL, LogLevel.ERROR, "PhoneStateReceiver failed", t.toString())
        }
    }

    private companion object {
        @Volatile
        var wasOffHook = false
    }
}

/**
 * Spec 17.4: restart everything on BOOT_COMPLETED and on MY_PACKAGE_REPLACED.
 * Without this, an app update silently stops capturing until the user next
 * opens the app - and they have no reason to.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val action = intent.action ?: return
            if (action != Intent.ACTION_BOOT_COMPLETED &&
                action != Intent.ACTION_MY_PACKAGE_REPLACED &&
                action != Intent.ACTION_LOCKED_BOOT_COMPLETED
            ) {
                return
            }
            val container = AppContainer.get(context)
            container.start()
            container.logger.post(Stage.SYSTEM, LogLevel.INFO, "restarting after $action")
            Scheduler.ensurePeriodicWork(context)
            Scheduler.ensureWatchdog(context)
            ResidencyService.start(context)
        } catch (t: Throwable) {
            runCatching {
                AppContainer.get(context).logger.post(Stage.SYSTEM, LogLevel.ERROR, "BootReceiver failed", t.toString())
            }
        }
    }
}

/**
 * Spec 17.4 - the watchdog.
 *
 * It RESCHEDULES ITSELF FIRST, before doing any work, so that a crash in its
 * body cannot break the chain. That ordering is the whole point of it.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Reschedule first. Always. Whatever happens below.
        runCatching { schedule(context) }

        try {
            val container = AppContainer.get(context)
            container.logger.post(Stage.SYSTEM, LogLevel.DEBUG, "watchdog tick")
            container.applicationScope.launch {
                runCatching { container.callPipeline.sweepCallLog("watchdog") }
                runCatching {
                    for (record in container.callPipeline.pendingDiscovery()) {
                        container.callPipeline.discoverRecording(record.id)
                    }
                }
                runCatching { Scheduler.enqueueExtraction(context) }
                runCatching { Scheduler.enqueueTranscription(context) }
                runCatching { Scheduler.enqueueReminderCheck(context) }
            }
            ResidencyService.start(context)
        } catch (t: Throwable) {
            runCatching {
                AppContainer.get(context).logger.post(Stage.SYSTEM, LogLevel.ERROR, "watchdog failed", t.toString())
            }
        }
    }

    companion object {
        const val ACTION = "com.taskmind.action.WATCHDOG"

        /** ~15 minutes, and allowed to fire in Doze (spec 11.2 / 17.4). */
        private const val INTERVAL_MILLIS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val pendingIntent = pendingIntent(context)
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MILLIS,
                    pendingIntent,
                )
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            runCatching { alarmManager.cancel(pendingIntent(context)) }
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WatchdogReceiver::class.java).setAction(ACTION)
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

/** Fires task reminders set by the user (spec 16). */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val container = AppContainer.get(context)
            val pending = goAsync()
            container.applicationScope.launch {
                try {
                    val now = System.currentTimeMillis()
                    val due = container.database.taskDao().dueReminders(now)
                    for (task in due) {
                        container.notifier.postReminder(task.id, task.title, task.notes)
                        container.database.taskDao().setReminder(task.id, null, now)
                    }
                    Scheduler.scheduleNextReminder(context, container)
                } finally {
                    pending.finish()
                }
            }
        } catch (t: Throwable) {
            runCatching {
                AppContainer.get(context).logger.post(Stage.SYSTEM, LogLevel.ERROR, "ReminderReceiver failed", t.toString())
            }
        }
    }

    companion object {
        const val ACTION = "com.taskmind.action.REMINDER"
    }
}
