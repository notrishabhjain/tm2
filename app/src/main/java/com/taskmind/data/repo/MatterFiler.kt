package com.taskmind.data.repo

import com.taskmind.ai.AiResult
import com.taskmind.ai.TraceContext
import com.taskmind.ai.errorText
import com.taskmind.core.ChatContext
import com.taskmind.core.Cleanup
import com.taskmind.core.DateResolver
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
import com.taskmind.tagging.AutoTagger

/**
 * Suggests a matter for each open task that has none, and files the ones the
 * user accepts. One model call per [Cleanup.BATCH] tasks, through the same
 * provider, consent switch and daily budget as capture. Only titles and who
 * each task is from are sent - the same task fields the web app already has.
 */
class MatterFiler(private val container: AppContainer) {

    sealed interface Result {
        data class Suggested(val assignments: List<Cleanup.Assignment>) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun suggest(unfiled: List<TaskEntity>): Result {
        val settings = container.settingsRepository.current()
        if (!settings.cloudConsent) {
            return Result.Failed("Sorting into matters uses the cloud model, and cloud processing is off (Settings, Privacy).")
        }
        val matters = container.database.projectDao().all().map { ChatContext.Matter(it.id, it.name) }
        val out = mutableListOf<Cleanup.Assignment>()
        for (batch in unfiled.chunked(Cleanup.BATCH)) {
            val now = System.currentTimeMillis()
            val today = DateResolver.dayKey(now)
            container.settingsRepository.rollBudgetIfNeeded(today)
            if (container.settingsRepository.currentUsage().llmCalls >= settings.maxLlmCallsPerDay) {
                if (out.isNotEmpty()) break
                return Result.Failed("Today's limit of ${settings.maxLlmCallsPerDay} model calls is used up.")
            }
            val tasks = batch.map { Cleanup.Task(it.id, it.title, from(it)) }
            container.settingsRepository.recordLlmCall(today, null)
            val result = container.llmClient.complete(
                config = container.llmConfig(),
                systemPrompt = Cleanup.SYSTEM,
                userPrompt = Cleanup.userPrompt(tasks, matters),
                maxTokens = 1500,
                trace = TraceContext(KIND_FILE),
            )
            val content = (result as? AiResult.Ok)?.value?.content
                ?: return Result.Failed(result.errorText ?: "The model did not answer.")
            val parsed = Cleanup.parse(content, tasks, matters)
                ?: return Result.Failed("The model's answer could not be read. Try again.")
            out += parsed
        }
        container.logger.write(Stage.SYSTEM, LogLevel.INFO, "matter suggestions", "${out.size} of ${unfiled.size} tasks")
        return Result.Suggested(out)
    }

    /** Files the accepted suggestions, creating each new matter once. Returns how many were filed. */
    suspend fun apply(accepted: List<Cleanup.Assignment>): Int {
        val dao = container.database.projectDao()
        val existing = dao.all()
        val created = mutableMapOf<String, String>()
        var filed = 0
        for (a in accepted) {
            val projectId = a.matterId ?: existing.firstOrNull { it.name.equals(a.matter, ignoreCase = true) }?.id
                ?: created.getOrPut(a.matter.lowercase()) { container.taskRepository.createProject(a.matter) }
            val task = container.taskRepository.byId(a.taskId) ?: continue
            if (task.projectId != null) continue
            container.taskRepository.update(task.copy(projectId = projectId))
            filed++
        }
        container.logger.write(Stage.SYSTEM, LogLevel.INFO, "filed tasks under matters", "$filed filed, ${created.size} new matter(s)")
        return filed
    }

    private fun from(task: TaskEntity): String? {
        val parsed = AutoTagger.parseLabel(task.sourceType, task.sourceLabel)
        return listOfNotNull(parsed.person, parsed.group?.let { "in $it" }).joinToString(" ").ifBlank { null }
    }

    companion object {
        /** How these calls are labelled in Model calls. */
        const val KIND_FILE = "file-matters"
    }
}
