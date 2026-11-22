package com.taskmind.capture

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer

/**
 * Keeps the long-term memory up to date with WhatsApp's own nightly backup
 * (v1.36.1), so "Read again" no longer has to be tapped by hand. A no-op until
 * the user has imported once (which saves the key) and WhatsApp has written a
 * newer backup since.
 */
class WhatsAppRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        return try {
            if (container.whatsAppBackupImport.refreshIfNewer()) {
                container.logger.write(Stage.CAPTURE, LogLevel.INFO, "WhatsApp history refreshed from the newest backup")
            }
            Result.success()
        } catch (t: Throwable) {
            container.logger.write(Stage.CAPTURE, LogLevel.WARN, "WhatsApp history refresh failed", t.toString())
            Result.success()
        }
    }
}
