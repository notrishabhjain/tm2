package com.taskmind.capture

import com.taskmind.ai.AiResult
import com.taskmind.ai.errorText
import com.taskmind.ai.ExtractedTask
import com.taskmind.ai.MessageInput
import com.taskmind.ai.TaskExtractor
import com.taskmind.ai.TranscriptInput
import com.taskmind.core.Backoff
import com.taskmind.core.CaptureState
import com.taskmind.core.DateResolver
import com.taskmind.core.LogLevel
import com.taskmind.core.SourceRef
import com.taskmind.core.ProviderDiagnosis
import com.taskmind.core.SourceType
import com.taskmind.core.Stage
import com.taskmind.data.db.dao.FingerprintDao
import com.taskmind.data.db.dao.RawCaptureDao
import com.taskmind.data.db.entity.FingerprintEntity
import com.taskmind.data.db.entity.RawCaptureEntity
import com.taskmind.data.repo.ActivityLogger
import com.taskmind.data.settings.SettingsRepository
import com.taskmind.intake.IntakeFunnel
import com.taskmind.intake.IntakeResult
import com.taskmind.intake.TaskCandidate

/**
 * EXTRACT - the single most quality-critical stage (spec 5, 14).
 *
 * cheap pre-filter -> LLM (JSON mode) -> verify pass -> intake funnel.
 *
 * This class never touches the task table. It builds TaskCandidates and hands
 * them to IntakeFunnel.submit(), which is the only code that may create a task.
 */
