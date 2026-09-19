package com.taskmind.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Keeps the standing reminder current.
 *
 * Runs hourly as a safety net and is also enqueued directly whenever the app
 * goes to the background, which is when tasks have most likely just changed.
 * It is cheap: one query, and it cancels rather than posting when nothing is
 * due.
 */
class OngoingReminderWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Never retry. A missed refresh is corrected by the next one an hour
        // later, and a reminder is not worth waking the device to retry.
        runCatching { OngoingReminder.refresh(applicationContext) }
        return Result.success()
    }
}
