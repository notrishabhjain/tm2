package com.taskmind.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

/**
 * Drains PENDING_EXTRACTION oldest-first (spec 8.4).
 *
 * The safety net behind the instant lane, which normally reads a capture the
 * moment it arrives. Both go through InstantLane's lock, so they never read
 * the same capture twice.
 */
class ExtractionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        return try {
            val pass = container.instantLane.drainMessages()
            if (pass.retry) Result.retry() else Result.success()
        } catch (t: Throwable) {
            container.logger.write(Stage.WORKER, LogLevel.ERROR, "extraction worker failed", t.toString())
            Result.retry()
        }
    }
}

/**
 * Transcribes calls whose recording has been found (spec 12): the safety net
 * behind the instant lane, sharing its lock.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        return try {
            val pass = container.instantLane.drainAudio()
            if (pass.retry) Result.retry() else Result.success()
        } catch (t: Throwable) {
            container.logger.write(Stage.WORKER, LogLevel.ERROR, "transcription worker failed", t.toString())
            Result.retry()
        }
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
            // Calls already being searched for by the instant lane are left to
            // it; this waits for the searches it starts itself.
            container.instantLane.passDiscovery("discovery worker", wait = true)
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

            // Candidates nobody answered. Dismissed rather than deleted, so the
            // Dismissed tab can hand one back - the inbox gets cleared without
            // a real commitment being able to disappear for good.
            if (settings.reviewExpiryDays > 0) {
                val stale = System.currentTimeMillis() - settings.reviewExpiryDays * DAY_MILLIS
                val count = container.taskRepository.dismissStaleReviewItems(stale)
                if (count > 0) {
                    container.logger.write(
                        Stage.SYSTEM,
                        LogLevel.INFO,
                        "dismissed $count review item(s) nobody answered",
                        "older than ${settings.reviewExpiryDays} days; recoverable from Review -> Dismissed",
                    )
                }
            }

            container.database.reviewItemDao().purgeResolved(cutoff)
            // One source of truth for the cap: the logger's own constant. These
            // two drifting apart is how the log ends up shorter than the code
            // that writes it thinks it is.
            container.database.activityLogDao().trimTo(ActivityLogger.KEEP)
            container.database.inferenceCallDao().trimTo(RoomInferenceRecorder.KEEP)

            // And by age, not only by count. Both of these tables quote the
            // message text they describe, so a count-only cap let excerpts sit
            // on the device long after the raw captures they came from were
            // purged - which is not what the retention screen says happens.
            container.database.activityLogDao().purgeOlderThan(cutoff)
            container.database.inferenceCallDao().purgeOlderThan(cutoff)
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
