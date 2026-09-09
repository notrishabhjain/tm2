package com.taskmind.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.core.CaptureState
import com.taskmind.data.db.entity.CallRecordEntity
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One call as the screen should show it.
 *
 * [record] is what the call table holds; [captureState] is what actually
 * happened, which is not the same thing.
 */
data class CallRow(
    val record: CallRecordEntity,
    val captureState: CaptureState?,
    val transcript: String?,
    val error: String?,
)

/** Spec 6.2 / 11: the call list, with what actually happened to each call. */
class CallsViewModel(private val container: AppContainer) : ViewModel() {

    /**
     * The call list, with each row's state taken from its capture.
     *
     * `call_records.state` is only advanced past PENDING_TRANSCRIPTION by the
     * manual-import path, so every automatically transcribed call still reads
     * "Waiting to transcribe" however long ago it finished - which is what the
     * screen was showing for calls whose tasks had already been created.
     *
     * The capture row is the one the transcription pipeline actually writes,
     * so it is the honest source. Read here rather than fixed in the pipeline
     * because the pipeline is working and is not being touched.
     */
    val calls: StateFlow<List<CallRow>> = container.taskRepository.observeCalls()
        .map { records ->
            // One query per emission rather than one per row.
            val captures = runCatching {
                container.database.rawCaptureDao().recent(CAPTURE_LOOKUP_LIMIT)
            }.getOrDefault(emptyList()).associateBy { it.id }

            records.map { record ->
                val capture = record.rawCaptureId?.let { captures[it] }
                CallRow(
                    record = record,
                    captureState = capture?.state,
                    transcript = record.transcript ?: capture?.rawText,
                    error = record.lastError ?: capture?.lastError,
                )
            }
        }
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

    private companion object {
        /** Comfortably more captures than the call list ever shows. */
        const val CAPTURE_LOOKUP_LIMIT = 300
    }
}
