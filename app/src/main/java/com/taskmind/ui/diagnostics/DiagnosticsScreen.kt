package com.taskmind.ui.diagnostics

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.diagnostics.DiagnosticReport
import com.taskmind.diagnostics.SelfTest
import com.taskmind.diagnostics.TaskCreationTest
import com.taskmind.ui.components.LabeledSwitch
import com.taskmind.ui.components.SectionCard
import java.io.File

/**
 * The screen to open when the app "isn't working".
 *
 * Two things live here, and they answer different questions. The test run
 * answers "which stage is broken" on the device, in order, so the first failure
 * is the cause rather than a symptom. The export answers "why", by putting
 * everything - settings, queue, model calls with their replies, the log - into
 * one file that can leave the phone and be read properly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onBack: () -> Unit,
    onShareFile: (File) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let(viewModel::saveReportTo) }
    // Off by default: running the tests as part of the export adds a minute and
    // two paid model calls behind a button that only says "Building...". A run
    // already on screen is folded in automatically either way.
    var includeSelfTest by remember { mutableStateOf(false) }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Test and diagnose") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionCard(title = "Run every check") {
                Text(
                    "Pushes a real Hinglish message and a real transcript through the production " +
                        "pipeline, and checks permissions, storage, the queue, both providers and " +
                        "the background workers on the way. Anything it creates is deleted afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = viewModel::runTests, enabled = !ui.running) {
                    Text(if (ui.running) "Running..." else "Run all tests")
                }
                if (ui.running) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            ui.report?.let { report ->
                Spacer(Modifier.height(12.dp))
                SectionCard(title = "Results - ${report.summary}") {
                    if (report.criticalFailures.isNotEmpty()) {
                        Text(
                            "Start with the first failure below. The ones under it are usually " +
                                "consequences of it rather than separate problems.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    for (step in report.steps) {
                        StepRow(step)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Does task creation work?") {
                Text(
                    "Sends ten sample messages - Hinglish, English and Hindi - through the real " +
                        "pipeline and shows what each one produced. Six of them should become tasks " +
                        "and four should not, so it catches both a pipeline that misses commitments " +
                        "and one that invents them. Nothing is left in your task list.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = viewModel::runTaskCreationTest, enabled = !ui.runningTaskTest) {
                    Text(if (ui.runningTaskTest) "Running..." else "Test task creation")
                }
                if (ui.runningTaskTest) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    ui.taskTestProgress?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                ui.taskReport?.let { report ->
                    Spacer(Modifier.height(12.dp))
                    Text(report.summary, style = MaterialTheme.typography.titleSmall)
                    if (report.missed.isNotEmpty()) {
                        Text(
                            "${report.missed.size} real request(s) were missed. That is the expensive " +
                                "kind of error - try a stronger model, or lower the thresholds in " +
                                "Settings -> Accuracy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (report.invented.isNotEmpty()) {
                        Text(
                            "${report.invented.size} non-request(s) became tasks. Raise the " +
                                "auto-create threshold in Settings -> Accuracy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    for (result in report.results) {
                        CaseRow(result)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Export a diagnostic report") {
                Text(
                    "One file with the settings, the capture queue, every recent model call with " +
                        "its reply, the recording folder survey and the activity log.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "It contains your captured message text, because a capture that produced no " +
                        "task cannot be explained without the words that failed. It never contains " +
                        "your API keys. Read it before you send it to anyone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (ui.report != null) {
                    Text(
                        "The test results above will be included.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LabeledSwitch(
                        label = "Run the tests first and include them",
                        description = "Adds about a minute and two model calls.",
                        checked = includeSelfTest,
                        onCheckedChange = { includeSelfTest = it },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.exportReport(includeSelfTest, onShareFile) },
                        enabled = !ui.buildingExport,
                    ) {
                        Text(if (ui.buildingExport) "Building..." else "Export and share")
                    }
                    // Saving does not go through a share sheet or another app,
                    // so when sharing silently does nothing this still works.
                    OutlinedButton(
                        onClick = { saveLauncher.launch(DiagnosticReport.fileName()) },
                        enabled = !ui.buildingExport,
                    ) {
                        Text("Save to a file")
                    }
                }
                if (ui.buildingExport) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Reading the recording folders can take a while the first time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "If captures are stuck") {
                Text(
                    "Captures blocked by a provider error retry on their own once you change the " +
                        "model or base URL. If you fixed something in your provider's console " +
                        "instead - enabling a model, raising a limit - this app cannot see that, " +
                        "so tell it to try again.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = viewModel::retryBlockedCaptures) {
                    Text("Retry blocked captures now")
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: SelfTest.Step) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (step.passed) "PASS" else if (step.critical) "FAIL" else "WARN",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = when {
                    step.passed -> MaterialTheme.colorScheme.primary
                    step.critical -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.tertiary
                },
            )
            Spacer(Modifier.height(0.dp))
            Text(
                "  ${step.name}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text("${step.millis}ms", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            step.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        step.hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CaseRow(result: TaskCreationTest.Result) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (result.correct) "OK  " else "BAD ",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = if (result.correct) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                result.case.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text("${result.millis}ms", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "\"${result.case.message}\"",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            buildString {
                append("expected: ")
                append(if (result.case.shouldCreateTask) "a task" else "no task")
                append("  ·  got: ")
                append(
                    when {
                        result.createdTitle != null -> "\"${result.createdTitle}\""
                        result.sentToReview -> "sent to the review inbox"
                        else -> result.rejectedBy ?: "no task"
                    },
                )
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (!result.correct) {
            Text(
                result.case.why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
