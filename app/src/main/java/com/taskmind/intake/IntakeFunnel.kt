package com.taskmind.intake

import com.taskmind.core.DateResolver
import com.taskmind.core.EvidenceMatcher
import com.taskmind.core.LogLevel
import com.taskmind.core.Normalize
import com.taskmind.core.SourceType
import com.taskmind.core.Stage

/**
 * THE most important architectural rule in this codebase (spec 5, spec 7).
 *
 * [submit] is the only code in the app that creates a task. Manual entry,
 * notification capture, call extraction, clipboard import and review-accept all
 * converge here. In the prior implementation the call path bypassed the funnel
 * and wrote to a different table: calls were processed correctly for weeks and
 * produced zero visible tasks, because each path looked fine in isolation.
 *
 * Two things keep that from happening again:
 *  - the funnel owns the only [TaskSink] in the app, and
 *  - `ArchitectureTest` fails the build if any file other than this one calls a
 *    task-insert method.
 *
 * Steps, in order (spec 7):
 *   validate -> evidence check -> normalise -> confidence gate -> dedup ->
 *   persist -> mark capture -> notify -> log
 */
class IntakeFunnel(
    private val taskSink: TaskSink,
    private val reviewSink: ReviewSink,
    private val captureMarker: CaptureMarker,
    private val log: FunnelLog,
    private val notifier: TaskCreatedNotifier,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val configProvider: suspend () -> FunnelConfig,
) {

    suspend fun submit(candidate: TaskCandidate): IntakeResult {
        val config = configProvider()
        val result = runFunnel(candidate, config)

        // Step 8: log the outcome regardless of which branch was taken.
        val level = when (result) {
            is IntakeResult.Created -> LogLevel.INFO
            is IntakeResult.SentToReview -> LogLevel.INFO
            is IntakeResult.Duplicate -> LogLevel.DEBUG
            is IntakeResult.Discarded -> LogLevel.DEBUG
            is IntakeResult.Invalid -> LogLevel.WARN
        }
        log.log(
            stage = Stage.FUNNEL,
            level = level,
            message = "${candidate.sourceType} -> ${result.summary}",
            detail = describe(candidate, result),
        )
        return result
    }

    private suspend fun runFunnel(candidate: TaskCandidate, config: FunnelConfig): IntakeResult {
        // ---- 1. Validate -------------------------------------------------
        val rawTitle = candidate.title.trim()
        if (rawTitle.isBlank()) {
            return IntakeResult.Invalid("title blank after trim")
        }
        if (rawTitle.length > MAX_TITLE_CHARS) {
            // Spec 7.1 truncates for display at 60 but stores the full title;
            // beyond 200 the model has produced prose, not a task.
            return IntakeResult.Invalid("title longer than $MAX_TITLE_CHARS chars")
        }
        val confidence = candidate.confidence
        if (confidence != null && (confidence.isNaN() || confidence < 0.0 || confidence > 1.0)) {
            return IntakeResult.Invalid("confidence out of range: $confidence")
        }
        if (candidate.sourceType != SourceType.MANUAL && candidate.sourceRef.isNullOrBlank()) {
            // Without a sourceRef the unique index cannot dedup this source.
            return IntakeResult.Invalid("sourceRef missing for ${candidate.sourceType}")
        }

        // ---- 2. Evidence check (spec 13) ---------------------------------
        if (candidate.sourceType != SourceType.MANUAL) {
            val tolerance = config.toleranceFor(candidate.sourceType)
            val match = EvidenceMatcher.match(candidate.evidence, candidate.sourceText, tolerance)
            if (!match.matched) {
                // Drop, log at WARN with the evidence and the source, and never
                // surface it to the user. This is the diagnostic you will need
                // most, so it carries the score it achieved.
                log.log(
                    stage = Stage.FUNNEL,
                    level = LogLevel.WARN,
                    message = "evidence check failed (${match.reason})",
                    detail = "title=$rawTitle\nevidence=${candidate.evidence}\nsource=${candidate.sourceText.take(600)}",
                )
                return IntakeResult.Discarded("evidence not found in source: ${match.reason}")
            }
        }

        // ---- 3. Normalise ------------------------------------------------
        val title = Normalize.tidyTitle(rawTitle)
        if (title.isBlank()) return IntakeResult.Invalid("title blank after normalisation")
        val titleKey = Normalize.titleKey(title)

        val resolved = DateResolver.resolve(candidate.dueAtRaw, candidate.occurredAt)
        val notes = mergeNotes(candidate.notes, resolved.noteSuffix)

        // ---- 4. Confidence gate (spec 14.2) ------------------------------
        val now = clock.now()
        if (candidate.sourceType != SourceType.MANUAL) {
            // A missing or unparseable confidence is uncertain, never certain.
            val effective = confidence
            when {
                effective == null -> return toReview(candidate, title, notes, resolved.dueAt, null, now)
                effective < config.reviewThreshold ->
                    return IntakeResult.Discarded(
                        "confidence $effective below review threshold ${config.reviewThreshold}",
                    )
                effective < config.autoCreateThreshold ->
                    return toReview(candidate, title, notes, resolved.dueAt, effective, now)
            }
        }

        // ---- 5 + 6. Dedup and persist ------------------------------------
        val task = NewTask(
            id = ids.newId(),
            title = title,
            titleKey = titleKey,
            notes = notes,
            dueAt = resolved.dueAt,
            priority = candidate.priority,
            projectId = candidate.projectId,
            tags = candidate.tags,
            recurrenceRule = candidate.recurrenceRule,
            reminderAt = candidate.reminderAt,
            parentTaskId = candidate.parentTaskId,
            sourceType = candidate.sourceType,
            sourceRef = candidate.sourceRef,
            sourceLabel = candidate.sourceLabel,
            sourceApp = candidate.sourceApp,
            evidence = candidate.evidence,
            confidence = candidate.confidence,
            inferenceOrigin = candidate.inferenceOrigin,
            rawCaptureId = candidate.rawCaptureId,
            createdAt = now,
        )

        val inserted = taskSink.insertIfAbsent(task)
        if (!inserted) {
            // The database refused it, which is the dedup mechanism working.
            // Log it; never surface it to the user.
            markCapture(candidate)
            return IntakeResult.Duplicate(titleKey)
        }

        // Persist first, mark second (failure mode 5). A transient failure
        // between the two costs a re-run, not a lost capture.
        markCapture(candidate)

        // ---- 7. Notify ---------------------------------------------------
        notifier.onTaskCreated(task.id, task.title)

        return IntakeResult.Created(task.id, task.title)
    }

    private suspend fun toReview(
        candidate: TaskCandidate,
        title: String,
        notes: String?,
        dueAt: Long?,
        confidence: Double?,
        now: Long,
    ): IntakeResult {
        val proposal = ReviewProposal(
            id = ids.newId(),
            title = title,
            notes = notes,
            dueAt = dueAt,
            priority = candidate.priority,
            evidence = candidate.evidence,
            confidence = confidence,
            reasoning = candidate.reasoning,
            sourceText = candidate.sourceText,
            sourceType = candidate.sourceType,
            sourceRef = candidate.sourceRef,
            sourceLabel = candidate.sourceLabel,
            sourceApp = candidate.sourceApp,
            rawCaptureId = candidate.rawCaptureId,
            inferenceOrigin = candidate.inferenceOrigin,
            occurredAt = candidate.occurredAt,
            createdAt = now,
        )
        reviewSink.propose(proposal)
        markCapture(candidate)
        return IntakeResult.SentToReview(proposal.id, confidence)
    }

    private suspend fun markCapture(candidate: TaskCandidate) {
        val id = candidate.rawCaptureId ?: return
        captureMarker.markProcessed(id)
    }

    private fun mergeNotes(notes: String?, suffix: String?): String? {
        val a = notes?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        val b = suffix?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            a == null && b == null -> null
            a == null -> b
            b == null -> a
            else -> "$a\n$b"
        }
    }

    private fun describe(candidate: TaskCandidate, result: IntakeResult): String = buildString {
        append("title=").append(candidate.title.take(120))
        append(" | source=").append(candidate.sourceLabel ?: candidate.sourceApp ?: "-")
        append(" | ref=").append(candidate.sourceRef ?: "-")
        append(" | confidence=").append(candidate.confidence?.toString() ?: "null")
        when (result) {
            is IntakeResult.Created -> append(" | taskId=").append(result.taskId)
            is IntakeResult.SentToReview -> append(" | reviewId=").append(result.reviewId)
            is IntakeResult.Duplicate -> append(" | titleKey=").append(result.titleKey)
            is IntakeResult.Discarded -> append(" | reason=").append(result.reason)
            is IntakeResult.Invalid -> append(" | reason=").append(result.reason)
        }
    }

    companion object {
        const val MAX_TITLE_CHARS = 200

        /** Spec 7.1: truncate at 60 for display, store the full title. */
        const val DISPLAY_TITLE_CHARS = 60
    }
}
