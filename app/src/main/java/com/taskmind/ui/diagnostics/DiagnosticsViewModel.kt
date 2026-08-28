package com.taskmind.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.diagnostics.DiagnosticReport
import com.taskmind.diagnostics.SelfTest
import com.taskmind.diagnostics.TaskCreationTest
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class DiagnosticsUiState(
    val running: Boolean = false,
    val report: SelfTest.Report? = null,
    val buildingExport: Boolean = false,
    val message: String? = null,

    val runningTaskTest: Boolean = false,
    val taskTestProgress: String? = null,
    val taskReport: TaskCreationTest.Report? = null,

    /** The last report written, so it can be saved as well as shared. */
    val reportFile: File? = null,
)

class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {

    private var lastReportFile: File? = null

    private val _ui = MutableStateFlow(DiagnosticsUiState())
    val ui: StateFlow<DiagnosticsUiState> = _ui.asStateFlow()

    fun runTests() {
        if (_ui.value.running) return
        _ui.value = _ui.value.copy(running = true, report = null, message = null)
        viewModelScope.launch {
            val report = runCatching { container.selfTest.run() }.getOrNull()
            _ui.value = _ui.value.copy(
                running = false,
                report = report,
                message = if (report == null) "The test run itself failed - see the activity log." else null,
            )
        }
    }

    /**
     * Writes the report to a cache file and hands back the file, because a
     * report is tens of thousands of characters and a share intent that carries
     * that as an extra is silently truncated by most targets.
     */
    /**
     * Ten sample messages through the real pipeline, with the answer a person
     * would give for each.
     *
     * This is the check that "no tasks are being created" needs, because that
     * sentence has two very different causes - a broken pipeline, and a week of
     * messages that genuinely contained no commitments - and nothing else in
     * the app can tell them apart.
     */
    fun runTaskCreationTest() {
        if (_ui.value.runningTaskTest) return
        _ui.value = _ui.value.copy(
            runningTaskTest = true,
            taskReport = null,
            taskTestProgress = "Sending ${TaskCreationTest.DEFAULT_CASES.size} sample messages...",
            message = null,
        )
        viewModelScope.launch {
            val report = runCatching {
                TaskCreationTest(container.context, container).run()
            }.getOrNull()
            _ui.value = _ui.value.copy(
                runningTaskTest = false,
                taskTestProgress = null,
                taskReport = report,
                message = if (report == null) {
                    "The test run itself failed - see the activity log."
                } else {
                    report.summary
                },
            )
        }
    }

    fun exportReport(includeSelfTest: Boolean, onReady: (File) -> Unit) {
        if (_ui.value.buildingExport) return
        _ui.value = _ui.value.copy(buildingExport = true, message = null)
        viewModelScope.launch {
            val result = runCatching {
                val text = DiagnosticReport(container.context, container).build(
                    includeSelfTest = includeSelfTest,
                    // Reuse the run already on screen instead of repeating it.
                    existingReport = _ui.value.report,
                )
                val dir = File(container.context.cacheDir, "diagnostics").apply { mkdirs() }
                // One file per export, named by timestamp: comparing two
                // reports from before and after a settings change is the normal
                // way this gets used.
                File(dir, DiagnosticReport.fileName()).apply { writeText(text) }
            }
            result.onSuccess { file ->
                lastReportFile = file
                _ui.value = _ui.value.copy(
                    buildingExport = false,
                    reportFile = file,
                    message = "Report ready: ${file.name} (${file.length() / 1024} KB)",
                )
                onReady(file)
            }.onFailure { error ->
                // Say what actually went wrong. "Not working" was the entire
                // report from the device, because nothing here named a cause.
                container.logger.write(
                    Stage.SYSTEM,
                    LogLevel.ERROR,
                    "diagnostic export failed",
                    error.stackTraceToString().take(2000),
                )
                _ui.value = _ui.value.copy(
                    buildingExport = false,
                    message = "Could not build the report: ${error.message ?: error.toString()}",
                )
            }
        }
    }

    fun retryBlockedCaptures() {
        viewModelScope.launch {
            container.runtimeStateStore.clearCooldowns()
            val released = runCatching { container.extractionPipeline.retryBlockedNow() }.getOrDefault(0)
            _ui.value = _ui.value.copy(
                message = if (released == 0) {
                    "Nothing was blocked."
                } else {
                    "$released capture(s) queued for another try."
                },
            )
        }
    }

    /**
     * Writes the report to wherever the user pointed the system file picker.
     *
     * Sharing goes through FileProvider and a receiving app, and on the device
     * that chain produced nothing but "the export is not working" with no way
     * to tell which link failed. Saving to a location the user chose has no
     * such chain: it either writes the bytes or reports why not.
     */
    fun saveReportTo(uri: android.net.Uri) {
        viewModelScope.launch {
            val file = lastReportFile
            val text = if (file != null && file.exists()) {
                runCatching { file.readText() }.getOrNull()
            } else {
                runCatching {
                    DiagnosticReport(container.context, container)
                        .build(includeSelfTest = false, existingReport = _ui.value.report)
                }.getOrNull()
            }
            if (text == null) {
                _ui.value = _ui.value.copy(message = "Could not read the report back.")
                return@launch
            }
            val ok = runCatching { container.backupRepository.writeToUri(uri, text) }.getOrDefault(false)
            _ui.value = _ui.value.copy(
                message = if (ok) "Report saved." else "Could not write to that location.",
            )
        }
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }
}
