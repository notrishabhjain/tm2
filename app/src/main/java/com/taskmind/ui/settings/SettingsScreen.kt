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
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.taskmind.ui.design.Grouped
import com.taskmind.ui.design.NavRow
import com.taskmind.ui.design.RowDivider
import com.taskmind.ui.design.Space
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
    onOpenStatus: () -> Unit = {},
    onOpenCalls: () -> Unit = {},
    onOpenImport: () -> Unit = {},
    initialPage: String? = null,
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

    // Which sub-page is open, or null for the hub. Held here rather than in
    // the navigation graph because these pages are leaves of one screen, not
    // destinations anyone links to.
    var page by remember {
        mutableStateOf(SettingsPage.entries.firstOrNull { it.name == initialPage })
    }
    // An advanced sub-page goes back to Advanced, everything else to the hub.
    fun up() {
        page = if (page?.advanced == true) SettingsPage.ADVANCED else null
    }
    BackHandler(enabled = page != null) { up() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(page?.title ?: "Settings") },
                navigationIcon = {
                    IconButton(onClick = { if (page != null) up() else onBack() }) {
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
            when (page) {
                null -> SettingsHub(SettingsPage.entries.filter { !it.advanced }, onOpen = { page = it })

                SettingsPage.YOU -> {
                    AboutMeSection(settings, viewModel)
                    GroupSection(settings, viewModel)
                    HowManySection(settings, viewModel)
                    MessageAppsSection(settings, ui, viewModel)
                    ProfileSection()
                }

                SettingsPage.CALLS ->
                    CallCaptureSection(settings, viewModel, onOpenCalls, onOpenImport) { dirLauncher.launch(null) }

                SettingsPage.REMINDERS -> BehaviourSection()

                SettingsPage.PRIVACY -> {
                    SecuritySection()
                    PrivacySection(settings, viewModel)
                    RetentionSection(settings, viewModel) { confirmErase = true }
                    WebSyncSection()
                    DataSection(viewModel, onShareText) {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                }

                SettingsPage.ADVANCED -> {
                    AdvancedNote()
                    SettingsHub(
                        SettingsPage.entries.filter { it.advanced },
                        // Health is a screen of its own, not a page of links
                        // to screens: one tap, not two.
                        onOpen = { if (it == SettingsPage.HEALTH) onOpenStatus() else page = it },
                    )
                }

                // Never shown as a page (see ADVANCED above); here for the
                // `when` to be complete, and as a way back if it ever is.
                SettingsPage.HEALTH -> SettingsHub(SettingsPage.entries.filter { it.advanced }, onOpen = { page = it })

                SettingsPage.PROVIDERS -> {
                    LlmSection(settings, ui, viewModel)
                    AsrSection(settings, ui, viewModel)
                }

                SettingsPage.ACCURACY -> QualitySection(settings, viewModel)

                SettingsPage.BUDGET -> BudgetSection(settings, viewModel)

                SettingsPage.ABOUT -> UpdateSection(settings, viewModel)
            }
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
 * Where settings can take you: five pages, and the machinery behind one door.
 *
 * There used to be twelve, three of them about people (which apps, which
 * groups, work or personal) on three different pages, and five of them tuning
 * knobs for the AI pipeline that nobody needs day to day. The person-facing
 * choices are now grouped by what they are about; the pipeline lives under
 * Advanced, where it is one tap further away and out of the way.
 */
enum class SettingsPage(
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val advanced: Boolean = false,
) {
    YOU(
        "You and your chats",
        "About you, your name, group chats, apps to read, work and personal",
        Icons.Outlined.Groups,
    ),
    CALLS(
        "Calls",
        "Call capture, the recordings folder, and your recent calls",
        Icons.Outlined.Call,
    ),
    REMINDERS(
        "Reminders and look",
        "Daily brief, swipes, the widget and theme",
        Icons.Outlined.NotificationsActive,
    ),
    PRIVACY(
        "Privacy and security",
        "App lock, what is kept and for how long, web access, export",
        Icons.Outlined.Lock,
    ),
    ADVANCED(
        "Advanced",
        "AI providers, accuracy, limits, health checks and updates",
        Icons.Outlined.Tune,
    ),

    HEALTH(
        "Health and diagnostics",
        "Is it working? Self-test, the log, model calls, prompts and recordings",
        Icons.Outlined.Science,
        advanced = true,
    ),
    PROVIDERS(
        "AI providers",
        "Keys, models and connection tests for text and speech",
        Icons.Outlined.Cloud,
        advanced = true,
    ),
    ACCURACY(
        "Accuracy",
        "The thresholds behind Fewer / Balanced / More",
        Icons.Outlined.Tune,
        advanced = true,
    ),
    BUDGET(
        "Limits",
        "Daily caps on model calls and audio, and the network rule",
        Icons.Outlined.Speed,
        advanced = true,
    ),
    ABOUT(
        "Updates",
        "Version and automatic update checks",
        Icons.Outlined.Info,
        advanced = true,
    ),
}

@Composable
private fun SettingsHub(pages: List<SettingsPage>, onOpen: (SettingsPage) -> Unit) {
    Column(Modifier.padding(vertical = Space.snug)) {
        Grouped {
            pages.forEachIndexed { index, entry ->
                NavRow(
                    title = entry.title,
                    supporting = entry.summary,
                    icon = entry.icon,
                    onClick = { onOpen(entry) },
                )
                if (index != pages.lastIndex) RowDivider()
            }
        }
    }
}

@Composable
private fun AdvancedNote() {
    Text(
        "How the capture engine is set up and tuned. Nothing here needs changing for everyday " +
            "use; the defaults are the tested ones.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Space.edge, vertical = Space.snug),
    )
}

/**
 * The one accuracy question a person can actually answer.
 *
 * Five sliders ask about thresholds and grounding tolerances; this asks
 * whether you want more tasks or fewer, and sets the two thresholds that
 * decide it. The sliders are still under Advanced for anyone who wants them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HowManySection(settings: Settings, viewModel: SettingsViewModel) {
    SectionCard(
        title = "How many tasks",
        subtitle = "How sure TaskMind must be before something becomes a task.",
    ) {
        val current = AccuracyPreset.matching(settings.autoCreateThreshold, settings.reviewThreshold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccuracyPreset.entries.forEach { preset ->
                FilterChip(
                    selected = current == preset,
                    onClick = { viewModel.setThresholds(preset.autoCreate, preset.review) },
                    label = { Text(preset.label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            (current ?: AccuracyPreset.BALANCED).let {
                if (current == null) "Custom thresholds are set under Advanced → Accuracy." else it.explanation
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The three stances on the accuracy question, and the thresholds behind each. */
enum class AccuracyPreset(
    val label: String,
    val autoCreate: Double,
    val review: Double,
    val explanation: String,
) {
    FEWER(
        "Fewer",
        0.90,
        0.60,
        "Only unmistakable commitments become tasks; the digest holds just the strongest maybes.",
    ),
    BALANCED(
        "Balanced",
        0.80,
        0.50,
        "Real commitments become tasks; maybes wait in the evening digest for one look.",
    ),
    MORE(
        "More",
        0.70,
        0.40,
        "Catches more, at the cost of a longer digest. For weeks when missing something is worse.",
    ),
    ;

    companion object {
        fun matching(autoCreate: Double, review: Double): AccuracyPreset? =
            entries.firstOrNull { kotlin.math.abs(it.autoCreate - autoCreate) < 0.005 && kotlin.math.abs(it.review - review) < 0.005 }
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
private fun MessageAppsSection(
    settings: Settings,
    ui: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    SectionCard(
        title = "Which apps to read",
        subtitle = "Only the apps switched on here are read. Everything else is ignored before it costs anything.",
    ) {
        LabeledSwitch(
            label = "Capture from messages",
            checked = settings.captureNotifications,
            onCheckedChange = viewModel::setCaptureNotifications,
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
    }
}

/**
 * Everything about calls, including the call list itself.
 *
 * The call list used to be a whole tab in the bottom bar. Once capture works
 * it is something you check occasionally, not a place you live, so it moved
 * here and the bar went back to the things you use every day.
 */
@Composable
private fun CallCaptureSection(
    settings: Settings,
    viewModel: SettingsViewModel,
    onOpenCalls: () -> Unit,
    onOpenImport: () -> Unit,
    onPickDirectory: () -> Unit,
) {
    SectionCard(
        title = "Calls",
        subtitle = "TaskMind cannot record calls. It reads the recordings your phone app writes.",
    ) {
        LabeledSwitch(
            label = "Capture from calls",
            checked = settings.captureCalls,
            onCheckedChange = viewModel::setCaptureCalls,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenCalls) { Text("Recent calls") }
            OutlinedButton(onClick = onOpenImport) { Text("Paste a transcript") }
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

        Spacer(Modifier.height(16.dp))
        Text(
            if (settings.recordingCutoffMillis > 0) {
                "Recordings made before " +
                    CUTOFF_STAMP.format(java.util.Date(settings.recordingCutoffMillis)) +
                    " are ignored. Every call after it is transcribed automatically."
            } else {
                "Every new call is transcribed automatically."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = viewModel::startFreshFromNow) { Text("Ignore everything recorded so far") }
    }
}

private val CUTOFF_STAMP =
    java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.US)

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

        Spacer(Modifier.height(16.dp))
        Text("Clear unanswered review items after", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Settings.REVIEW_EXPIRY_CHOICES.forEach { days ->
                FilterChip(
                    selected = settings.reviewExpiryDays == days,
                    onClick = { viewModel.setReviewExpiryDays(days) },
                    label = { Text(if (days == 0) "Never" else "$days days") },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "They move to Review \u2192 Dismissed rather than being deleted, so one you would " +
                "have accepted can still be put back. Nothing in the review inbox expired before " +
                "this, so the queue only ever grew.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onErase) { Text("Erase all captured content") }
    }
}

@Composable
private fun UpdateSection(settings: Settings, viewModel: SettingsViewModel) {
    SectionCard(
        title = "Updates",
        subtitle = "TaskMind updates itself from its GitHub releases.",
    ) {
        KeyValueRow("Installed", "${com.taskmind.BuildConfig.APP_VERSION_NAME} (${com.taskmind.BuildConfig.APP_VERSION_CODE})")
        LabeledSwitch(
            label = "Check daily on Wi-Fi",
            checked = settings.autoCheckUpdates,
            onCheckedChange = viewModel::setAutoCheckUpdates,
        )
        Text(
            "Downloads are verified against the checksum in the release before anything is installed. " +
                "Is it working? → Check for updates checks right now.",
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
        OutlinedButton(onClick = { viewModel.exportJson { onShareText("taskmind-export.json", it) } }) {
            Text("Export a backup")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onImport) { Text("Restore from a backup") }
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
