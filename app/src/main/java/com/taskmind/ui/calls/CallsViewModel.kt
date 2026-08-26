package com.taskmind.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.data.db.entity.CallRecordEntity
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Spec 6.2 / 11: the call list, with what actually happened to each call. */
class CallsViewModel(private val container: AppContainer) : ViewModel() {

    val calls: StateFlow<List<CallRecordEntity>> = container.taskRepository.observeCalls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun sweepNow() {
        viewModelScope.launch {
            val found = container.callPipeline.sweepCallLog("manual sweep")
            Scheduler.enqueueCallDiscovery(container.context)
            _message.value = if (found > 0) "Found $found new call(s)." else "No new calls in the call log."
        }
    }

    fun retryDiscovery(record: CallRecordEntity) {
        viewModelScope.launch {
            val found = container.callPipeline.discoverRecording(record.id)
            _message.value = if (found) {
                "Recording found; transcription queued."
            } else {
                "No recording found for that call yet."
            }
            if (found) Scheduler.enqueueTranscription(container.context)
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
