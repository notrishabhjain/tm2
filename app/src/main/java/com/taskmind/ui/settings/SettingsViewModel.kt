package com.taskmind.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.ai.ConnectionTester
import com.taskmind.ai.ModelLister
import com.taskmind.core.ModelCatalog
import com.taskmind.capture.AudioChunker
import com.taskmind.core.AsrProvider
import com.taskmind.core.CaptureState
import com.taskmind.core.LogLevel
import com.taskmind.core.Rules
import com.taskmind.core.Stage
import com.taskmind.data.db.entity.SeenPackageEntity
import com.taskmind.data.settings.Settings
import com.taskmind.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    // -- areas and routines ---------------------------------------------------

    fun setAreas(text: String) {
        viewModelScope.launch {
            container.settingsRepository.setAreas(com.taskmind.core.Areas.parse(text))
        }
    }

    /** Open tasks that repeat: the routines. */
    val routines: StateFlow<List<com.taskmind.data.db.entity.TaskEntity>> = container.taskRepository.observeTasks()
        .map { all ->
            all.filter {
                it.status == com.taskmind.core.TaskStatus.ACTIVE && it.parentTaskId == null &&
                    com.taskmind.core.Recurrence.isValid(it.recurrenceRule)
            }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addRoutine(title: String, every: com.taskmind.core.Routines.Every, dueAt: Long, leadDays: Int, area: String?) {
        if (title.isBlank()) return
        viewModelScope.launch {
            container.taskRepository.createManualTask(
                title = title.trim(),
                dueAt = dueAt,
                reminderAt = com.taskmind.core.Routines.reminderFor(dueAt, leadDays),
                recurrenceRule = every.rule,
                area = area,
            )
            com.taskmind.work.Scheduler.scheduleNextReminder(container.context, container)
            _ui.value = _ui.value.copy(message = "Routine added: ${title.trim()}")
        }
    }

    /** Stops a routine repeating; the current one stays as a normal task. */
    fun stopRoutine(task: com.taskmind.data.db.entity.TaskEntity) {
        viewModelScope.launch {
            container.taskRepository.update(task.copy(recurrenceRule = null))
            _ui.value = _ui.value.copy(message = "\"${task.title.take(30)}\" will not repeat")
        }
    }

    // -- rules --------------------------------------------------------------

    /** The rules the user decided on, approved and rejected. */
    val rules: StateFlow<List<Rules.Rule>> = container.learningStore.rules

    private val _ideas = MutableStateFlow<List<Rules.Idea>>(emptyList())
    /** Rule ideas from recent feedback, not yet decided. */
    val ideas: StateFlow<List<Rules.Idea>> = _ideas.asStateFlow()

    fun refreshIdeas() {
        viewModelScope.launch { _ideas.value = container.learningStore.ideas() }
    }

    fun decideIdea(idea: Rules.Idea, approve: Boolean) {
        viewModelScope.launch {
            container.learningStore.decide(
                Rules.Rule(idea.kind, idea.subject, if (approve) Rules.State.APPROVED else Rules.State.REJECTED),
            )
            container.logger.write(Stage.SYSTEM, LogLevel.INFO, if (approve) "rule approved" else "rule idea rejected", idea.id)
            _ideas.value = container.learningStore.ideas()
        }
    }

    fun addRule(text: String) {
        val clean = text.trim().take(Rules.MAX_CUSTOM)
        if (clean.isEmpty()) return
        viewModelScope.launch {
            container.learningStore.decide(Rules.Rule(Rules.Kind.CUSTOM, clean, Rules.State.APPROVED))
        }
    }

    /** Forgets a decision: an approved rule stops applying, a rejected idea may come back. */
    fun removeRule(id: String) {
        viewModelScope.launch {
            container.learningStore.remove(id)
            _ideas.value = container.learningStore.ideas()
        }
    }

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

            // Spec 8.4: nothing is lost for want of a key. Transcription parks a
            // recording when there is no key or the provider rejects the model,
            // and a parked row is not in the queue any more - so fixing the
            // setting has to put it back, or the fix does nothing visible and
            // the recordings stay stuck.
            val requeued = runCatching {
                val dao = container.database.rawCaptureDao()
                val count = dao.countRecordingsInState(CaptureState.FAILED_PERMANENT)
                if (count > 0) {
                    dao.requeueRecordings(CaptureState.FAILED_PERMANENT, CaptureState.PENDING_TRANSCRIPTION)
                }
                count
            }.getOrDefault(0)
            if (requeued > 0) {
                container.logger.write(
                    Stage.CALL,
                    LogLevel.INFO,
                    "retrying $requeued recording(s) after the transcription settings changed",
                )
            }

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

    /**
     * Re-draws the line: from now on, and nothing before it.
     *
     * The escape hatch for a queue that has somehow filled with old recordings.
     * Retiring the backlog here rather than waiting for the next worker pass
     * means the button visibly does something the moment it is pressed.
     */
    fun startFreshFromNow() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            container.settingsRepository.setRecordingCutoff(now)
            val retired = runCatching {
                container.database.rawCaptureDao().retireCallCapturesBefore(now)
            }.getOrDefault(0)
            container.logger.write(
                Stage.CALL,
                LogLevel.INFO,
                "ignoring everything recorded before now at your request",
                "$retired queued recording(s) retired. New calls are unaffected.",
            )
            _ui.value = _ui.value.copy(
                message = if (retired > 0) {
                    "Ignoring everything recorded so far - $retired removed from the queue."
                } else {
                    "Ignoring everything recorded so far. The queue was already clear."
                },
            )
        }
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

    // -- who you are, and group chats --------------------------------------

    fun setAboutMe(text: String) {
        viewModelScope.launch {
            container.settingsRepository.setAboutMe(text)
            _ui.value = _ui.value.copy(message = "Saved. TaskMind uses it from the next message.")
        }
    }

    fun setOwnNames(names: Set<String>) {
        viewModelScope.launch { container.settingsRepository.setOwnNames(names) }
    }

    fun setGroupPolicy(policy: com.taskmind.core.PreFilter.GroupPolicy) {
        viewModelScope.launch { container.settingsRepository.setGroupPolicy(policy) }
    }

    /** A null policy removes the group from both lists. */
    fun setGroupOverride(groupName: String, policy: com.taskmind.core.PreFilter.GroupPolicy?) {
        viewModelScope.launch { container.settingsRepository.setGroupOverride(groupName, policy) }
    }

    // -- speed and retention -----------------------------------------------

    fun setInstantCapture(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setInstantCapture(value) }
    }

    fun setReviewExpiryDays(days: Int) {
        viewModelScope.launch { container.settingsRepository.setReviewExpiryDays(days) }
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

    /**
     * Spec 16: clears captured content but KEEPS tasks.
     *
     * Both erases run in the app's scope rather than this screen's: leaving
     * Settings half-way must not stop one with half the tables emptied.
     */
    fun eraseCapturedContent() {
        _ui.update { it.copy(busy = true) }
        container.applicationScope.launch {
            val message = runCatching {
                val settings = container.settingsRepository.current()
                container.backupRepository.eraseCapturedContent(deleteRecordings = settings.deleteRecordingsAfterTranscription)
            }.fold(
                onSuccess = { count -> "Erased $count captures, transcripts and logs. Your tasks are untouched." },
                onFailure = { "Could not erase: ${it.message ?: it.javaClass.simpleName}" },
            )
            _ui.update { it.copy(busy = false, message = message) }
        }
    }

    /** One tap, a fresh start - see FreshStart for what goes and what stays. */
    fun eraseEverything() {
        _ui.update { it.copy(busy = true) }
        container.applicationScope.launch {
            val message = runCatching { container.freshStart.eraseEverything() }.fold(
                onSuccess = { r ->
                    "Erased everything: ${r.tasks} task(s) and ${r.captures} capture(s)." +
                        if (r.web) " The web page empties at the next sync." else ""
                },
                onFailure = { "Could not erase everything: ${it.message ?: it.javaClass.simpleName}" },
            )
            _ui.update { it.copy(busy = false, message = message) }
        }
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }
}
