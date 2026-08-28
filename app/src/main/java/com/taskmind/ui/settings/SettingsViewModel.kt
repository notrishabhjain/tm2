package com.taskmind.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.ai.ConnectionTester
import com.taskmind.ai.ModelLister
import com.taskmind.core.ModelCatalog
import com.taskmind.capture.AudioChunker
import com.taskmind.core.AsrProvider
import com.taskmind.data.db.entity.SeenPackageEntity
import com.taskmind.data.settings.Settings
import com.taskmind.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class SettingsUiState(
    val llmKeyMasked: String = "not set",
    val asrKeyMasked: String = "not set",
    val testingLlm: Boolean = false,
    val testingAsr: Boolean = false,
    val llmTestResult: ConnectionTester.TestResult? = null,
    val asrTestResult: ConnectionTester.TestResult? = null,
    val seenPackages: List<SeenPackageEntity> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,

    /** What the provider says this key may use. Empty until asked. */
    val availableModels: List<ModelCatalog.Model> = emptyList(),
    val loadingModels: Boolean = false,
    val modelListError: String? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<Settings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings.DEFAULT)

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        refreshKeys()
        viewModelScope.launch {
            container.database.seenPackageDao().observeAll().collect { packages ->
                _ui.value = _ui.value.copy(seenPackages = packages)
            }
        }
    }

    private fun refreshKeys() {
        _ui.value = _ui.value.copy(
            llmKeyMasked = container.secretStore.mask(container.secretStore.llmApiKey),
            asrKeyMasked = container.secretStore.mask(container.secretStore.asrApiKey),
        )
    }

    // -- providers ---------------------------------------------------------

    fun setLlm(baseUrl: String, model: String, apiKey: String?) {
        viewModelScope.launch {
            container.settingsRepository.setLlm(baseUrl, model)
            if (!apiKey.isNullOrBlank()) container.secretStore.llmApiKey = apiKey
            refreshKeys()
            // Spec 8.4: adding a key drains the backlog oldest-first.
            com.taskmind.work.Scheduler.enqueueExtraction(container.context)
        }
    }

    fun setAsr(provider: AsrProvider, baseUrl: String, model: String, language: String, apiKey: String?) {
        viewModelScope.launch {
            container.settingsRepository.setAsr(provider, baseUrl, model, language)
            if (!apiKey.isNullOrBlank()) container.secretStore.asrApiKey = apiKey
            refreshKeys()
            com.taskmind.work.Scheduler.enqueueTranscription(container.context)
        }
    }

    fun clearKeys() {
        container.secretStore.clear()
        refreshKeys()
        _ui.value = _ui.value.copy(message = "API keys cleared.")
    }

    /** Spec 8.3 - Test connection, per provider. */
    fun testLlm() {
        if (_ui.value.testingLlm) return
        _ui.value = _ui.value.copy(testingLlm = true, llmTestResult = null)
        viewModelScope.launch {
            val result = container.connectionTester.testLlm(container.llmConfig())
            _ui.value = _ui.value.copy(testingLlm = false, llmTestResult = result)
        }
    }

    /**
     * Asks the provider which models this key can use.
     *
     * The alternative is guessing, and guessing is what went wrong: the app
     * shipped a suggestion list, the account had a different set enabled, and
     * the mismatch presented as a 403 naming a model the user had never heard
     * of. The provider is the only authority on this, so ask it.
     */
    fun loadModels() {
        if (_ui.value.loadingModels) return
        _ui.value = _ui.value.copy(loadingModels = true, modelListError = null)
        viewModelScope.launch {
            val settings = container.settingsRepository.current()
            when (val result = container.modelLister.list(settings.llmBaseUrl, container.secretStore.llmApiKey)) {
                is ModelLister.Result.Ok -> _ui.value = _ui.value.copy(
                    loadingModels = false,
                    availableModels = ModelCatalog.forExtraction(result.models),
                    modelListError = null,
                )
                is ModelLister.Result.Failed -> _ui.value = _ui.value.copy(
                    loadingModels = false,
                    availableModels = emptyList(),
                    modelListError = result.message,
                )
                ModelLister.Result.Unsupported -> _ui.value = _ui.value.copy(
                    loadingModels = false,
                    availableModels = emptyList(),
                    modelListError = "This provider does not publish a model list. " +
                        "Enter the model name from its console.",
                )
            }
        }
    }

    fun testAsr() {
        if (_ui.value.testingAsr) return
        _ui.value = _ui.value.copy(testingAsr = true, asrTestResult = null)
        viewModelScope.launch {
            val sample = AudioChunker.writeSilentWav(File(container.context.cacheDir, "selftest/silence.wav"))
            val (transcriber, _) = container.transcriber()
            val result = container.connectionTester.testAsr(
                transcriber,
                sample,
                container.settingsRepository.current().asrLanguage,
            )
            _ui.value = _ui.value.copy(testingAsr = false, asrTestResult = result)
        }
    }

    // -- capture -----------------------------------------------------------

    fun togglePackage(packageName: String, allowed: Boolean) {
        viewModelScope.launch { container.settingsRepository.togglePackage(packageName, allowed) }
    }

    fun setCaptureNotifications(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setCaptureNotifications(value) }
    }

    fun setCaptureCalls(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setCaptureCalls(value) }
    }

    fun setMinCallDuration(seconds: Long) {
        viewModelScope.launch { container.settingsRepository.setMinCallDuration(seconds) }
    }

    fun setCallRecordingDir(uri: Uri?) {
        viewModelScope.launch {
            if (uri != null) {
                runCatching {
                    container.context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            container.settingsRepository.setCallRecordingDirUri(uri?.toString())
        }
    }

    // -- quality -----------------------------------------------------------

    fun setThresholds(autoCreate: Double, review: Double) {
        viewModelScope.launch {
            // The review gate can never sit above the auto-create gate, or the
            // middle band disappears and uncertain items are silently dropped.
            container.settingsRepository.setThresholds(autoCreate, review.coerceAtMost(autoCreate))
        }
    }

    fun setTolerances(notification: Double, clipboard: Double, call: Double) {
        viewModelScope.launch { container.settingsRepository.setTolerances(notification, clipboard, call) }
    }

    fun setVerifyPass(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setVerifyPass(value) }
    }

    fun resetQualityDefaults() {
        viewModelScope.launch {
            container.settingsRepository.resetQualityDefaults()
            _ui.value = _ui.value.copy(message = "Thresholds reset to defaults.")
        }
    }

    // -- budgets and retention ---------------------------------------------

    fun setBudgets(llmCalls: Int, asrMinutes: Int, perPackage: Int) {
        viewModelScope.launch { container.settingsRepository.setBudgets(llmCalls, asrMinutes, perPackage) }
    }

    fun setWifiOnlyAsr(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setWifiOnlyAsr(value) }
    }

    fun setRetentionDays(days: Int) {
        viewModelScope.launch { container.settingsRepository.setRetentionDays(days) }
    }

    fun setDeleteRecordings(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setDeleteRecordings(value) }
    }

    fun setUpdateManifestUrl(url: String) {
        viewModelScope.launch { container.settingsRepository.setUpdateManifestUrl(url) }
    }

    fun setAutoCheckUpdates(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAutoCheckUpdates(value) }
    }

    fun setCloudConsent(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setCloudConsent(value) }
    }

    // -- data --------------------------------------------------------------

    fun exportJson(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(container.backupRepository.exportJson()) }
    }

    fun exportCsv(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(container.backupRepository.exportCsv()) }
    }

    fun writeTo(uri: Uri, content: String) {
        viewModelScope.launch {
            val ok = container.backupRepository.writeToUri(uri, content)
            _ui.value = _ui.value.copy(message = if (ok) "Saved." else "Could not write that file.")
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val text = container.backupRepository.readFromUri(uri)
            val message = if (text == null) {
                "Could not read that file."
            } else {
                val result = container.backupRepository.importJson(text)
                result.error ?: "Imported ${result.created} task(s); skipped ${result.skipped} duplicate(s)."
            }
            _ui.value = _ui.value.copy(busy = false, message = message)
        }
    }

    /** Spec 16: clears captured content but KEEPS tasks. */
    fun eraseCapturedContent() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val count = container.backupRepository.eraseCapturedContent()
            _ui.value = _ui.value.copy(
                busy = false,
                message = "Erased $count captures, transcripts, recordings and logs. Your tasks are untouched.",
            )
        }
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }
}
