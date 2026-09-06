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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.taskmind.core.CaptureState
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

    // While anything is queued, re-read the database every few seconds. This
    // is a cheap query, not a disk walk, and it is the difference between
    // "nothing happens" and watching a row go queued -> transcribed.
    val hasWorkInFlight = ui.rows.any {
        it.existingState == CaptureState.PENDING_TRANSCRIPTION ||
            it.existingState == CaptureState.PENDING_EXTRACTION
    }
    LaunchedEffect(hasWorkInFlight) {
        while (hasWorkInFlight) {
            delay(3_000)
            viewModel.refreshStates()
        }
    }

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
                actions = {
                    TextButton(
                        onClick = { viewModel.refresh(forceRescan = true) },
                        enabled = !ui.scanning,
                    ) {
                        Text("Rescan")
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
                SectionCard(title = "New calls transcribe themselves") {
                    Text(
                        "Every call your dialer records from now on is transcribed and read " +
                            "for tasks automatically. You do not need to pick anything.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (ui.cutoffMillis > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Recordings made before ${STAMP.format(Date(ui.cutoffMillis))} are ignored, " +
                                "so an old backlog is never uploaded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        buildString {
                            append("${ui.queuedCount} in the queue")
                            if (ui.failedCount > 0) append(", ${ui.failedCount} failed")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (ui.failedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = viewModel::retryFailed) {
                            Text("Retry ${ui.failedCount} failed")
                        }
                        Text(
                            "Each failed row below says why. Fix the cause, then retry.",
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
                        Button(onClick = viewModel::transcribeSelected, enabled = !ui.queueing) {
                            Text(if (ui.queueing) "Queueing..." else "Transcribe ${ui.selected.size} now")
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
                    "Reading the recording folders. Only recordings made since TaskMind was set " +
                        "up are listed, so this should be quick.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            } else if (ui.rows.isEmpty()) {
                Text(
                    "Nothing recorded yet since TaskMind was set up. New calls appear here on " +
                        "their own.\n\nTaskMind cannot record calls itself - this list is what your " +
                        "dialer has written. If a call you expected is missing, check that call " +
                        "recording is on in your phone app, and that TaskMind has All Files Access " +
                        "or a folder chosen in Settings.",
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
            // The transcript is the proof that this worked. Without it a
            // finished recording and a stuck one look identical.
            row.transcript?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text.take(300) + if (text.length > 300) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            row.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    error.take(200),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun describe(state: CaptureState): String = when (state) {
    CaptureState.DONE -> "transcribed"
    CaptureState.PENDING_TRANSCRIPTION -> "queued"
    CaptureState.PENDING_EXTRACTION -> "reading for tasks"
    // Only reachable for rows recorded before transcription became automatic;
    // the transcription worker moves them back into the queue on its next pass.
    CaptureState.AWAITING_SELECTION -> "about to be queued"
    CaptureState.BUDGET_HELD -> "held for the daily limit"
    CaptureState.BLOCKED_CONFIG -> "blocked by a provider setting"
    CaptureState.FAILED_PERMANENT -> "failed"
    CaptureState.REJECTED -> "no task found"
}

private val STAMP = SimpleDateFormat("d MMM, HH:mm", Locale.US)
