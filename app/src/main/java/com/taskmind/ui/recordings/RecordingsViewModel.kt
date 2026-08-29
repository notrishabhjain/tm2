package com.taskmind.ui.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.capture.CaptureCoordinator
import com.taskmind.capture.RecordingFinder
import com.taskmind.core.CaptureState
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecordingRow(
    val path: String,
    val name: String,
    val lastModified: Long,
    val sizeBytes: Long,
    /** Already queued, transcribed or failed - anything the app knows about. */
    val existingState: CaptureState?,
    val selected: Boolean = false,
    /** What the recogniser produced, once it has. */
    val transcript: String? = null,
    val lastError: String? = null,
) {
    /** Roughly, at the ~16 kB/s a phone dialer records at. */
    val approximateMinutes: Int get() = (sizeBytes / (16_000L * 60)).toInt().coerceAtLeast(1)

    val alreadyHandled: Boolean
        get() = existingState == CaptureState.DONE ||
            existingState == CaptureState.PENDING_TRANSCRIPTION ||
            existingState == CaptureState.PENDING_EXTRACTION
}

data class RecordingsUiState(
    val loading: Boolean = true,
    /** A disk walk is in progress, as opposed to a quick database read. */
    val scanning: Boolean = false,
    val queueing: Boolean = false,
    val rows: List<RecordingRow> = emptyList(),
    val autoTranscribe: Boolean = true,
    val queuedCount: Int = 0,
    val awaitingSelectionCount: Int = 0,
    val message: String? = null,
) {
    val selected: List<RecordingRow> get() = rows.filter { it.selected }
    val selectedMinutes: Int get() = selected.sumOf { it.approximateMinutes }
}

/**
 * Picking which recordings are worth spending transcription on.
 *
 * The device this was built for had 6463 recordings and a 60-minute daily
 * transcription budget. Automatic transcription is right for someone whose
 * dialer records the occasional call and wrong for someone whose dialer records
 * everything, and the app has no way to tell which it is looking at - so it
 * asks rather than guessing.
 */
class RecordingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(RecordingsUiState())
    val ui: StateFlow<RecordingsUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh(forceRescan: Boolean = false) {
        // Only claim to be loading when the disk is actually about to be
        // walked. Re-reading capture states after queueing is a single query,
        // and showing a spinner for it is what made a finished action look
        // like a hang.
        val willScan = forceRescan || !container.recordingFinder.hasFreshListing()
        _ui.value = _ui.value.copy(loading = willScan, scanning = willScan)
        viewModelScope.launch {
            val settings = container.settingsRepository.current()
            val dao = container.database.rawCaptureDao()

            val found = runCatching {
                container.recordingFinder.listRecent(
                    limit = LIST_LIMIT,
                    userDirUri = settings.callRecordingDirUri,
                    forceRescan = forceRescan,
                )
            }.getOrDefault(emptyList())

            // One lookup for everything the app already knows, rather than a
            // query per file: with hundreds of rows that is the difference
            // between a list that opens and one that stalls.
            val known = runCatching { dao.byAudioPaths(found.map { it.path }) }
                .getOrDefault(emptyList())
                .associateBy { it.audioPath }

            _ui.value = RecordingsUiState(
                loading = false,
                scanning = false,
                rows = found.map { candidate ->
                    val capture = known[candidate.path]
                    RecordingRow(
                        path = candidate.path,
                        name = candidate.name,
                        lastModified = candidate.lastModified,
                        sizeBytes = candidate.sizeBytes,
                        existingState = capture?.state,
                        transcript = capture?.rawText,
                        lastError = capture?.lastError,
                    )
                },
                autoTranscribe = settings.autoTranscribeCalls,
                queuedCount = runCatching { dao.countByState(CaptureState.PENDING_TRANSCRIPTION) }.getOrDefault(0),
                awaitingSelectionCount = runCatching { dao.countByState(CaptureState.AWAITING_SELECTION) }
                    .getOrDefault(0),
            )
        }
    }

    /**
     * Re-reads only what the database knows, never the disk.
     *
     * The screen polls this while a transcription is in flight. Sharing
     * refresh() would have meant that once the five-minute listing cache
     * expired, a poll would silently start walking 6000 files and put the
     * spinner back up under the user - the exact behaviour being fixed.
     */
    fun refreshStates() {
        viewModelScope.launch {
            val rows = _ui.value.rows
            if (rows.isEmpty()) return@launch
            val known = runCatching {
                container.database.rawCaptureDao().byAudioPaths(rows.map { it.path })
            }.getOrDefault(emptyList()).associateBy { it.audioPath }

            val dao = container.database.rawCaptureDao()
            _ui.value = _ui.value.copy(
                rows = rows.map { row ->
                    val capture = known[row.path]
                    row.copy(
                        existingState = capture?.state,
                        transcript = capture?.rawText,
                        lastError = capture?.lastError,
                    )
                },
                queuedCount = runCatching { dao.countByState(CaptureState.PENDING_TRANSCRIPTION) }
                    .getOrDefault(_ui.value.queuedCount),
                awaitingSelectionCount = runCatching { dao.countByState(CaptureState.AWAITING_SELECTION) }
                    .getOrDefault(_ui.value.awaitingSelectionCount),
            )
        }
    }

    fun toggle(path: String) {
        _ui.value = _ui.value.copy(
            rows = _ui.value.rows.map { if (it.path == path) it.copy(selected = !it.selected) else it },
        )
    }

    fun clearSelection() {
        _ui.value = _ui.value.copy(rows = _ui.value.rows.map { it.copy(selected = false) })
    }

    fun setAutoTranscribe(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setAutoTranscribeCalls(enabled)
            _ui.value = _ui.value.copy(
                autoTranscribe = enabled,
                message = if (enabled) {
                    "New calls will be transcribed automatically."
                } else {
                    "New calls will wait here until you pick them."
                },
            )
        }
    }

    /** Queues exactly the selected files, through the normal capture path. */
    fun transcribeSelected() {
        val chosen = _ui.value.selected
        if (chosen.isEmpty() || _ui.value.queueing) return
        _ui.value = _ui.value.copy(queueing = true)
        viewModelScope.launch {
            val dao = container.database.rawCaptureDao()
            var queued = 0
            for (row in chosen) {
                val existing = runCatching { dao.byAudioPath(row.path) }.getOrNull()
                if (existing != null) {
                    // Already known: put it back in the queue rather than
                    // creating a second capture for the same audio.
                    dao.setRetry(
                        id = existing.id,
                        state = CaptureState.PENDING_TRANSCRIPTION,
                        attemptCount = 0,
                        error = null,
                        nextAttemptAt = null,
                    )
                    queued++
                    continue
                }
                val outcome = container.captureCoordinator.captureCall(
                    sourceRef = "file:${row.path}",
                    label = "Recording ${row.name}",
                    occurredAt = row.lastModified,
                    audioPath = row.path,
                )
                if (outcome is CaptureCoordinator.Outcome.Captured) queued++
            }
            container.logger.write(
                Stage.CALL,
                LogLevel.INFO,
                "queued $queued recording(s) for transcription at your request",
            )
            // Ignore the Wi-Fi-only rule here: the user picked these files by
            // hand just now, which is a clearer signal than a blanket setting
            // meant to stop unattended background uploads.
            Scheduler.enqueueTranscription(container.context, wifiOnly = false)
            clearSelection()
            _ui.value = _ui.value.copy(
                queueing = false,
                message = "Queued $queued. Transcribing starts now - watch the state on each row.",
            )
            // Deliberately NOT a rescan: the files on disk did not change, only
            // their state in the database did, and re-walking 6000 files here
            // is what made this button look like it did nothing.
            refreshStates()
        }
    }

    /**
     * Empties the transcription queue without deleting anything.
     *
     * The backlog that builds up before someone realises their dialer records
     * every call is the reason this exists; the captures move to
     * AWAITING_SELECTION and stay pickable.
     */
    fun clearQueue() {
        viewModelScope.launch {
            val dao = container.database.rawCaptureDao()
            val count = runCatching { dao.countByState(CaptureState.PENDING_TRANSCRIPTION) }.getOrDefault(0)
            dao.releaseState(CaptureState.PENDING_TRANSCRIPTION, CaptureState.AWAITING_SELECTION)
            container.logger.write(
                Stage.CALL,
                LogLevel.INFO,
                "cleared $count queued transcription(s) at your request",
                "Nothing was deleted - they are still listed under Recordings.",
            )
            _ui.value = _ui.value.copy(message = "Cleared $count from the queue. Nothing was deleted.")
            refreshStates()
        }
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    private companion object {
        /** Enough to find last week's calls without building a 6000-row list. */
        const val LIST_LIMIT = 200
    }
}
