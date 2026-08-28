package com.taskmind.ui.status

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.core.CaptureState
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.components.KeyValueRow
import com.taskmind.ui.components.SectionCard
import com.taskmind.ui.components.StatusPill

/**
 * Spec 18.8 - the status screen, permanently reachable from the task list.
 *
 * Principle 6: the app works before it is configured, and this screen explains
 * exactly which capability is unavailable and why. Principle 5: everything is
 * visible - when something does not work, this is where it says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    viewModel: StatusViewModel,
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelCalls: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onShareText: (String, String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Status") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.refresh() }) { Text("Refresh") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
        ) {
            if (!state.cloudConsent) {
                SectionCard(
                    title = "Capture is off",
                    subtitle = "You have not agreed to send message text and call audio to a cloud provider, " +
                        "so nothing is being captured. The task list works normally.",
                ) {
                    Button(onClick = onOpenSettings) { Text("Review the privacy setting") }
                }
            }

            SectionCard(title = "Permissions", subtitle = "Re-checked every time you open this screen.") {
                state.permissions.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.bodyLarge)
                            if (!item.granted) {
                                Text(
                                    item.explanation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        StatusPill(if (item.granted) "Granted" else "Missing", item.granted)
                        if (!item.granted && item.fixIntent != null) {
                            TextButton(onClick = {
                                runCatching {
                                    context.startActivity(
                                        item.fixIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }) {
                                Text("Fix")
                            }
                        }
                    }
                }
            }

            SectionCard(title = "AI providers") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Extraction (LLM)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (state.llmConfigured) {
                                state.llmSummary
                            } else {
                                "Extraction unavailable - no API key configured. Captures are being kept and " +
                                    "will be processed the moment you add one."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(if (state.llmConfigured) "Ready" else "Not set", state.llmConfigured)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Transcription (ASR)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (state.asrConfigured) {
                                state.asrSummary
                            } else {
                                "Call transcription unavailable - no API key configured. Recordings are kept."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(if (state.asrConfigured) "Ready" else "Not set", state.asrConfigured)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenSettings) { Text("Open settings") }
            }

            SectionCard(title = "Today's usage", subtitle = "Budgets reset at midnight IST.") {
                KeyValueRow("LLM calls", "${state.llmCallsToday} of ${state.llmBudget}")
                LinearProgressIndicator(
                    progress = { if (state.llmBudget == 0) 0f else (state.llmCallsToday.toFloat() / state.llmBudget).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                KeyValueRow(
                    "Transcription",
                    "${state.asrSecondsToday / 60} min of ${state.asrBudgetSeconds / 60} min",
                )
                LinearProgressIndicator(
                    progress = { if (state.asrBudgetSeconds == 0) 0f else (state.asrSecondsToday.toFloat() / state.asrBudgetSeconds).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard(
                title = "Captures waiting",
                subtitle = "Nothing captured is ever thrown away. Anything stuck here is retried.",
            ) {
                CaptureState.entries.forEach { captureState ->
                    val count = state.captureCounts[captureState] ?: 0
                    if (count > 0 || captureState == CaptureState.PENDING_EXTRACTION) {
                        KeyValueRow(captureState.name.lowercase().replace('_', ' '), count.toString())
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = viewModel::drainNow) { Text("Process everything now") }
            }

            SectionCard(title = "Diagnostics", subtitle = "The self-test runs the real capture pipeline, end to end.") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::runSelfTest, enabled = !state.running) {
                        if (state.running) {
                            CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(if (state.running) "Running..." else "Run self-test")
                    }
                    OutlinedButton(onClick = onOpenLog) { Text("Activity log") }
                    OutlinedButton(onClick = onOpenModelCalls) { Text("Model calls") }
                    OutlinedButton(onClick = onOpenDiagnostics) { Text("Test and diagnose") }
                }

                state.selfTestReport?.let { report ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        report.summary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (report.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    report.steps.forEach { step ->
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth()) {
                            StatusPill(if (step.passed) "PASS" else "FAIL", step.passed)
                            Spacer(Modifier.height(4.dp))
                            Column(Modifier.padding(start = 8.dp)) {
                                Text("${step.name} (${step.millis}ms)", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    step.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    viewModel.exportLog { text -> onShareText("TaskMind activity log", text) }
                }) {
                    Text("Share the log")
                }
            }

            SectionCard(title = "Recent activity") {
                if (state.recentLog.isEmpty()) {
                    Text("Nothing logged yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    state.recentLog.forEach { entry ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                "${DateFormats.timestamp(entry.timestamp)} ${entry.level.name} ${entry.stage}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(entry.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onOpenLog) { Text("See the full log") }
                }
            }

            SectionCard(title = "App version", subtitle = state.installedVersion) {
                state.updateProgress?.let { progress ->
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                val update = state.availableUpdate
                if (update != null) {
                    Text(
                        "Version ${update.versionName} is available.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    update.releaseNotes?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = viewModel::installUpdate) { Text("Download and install") }
                } else {
                    Button(onClick = viewModel::checkForUpdate) { Text("Check for updates") }
                }
            }
        }
    }
}
