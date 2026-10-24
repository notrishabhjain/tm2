package com.taskmind.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.ai.AiResult
import com.taskmind.ai.TraceContext
import com.taskmind.ai.errorText
import com.taskmind.core.AskPrompt
import com.taskmind.core.DateResolver
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
import com.taskmind.tagging.AutoTagger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class AskUiState(
    val question: String = "",
    val busy: Boolean = false,
    val answer: String? = null,
    /** The tasks the answer is about, in the order the model named them. */
    val tasks: List<TaskEntity> = emptyList(),
    val error: String? = null,
)

/**
 * Asks the task list a question through the same provider, key, consent
 * switch and daily budget as capture. One question is one model call.
 */
class AskViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    fun setQuestion(text: String) {
        _state.value = _state.value.copy(question = text)
    }

    fun ask() {
        val question = _state.value.question.trim()
        if (question.isEmpty() || _state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, answer = null, tasks = emptyList())
        viewModelScope.launch {
            _state.value = try {
                answer(question)
            } catch (t: Throwable) {
                _state.value.copy(busy = false, error = "Something went wrong: ${t.message ?: t}")
            }
        }
    }

    private suspend fun answer(question: String): AskUiState {
        val settings = container.settingsRepository.current()
        val current = _state.value
        if (!settings.cloudConsent) {
            return current.copy(
                busy = false,
                error = "Asking uses the cloud model, and cloud processing is off. " +
                    "Turn it on in Settings → Privacy and security.",
            )
        }
        val now = System.currentTimeMillis()
        val today = DateResolver.dayKey(now)
        container.settingsRepository.rollBudgetIfNeeded(today)
        if (container.settingsRepository.currentUsage().llmCalls >= settings.maxLlmCallsPerDay) {
            return current.copy(busy = false, error = "Today's limit of ${settings.maxLlmCallsPerDay} model calls is used up.")
        }

        val all = container.taskRepository.observeTasks().first()
            .filter { it.parentTaskId == null && (it.status == TaskStatus.ACTIVE || it.status == TaskStatus.COMPLETED) }
        val byId = all.associateBy { it.id }
        val sent = AskPrompt.select(all.map { it.toAskTask() })

        container.settingsRepository.recordLlmCall(today, null)
        val result = container.llmClient.complete(
            config = container.llmConfig(),
            systemPrompt = AskPrompt.SYSTEM,
            userPrompt = AskPrompt.userPrompt(question, sent, now),
            maxTokens = 600,
            trace = TraceContext(KIND_ASK),
        )
        val content = (result as? AiResult.Ok)?.value?.content
            ?: return current.copy(busy = false, error = result.errorText ?: "The model did not answer.")
        val parsed = AskPrompt.parse(content, sent)
            ?: return current.copy(busy = false, error = "The model's answer could not be read. Try asking again.")
        return current.copy(
            busy = false,
            answer = parsed.text,
            tasks = parsed.taskIds.mapNotNull { byId[it] },
        )
    }

    private fun TaskEntity.toAskTask(): AskPrompt.Task {
        val parsed = AutoTagger.parseLabel(sourceType, sourceLabel)
        return AskPrompt.Task(
            id = id,
            title = title,
            open = status == TaskStatus.ACTIVE,
            dueAt = dueAt,
            from = listOfNotNull(parsed.person, parsed.group?.let { "in $it" }).joinToString(" ").ifBlank { null },
            evidence = evidence,
            createdAt = createdAt,
        )
    }

    companion object {
        /** How these calls are labelled in Model calls. */
        const val KIND_ASK = "ask"
    }
}
