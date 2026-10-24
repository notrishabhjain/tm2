package com.taskmind.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Kept only so work an older version queued under this class still has
 * something to run: it takes the retired standing notification down and
 * finishes. See [OngoingReminder].
 */
class OngoingReminderWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        OngoingReminder.cancel(applicationContext)
        return Result.success()
    }
}
