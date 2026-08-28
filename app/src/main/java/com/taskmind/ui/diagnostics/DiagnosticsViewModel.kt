package com.taskmind.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.diagnostics.DiagnosticReport
import com.taskmind.diagnostics.SelfTest
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
)

class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {

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
    fun exportReport(includeSelfTest: Boolean, onReady: (File) -> Unit) {
        if (_ui.value.buildingExport) return
        _ui.value = _ui.value.copy(buildingExport = true, message = null)
        viewModelScope.launch {
            val result = runCatching {
                val text = DiagnosticReport(container.context, container).build(includeSelfTest)
                val dir = File(container.context.cacheDir, "diagnostics").apply { mkdirs() }
                // One file per export, named by timestamp: comparing two
                // reports from before and after a settings change is the normal
                // way this gets used.
                File(dir, DiagnosticReport.fileName()).apply { writeText(text) }
            }
            result.onSuccess { file ->
                _ui.value = _ui.value.copy(
                    buildingExport = false,
                    message = "Report ready: ${file.name} (${file.length() / 1024} KB)",
                )
                onReady(file)
            }.onFailure { error ->
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

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }
}
