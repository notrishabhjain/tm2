package com.taskmind.capture

import com.taskmind.core.CaptureState
import com.taskmind.core.ChatContext
import com.taskmind.core.DateResolver
import com.taskmind.core.LogLevel
import com.taskmind.core.Normalize
import com.taskmind.core.Priority
import com.taskmind.core.QuickRead
import com.taskmind.core.Rules
import com.taskmind.core.SmartReminder
import com.taskmind.core.SourceType
import com.taskmind.core.Stage
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
import com.taskmind.intake.IntakeResult
import com.taskmind.intake.TaskCandidate
import java.time.Instant

/**
 * The phone's first read of each message, and keeping it in step with the
 * model's.
 *
 * A message that is plainly an ask or a promise (see [QuickRead]) becomes an
 * Inbox task, with its notification, the moment it arrives - before the chat
 * has gone quiet and before any model has been asked. Seconds later the
 * model reads the same message, and its answer settles it (see [FirstReads]):
 * the task takes the model's better words, or is taken back.
 *
 * Offline, or when the model cannot be reached, the first read simply stays
 * until the model's read comes through.
 *
 * Everything goes through the intake funnel like any other capture, so the
 * evidence check, the de-duplication and the notification are the same ones.
 */
class InstantCapture(private val container: AppContainer) : FirstReads {

    /** Reads a capture that has just been written. Cheap: rules, then at most one insert. */
    suspend fun onCaptured(rawCaptureId: String) {
        val settings = container.settingsRepository.current()
        if (!settings.instantCapture || !settings.cloudConsent) return
        val dao = container.database.rawCaptureDao()
        val capture = dao.byId(rawCaptureId) ?: return
        if (capture.sourceType != SourceType.NOTIFICATION) return
        if (capture.state != CaptureState.PENDING_EXTRACTION) return
        val text = capture.rawText?.takeIf { it.isNotBlank() } ?: return
        val chat = ChatContext.parseLabel(capture.contextLabel)
        if (ruledOut(chat)) return

        val read = QuickRead.read(
            text = text,
            sender = chat.sender,
            myNames = settings.ownNames,
            isGroup = chat.group != null,
            now = capture.occurredAt,
        ) ?: return
        // The user's own bar. "Fewer" asks for more certainty than a first
        // read on the phone can give, so with it the model decides alone.
        if (read.confidence < settings.autoCreateThreshold) return

        // Checked again just before writing: once the model's read of this
        // capture has finished, a first read would only be a duplicate.
        if (dao.byId(capture.id)?.state != CaptureState.PENDING_EXTRACTION) return

        val result = container.intakeFunnel.submit(
            TaskCandidate(
                title = read.title,
                evidence = read.evidence,
                priority = if (read.urgent) Priority.HIGH else Priority.MEDIUM,
                dueAtRaw = read.dueAt?.let { iso(it) },
                confidence = read.confidence,
                sourceType = SourceType.NOTIFICATION,
                sourceRef = capture.sourceRef,
                sourceLabel = capture.sourceLabel,
                sourceApp = capture.sourceApp,
                rawCaptureId = capture.id,
                inferenceOrigin = QuickRead.ORIGIN,
                sourceText = text,
                occurredAt = capture.occurredAt,
                tags = if (read.kind == QuickRead.Kind.PROMISE) listOf(ExtractionPipeline.WAITING_ON_TAG) else emptyList(),
                inbox = true,
                provisional = true,
            ),
        )
        if (result is IntakeResult.Created) {
            container.logger.write(
                Stage.FUNNEL,
                LogLevel.INFO,
                "first read on the phone: ${read.kind.name.lowercase()}",
                "task=${read.title} confidence=${read.confidence} - the AI reads it next",
            )
        }
    }

    // ---------------------------------------------------------- FirstReads

    override suspend fun pending(captureIds: Collection<String>): List<FirstRead> {
        val dao = container.database.taskDao()
        // Every status, deliberately. A first read the user already finished or
        // marked "Not mine" must still be matched, or the model's read of the
        // same message would make it all over again.
        return captureIds.distinct()
            .flatMap { dao.byRawCapture(it) }
            .filter { it.inferenceOrigin == QuickRead.ORIGIN }
            .sortedBy { it.createdAt }
            .map { FirstRead(it.id, it.rawCaptureId.orEmpty(), it.title, it.evidence) }
    }

