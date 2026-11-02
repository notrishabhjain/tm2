package com.taskmind.capture

import com.taskmind.core.CaptureState
import com.taskmind.core.EmailApps
import com.taskmind.core.EmailFilter
import com.taskmind.core.EmailText
import com.taskmind.core.LogLevel
import com.taskmind.core.Mention
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

    companion object {
        /** What mail read from the mailbox carries as its "app". */
        const val EMAIL_APP = "email"
    }

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

        // The package decision comes first, and silently.
        //
        // On the device this ran second, so every screenshot, gallery and
        // sound-effect notification from com.miui.* wrote a "no usable text"
        // line. The activity log keeps 500 entries; that noise was evicting the
        // capture and extraction entries the log exists to show. A package that
        // is not being watched should cost nothing at all, including a log line.
        // A mail app is read as email when that is switched on, allow-list or
        // not: its notifications are shaped differently from a chat's.
        val asEmail = settings.captureEmailApps && EmailApps.isEmailApp(fields.packageName)
        val packageVerdict = PreFilter.evaluatePackage(
            packageName = fields.packageName,
            isAllowListed = asEmail || fields.packageName in settings.allowedPackages,
            ownPackageName = ownPackageName,
        )
        if (packageVerdict is PreFilter.Verdict.Reject) {
            return Outcome.Rejected(packageVerdict.rule)
        }
        if (asEmail) return handleEmailNotification(fields, appLabel, settings, ownPackageName)

        val resolved = NotificationResolver.resolve(fields)
        if (resolved == null) {
            logger.write(Stage.PREFILTER, LogLevel.DEBUG, "no usable text", fields.packageName)
            return Outcome.Unusable
        }

        // Who the message names, worked out once and used twice: the group
        // rule below decides whether to read it at all, and the same answer is
        // handed to the model later so it can tell whether the ask is aimed at
        // the user. Scanning is pure string work and costs nothing.
        val mention = Mention.scan(resolved.messageText, settings.ownNames)
        val groupPolicy = PreFilter.resolveGroupPolicy(
            isGroup = resolved.isGroup,
            groupName = resolved.groupName,
            chosen = settings.groupPolicy,
            ownNames = settings.ownNames,
            alwaysWatch = settings.groupsAlwaysWatch,
            neverWatch = settings.groupsNeverWatch,
        )

        val verdict = PreFilter.evaluate(
            PreFilter.Input(
                packageName = fields.packageName,
                senderKey = resolved.senderKey,
                text = resolved.messageText,
                isGroupSummary = fields.isGroupSummary,
                // A summary that resolved to one MessagingStyle entry names a
                // single sender and message, so it is a real message rather
                // than a roll-up banner.
                hasResolvedMessage = resolved.textSource == "EXTRA_MESSAGES.last" &&
                    resolved.senderKey.isNotBlank(),
                isOngoing = fields.isOngoing,
                isMediaStyle = fields.isMediaStyle,
                isAllowListed = fields.packageName in settings.allowedPackages,
                // The fingerprint lookup needs I/O; it runs in the worker, so
                // that this function can complete inside onNotificationPosted.
                fingerprintSeen = false,
                ownPackageName = ownPackageName,
                isGroup = resolved.isGroup,
                groupPolicy = groupPolicy,
                mention = mention,
            ),
        )
        if (verdict is PreFilter.Verdict.Reject) {
            // Every rejection writes one line naming the rule, so a false
            // reject is diagnosable from the log alone (spec 10.3).
            logger.write(
                Stage.PREFILTER,
                LogLevel.DEBUG,
                "rejected: ${verdict.rule}",
                // The text source is part of the rejection because it is what
                // distinguishes a real message that happened to arrive as a
                // summary from a genuine roll-up banner - a question the log
                // previously could not answer.
                "pkg=${fields.packageName} sender=${resolved.senderKey} via=${resolved.textSource} " +
                    "text=${resolved.messageText.take(160)}",
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

        val inserted = rawCaptureDao.insert(
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

        if (inserted == -1L) {
            // The unique index rejected it: another thread captured the same
            // thing between our lookup above and this insert. Whoever won owns
            // it; report theirs rather than creating a second.
            val winner = rawCaptureDao.bySourceRef(SourceType.NOTIFICATION.name, sourceRef)
            return if (winner != null) Outcome.Duplicate(winner.id) else Outcome.Rejected("insert conflict")
        }

        logger.write(
            Stage.CAPTURE,
            LogLevel.INFO,
            "captured notification from ${resolved.senderKey}",
            "pkg=${fields.packageName} via=${resolved.textSource} text=${resolved.messageText.take(200)}",
        )
        return Outcome.Captured(id, sourceRef)
    }

    /**
     * One mail, from a mail app's notification. The cheap rules still run
     * first - OTPs, receipts, promotions - and an account whose mailbox is
     * read directly is skipped, since the full mail arrives from there.
     */
    private suspend fun handleEmailNotification(
        fields: NotificationResolver.Fields,
        appLabel: String?,
        settings: Settings,
        ownPackageName: String,
    ): Outcome {
        if (fields.isGroupSummary) return Outcome.Rejected("group summary")
        if (fields.isOngoing || fields.isMediaStyle) return Outcome.Rejected("not a mail")
        val mail = EmailApps.read(fields.title, fields.text, fields.bigText, fields.subText)
        if (mail == null) {
            logger.write(Stage.PREFILTER, LogLevel.DEBUG, "no usable email text", fields.packageName)
            return Outcome.Unusable
        }
        val connected = settings.emailConnectedAddress
        if (connected.isNotBlank() && mail.account != null && EmailFilter.isMine(mail.account, setOf(connected))) {
            logger.write(Stage.PREFILTER, LogLevel.DEBUG, "email skipped: that mailbox is read directly", mail.account)
            return Outcome.Rejected("mailbox read directly")
        }
        val verdict = PreFilter.evaluate(
            PreFilter.Input(
                packageName = fields.packageName,
                senderKey = mail.sender,
                text = (mail.subject + "\n" + mail.body).trim(),
                isAllowListed = true,
                ownPackageName = ownPackageName,
            ),
        )
        if (verdict is PreFilter.Verdict.Reject) {
            logger.write(
                Stage.PREFILTER,
                LogLevel.DEBUG,
                "email rejected: ${verdict.rule}",
                "pkg=${fields.packageName} sender=${mail.sender} subject=${mail.subject.take(120)}",
            )
            return Outcome.Rejected(verdict.rule)
        }

        val text = EmailApps.captureText(mail)
        val sourceRef = SourceRef.forNotification(fields.packageName, mail.sender, text)
        rawCaptureDao.bySourceRef(SourceType.EMAIL.name, sourceRef)?.let { return Outcome.Duplicate(it.id) }
        val channel = EmailApps.label(fields.packageName) ?: appLabel ?: "Email"
        val id = UUID.randomUUID().toString()
        val inserted = rawCaptureDao.insert(
            RawCaptureEntity(
                id = id,
                sourceType = SourceType.EMAIL,
                sourceRef = sourceRef,
                sourceApp = fields.packageName,
                sourceLabel = "${mail.sender} - $channel",
                rawText = text,
                capturedAt = System.currentTimeMillis(),
                occurredAt = if (fields.postTime > 0) fields.postTime else System.currentTimeMillis(),
                state = CaptureState.PENDING_EXTRACTION,
                contextLabel = EmailText.contextLabel(mail.sender, null, mail.subject, sent = false),
            ),
        )
        if (inserted == -1L) {
            val winner = rawCaptureDao.bySourceRef(SourceType.EMAIL.name, sourceRef)
            return if (winner != null) Outcome.Duplicate(winner.id) else Outcome.Rejected("insert conflict")
        }
        logger.write(Stage.CAPTURE, LogLevel.INFO, "captured email from ${mail.sender}", "via=$channel subject=${mail.subject.take(160)}")
        return Outcome.Captured(id, sourceRef)
    }

    /**
     * One mail read from the mailbox. [sourceRef] is built from its
     * Message-ID, so the same mail met twice - in the inbox and the sent
     * folder, or on a second pass - is captured once.
     */
    suspend fun captureEmail(
        sourceRef: String,
        label: String,
        text: String,
        occurredAt: Long,
        contextLabel: String,
    ): Outcome {
        rawCaptureDao.bySourceRef(SourceType.EMAIL.name, sourceRef)?.let { return Outcome.Duplicate(it.id) }
        val id = UUID.randomUUID().toString()
        val inserted = rawCaptureDao.insert(
            RawCaptureEntity(
                id = id,
                sourceType = SourceType.EMAIL,
                sourceRef = sourceRef,
                sourceApp = EMAIL_APP,
                sourceLabel = label,
                rawText = text,
                capturedAt = System.currentTimeMillis(),
                occurredAt = occurredAt,
                state = CaptureState.PENDING_EXTRACTION,
                contextLabel = contextLabel,
            ),
        )
        if (inserted == -1L) {
            val winner = rawCaptureDao.bySourceRef(SourceType.EMAIL.name, sourceRef)
            return if (winner != null) Outcome.Duplicate(winner.id) else Outcome.Rejected("insert conflict")
        }
        logger.write(Stage.CAPTURE, LogLevel.INFO, "captured email", label)
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
        val inserted = rawCaptureDao.insert(
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
        if (inserted == -1L) {
            // Three triggers race to notice a call ending, and on the device two
            // of them landed in the same second. Without this the app captured
            // the call twice and paid to transcribe and extract it twice.
            val winner = rawCaptureDao.bySourceRef(SourceType.CALL.name, sourceRef)
            return if (winner != null) {
                Outcome.Duplicate(winner.id)
            } else {
                Outcome.Rejected("insert conflict")
            }
        }

        logger.write(Stage.CALL, LogLevel.INFO, "captured call", "ref=$sourceRef label=$label")
        return Outcome.Captured(id, sourceRef)
    }
}
