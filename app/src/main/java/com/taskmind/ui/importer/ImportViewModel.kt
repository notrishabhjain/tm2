package com.taskmind.ui.importer

import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.capture.CaptureCoordinator
import com.taskmind.core.TranscriptParser
import com.taskmind.data.db.entity.CallRecordEntity
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Spec 11.4 - the Import transcript screen.
 *
 * The Xiaomi HyperOS Recorder can transcribe a call with Xiaomi's cloud AI and
 * its Hindi quality is good, but it cannot be automated: no share action, no
 * export, no readable transcript file. Driving that UI with an
 * AccessibilityService is technically possible and is the wrong choice - it
 * breaks on every Recorder update and demands a permission that reads every
 * screen the user sees. So the flow is: the user copies, and this screen makes
 * that one paste worth something.
 */
data class ImportUiState(
    val text: String = "",
    val prefilledFromClipboard: Boolean = false,
    val parsed: TranscriptParser.Parsed? = null,
    val recentCalls: List<CallRecordEntity> = emptyList(),
    val attachToCallId: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val done: Boolean = false,
    /** The text came from screenshots read on the phone. */
    val fromScreenshots: Boolean = false,
    /** Screenshots being read. */
    val reading: Boolean = false,
)

class ImportViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                recentCalls = container.database.callRecordDao().recent(20),
            )
        }
    }

    /** On open, read the clipboard and prefill if it looks like a transcript. */
    fun readClipboard(context: Context) {
        if (_state.value.text.isNotEmpty()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val text = runCatching {
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull().orEmpty()

        if (text.isNotBlank() && TranscriptParser.looksLikeTranscript(text)) {
            setText(text, fromClipboard = true)
        }
    }

    fun setText(text: String, fromClipboard: Boolean = false) {
        _state.value = _state.value.copy(
            text = text,
            prefilledFromClipboard = fromClipboard,
            parsed = if (text.isBlank()) null else TranscriptParser.parse(text),
        )
    }

    /** Reads the words in shared or picked screenshots, on the phone, into the text box. */
    fun readImages(context: Context, uris: List<android.net.Uri>) {
        if (uris.isEmpty() || _state.value.reading) return
        _state.value = _state.value.copy(reading = true, message = null)
        viewModelScope.launch {
            val text = runCatching { com.taskmind.capture.ScreenshotReader.read(context, uris) }.getOrDefault("")
            _state.value = _state.value.copy(reading = false)
            if (text.isBlank()) {
                _state.value = _state.value.copy(message = "No words could be read in that picture.")
            } else {
                setText(text)
                _state.value = _state.value.copy(fromScreenshots = true, attachToCallId = null)
            }
        }
    }

    fun attachTo(callId: String?) {
        _state.value = _state.value.copy(attachToCallId = callId)
    }

    fun import() {
        val current = _state.value
        if (current.text.isBlank() || current.busy) return

        viewModelScope.launch {
            _state.value = current.copy(busy = true, message = null)

            // Timestamps are stripped and speaker labels kept: "send me the
            // report" means something different depending on who said it.
            val parsed = TranscriptParser.parse(current.text)
            val rendered = parsed.rendered.ifBlank { current.text }

            val callId = current.attachToCallId
            val outcome = if (callId != null) {
                // Attaching to a specific call gives the extracted tasks correct
                // provenance and the right occurredAt for relative dates.
                val rawId = container.callPipeline.attachTranscript(callId, rendered)
                if (rawId != null) CaptureCoordinator.Outcome.Captured(rawId, callId) else null
            } else {
                container.captureCoordinator.captureClipboardTranscript(
                    transcript = rendered,
                    contactLabel = if (current.fromScreenshots) "Screenshot" else "Imported transcript",
                    occurredAt = System.currentTimeMillis(),
                )
            }

            val message = when (outcome) {
                is CaptureCoordinator.Outcome.Captured -> {
                    Scheduler.enqueueExtraction(container.context)
                    if (!parsed.diarised) {
                        "Imported. No speaker labels were found, so it was accepted as plain text - " +
                            "extraction still runs, with lower confidence."
                    } else {
                        "Imported ${parsed.turns.size} turns. Extraction is running; tasks will appear shortly."
                    }
                }
                is CaptureCoordinator.Outcome.Duplicate -> "You have already imported this transcript."
                else -> "Nothing usable in that text."
            }

            _state.value = _state.value.copy(
                busy = false,
                message = message,
                done = outcome is CaptureCoordinator.Outcome.Captured,
            )
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
