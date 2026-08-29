package com.taskmind.ui.calls

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
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.core.CallState
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.components.EmptyState
import com.taskmind.ui.components.StatusPill

/**
 * The call list. Spec 4.5: everything is visible - if a call produced no task,
 * this screen says which stage it stopped at, and offers to try again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    viewModel: CallsViewModel,
    onBack: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenRecordings: () -> Unit = {},
) {
    val calls by viewModel.calls.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Calls") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::sweepNow) { Text("Check now") }
                    TextButton(onClick = onOpenRecordings) { Text("Recordings") }
                    TextButton(onClick = onOpenImport) { Text("Import") }
                },
            )
        },
    ) { padding ->
        if (calls.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = "No calls yet",
                    body = "TaskMind notices when a call ends and looks for the recording your phone app " +
                        "wrote. If your dialer is not recording calls, nothing will appear here.",
                    icon = Icons.Outlined.Phone,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(calls, key = { it.id }) { call ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    call.contactName ?: call.phoneNumber ?: "Unknown number",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${call.direction.name.lowercase()} - ${DateFormats.full(call.startTime)}" +
                                        (call.durationSeconds?.let { " - ${it}s" } ?: " - length unknown"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusPill(
                                text = stateLabel(call.state),
                                ok = call.state == CallState.TRANSCRIBED || call.state == CallState.DONE,
                            )
                        }

                        call.summary?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }

                        call.transcript?.let { transcript ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                transcript.take(400),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        call.lastError?.let { error ->
                            Spacer(Modifier.height(8.dp))
                            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }

                        if (call.recordingPath == null) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.retryDiscovery(call) }) {
                                Text("Look for the recording again")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun stateLabel(state: CallState): String = when (state) {
    CallState.PENDING_RECORDING -> "Looking for recording"
    CallState.PENDING_TRANSCRIPTION -> "Waiting to transcribe"
    CallState.AWAITING_SELECTION -> "Recording found - pick it under Recordings"
    CallState.TRANSCRIBED -> "Transcribed"
    CallState.NO_RECORDING -> "No recording"
    CallState.FAILED -> "Failed"
    CallState.DONE -> "Done"
}
