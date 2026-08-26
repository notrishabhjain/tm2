package com.taskmind.ui.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.components.SectionCard

/**
 * Spec 11.4 - Import transcript.
 *
 * The HyperOS Recorder transcribes calls well in Hindi but cannot be automated:
 * no share action, no export, no readable file. Rather than driving its UI with
 * an AccessibilityService - which breaks on every Recorder update and demands a
 * permission that reads every screen the user sees - TaskMind makes the one
 * manual paste as useful as possible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTranscriptScreen(
    viewModel: ImportViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.readClipboard(context) }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Import transcript") },
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
                .padding(bottom = 40.dp),
        ) {
            SectionCard(title = "How to get a transcript") {
                Text(
                    "Open the Recorder app, tap the call recording, choose Show text, pick Hindi, wait for " +
                        "it to finish, then tap the three dots and Copy.\n\n" +
                        "Come back here and it will already be pasted.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionCard(
                title = "Transcript",
                subtitle = if (state.prefilledFromClipboard) "Pasted from your clipboard." else null,
            ) {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = { viewModel.setText(it) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    placeholder = { Text("Paste the transcript here") },
                )
                state.parsed?.let { parsed ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (parsed.diarised) {
                            "Recognised ${parsed.turns.size} speaker turns. Timestamps are removed; the " +
                                "speaker labels are kept, because who said it changes what it means."
                        } else {
                            "No speaker labels found. That is fine - it will be read as plain text, with " +
                                "slightly lower confidence."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.recentCalls.isNotEmpty()) {
                SectionCard(
                    title = "Attach to a call",
                    subtitle = "Optional, but it gives the tasks the right caller and the right date for " +
                        "\"kal\" and \"parso\".",
                ) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.attachToCallId == null,
                            onClick = { viewModel.attachTo(null) },
                            label = { Text("Not a call") },
                        )
                        state.recentCalls.forEach { call ->
                            FilterChip(
                                selected = state.attachToCallId == call.id,
                                onClick = { viewModel.attachTo(call.id) },
                                label = {
                                    Text(
                                        "${call.contactName ?: call.phoneNumber ?: "Unknown"} " +
                                            DateFormats.due(call.startTime),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            Column(Modifier.padding(16.dp)) {
                Button(
                    onClick = viewModel::import,
                    enabled = state.text.isNotBlank() && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.busy) "Importing..." else "Find tasks in this transcript")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "The transcript is sent to your configured provider, the same as any other capture.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}
