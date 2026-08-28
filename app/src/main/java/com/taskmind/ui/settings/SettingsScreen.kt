package com.taskmind.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.core.AsrProvider
import com.taskmind.core.ModelCatalog
import com.taskmind.core.ProviderDiagnosis
import com.taskmind.data.settings.Settings
import com.taskmind.ui.components.KeyValueRow
import com.taskmind.ui.components.LabeledSwitch
import com.taskmind.ui.components.SectionCard
import com.taskmind.ui.components.SliderRow
import com.taskmind.ui.components.StatusPill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onShareText: (String, String) -> Unit,
    onOpenPrompts: () -> Unit = {},
    onOpenModelCalls: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenRecordings: () -> Unit = {},
    onOpenHowItWorks: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmErase by remember { mutableStateOf(false) }

    LaunchedEffect(ui.message) {
        val message = ui.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFrom) }

    val dirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> viewModel.setCallRecordingDir(uri) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 48.dp),
        ) {
            TransparencySection(onOpenHowItWorks, onOpenPrompts, onOpenModelCalls, onOpenDiagnostics, onOpenRecordings)
            PrivacySection(settings, viewModel)
            LlmSection(settings, ui, viewModel)
            AsrSection(settings, ui, viewModel)
            CaptureSection(settings, ui, viewModel) { dirLauncher.launch(null) }
            QualitySection(settings, viewModel)
            BudgetSection(settings, viewModel)
            RetentionSection(settings, viewModel) { confirmErase = true }
            UpdateSection(settings, viewModel)
            DataSection(viewModel, onShareText) { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        }
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase captured content?") },
            text = {
                Text(
                    "This removes every stored message, transcript, recording and log line. " +
                        "Your tasks are kept, along with the quote and the source shown on each one.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.eraseCapturedContent()
                    confirmErase = false
                }) {
                    Text("Erase")
                }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The app reads the user's messages and decides what becomes a task. These
 * three screens are how that stays accountable rather than magic, so they sit
 * at the top of settings instead of buried at the bottom.
 */
@Composable
private fun TransparencySection(
    onOpenHowItWorks: () -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenModelCalls: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenRecordings: () -> Unit,
) {
    SectionCard(
        title = "Look inside",
        subtitle = "Every rule, every prompt, and every request this app has made.",
    ) {
        Button(onClick = onOpenHowItWorks, modifier = Modifier.fillMaxWidth()) {
            Text("How TaskMind decides")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Every filter, threshold and rule in plain language, showing the value each one is using " +
                "right now.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onOpenPrompts, modifier = Modifier.fillMaxWidth()) {
            Text("Prompts — read and edit")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "The exact instructions sent to the model. Editable, and every one can be reset.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onOpenModelCalls, modifier = Modifier.fillMaxWidth()) {
            Text("Model calls — what was sent and returned")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "The last hundred requests, with the prompt, your data as it was sent, and the unedited " +
                "reply. This is where a failing provider explains itself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("Test and diagnose")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Run every stage end to end, push ten sample messages through task creation, or export " +
                "one file with everything in it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onOpenRecordings, modifier = Modifier.fillMaxWidth()) {
            Text("Recordings — choose what to transcribe")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Run every stage end to end and see which one fails, or export one file with " +
                "everything in it - settings, queue, model calls and the log.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Spec 20 - said plainly, once on onboarding and again here. */
@Composable
private fun PrivacySection(settings: Settings, viewModel: SettingsViewModel) {
    SectionCard(title = "Privacy") {
        Text(
            "TaskMind sends the text of watched messages and your call recordings to the AI provider you " +
                "configure, so it can find tasks in them. Nothing is sent anywhere else, and nothing is sent " +
                "until you add a key. Free API tiers commonly reserve the right to train on what you submit - " +
                "check your provider's terms.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = settings.cloudConsent, onCheckedChange = viewModel::setCloudConsent)
            Text(
                "Send my message text and call audio to the cloud provider I configure.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!settings.cloudConsent) {
            Text(
                "Capture stays off until this is ticked. The task list works either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlmSection(settings: Settings, ui: SettingsUiState, viewModel: SettingsViewModel) {
    var baseUrl by remember(settings.llmBaseUrl) { mutableStateOf(settings.llmBaseUrl) }
    var model by remember(settings.llmModel) { mutableStateOf(settings.llmModel) }
    var apiKey by remember { mutableStateOf("") }

    SectionCard(title = "Extraction provider", subtitle = "Any OpenAI-compatible /chat/completions endpoint.") {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Settings.LLM_PRESETS.forEach { (name, url) ->
                FilterChip(
                    selected = baseUrl == url && url.isNotBlank(),
                    onClick = {
                        if (url.isNotBlank()) {
                            baseUrl = url
                            Settings.LLM_MODEL_SUGGESTIONS[url]?.let { model = it }
                        }
                    },
                    label = { Text(name) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        ProviderDiagnosis.settingsWarningFor(model)?.let { warning ->
            Spacer(Modifier.height(6.dp))
            Text(
                warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = viewModel::loadModels, enabled = !ui.loadingModels) {
            Text(if (ui.loadingModels) "Asking the provider..." else "Show models this key can use")
        }
        ui.modelListError?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (ui.availableModels.isNotEmpty()) {
            Text(
                "Your provider says this key may use these. Tap one to select it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            for (candidate in ui.availableModels) {
                ModelRow(
                    candidate = candidate,
                    selected = candidate.id == model,
                    onSelect = { model = candidate.id },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key (stored encrypted; currently ${ui.llmKeyMasked})") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                viewModel.setLlm(baseUrl, model, apiKey.ifBlank { null })
                apiKey = ""
            }) {
                Text("Save")
            }
            OutlinedButton(onClick = viewModel::testLlm, enabled = !ui.testingLlm) {
                Text(if (ui.testingLlm) "Testing..." else "Test connection")
            }
        }
        ui.llmTestResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            StatusPill(if (result.ok) "OK" else "Failed", result.ok)
            Text(result.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AsrSection(settings: Settings, ui: SettingsUiState, viewModel: SettingsViewModel) {
    var provider by remember(settings.asrProvider) { mutableStateOf(settings.asrProvider) }
    var baseUrl by remember(settings.asrBaseUrl) { mutableStateOf(settings.asrBaseUrl) }
    var model by remember(settings.asrModel) { mutableStateOf(settings.asrModel) }
    var language by remember(settings.asrLanguage) { mutableStateOf(settings.asrLanguage) }
    var apiKey by remember { mutableStateOf("") }

    SectionCard(
        title = "Transcription provider",
        subtitle = "Sarvam is materially better than Whisper on Hindi phone audio.",
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Settings.ASR_PRESETS.forEach { (name, presetProvider, config) ->
                FilterChip(
                    selected = provider == presetProvider && baseUrl == config.first,
                    onClick = {
                        provider = presetProvider
                        baseUrl = config.first
                        model = config.second
                    },
                    label = { Text(name) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = language,
            onValueChange = { language = it },
            label = { Text("Language code (hi covers Hinglish)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key (currently ${ui.asrKeyMasked})") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                viewModel.setAsr(provider, baseUrl, model, language, apiKey.ifBlank { null })
                apiKey = ""
            }) {
                Text("Save")
            }
            OutlinedButton(onClick = viewModel::testAsr, enabled = !ui.testingAsr) {
                Text(if (ui.testingAsr) "Testing..." else "Test connection")
            }
        }
        ui.asrTestResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            StatusPill(if (result.ok) "OK" else "Failed", result.ok)
            Text(result.message, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = viewModel::clearKeys) { Text("Clear both API keys") }
    }
}

@Composable
private fun CaptureSection(
    settings: Settings,
    ui: SettingsUiState,
    viewModel: SettingsViewModel,
    onPickDirectory: () -> Unit,
) {
    SectionCard(
        title = "What to watch",
        subtitle = "An allow-list, not a deny-list: it is cheaper, and it makes cost predictable.",
    ) {
        LabeledSwitch(
            label = "Capture from messages",
            checked = settings.captureNotifications,
            onCheckedChange = viewModel::setCaptureNotifications,
        )
        LabeledSwitch(
            label = "Capture from calls",
            description = "TaskMind cannot record calls. It reads the recordings your phone app writes.",
            checked = settings.captureCalls,
            onCheckedChange = viewModel::setCaptureCalls,
        )
        Spacer(Modifier.height(12.dp))
        Text("Apps that have sent notifications", style = MaterialTheme.typography.labelLarge)
        if (ui.seenPackages.isEmpty()) {
            Text(
                "Nothing yet. Once notification access is granted, every app that posts a notification " +
                    "appears here and you decide which ones are worth reading.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.seenPackages.forEach { seen ->
            LabeledSwitch(
                label = seen.label ?: seen.packageName,
                description = "${seen.packageName} - ${seen.notificationCount} notifications seen",
                checked = seen.packageName in settings.allowedPackages,
                onCheckedChange = { allowed -> viewModel.togglePackage(seen.packageName, allowed) },
            )
        }

        Spacer(Modifier.height(12.dp))
        SliderRow(
            label = "Ignore calls shorter than",
            value = settings.minCallDurationSeconds.toFloat(),
            onValueChange = { viewModel.setMinCallDuration(it.toLong()) },
            valueRange = 0f..120f,
            steps = 23,
            format = { "${it.toInt()}s" },
            description = "Calls whose length the phone has not reported yet are always processed.",
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPickDirectory) { Text("Choose the call recordings folder") }
        settings.callRecordingDirUri?.let {
            Text(
                "Searching: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QualitySection(settings: Settings, viewModel: SettingsViewModel) {
    SectionCard(
        title = "Accuracy",
        subtitle = "Higher thresholds mean fewer, surer tasks. A wrong task costs more than a missed one.",
    ) {
        SliderRow(
            label = "Create automatically above",
            value = settings.autoCreateThreshold.toFloat(),
            onValueChange = { viewModel.setThresholds(it.toDouble(), settings.reviewThreshold) },
            valueRange = 0.3f..1f,
            description = "Below this, a candidate goes to the review inbox instead of your list.",
        )
        SliderRow(
            label = "Discard below",
            value = settings.reviewThreshold.toFloat(),
            onValueChange = { viewModel.setThresholds(settings.autoCreateThreshold, it.toDouble()) },
            valueRange = 0f..0.9f,
            description = "Anything less certain than this is logged and dropped.",
        )
        Spacer(Modifier.height(8.dp))
        Text("Grounding tolerance", style = MaterialTheme.typography.labelLarge)
        Text(
            "A task is only created if the quote the model gave can be found in the original text. " +
                "These control how exact that match has to be.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SliderRow(
            label = "Messages",
            value = settings.notificationTolerance.toFloat(),
            onValueChange = { viewModel.setTolerances(it.toDouble(), settings.clipboardTolerance, settings.callTolerance) },
            valueRange = 0.5f..1f,
        )
        SliderRow(
            label = "Pasted transcripts",
            value = settings.clipboardTolerance.toFloat(),
            onValueChange = { viewModel.setTolerances(settings.notificationTolerance, it.toDouble(), settings.callTolerance) },
            valueRange = 0.5f..1f,
        )
        SliderRow(
            label = "Call transcripts",
            value = settings.callTolerance.toFloat(),
            onValueChange = { viewModel.setTolerances(settings.notificationTolerance, settings.clipboardTolerance, it.toDouble()) },
            valueRange = 0.5f..1f,
            description = "Lower, because speech recognition itself is noisy.",
        )
        LabeledSwitch(
            label = "Second-opinion pass",
            description = "A second model call reviews each candidate before it becomes a task. Costs one extra call.",
            checked = settings.verifyPass,
            onCheckedChange = viewModel::setVerifyPass,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = viewModel::resetQualityDefaults) { Text("Reset to defaults") }
    }
}

@Composable
private fun BudgetSection(settings: Settings, viewModel: SettingsViewModel) {
    SectionCard(title = "Daily limits", subtitle = "Cloud calls cost money. Nothing over the limit is lost - it waits for tomorrow.") {
        SliderRow(
            label = "Model calls per day",
            value = settings.maxLlmCallsPerDay.toFloat(),
            onValueChange = {
                viewModel.setBudgets(it.toInt(), settings.maxAsrMinutesPerDay, settings.maxLlmCallsPerPackagePerDay)
            },
            valueRange = 20f..2000f,
            format = { it.toInt().toString() },
        )
        SliderRow(
            label = "Per app, per day",
            value = settings.maxLlmCallsPerPackagePerDay.toFloat(),
            onValueChange = {
                viewModel.setBudgets(settings.maxLlmCallsPerDay, settings.maxAsrMinutesPerDay, it.toInt())
            },
            valueRange = 10f..500f,
            format = { it.toInt().toString() },
            description = "Contains a single chatty group chat.",
        )
        SliderRow(
            label = "Transcription minutes per day",
            value = settings.maxAsrMinutesPerDay.toFloat(),
            onValueChange = {
                viewModel.setBudgets(settings.maxLlmCallsPerDay, it.toInt(), settings.maxLlmCallsPerPackagePerDay)
            },
            valueRange = 5f..300f,
            format = { "${it.toInt()} min" },
        )
        LabeledSwitch(
            label = "Upload call audio on Wi-Fi only",
            description = "Call audio is the expensive upload.",
            checked = settings.wifiOnlyAsr,
            onCheckedChange = viewModel::setWifiOnlyAsr,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetentionSection(settings: Settings, viewModel: SettingsViewModel, onErase: () -> Unit) {
    SectionCard(
        title = "Keeping and deleting",
        subtitle = "Deleting captured content never deletes the tasks that came from it.",
    ) {
        Text("Keep messages, transcripts and recordings for", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Settings.RETENTION_CHOICES.forEach { days ->
                FilterChip(
                    selected = settings.retentionDays == days,
                    onClick = { viewModel.setRetentionDays(days) },
                    label = { Text("$days days") },
                )
            }
        }
        LabeledSwitch(
            label = "Delete a recording once it is transcribed",
            checked = settings.deleteRecordingsAfterTranscription,
            onCheckedChange = viewModel::setDeleteRecordings,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onErase) { Text("Erase all captured content") }
    }
}

@Composable
private fun UpdateSection(settings: Settings, viewModel: SettingsViewModel) {
    var url by remember(settings.updateManifestUrl) { mutableStateOf(settings.updateManifestUrl) }
    SectionCard(
        title = "Updates",
        subtitle = "TaskMind is sideloaded, so it updates itself from a URL you control.",
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Update manifest URL") },
            placeholder = { Text("https://github.com/<owner>/<repo>/releases/latest/download/update.json") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { viewModel.setUpdateManifestUrl(url) }) { Text("Save") }
        LabeledSwitch(
            label = "Check daily on Wi-Fi",
            checked = settings.autoCheckUpdates,
            onCheckedChange = viewModel::setAutoCheckUpdates,
        )
        Text(
            "Downloads are verified against the checksum in the manifest before anything is installed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DataSection(
    viewModel: SettingsViewModel,
    onShareText: (String, String) -> Unit,
    onImport: () -> Unit,
) {
    SectionCard(title = "Your data") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.exportJson { onShareText("taskmind-export.json", it) } }) {
                Text("Export JSON")
            }
            OutlinedButton(onClick = { viewModel.exportCsv { onShareText("taskmind-export.csv", it) } }) {
                Text("Export CSV")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onImport) { Text("Import a JSON export") }
        Spacer(Modifier.height(8.dp))
        KeyValueRow("Imports", "go through the same checks as everything else, so duplicates are skipped")
    }
}

/**
 * One model the provider offered, and whether this app can use it.
 *
 * A model that is listed but unusable is shown rather than hidden: the user is
 * looking at the same name in their provider's console, so silently omitting it
 * would read as the app failing to see it. Saying why it will not work here is
 * the whole point.
 */
@Composable
private fun ModelRow(
    candidate: ModelCatalog.Model,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val reason = ModelCatalog.whyUnusable(candidate.kind)
    val usable = reason == null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = usable, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = usable)
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                candidate.id,
                style = MaterialTheme.typography.bodyMedium,
                color = if (usable) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (reason != null) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
