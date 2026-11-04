package com.taskmind.data.repo

import com.taskmind.ai.AiResult
import com.taskmind.ai.TraceContext
import com.taskmind.core.BrainDump
import com.taskmind.core.DateResolver
import com.taskmind.core.LogLevel
import com.taskmind.core.SmartReminder
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import com.taskmind.intake.IntakeResult

/**
 * Turns a brain dump or a pasted list into tasks: one model call to split it,
 * the user's approval, then one task per item through the intake funnel.
 *
 * With no key, no consent or no budget left, it splits the text locally
 * instead and says so, so the list is never lost.
 */
class BrainDumper(private val container: AppContainer) {

    data class Split(val items: List<BrainDump.Item>, val usedModel: Boolean, val note: String?)

    suspend fun split(text: String): Split {
        val now = System.currentTimeMillis()
        val settings = container.settingsRepository.current()
        val local = { why: String -> Split(BrainDump.fallback(text, now), usedModel = false, note = why) }

        if (!container.secretStore.hasLlmKey()) return local("No AI key set, so the list was split line by line.")
        if (!settings.cloudConsent) return local("Cloud processing is off, so the list was split line by line.")
        val today = DateResolver.dayKey(now)
        container.settingsRepository.rollBudgetIfNeeded(today)

        container.settingsRepository.recordLlmCall(today, null)
        val result = container.llmClient.complete(
            config = container.llmConfig(),
            systemPrompt = BrainDump.SYSTEM,
            userPrompt = BrainDump.userPrompt(text, now, settings.areas),
            maxTokens = 2000,
            trace = TraceContext(KIND_DUMP),
        )
        val content = (result as? AiResult.Ok)?.value?.content
            ?: return local("The AI did not answer, so the list was split line by line.")
        val items = BrainDump.parse(content, now, settings.areas)
            ?: return local("The AI's answer could not be read, so the list was split line by line.")
        return Split(items, usedModel = true, note = null)
    }

    /** Adds the approved items. Returns how many tasks were created. */
    suspend fun add(items: List<BrainDump.Item>): Int {
        val now = System.currentTimeMillis()
        val today = DateResolver.dayKey(now)
        var created = 0
        for (item in items) {
            val result = container.taskRepository.createManualTask(
                title = item.title,
                notes = item.notes,
                dueAt = item.dueAt,
                priority = item.priority,
                // Said, not typed into a date picker: it gets the same
                // automatic reminder a captured task would.
                reminderAt = SmartReminder.forDue(item.dueAt, now),
                area = item.area,
                plannedFor = if (item.today) today else null,
            )
            if (result is IntakeResult.Created) created++
        }
        container.logger.write(Stage.SYSTEM, LogLevel.INFO, "brain dump added", "$created of ${items.size} item(s)")
        return created
    }

    companion object {
        /** How these calls are labelled in Model calls. */
        const val KIND_DUMP = "brain-dump"
    }
}
