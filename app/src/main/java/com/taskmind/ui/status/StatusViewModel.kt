package com.taskmind.ui.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.core.CaptureState
import com.taskmind.core.DateResolver
import com.taskmind.data.db.entity.ActivityLogEntity
import com.taskmind.di.AppContainer
import com.taskmind.diagnostics.PermissionState
import com.taskmind.diagnostics.SelfTest
import com.taskmind.update.SelfUpdater
import com.taskmind.work.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Spec 18.8 - the status screen. Permanently reachable from the task list and
 * shows: every permission's state, both provider connection states, today's
 * budget usage, the count of parked captures by state, the last 5 activity-log
 * lines, and buttons for self-test and log export.
 *
 * Spec 4.6: the app works before it is configured. This screen is where "which
 * capability is unavailable and why" is answered.
 */
data class StatusUiState(
    val permissions: List<PermissionState.Item> = emptyList(),
    val llmConfigured: Boolean = false,
    val asrConfigured: Boolean = false,
    val llmSummary: String = "",
    val asrSummary: String = "",
    val cloudConsent: Boolean = false,
    val llmCallsToday: Int = 0,
    val llmBudget: Int = 0,
    val asrSecondsToday: Int = 0,
    val asrBudgetSeconds: Int = 0,
    val captureCounts: Map<CaptureState, Int> = emptyMap(),
    val recentLog: List<ActivityLogEntity> = emptyList(),
    val running: Boolean = false,
    val selfTestReport: SelfTest.Report? = null,
    val availableUpdate: SelfUpdater.Manifest? = null,
    val updateProgress: Float? = null,
    val message: String? = null,
    val installedVersion: String = "",
)

class StatusViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(StatusUiState())
    val state: StateFlow<StatusUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            container.database.activityLogDao().observeRecent(5).collect { entries ->
                _state.value = _state.value.copy(recentLog = entries)
            }
        }
    }

    /** Spec 17.4: re-check every permission on start and on every refresh. */
    fun refresh() {
        viewModelScope.launch {
            val settings = container.settingsRepository.current()
            val usage = container.settingsRepository.currentUsage()
            val today = DateResolver.dayKey(System.currentTimeMillis())
            val dao = container.database.rawCaptureDao()

            val counts = CaptureState.entries.associateWith { dao.countByState(it) }

            _state.value = _state.value.copy(
                permissions = PermissionState.all(container.context),
                llmConfigured = container.secretStore.hasLlmKey(),
                asrConfigured = container.secretStore.hasAsrKey(),
                llmSummary = "${settings.llmModel} at ${settings.llmBaseUrl}",
                asrSummary = "${settings.asrProvider.name.lowercase()} - ${settings.asrModel}",
                cloudConsent = settings.cloudConsent,
                llmCallsToday = if (usage.dayKey == today) usage.llmCalls else 0,
                llmBudget = settings.maxLlmCallsPerDay,
                asrSecondsToday = if (usage.dayKey == today) usage.asrSeconds else 0,
                asrBudgetSeconds = settings.maxAsrMinutesPerDay * 60,
                captureCounts = counts,
                installedVersion = "${container.selfUpdater.installedVersionName} (${container.selfUpdater.installedVersionCode})",
            )
        }
    }

    fun runSelfTest() {
        if (_state.value.running) return
        _state.value = _state.value.copy(running = true, selfTestReport = null, message = null)
        viewModelScope.launch {
            val report = container.selfTest.run()
            _state.value = _state.value.copy(running = false, selfTestReport = report)
            refresh()
        }
    }

    /** Drains everything parked, now, rather than waiting for the next cycle. */
    fun drainNow() {
        Scheduler.enqueueExtraction(container.context)
        Scheduler.enqueueTranscription(container.context)
        Scheduler.enqueueCallDiscovery(container.context)
        _state.value = _state.value.copy(message = "Processing queued captures in the background.")
        viewModelScope.launch {
            container.database.rawCaptureDao()
                .releaseState(CaptureState.BUDGET_HELD, CaptureState.PENDING_EXTRACTION)
            refresh()
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            val settings = container.settingsRepository.current()
            if (settings.updateManifestUrl.isBlank()) {
                _state.value = _state.value.copy(message = "Set an update manifest URL in Settings first.")
                return@launch
            }
            val manifest = container.selfUpdater.fetchManifest(settings.updateManifestUrl)
            _state.value = when {
                manifest == null ->
                    _state.value.copy(message = "Could not read the update manifest.")
                container.selfUpdater.isNewer(manifest) ->
                    _state.value.copy(availableUpdate = manifest, message = null)
                else ->
                    _state.value.copy(availableUpdate = null, message = "You are on the latest version.")
            }
        }
    }

    fun installUpdate() {
        val manifest = _state.value.availableUpdate ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(updateProgress = 0f)
            val result = container.selfUpdater.download(manifest) { progress ->
                _state.value = _state.value.copy(updateProgress = progress)
            }
            when (result) {
                is SelfUpdater.DownloadResult.Ready -> {
                    _state.value = _state.value.copy(updateProgress = null, message = "Starting the installer...")
                    container.selfUpdater.install(result.apk)
                }
                is SelfUpdater.DownloadResult.Failed ->
                    _state.value = _state.value.copy(updateProgress = null, message = result.reason)
            }
        }
    }

    fun exportLog(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(container.logger.exportAsText()) }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
