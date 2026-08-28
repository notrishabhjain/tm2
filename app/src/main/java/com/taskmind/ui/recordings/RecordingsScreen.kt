package com.taskmind.ui.recordings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.core.CaptureState
import com.taskmind.ui.components.LabeledSwitch
import com.taskmind.ui.components.SectionCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Choosing which recordings are worth transcribing.
 *
 * Everything here exists because a dialer that records every call turns a
 * useful feature into an unbounded upload: 6463 files against a 60-minute daily
 * budget is not a queue, it is a bill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    viewModel: RecordingsViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recordings") },
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
                .fillMaxSize(),
        ) {
            Column(Modifier.padding(16.dp)) {
                SectionCard(title = "How calls get transcribed") {
                    LabeledSwitch(
                        label = "Transcribe every call automatically",
                        description = "Turn this off if your dialer records everything. " +
                            "New calls then wait in this list until you pick them.",
                        checked = ui.autoTranscribe,
                        onCheckedChange = viewModel::setAutoTranscribe,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${ui.queuedCount} queued for transcription, ${ui.awaitingSelectionCount} waiting " +
                            "for you to choose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (ui.queuedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = viewModel::clearQueue) {
                            Text("Empty the queue")
                        }
                        Text(
                            "Nothing is deleted - they stay in this list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (ui.selected.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = viewModel::transcribeSelected) {
                            Text("Transcribe ${ui.selected.size}")
                        }
                        OutlinedButton(onClick = viewModel::clearSelection) { Text("Clear") }
                    }
                    Text(
                        "Roughly ${ui.selectedMinutes} minute(s) of audio against your daily limit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (ui.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "  Reading the recording folders...",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            } else if (ui.rows.isEmpty()) {
                Text(
                    "No recordings found. TaskMind cannot record calls itself - this list is what " +
                        "your dialer has written. Check that call recording is on in your phone app, " +
                        "and that TaskMind has All Files Access or a folder chosen in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.rows, key = { it.path }) { row ->
                        RecordingItem(row = row, onToggle = { viewModel.toggle(row.path) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingItem(row: RecordingRow, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.selected, onCheckedChange = { onToggle() })
        Column(Modifier.padding(start = 8.dp)) {
            Text(row.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                buildString {
                    append(STAMP.format(Date(row.lastModified)))
                    append(" · ~").append(row.approximateMinutes).append(" min")
                    row.existingState?.let { append(" · ").append(describe(it)) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun describe(state: CaptureState): String = when (state) {
    CaptureState.DONE -> "transcribed"
    CaptureState.PENDING_TRANSCRIPTION -> "queued"
    CaptureState.PENDING_EXTRACTION -> "reading for tasks"
    CaptureState.AWAITING_SELECTION -> "waiting for you"
    CaptureState.BUDGET_HELD -> "held for the daily limit"
    CaptureState.BLOCKED_CONFIG -> "blocked by a provider setting"
    CaptureState.FAILED_PERMANENT -> "failed"
    CaptureState.REJECTED -> "no task found"
}

private val STAMP = SimpleDateFormat("d MMM, HH:mm", Locale.US)
