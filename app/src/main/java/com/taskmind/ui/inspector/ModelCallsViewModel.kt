package com.taskmind.ui.inspector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.ai.RecordedCall
import com.taskmind.data.db.entity.InferenceCallEntity
import com.taskmind.data.repo.RoomInferenceRecorder
import com.taskmind.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CallFilter(val label: String) {
    ALL("Everything"),
    FAILED("Failures only"),
    MESSAGES("Messages"),
    CALLS("Calls"),
    ;

    fun matches(call: InferenceCallEntity): Boolean = when (this) {
        ALL -> true
        FAILED -> !call.ok
        MESSAGES -> call.kind == RecordedCall.KIND_MESSAGE
        CALLS -> call.kind == RecordedCall.KIND_TRANSCRIPT || call.kind == RecordedCall.KIND_ASR
    }
}

/**
 * Backs the screen that shows exactly what TaskMind sent to a cloud model and
 * exactly what came back.
 */
class ModelCallsViewModel(private val container: AppContainer) : ViewModel() {

    private val _filter = MutableStateFlow(CallFilter.ALL)
    val filter: StateFlow<CallFilter> = _filter.asStateFlow()

    private val _expanded = MutableStateFlow<Set<Long>>(emptySet())
    val expanded: StateFlow<Set<Long>> = _expanded.asStateFlow()

    val calls: StateFlow<List<InferenceCallEntity>> = combine(
        container.database.inferenceCallDao().observeRecent(RoomInferenceRecorder.KEEP),
        _filter,
    ) { all, filter -> all.filter { filter.matches(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(filter: CallFilter) {
        _filter.value = filter
    }

    fun toggle(id: Long) {
        _expanded.value = if (id in _expanded.value) _expanded.value - id else _expanded.value + id
    }

    fun clear() {
        viewModelScope.launch { container.database.inferenceCallDao().deleteAll() }
    }

    /** The whole trace as text, for sharing when something needs diagnosing. */
    fun exportText(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val calls = container.database.inferenceCallDao().recent(RoomInferenceRecorder.KEEP)
            onReady(render(calls))
        }
    }

    private fun render(calls: List<InferenceCallEntity>): String = buildString {
        append("TaskMind model calls (newest first, ").append(calls.size).append(" entries)\n")
        append("WARNING: this contains the text of your messages and transcripts.\n\n")
        for (call in calls) {
            append("=".repeat(72)).append('\n')
            append(timestamp(call.startedAt)).append("  ").append(call.kind).append('\n')
            append("model: ").append(call.model).append("   at: ").append(call.baseUrl).append('\n')
            append("outcome: ").append(if (call.ok) "ok" else "FAILED")
            call.httpStatus?.let { append("  HTTP ").append(it) }
            append("  in ").append(call.durationMillis).append("ms")
            call.totalTokens?.let { append("  ").append(it).append(" tokens") }
            append('\n')
            call.sourceLabel?.let { append("source: ").append(it).append('\n') }
            call.errorText?.let { append("\nERROR\n").append(it).append('\n') }
            call.diagnosis?.let { append("\nWHAT THIS MEANS\n").append(it).append('\n') }
            call.systemPrompt?.let { append("\n--- SYSTEM PROMPT ---\n").append(it).append('\n') }
            call.userPrompt?.let { append("\n--- USER MESSAGE ---\n").append(it).append('\n') }
            call.responseBody?.let { append("\n--- RAW RESPONSE ---\n").append(it).append('\n') }
            append('\n')
        }
    }

    private fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
}
