package com.taskmind.email

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer

/**
 * Checks the connected mailbox: on its schedule, or once when the user taps
 * "Check now". Retries only what another attempt could fix - a dropped
 * connection, not a refused password.
 */
class EmailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        return try {
            val manual = inputData.getBoolean(KEY_MANUAL, false)
            when (val outcome = EmailSync(container).run(manual)) {
                is EmailSync.Result.Failed -> if (outcome.retryable && runAttemptCount < 3) Result.retry() else Result.success()
                else -> Result.success()
            }
        } catch (t: Throwable) {
            container.logger.write(Stage.CAPTURE, LogLevel.WARN, "email worker failed", t.toString())
            Result.success()
        }
    }

    companion object {
        const val KEY_MANUAL = "manual"
    }
}
