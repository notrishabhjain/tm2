package com.taskmind.ui.prompts

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.core.PromptKind
import com.taskmind.ui.components.SectionCard
import com.taskmind.ui.components.StatusPill

/**
 * Settings -> Prompts.
 *
 * The instructions TaskMind gives the model decide what becomes a task and what
 * does not. Hiding them would make every judgement the app makes unaccountable,
 * so they are here in full, editable, and resettable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptScreen(
    viewModel: PromptViewModel,
    onBack: () -> Unit,
    onShareText: (String, String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    val editing = state.editing
    if (editing != null) {
        PromptEditor(
            kind = editing,
            state = state,
            onDraftChange = viewModel::updateDraft,
            onSave = viewModel::save,
            onRevertToDefault = viewModel::revertDraftToDefault,
            onClose = viewModel::closeEditor,
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Prompts") },
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
            SectionCard(title = "What these are") {
                Text(
                    "TaskMind does not decide what counts as a task. It asks a model, using the exact " +
                        "instructions below, and then checks the answer against rules you can also see " +
                        "and change.\n\n" +
                        "Edit these and the change applies to the very next message or call — no restart. " +
                        "If you make things worse, every one of them has a reset.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            PromptKind.entries.forEach { kind ->
                val text = kind.textIn(state.prompts)
                val edited = kind in state.overridden

                SectionCard(title = kind.title, subtitle = kind.purpose) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        StatusPill(if (edited) "Edited by you" else "Default", ok = !edited)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "  ${text.length} characters",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text.lineSequence().take(4).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { viewModel.edit(kind) }) { Text("Read and edit") }
                        OutlinedButton(onClick = { onShareText("${kind.title} prompt", text) }) {
                            Text("Share")
                        }
                        if (edited) {
                            TextButton(onClick = { viewModel.reset(kind) }) { Text("Reset") }
                        }
                    }
                }
            }

            SectionCard(
                title = "Reset everything",
                subtitle = "Puts all three prompts back to the versions that shipped with this build.",
            ) {
                OutlinedButton(onClick = viewModel::resetAll) { Text("Reset all prompts") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptEditor(
    kind: PromptKind,
    state: PromptUiState,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onRevertToDefault: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(kind.title) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = state.draftDiffersFromSaved) { Text("Save") }
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
            SectionCard(title = "Before you change this") {
                Text(kind.caution, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "The reply must stay valid JSON in the shape described at the bottom of the prompt. " +
                        "TaskMind discards a reply it cannot parse rather than guessing at it, so a prompt " +
                        "that stops producing that shape produces no tasks at all — which the model-call " +
                        "trace will show you plainly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("System prompt") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 18,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSave, enabled = state.draftDiffersFromSaved) { Text("Save") }
                    OutlinedButton(onClick = onRevertToDefault, enabled = !state.draftIsDefault) {
                        Text("Load the default")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.draftIsDefault) {
                        "This is the default text. Saving it removes your override, so future improvements " +
                            "to the default will reach you."
                    } else {
                        "Changed from the default."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}