    override suspend fun confirm(read: FirstRead, update: FirstReadUpdate) {
        val dao = container.database.taskDao()
        val task = dao.byId(read.taskId) ?: return
        val now = System.currentTimeMillis()
        if (task.status != TaskStatus.ACTIVE || touched(task)) {
            // The user has already decided about it; their version stands. It
            // is marked as read by the model so nothing asks about it again.
            runCatching { dao.update(task.copy(inferenceOrigin = update.origin)) }
            container.logger.write(Stage.FUNNEL, LogLevel.DEBUG, "the AI agreed with a first read you had already acted on", "task=${task.title}")
            return
        }

        val title = Normalize.tidyTitle(update.title).ifBlank { task.title }
        val resolved = DateResolver.resolve(update.dueAtRaw, update.occurredAt)
        // The model's date when it has one; the date the phone read from the
        // message's own words otherwise.
        val dueAt = resolved.dueAt ?: task.dueAt
        val notes = listOfNotNull(
            update.notes?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) },
            resolved.noteSuffix,
        ).joinToString("\n").ifBlank { null } ?: task.notes
        val projectId = runCatching { container.chatMemory.matterId(update.matter) }.getOrNull() ?: task.projectId
        val tags = task.tags.filter { it != ExtractionPipeline.WAITING_ON_TAG } +
            if (update.waitingOn) listOf(ExtractionPipeline.WAITING_ON_TAG) else emptyList()

        val refined = task.copy(
            title = title,
            titleKey = Normalize.titleKey(title),
            evidence = update.evidence ?: task.evidence,
            priority = update.priority,
            dueAt = dueAt,
            reminderAt = if (dueAt != task.dueAt) SmartReminder.forDue(dueAt, now) else task.reminderAt,
            notes = notes,
            confidence = update.confidence ?: task.confidence,
            inferenceOrigin = update.origin,
            projectId = projectId,
            area = update.area ?: task.area,
            tags = tags,
            // Moves, so the widget and the web page pick up the new words.
            updatedAt = now,
        )
        val saved = runCatching { dao.update(refined) }.isSuccess ||
            // The model's title collided with another task from the same
            // message (the unique index): keep the phone's words, take the rest.
            runCatching { dao.update(refined.copy(title = task.title, titleKey = task.titleKey)) }.isSuccess
        if (!saved) return
        if (title != task.title) container.notifier.refineTask(task.id, title)
        container.logger.write(
            Stage.FUNNEL,
            LogLevel.INFO,
            "the AI confirmed a first read",
            if (title != task.title) "\"${task.title}\" -> \"$title\"" else "task=$title",
        )
    }

    override suspend fun withdraw(read: FirstRead, reason: String) {
        val dao = container.database.taskDao()
        val task = dao.byId(read.taskId) ?: return
        if (task.status != TaskStatus.ACTIVE) return
        if (touched(task)) {
            runCatching { dao.update(task.copy(inferenceOrigin = container.taskExtractor.originLabel)) }
            container.logger.write(Stage.FUNNEL, LogLevel.INFO, "first read kept: you had already acted on it", "task=${task.title} ($reason)")
            return
        }
        // Straight to the table, not through the repository: taking back the
        // phone's own guess is not the user saying "not mine", and must not
        // teach the rules anything. The activity log keeps the line below.
        dao.setStatus(task.id, TaskStatus.DELETED, null, System.currentTimeMillis())
        container.notifier.withdrawTask(task.id)
        container.logger.write(Stage.FUNNEL, LogLevel.INFO, "first read taken back: $reason", "task=${task.title}")
    }

    // ------------------------------------------------------------- plumbing

    /**
     * Whether the user has done anything with the task since the phone made
     * it. The funnel writes updatedAt equal to createdAt, and every edit,
     * triage and plan moves it.
     */
    private fun touched(task: TaskEntity): Boolean =
        task.updatedAt != task.createdAt || !task.inbox || task.plannedFor != null

    /** An approved "ignore" rule covers this chat: the model's read decides, as the rule says. */
    private fun ruledOut(chat: ChatContext.Chat): Boolean =
        container.learningStore.rules.value.any { rule ->
            rule.state == Rules.State.APPROVED && when (rule.kind) {
                Rules.Kind.IGNORE_SENDER -> rule.subject.equals(chat.sender, ignoreCase = true)
                Rules.Kind.IGNORE_GROUP -> chat.group != null && rule.subject.equals(chat.group, ignoreCase = true)
                else -> false
            }
        }

    private fun iso(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(DateResolver.IST).toOffsetDateTime().toString()
}
