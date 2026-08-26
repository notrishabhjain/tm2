package com.taskmind.capture

import com.taskmind.core.CaptureState
import com.taskmind.core.LogLevel
import com.taskmind.core.NotificationResolver
import com.taskmind.core.PreFilter
import com.taskmind.core.SourceRef
import com.taskmind.core.SourceType
import com.taskmind.core.Stage
import com.taskmind.data.db.dao.RawCaptureDao
import com.taskmind.data.db.dao.SeenPackageDao
import com.taskmind.data.db.entity.RawCaptureEntity
import com.taskmind.data.repo.ActivityLogger
import com.taskmind.data.settings.Settings
import java.util.UUID

/**
 * Spec 5: every capture path writes a RawCapture row and NOTHING else. Capture
 * never writes to the task table.
 *
 * Spec 10.4: the row is written synchronously, before any filtering that needs
 * I/O, because the listener process can die at any moment and the row must
 * already exist. Only the checks that are pure in-memory decisions run first;
 * the fingerprint lookup happens later, in the worker.
 */
class CaptureCoordinator(
    private val rawCaptureDao: RawCaptureDao,
    private val seenPackageDao: SeenPackageDao,
    private val logger: ActivityLogger,
) {

    sealed interface Outcome {
        data class Captured(val rawCaptureId: String, val sourceRef: String) : Outcome
        data class Rejected(val rule: String) : Outcome
        data class Duplicate(val rawCaptureId: String) : Outcome
        data object Unusable : Outcome
    }

    /**
     * The production entry point for a notification. The self-test (spec 15)
     * injects a synthetic notification through THIS function, not a parallel
     * one - failure mode 8 was a diagnostic that tested a path the app did not
     * actually use.
     */
    suspend fun handleNotification(
        fields: NotificationResolver.Fields,
        appLabel: String?,
        settings: Settings,
        ownPackageName: String,
    ): Outcome {
        // Record the package first, so the settings allow-list can be built
        // from what has actually arrived rather than from a guess (spec 10.3).
        runCatching { seenPackageDao.record(fields.packageName, appLabel, System.currentTimeMillis()) }

        if (!settings.captureNotifications) {
            return Outcome.Rejected("notification capture disabled in settings")
        }
        if (!settings.cloudConsent) {
            // Spec 18.1: capture stays disabled until the user has ticked the
            // consent box. Nothing leaves the device before then, and nothing
            // accumulates that they did not agree to.
            return Outcome.Rejected("cloud consent not given")
        }

        val resolved = NotificationResolver.resolve(fields)
        if (resolved == null) {
            logger.write(Stage.PREFILTER, LogLevel.DEBUG, "no usable text", fields.packageName)
            return Outcome.Unusable
        }

        val verdict = PreFilter.evaluate(
            PreFilter.Input(
                packageName = fields.packageName,
                senderKey = resolved.senderKey,
                text = resolved.messageText,
                isGroupSummary = fields.isGroupSummary,
                isOngoing = fields.isOngoing,
                isMediaStyle = fields.isMediaStyle,
                isAllowListed = fields.packageName in settings.allowedPackages,
                // The fingerprint lookup needs I/O; it runs in the worker, so
                // that this function can complete inside onNotificationPosted.
                fingerprintSeen = false,
                ownPackageName = ownPackageName,
            ),
        )
        if (verdict is PreFilter.Verdict.Reject) {
            // Every rejection writes one line naming the rule, so a false
            // reject is diagnosable from the log alone (spec 10.3).
            logger.write(
                Stage.PREFILTER,
                LogLevel.DEBUG,
                "rejected: ${verdict.rule}",
                "pkg=${fields.packageName} sender=${resolved.senderKey} text=${resolved.messageText.take(160)}",
            )
            return Outcome.Rejected(verdict.rule)
        }

        val sourceRef = SourceRef.forNotification(
            packageName = fields.packageName,
            senderKey = resolved.senderKey,
            messageText = resolved.messageText,
        )

        val existing = rawCaptureDao.bySourceRef(SourceType.NOTIFICATION.name, sourceRef)
        if (existing != null) {
            logger.write(Stage.CAPTURE, LogLevel.DEBUG, "already captured", sourceRef)
            return Outcome.Duplicate(existing.id)
        }

        val id = UUID.randomUUID().toString()
        val occurredAt = if (fields.postTime > 0) fields.postTime else System.currentTimeMillis()
        val label = buildString {
            append(resolved.senderKey.ifBlank { "Unknown" })
            resolved.groupName?.let { append(" in ").append(it) }
            append(" - ").append(appLabel ?: fields.packageName)
        }

        rawCaptureDao.insert(
            RawCaptureEntity(
                id = id,
                sourceType = SourceType.NOTIFICATION,
                sourceRef = sourceRef,
                sourceApp = fields.packageName,
                sourceLabel = label,
                rawText = resolved.messageText,
                capturedAt = System.currentTimeMillis(),
                occurredAt = occurredAt,
                state = CaptureState.PENDING_EXTRACTION,
                contextLabel = contextLabel(resolved),
            ),
        )

        logger.write(
            Stage.CAPTURE,
            LogLevel.INFO,
            "captured notification from ${resolved.senderKey}",
            "pkg=${fields.packageName} via=${resolved.textSource} text=${resolved.messageText.take(200)}",
        )
        return Outcome.Captured(id, sourceRef)
    }

    private fun contextLabel(resolved: NotificationResolver.Resolved): String =
        buildString {
            append("sender=").append(resolved.senderKey)
            resolved.groupName?.let { append("|group=").append(it) }
            append("|isGroup=").append(resolved.isGroup)
        }

    /** Spec 11.4 - clipboard import of a Xiaomi HyperAI transcript. */
    suspend fun captureClipboardTranscript(
        transcript: String,
        contactLabel: String?,
        occurredAt: Long,
    ): Outcome {
        if (transcript.isBlank()) return Outcome.Unusable
        val sourceRef = SourceRef.forClipboard(transcript)
        rawCaptureDao.bySourceRef(SourceType.CLIPBOARD.name, sourceRef)?.let {
            return Outcome.Duplicate(it.id)
        }
        val id = UUID.randomUUID().toString()
        rawCaptureDao.insert(
            RawCaptureEntity(
                id = id,
                sourceType = SourceType.CLIPBOARD,
                sourceRef = sourceRef,
                sourceApp = null,
                sourceLabel = contactLabel ?: "Imported transcript",
                rawText = transcript,
                capturedAt = System.currentTimeMillis(),
                occurredAt = occurredAt,
                state = CaptureState.PENDING_EXTRACTION,
                contextLabel = contactLabel,
            ),
        )
        logger.write(Stage.IMPORT, LogLevel.INFO, "imported transcript", "chars=${transcript.length}")
        return Outcome.Captured(id, sourceRef)
    }

    /**
     * Spec 11: a call produces a RawCapture the moment it is detected, before
     * any recording has been found. The capture is the promise that the call
     * will not be lost; discovery and transcription fill it in later.
     */
    suspend fun captureCall(
        sourceRef: String,
        label: String,
        occurredAt: Long,
        audioPath: String?,
    ): Outcome {
        rawCaptureDao.bySourceRef(SourceType.CALL.name, sourceRef)?.let {
            return Outcome.Duplicate(it.id)
        }
        val id = UUID.randomUUID().toString()
        rawCaptureDao.insert(
            RawCaptureEntity(
                id = id,
                sourceType = SourceType.CALL,
                sourceRef = sourceRef,
                sourceApp = null,
                sourceLabel = label,
                rawText = null,
                audioPath = audioPath,
                capturedAt = System.currentTimeMillis(),
                occurredAt = occurredAt,
                state = CaptureState.PENDING_TRANSCRIPTION,
                contextLabel = label,
            ),
        )
        logger.write(Stage.CALL, LogLevel.INFO, "captured call", "ref=$sourceRef label=$label")
        return Outcome.Captured(id, sourceRef)
    }
}
