package com.taskmind.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.taskmind.core.CallDirection
import com.taskmind.core.CallState
import com.taskmind.core.CaptureState
import com.taskmind.core.LogLevel
import com.taskmind.core.SourceRef
import com.taskmind.core.SourceType
import com.taskmind.core.Stage
import com.taskmind.data.db.dao.CallRecordDao
import com.taskmind.data.db.dao.RawCaptureDao
import com.taskmind.data.db.entity.CallRecordEntity
import com.taskmind.data.repo.ActivityLogger
import com.taskmind.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Spec 11 - call capture.
 *
 * The app must NOT record calls (spec 11.1). It consumes recordings written by
 * the device's own dialer. Three independent end-of-call triggers feed this
 * class, because on HyperOS any single mechanism can be silently blocked.
 */
class CallPipeline(
    private val context: Context,
    private val callRecordDao: CallRecordDao,
    private val rawCaptureDao: RawCaptureDao,
    private val captureCoordinator: CaptureCoordinator,
    private val recordingFinder: RecordingFinder,
    private val settingsRepository: SettingsRepository,
    private val logger: ActivityLogger,
) {

    fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Reads the call log for calls we have not seen and registers each one.
     *
     * This is the shared body of all three triggers plus both recovery paths.
     * It is idempotent: the CallRecord unique index on callLogId and the
     * RawCapture sourceRef lookup both absorb a repeat.
     */
    suspend fun sweepCallLog(reason: String): Int = withContext(Dispatchers.IO) {
        val settings = settingsRepository.current()
        if (!settings.captureCalls) return@withContext 0
        if (!settings.cloudConsent) return@withContext 0
        if (!hasCallLogPermission()) {
            logger.write(Stage.CALL, LogLevel.WARN, "call sweep skipped - READ_CALL_LOG not granted", reason)
            return@withContext 0
        }

        val since = (callRecordDao.latestStartTime() ?: (System.currentTimeMillis() - LOOKBACK_MILLIS))
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )

        var registered = 0
        val cursor = runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                "${CallLog.Calls.DATE} > ?",
                arrayOf(since.toString()),
                "${CallLog.Calls.DATE} DESC",
            )
        }.getOrNull()

        if (cursor == null) {
            logger.write(Stage.CALL, LogLevel.WARN, "call log query returned nothing", reason)
            return@withContext 0
        }

        cursor.use { c ->
            val idIdx = c.getColumnIndex(CallLog.Calls._ID)
            val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
            val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
            val durationIdx = c.getColumnIndex(CallLog.Calls.DURATION)

            while (c.moveToNext()) {
                val callLogId = if (idIdx >= 0) c.getLong(idIdx) else null
                val number = if (numberIdx >= 0) c.getString(numberIdx) else null
                val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                val type = if (typeIdx >= 0) c.getInt(typeIdx) else 0
                val date = if (dateIdx >= 0) c.getLong(dateIdx) else continue
                // The call log genuinely reports NULL duration while the row is
                // still being written. Reading it as a nullable and handling
                // that explicitly is failure mode 4.
                val duration = if (durationIdx >= 0 && !c.isNull(durationIdx)) c.getLong(durationIdx) else null

                val direction = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> CallDirection.INCOMING
                    CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
                    CallLog.Calls.MISSED_TYPE -> CallDirection.MISSED
                    CallLog.Calls.REJECTED_TYPE -> CallDirection.REJECTED
                    else -> CallDirection.UNKNOWN
                }

                // Spec 11.2: process only completed incoming/outgoing calls.
                if (direction != CallDirection.INCOMING && direction != CallDirection.OUTGOING) continue

                // Spec 11.2 / failure mode 4: a NULL duration must NOT exclude
                // the call. Unknown means "we do not know yet", not "too short".
                if (duration != null && duration < settings.minCallDurationSeconds) {
                    logger.write(
                        Stage.CALL,
                        LogLevel.DEBUG,
                        "call too short (${duration}s)",
                        "min=${settings.minCallDurationSeconds}s",
                    )
                    continue
                }

                if (callLogId != null && callRecordDao.byCallLogId(callLogId) != null) continue

                registerCall(callLogId, number, name, direction, date, duration)
                registered++
            }
        }

        if (registered > 0) {
            logger.write(Stage.CALL, LogLevel.INFO, "registered $registered call(s)", "trigger=$reason")
        }
        registered
    }

    /**
     * Creates the CallRecord and the RawCapture. The capture exists from this
     * moment: it is the promise that the call will not be lost even if
     * discovery, ASR and extraction all fail later.
     */
    suspend fun registerCall(
        callLogId: Long?,
        number: String?,
        contactName: String?,
        direction: CallDirection,
        startTime: Long,
        durationSeconds: Long?,
    ): String {
        val sourceRef = SourceRef.forCall(callLogId, startTime, number)
        val label = buildString {
            append("Call with ")
            append(contactName ?: number ?: "unknown number")
            append(" - ")
            append(
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata") }
                    .format(java.util.Date(startTime)),
            )
        }

        val outcome = captureCoordinator.captureCall(sourceRef, label, startTime, audioPath = null)
        val rawCaptureId = when (outcome) {
            is CaptureCoordinator.Outcome.Captured -> outcome.rawCaptureId
            is CaptureCoordinator.Outcome.Duplicate -> outcome.rawCaptureId
            else -> null
        }

        val existing = callLogId?.let { callRecordDao.byCallLogId(it) }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        callRecordDao.upsert(
            CallRecordEntity(
                id = id,
                callLogId = callLogId,
                contactName = contactName,
                phoneNumber = number,
                direction = direction,
                startTime = startTime,
                durationSeconds = durationSeconds,
                recordingPath = existing?.recordingPath,
                transcript = existing?.transcript,
                state = existing?.state ?: CallState.PENDING_RECORDING,
                rawCaptureId = rawCaptureId,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        return id
    }

    /**
     * Spec 11.3 - discovery, with its own retry loop.
     *
     * Failure mode 7: do NOT pre-check whether a recording exists before
     * starting this. Right after a call the recorder is still flushing and the
     * answer is always "no". Start the worker; let it do the looking.
     */
    suspend fun discoverRecording(callRecordId: String): Boolean = withContext(Dispatchers.IO) {
        val record = callRecordDao.byId(callRecordId) ?: return@withContext false
        if (record.recordingPath != null) return@withContext true

        val settings = settingsRepository.current()

        // All Files Access is needed to sweep the OEM paths, but NOT to read a
        // folder the user nominated with the system picker - that grant stands
        // on its own. Refusing to look at all unless All Files Access is held
        // made the folder picker useless for exactly the people who chose it
        // because they could not, or would not, grant blanket storage access.
        val hasUserDir = !settings.callRecordingDirUri.isNullOrBlank()
        if (!recordingFinder.hasAllFilesAccess() && !hasUserDir) {
            logger.write(
                Stage.CALL,
                LogLevel.WARN,
                "cannot search for recordings - no All Files Access and no folder chosen",
                "call=${record.phoneNumber}. Grant All Files Access, or pick your dialer's " +
                    "recording folder in Settings.",
            )
            return@withContext false
        }

        val callEnd = record.startTime + (record.durationSeconds ?: 0L) * 1000

        for ((attempt, delayMillis) in DISCOVERY_DELAYS.withIndex()) {
            delay(delayMillis)
            val candidate = recordingFinder.findForCall(
                callStartMillis = record.startTime,
                callEndMillis = callEnd,
                phoneNumber = record.phoneNumber,
                userDirUri = settings.callRecordingDirUri,
            )
            if (candidate == null) continue

            // Spec 11.3: the file must be stable across two reads 1 s apart, or
            // we would transcribe a half-written file.
            if (!recordingFinder.isStable(candidate)) {
                logger.write(Stage.CALL, LogLevel.DEBUG, "recording still being written", candidate.name)
                continue
            }

            // Persist first, mark second (failure mode 5).
            //
            // Finding the recording and deciding to upload it are two different
            // things. With automatic transcription off the file is recorded
            // against the call and left alone, so a phone holding thousands of
            // recordings does not turn into an unbounded upload.
            val nextState = if (settings.autoTranscribeCalls) {
                CaptureState.PENDING_TRANSCRIPTION
            } else {
                CaptureState.AWAITING_SELECTION
            }
            record.rawCaptureId?.let { rawId ->
                val capture = rawCaptureDao.byId(rawId)
                if (capture != null) {
                    rawCaptureDao.update(
                        capture.copy(audioPath = candidate.path, state = nextState),
                    )
                }
            }
            callRecordDao.upsert(
                record.copy(
                    recordingPath = candidate.path,
                    state = CallState.PENDING_TRANSCRIPTION,
                    discoveryAttempts = attempt + 1,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            logger.write(
                Stage.CALL,
                LogLevel.INFO,
                "found recording for ${record.contactName ?: record.phoneNumber}",
                "file=${candidate.name} attempt=${attempt + 1} bytes=${candidate.sizeBytes}",
            )
            return@withContext true
        }

        // Not found yet. Keep the marker alive so later sweeps retry within the
        // ~5 minute window (spec 11.3); the capture is untouched and the call
        // is not lost.
        val attempts = record.discoveryAttempts + DISCOVERY_DELAYS.size
        val expired = System.currentTimeMillis() - record.startTime > PENDING_MARKER_MILLIS
        callRecordDao.upsert(
            record.copy(
                discoveryAttempts = attempts,
                state = if (expired) CallState.NO_RECORDING else CallState.PENDING_RECORDING,
                lastError = if (expired) "no recording found within 5 minutes" else null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        if (expired) {
            rawCaptureDao.setState(record.rawCaptureId ?: "", CaptureState.REJECTED)
            logger.write(
                Stage.CALL,
                LogLevel.WARN,
                "no recording found for ${record.contactName ?: record.phoneNumber}",
                "Is call recording enabled in your phone app?",
            )
        }
        false
    }

    /** Calls whose recording never turned up and whose marker has not expired. */
    suspend fun pendingDiscovery(): List<CallRecordEntity> =
        callRecordDao.byState(CallState.PENDING_RECORDING)

    /**
     * Spec 11.4: attach a manually imported transcript to a specific recent
     * call, so the extracted tasks get correct provenance and occurredAt.
     */
    suspend fun attachTranscript(callRecordId: String, transcript: String): String? {
        val record = callRecordDao.byId(callRecordId) ?: return null
        val rawId = record.rawCaptureId
        val now = System.currentTimeMillis()
        callRecordDao.upsert(record.copy(transcript = transcript, state = CallState.TRANSCRIBED, updatedAt = now))
        if (rawId != null) {
            val capture = rawCaptureDao.byId(rawId)
            if (capture != null) {
                rawCaptureDao.update(
                    capture.copy(rawText = transcript, state = CaptureState.PENDING_EXTRACTION),
                )
                return rawId
            }
        }
        val label = "Call with ${record.contactName ?: record.phoneNumber ?: "unknown"}"
        val outcome = captureCoordinator.captureClipboardTranscript(transcript, label, record.startTime)
        return (outcome as? CaptureCoordinator.Outcome.Captured)?.rawCaptureId
    }

    private companion object {
        /** Spec 11.3: retry discovery at 3 s, 6 s, 10 s, 20 s. */
        val DISCOVERY_DELAYS = listOf(3_000L, 3_000L, 4_000L, 10_000L)

        const val PENDING_MARKER_MILLIS = 5 * 60 * 1000L
        const val LOOKBACK_MILLIS = 6 * 60 * 60 * 1000L
    }
}
