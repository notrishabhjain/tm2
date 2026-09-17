package com.taskmind.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer

/**
 * Pushes to the web mirror on a schedule and whenever the app goes to the
 * background.
 *
 * Deliberately in the sync package rather than alongside the pipeline workers:
 * everything in `work/Workers.kt` belongs to the capture-to-task engine, and
 * this has no business being tangled up with it. Only the one line that
 * schedules it lives over there.
 *
 * Idempotent, like the others: an upsert keyed on the row id, and a high-water
 * mark that only moves when a whole run has succeeded.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        return try {
            val engine = SyncEngine(
                container = container,
                store = SyncStore(applicationContext),
                secrets = SyncSecrets(applicationContext),
            )
            when (val outcome = engine.run()) {
                is SyncEngine.Result.Pushed -> Result.success()
                SyncEngine.Result.Skipped -> Result.success()

                // Retry only what another attempt could fix. A wrong key or a
                // missing table fails identically every time, and retrying it
                // on WorkManager's backoff just writes the same error into the
                // log until someone changes a setting.
                is SyncEngine.Result.Failed ->
                    if (outcome.retryable) Result.retry() else Result.success()
            }
        } catch (t: Throwable) {
            container.logger.write(Stage.SYSTEM, LogLevel.WARN, "web sync worker failed", t.toString())
            Result.success()
        }
    }
}