class ExtractionPipeline(
    private val rawCaptureDao: RawCaptureDao,
    private val fingerprintDao: FingerprintDao,
    private val settingsRepository: SettingsRepository,
    private val extractor: TaskExtractor,
    private val funnel: IntakeFunnel,
    private val logger: ActivityLogger,
    private val hasLlmKey: () -> Boolean,
    private val appLabelFor: (String?) -> String,
) {

    sealed interface Outcome {
        data class Done(val created: Int, val review: Int, val dropped: Int) : Outcome
        data class Parked(val reason: String) : Outcome
        data class BudgetHeld(val reason: String) : Outcome
        data class Retry(val reason: String, val at: Long?) : Outcome
        data class Rejected(val reason: String) : Outcome
        data class Permanent(val reason: String) : Outcome
    }

    suspend fun process(capture: RawCaptureEntity): Outcome {
        val text = capture.rawText
        if (text.isNullOrBlank()) {
            return Outcome.Parked("no text yet - awaiting transcription")
        }

        // Spec 8.4: with no key, captures are parked, never discarded. When a
        // key is added, the drain worker comes back for them oldest-first.
        if (!hasLlmKey()) {
            rawCaptureDao.setState(capture.id, CaptureState.PENDING_EXTRACTION)
            return Outcome.Parked("no LLM API key configured")
        }

        val settings = settingsRepository.current()
        val now = System.currentTimeMillis()
        val todayKey = DateResolver.dayKey(now)
        settingsRepository.rollBudgetIfNeeded(todayKey)
        val usage = settingsRepository.currentUsage()

        // Spec 9: on exhaustion, captures move to BUDGET_HELD and are retried
        // after the next local midnight. Never discarded.
        if (usage.llmCalls >= settings.maxLlmCallsPerDay) {
            hold(capture, "daily LLM budget of ${settings.maxLlmCallsPerDay} calls reached", now)
            return Outcome.BudgetHeld("daily LLM budget reached")
        }
        val pkg = capture.sourceApp
        if (pkg != null) {
            val used = usage.perPackage[pkg] ?: 0
            if (used >= settings.maxLlmCallsPerPackagePerDay) {
                hold(capture, "per-package cap reached for $pkg", now)
                return Outcome.BudgetHeld("per-package cap reached for $pkg")
            }
        }

        // Fingerprint check. Cheap pre-inference reject only; the unique index
        // on tasks is what actually prevents duplicates (spec 6.2).
        if (capture.sourceType == SourceType.NOTIFICATION) {
            val fingerprint = SourceRef.fingerprint(
                packageName = capture.sourceApp.orEmpty(),
                senderKey = capture.contextLabel.orEmpty(),
                messageText = text,
            )
            val sevenDaysAgo = now - SEVEN_DAYS
            if (fingerprintDao.seenSince(fingerprint, sevenDaysAgo) > 0) {
                rawCaptureDao.setState(capture.id, CaptureState.REJECTED)
                logger.write(Stage.PREFILTER, LogLevel.DEBUG, "fingerprint seen within 7 days", capture.sourceRef)
                return Outcome.Rejected("fingerprint seen within 7 days")
            }
            fingerprintDao.insert(FingerprintEntity(fingerprint, now))
        }

        return when (capture.sourceType) {
            SourceType.NOTIFICATION -> processMessage(capture, text, settings.verifyPass, now, todayKey)
            SourceType.CALL, SourceType.CLIPBOARD -> processTranscript(capture, text, settings.verifyPass, now, todayKey)
            SourceType.MANUAL, SourceType.REVIEW -> {
                rawCaptureDao.setState(capture.id, CaptureState.DONE)
                Outcome.Done(0, 0, 0)
            }
        }
    }

    // ------------------------------------------------------------- messages

    private suspend fun processMessage(
        capture: RawCaptureEntity,
        text: String,
        verifyPass: Boolean,
        now: Long,
        todayKey: String,
    ): Outcome {
        val context = parseContext(capture.contextLabel)
        val input = MessageInput(
            appLabel = appLabelFor(capture.sourceApp),
            senderKey = context.sender,
            groupName = context.group,
            text = text,
            occurredAt = capture.occurredAt,
            rawCaptureId = capture.id,
            sourceLabel = capture.sourceLabel,
        )

        logger.write(
            Stage.EXTRACT,
            LogLevel.DEBUG,
            "LLM call for notification",
            "engine=${extractor.originLabel} sender=${context.sender}",
        )
        val result = extractor.extractFromMessage(input)
        settingsRepository.recordLlmCall(todayKey, capture.sourceApp)

        val extraction = when (result) {
            is AiResult.Ok -> result.value
            else -> return failure(capture, result, now)
        }

        logger.write(
            Stage.EXTRACT,
            LogLevel.INFO,
            "extraction: isTask=${extraction.isTask}",
            "tokens=${extraction.tokens ?: "?"} reasoning=${extraction.reasoning?.take(300)}",
        )

        val task = extraction.task
        if (!extraction.isTask || task == null) {
            rawCaptureDao.setState(capture.id, CaptureState.REJECTED)
            return Outcome.Done(0, 0, 1)
        }

        val candidates = verifyAndCorrect(listOf(task), text, verifyPass, now, todayKey, capture.sourceApp)
        return submitAll(capture, candidates, text, context.label(capture.sourceLabel))
    }

    // ---------------------------------------------------------- transcripts

    private suspend fun processTranscript(
        capture: RawCaptureEntity,
        transcript: String,
        verifyPass: Boolean,
        now: Long,
        todayKey: String,
    ): Outcome {
        val label = capture.sourceLabel ?: capture.contextLabel ?: "Unknown caller"
        logger.write(Stage.EXTRACT, LogLevel.DEBUG, "LLM call for transcript", "engine=${extractor.originLabel} label=$label")

        val result = extractor.extractFromTranscript(
            TranscriptInput(
                contactLabel = label,
                transcript = transcript,
                occurredAt = capture.occurredAt,
                rawCaptureId = capture.id,
                sourceLabel = capture.sourceLabel,
            ),
        )
        settingsRepository.recordLlmCall(todayKey, capture.sourceApp)

        val extraction = when (result) {
            is AiResult.Ok -> result.value
            else -> return failure(capture, result, now)
        }

        logger.write(
            Stage.EXTRACT,
            LogLevel.INFO,
            "extraction found ${extraction.tasks.size} candidate(s)",
            "tokens=${extraction.tokens ?: "?"} summary=${extraction.summary?.take(300)}",
        )

        if (extraction.tasks.isEmpty()) {
            // Spec 14.4: an empty list is a correct and common answer.
            rawCaptureDao.setState(capture.id, CaptureState.DONE)
            return Outcome.Done(0, 0, 0)
        }

        val candidates = verifyAndCorrect(extraction.tasks, transcript, verifyPass, now, todayKey, capture.sourceApp)
        return submitAll(capture, candidates, transcript, label)
    }

    // -------------------------------------------------------------- verify

    /**
     * Spec 14.5. A "fix" verdict may correct the title or the date; it does NOT
     * invalidate the evidence, because the evidence check in spec 13 runs
     * against the original source either way.
     */
    private suspend fun verifyAndCorrect(
        tasks: List<ExtractedTask>,
        source: String,
        verifyPass: Boolean,
        now: Long,
        todayKey: String,
        packageName: String?,
    ): List<ExtractedTask> {
        if (!verifyPass || tasks.isEmpty()) return tasks

        val settings = settingsRepository.current()
        val usage = settingsRepository.currentUsage()
        if (usage.llmCalls >= settings.maxLlmCallsPerDay) {
            logger.write(Stage.VERIFY, LogLevel.INFO, "verify pass skipped - daily budget short")
            return tasks
        }

        val result = extractor.verify(source, tasks)
        settingsRepository.recordLlmCall(todayKey, packageName)

        val verdicts = when (result) {
            is AiResult.Ok -> result.value
            else -> {
                // A failed verify pass must not lose the candidates; principle
                // 1 outranks the extra precision the second call would buy.
                logger.write(Stage.VERIFY, LogLevel.WARN, "verify pass failed, keeping candidates", result.errorText)
                return tasks
            }
        }

        val byIndex = verdicts.associateBy { it.index }
        val kept = tasks.mapIndexedNotNull { index, task ->
            val verdict = byIndex[index] ?: return@mapIndexedNotNull task
            when {
                verdict.isDrop -> {
                    logger.write(Stage.VERIFY, LogLevel.INFO, "verify dropped a candidate", "${task.title} - ${verdict.reason}")
                    null
                }
                verdict.isFix -> task.copy(
                    title = verdict.title?.takeIf { it.isNotBlank() } ?: task.title,
                    dueDateRaw = verdict.dueDate ?: task.dueDateRaw,
                )
                else -> task
            }
        }
        logger.write(Stage.VERIFY, LogLevel.INFO, "verify kept ${kept.size} of ${tasks.size}")
        return kept
    }

    // -------------------------------------------------------------- funnel

    private suspend fun submitAll(
        capture: RawCaptureEntity,
        tasks: List<ExtractedTask>,
        sourceText: String,
        label: String,
    ): Outcome {
        var created = 0
        var review = 0
        var dropped = 0

        for (task in tasks) {
            val tags = if (task.assignedToMe) emptyList() else listOf(WAITING_ON_TAG)
            val candidate = TaskCandidate(
                title = task.title,
                evidence = task.evidence,
                priority = task.priority,
                dueAtRaw = task.dueDateRaw,
                notes = task.notes,
                confidence = task.confidence,
                sourceType = capture.sourceType,
                sourceRef = capture.sourceRef,
                sourceLabel = label,
                sourceApp = capture.sourceApp,
                rawCaptureId = capture.id,
                inferenceOrigin = extractor.originLabel,
                sourceText = sourceText,
                occurredAt = capture.occurredAt,
                tags = tags,
            )
            when (funnel.submit(candidate)) {
                is IntakeResult.Created -> created++
                is IntakeResult.SentToReview -> review++
                is IntakeResult.Duplicate -> dropped++
                is IntakeResult.Discarded -> dropped++
                is IntakeResult.Invalid -> dropped++
            }
        }

        // The funnel marks the capture DONE once something is persisted. If
        // everything was dropped, close it here so it is not retried forever.
        if (created == 0 && review == 0) {
            rawCaptureDao.setState(capture.id, CaptureState.REJECTED)
        }
        return Outcome.Done(created, review, dropped)
    }

    // -------------------------------------------------------- failure paths

    private suspend fun failure(capture: RawCaptureEntity, result: AiResult<*>, now: Long): Outcome {
        val error = result.errorText ?: "unknown error"
        val modelName = extractor.originLabel.substringAfter("cloud:", extractor.originLabel)

        // Spec 9: 400 and 401 are configuration errors. Retrying them against a
        // wrong base URL forever costs money and hides the real problem, so
        // they surface on the status screen instead.
        val configurationError = result is AiResult.HttpError && result.configuration
        if (configurationError) {
            // The capture is parked with nextAttemptAt = null so the next drain
            // picks it up the moment the configuration is fixed.
            //
            // attemptCount is deliberately NOT incremented. A wrong model name
            // is not a failed attempt against a working setup, and counting it
            // as one burns the five-attempt budget: a capture that sat through
            // a misconfiguration would then hit FAILED_PERMANENT on its first
            // real network blip after the key was fixed.
            rawCaptureDao.setRetry(
                id = capture.id,
                state = CaptureState.PENDING_EXTRACTION,
                attemptCount = capture.attemptCount,
                error = error,
                nextAttemptAt = null,
            )
            val status = (result as? AiResult.HttpError)?.code ?: 0
            val diagnosis = ProviderDiagnosis.diagnose(modelName, status, error)
            logger.write(
                Stage.EXTRACT,
                LogLevel.ERROR,
                "provider configuration error",
                if (diagnosis != null) "$error\n\n$diagnosis" else error,
            )
            return Outcome.Parked(diagnosis ?: error)
        }

        val attempts = capture.attemptCount + 1
        val nextAt = Backoff.nextAttemptAt(attempts, now)
        return if (nextAt == null) {
            rawCaptureDao.setRetry(capture.id, CaptureState.FAILED_PERMANENT, attempts, error, null)
            logger.write(Stage.EXTRACT, LogLevel.ERROR, "extraction failed permanently after $attempts attempts", error)
            Outcome.Permanent(error)
        } else {
            rawCaptureDao.setRetry(capture.id, CaptureState.PENDING_EXTRACTION, attempts, error, nextAt)
            logger.write(Stage.EXTRACT, LogLevel.WARN, "extraction failed, retrying (attempt $attempts)", error)
            Outcome.Retry(error, nextAt)
        }
    }

    private suspend fun hold(capture: RawCaptureEntity, reason: String, now: Long) {
        rawCaptureDao.setRetry(
            id = capture.id,
            state = CaptureState.BUDGET_HELD,
            attemptCount = capture.attemptCount,
            error = reason,
            nextAttemptAt = DateResolver.nextMidnight(now),
        )
        logger.write(Stage.BUDGET, LogLevel.INFO, "capture held for budget", reason)
    }

    private data class Context(val sender: String, val group: String?) {
        fun label(fallback: String?): String = fallback ?: buildString {
            append(sender.ifBlank { "Unknown" })
            group?.let { append(" in ").append(it) }
        }
    }

    private fun parseContext(raw: String?): Context {
        if (raw.isNullOrBlank()) return Context("Unknown", null)
        var sender = ""
        var group: String? = null
        for (part in raw.split('|')) {
            when {
                part.startsWith("sender=") -> sender = part.removePrefix("sender=")
                part.startsWith("group=") -> group = part.removePrefix("group=")
            }
        }
        if (sender.isBlank() && !raw.contains('=')) sender = raw
        return Context(sender.ifBlank { "Unknown" }, group)
    }

    companion object {
        /** Spec 14.4: the other party's commitments are kept so the user can chase them. */
        const val WAITING_ON_TAG = "waiting-on"
        private const val SEVEN_DAYS = 7L * 24 * 60 * 60 * 1000
    }
}
