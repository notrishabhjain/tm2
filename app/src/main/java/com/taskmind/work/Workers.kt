package com.taskmind.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.taskmind.capture.ExtractionPipeline
import com.taskmind.capture.TranscriptionPipeline
import com.taskmind.core.CaptureState
import com.taskmind.core.DateResolver
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import java.io.File

/**
 * Spec 2: WorkManager for retryable jobs. Foreground services only for the two
 * cases in spec 17.1.
 *
 * Every worker here is idempotent and safe to run twice: the intake funnel's
 * unique index absorbs a repeat, and capture states are only advanced forward.
 */

/** Drains PENDING_EXTRACTION oldest-first (spec 8.4). */
class ExtractionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        return try {
            val now = System.currentTimeMillis()
            val dao = container.database.rawCaptureDao()

            // Spec 9: anything held for budget is released after local midnight.
            container.settingsRepository.rollBudgetIfNeeded(DateResolver.dayKey(now))
            releaseBudgetHoldsIfNewDay(container, now)

            val batch = dao.dueForState(CaptureState.PENDING_EXTRACTION, now, BATCH_SIZE)
            if (batch.isEmpty()) return Result.success()

            var retryNeeded = false
            for (capture in batch) {
                when (container.extractionPipeline.process(capture)) {
                    is ExtractionPipeline.Outcome.Retry -> retryNeeded = true
                    is ExtractionPipeline.Outcome.Parked -> Unit
                    else -> Unit
                }
            }
            if (retryNeeded) Result.retry() else Result.success()
        } catch (t: Throwable) {
            container.logger.write(Stage.WORKER, LogLevel.ERROR, "extraction worker failed", t.toString())
            Result.retry()
        }
    }

    private suspend fun releaseBudgetHoldsIfNewDay(container: AppContainer, now: Long) {
        val dao = container.database.rawCaptureDao()
        val held = dao.byState(CaptureState.BUDGET_HELD, 1)
        if (held.isEmpty()) return
        val releaseAt = held.first().nextAttemptAt ?: return
        if (now >= releaseAt) {
            dao.releaseState(CaptureState.BUDGET_HELD, CaptureState.PENDING_EXTRACTION)
            container.logger.write(Stage.BUDGET, LogLevel.INFO, "released budget-held captures")
        }
    }

    private companion object {
        const val BATCH_SIZE = 20
    }
}

/** Transcribes calls whose recording has been found (spec 12). */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        return try {
            val now = System.currentTimeMillis()
            val batch = container.database.rawCaptureDao()
                .dueForState(CaptureState.PENDING_TRANSCRIPTION, now, BATCH_SIZE)
            if (batch.isEmpty()) return Result.success()

            var retryNeeded = false
            for (capture in batch) {
                when (container.transcriptionPipeline.transcribe(capture)) {
                    is TranscriptionPipeline.Outcome.Transcribed -> Scheduler.enqueueExtraction(applicationContext)
                    is TranscriptionPipeline.Outcome.Retry -> retryNeeded = true
                    else -> Unit
                }
            }
            if (retryNeeded) Result.retry() else Result.success()
        } catch (t: Throwable) {
            container.logger.write(Stage.WORKER, LogLevel.ERROR, "transcription worker failed", t.toString())
            Result.retry()
        }
    }

    private companion object {
        const val BATCH_SIZE = 3
    }
}

/**
 * Looks for the recordings of calls we know about (spec 11.3).
 *
 * Failure mode 7: the caller must NOT pre-check whether a recording exists.
 * This worker owns the retry loop; it is started unconditionally.
 */
class CallDiscoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        return try {
            container.callPipeline.sweepCallLog("discovery worker")
            val pending = container.callPipeline.pendingDiscovery()
            var found = 0
            for (record in pending) {
                if (container.callPipeline.discoverRecording(record.id)) found++
            }
            if (found > 0) Scheduler.enqueueTranscription(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            container.logger.write(Stage.WORKER, LogLevel.ERROR, "call discovery worker failed", t.toString())
            Result.retry()
        }
    }
}

/** The periodic heartbeat: sweeps, drains and reschedules (spec 17.4). */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        return try {
            container.settingsRepository.rollBudgetIfNeeded(DateResolver.dayKey(System.currentTimeMillis()))
            container.callPipeline.sweepCallLog("maintenance")
            Scheduler.enqueueCallDiscovery(applicationContext)
            Scheduler.enqueueExtraction(applicationContext)
            Scheduler.enqueueTranscription(applicationContext)
            Scheduler.scheduleNextReminder(applicationContext, container)
            Scheduler.ensureWatchdog(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            container.logger.write(Stage.WORKER, LogLevel.ERROR, "maintenance worker failed", t.toString())
            Result.success()
        }
    }
}

/**
 * Spec 6.3 - retention.
 *
 * Purges transcripts, raw message text and audio on the user's schedule. It
 * must NEVER delete tasks derived from the raw capture: sourceLabel and
 * evidence are denormalised onto the task exactly so that a purged task still
 * shows who said it and the words that created it.
 */
class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        return try {
            val settings = container.settingsRepository.current()
            val cutoff = System.currentTimeMillis() - settings.retentionDays * DAY_MILLIS
            val dao = container.database.rawCaptureDao()

            val purgeable = dao.purgeable(cutoff)
            if (purgeable.isNotEmpty()) {
                // Detach first so the tasks survive with their evidence intact,
                // then delete the raw rows. Never the other way round.
                container.database.taskDao().detachRawCaptures(purgeable.map { it.id })
                for (capture in purgeable) {
                    capture.audioPath?.let { path -> runCatching { File(path).delete() } }
                }
                dao.delete(purgeable.map { it.id })
                container.logger.write(
                    Stage.SYSTEM,
                    LogLevel.INFO,
                    "retention purge removed ${purgeable.size} raw capture(s)",
                    "older than ${settings.retentionDays} days; tasks kept",
                )
            }

            container.database.fingerprintDao().purgeOlderThan(System.currentTimeMillis() - SEVEN_DAYS)
            container.database.reviewItemDao().purgeResolved(cutoff)
            container.database.activityLogDao().trimTo(500)
            Result.success()
        } catch (t: Throwable) {
            container.logger.write(Stage.WORKER, LogLevel.ERROR, "retention worker failed", t.toString())
            Result.success()
        }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val SEVEN_DAYS = 7L * 24 * 60 * 60 * 1000
    }
}

/** Spec 19 - the daily self-update check. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        return try {
            val settings = container.settingsRepository.current()
            if (!settings.autoCheckUpdates || settings.updateManifestUrl.isBlank()) return Result.success()
            val manifest = container.selfUpdater.fetchManifest(settings.updateManifestUrl)
            if (manifest != null && container.selfUpdater.isNewer(manifest) && manifest.mandatory) {
                // Spec 19: no notification unless the update is mandatory.
                container.notifier.postMandatoryUpdate(manifest.versionName)
            }
            Result.success()
        } catch (t: Throwable) {
            container.logger.write(Stage.UPDATE, LogLevel.WARN, "update check failed", t.toString())
            Result.success()
        }
    }
}
