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
import com.taskmind.data.repo.ActivityLogger
import com.taskmind.data.repo.RoomInferenceRecorder
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

            // Captures blocked by a bad model or key come back into the queue
            // when - and only when - that configuration has actually changed.
            container.extractionPipeline.releaseBlockedIfConfigChanged()

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
            val dao = container.database.rawCaptureDao()

            // Order matters. Retire the backlog first, then release what is
            // left: doing it the other way round would queue thousands of old
            // recordings for a moment, and a moment is enough for the batch
            // below to start uploading them.
            reconcileAgainstCutoff(container)

            val batch = dao.dueForState(CaptureState.PENDING_TRANSCRIPTION, now, BATCH_SIZE)
            if (batch.isEmpty()) return Result.success()

            var retryNeeded = false
            var progressed = 0
            for (capture in batch) {
                when (container.transcriptionPipeline.transcribe(capture)) {
                    is TranscriptionPipeline.Outcome.Transcribed -> {
                        progressed++
                        Scheduler.enqueueExtraction(applicationContext)
                    }
                    is TranscriptionPipeline.Outcome.Retry -> {
                        progressed++
                        retryNeeded = true
                    }
                    // Waiting: discovery has not found the recording yet. Left
                    // untouched on purpose, and deliberately NOT counted as
                    // progress - a batch of nothing but waiting rows that
                    // re-enqueued itself would spin the worker in a tight loop.
                    TranscriptionPipeline.Outcome.Waiting -> Unit
                    else -> progressed++
                }
            }

            // A full batch of real work means there is more behind it. Without
            // this the queue drained three recordings per maintenance tick,
            // which on a busy day is slower than the calls arrive.
            if (progressed > 0 && batch.size == BATCH_SIZE) {
                Scheduler.enqueueTranscription(applicationContext)
            }

            if (retryNeeded) Result.retry() else Result.success()
        } catch (t: Throwable) {
            container.logger.write(Stage.WORKER, LogLevel.ERROR, "transcription worker failed", t.toString())
            Result.retry()
        }
    }

    /**
     * Brings the queue into line with the cutoff, in that order.
     *
     * Two separate corrections, and they pull in opposite directions:
     *
     * Old call captures are retired. An upgrade inherits everything the
     * previous build had queued or parked, all of it pointing at recordings
     * made before the app drew its line, and none of it wanted.
     *
     * What survives that, and is still waiting to be hand-picked, is released.
     * Transcription is automatic now, so AWAITING_SELECTION has no way out -
     * no screen asks for the tap any more - and a recording stuck there would
     * be invisible forever.
     */
    private suspend fun reconcileAgainstCutoff(container: AppContainer) {
        val dao = container.database.rawCaptureDao()
        val cutoff = container.settingsRepository.current().recordingCutoffMillis

        if (cutoff > 0) {
            val retired = runCatching { dao.retireCallCapturesBefore(cutoff) }.getOrDefault(0)
            if (retired > 0) {
                container.logger.write(
                    Stage.CALL,
                    LogLevel.INFO,
                    "retired $retired old recording(s) from the queue",
                    "They were recorded before TaskMind started watching, so they are not " +
                        "transcribed. New calls are handled automatically.",
                )
            }
        }

        if (dao.byState(CaptureState.AWAITING_SELECTION, 1).isNotEmpty()) {
            dao.requeueRecordings(CaptureState.AWAITING_SELECTION, CaptureState.PENDING_TRANSCRIPTION)
            container.logger.write(
                Stage.CALL,
                LogLevel.INFO,
                "queued recordings that were waiting to be picked",
                "Transcription is automatic now, so nothing waits for a tap.",
            )
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
            // One source of truth for the cap: the logger's own constant. These
            // two drifting apart is how the log ends up shorter than the code
            // that writes it thinks it is.
            container.database.activityLogDao().trimTo(ActivityLogger.KEEP)
            container.database.inferenceCallDao().trimTo(RoomInferenceRecorder.KEEP)
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
