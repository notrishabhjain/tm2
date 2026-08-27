package com.taskmind.ui.inspector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.data.db.entity.InferenceCallEntity
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.components.EmptyState
import com.taskmind.ui.components.StatusPill

/**
 * Settings -> Model calls.
 *
 * Every request TaskMind makes to a cloud model, with the prompt it sent and
 * the reply it got, unedited. Two reasons this screen exists:
 *
 *  - The privacy statement says message text leaves the device. This is how you
 *    check that against what was actually sent.
 *  - When extraction produces nothing, the reason is almost always in the
 *    provider's reply, and it is usually specific. A 403 naming a model you did
 *    not choose reads as a mystery in a log line and as an obvious explanation
 *    here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelCallsScreen(
    viewModel: ModelCallsViewModel,
    onBack: () -> Unit,
    onShareText: (String, String) -> Unit,
) {
    val calls by viewModel.calls.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val expanded by viewModel.expanded.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model calls") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.exportText { text -> onShareText("taskmind-model-calls.txt", text) }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share the trace")
                    }
                    TextButton(onClick = viewModel::clear) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CallFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { viewModel.setFilter(option) },
                        label = { Text(option.label) },
                    )
                }
            }

            if (calls.isEmpty()) {
                EmptyState(
                    title = "No model calls yet",
                    body = "Once a message or a call gets past the pre-filter, the request TaskMind sends " +
                        "and the reply it gets will appear here in full.",
                    icon = Icons.Outlined.Terminal,
                )
                return@Scaffold
            }

            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(calls, key = { it.id }) { call ->
                    CallCard(
                        call = call,
                        expanded = call.id in expanded,
                        onToggle = { viewModel.toggle(call.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CallCard(call: InferenceCallEntity, expanded: Boolean, onToggle: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (call.ok) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        kindLabel(call.kind),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${DateFormats.full(call.startedAt)}  ·  ${call.durationMillis} ms" +
                            (call.totalTokens?.let { "  ·  $it tokens" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    text = if (call.ok) "OK" else "HTTP ${call.httpStatus ?: "—"}",
                    ok = call.ok,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "${call.model}  at  ${call.baseUrl}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            call.sourceLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The diagnosis is the whole point on a failure, so it is never
            // hidden behind the expand toggle.
            call.diagnosis?.let { diagnosis ->
                Spacer(Modifier.height(10.dp))
                Text("What this means", style = MaterialTheme.typography.labelLarge)
                Text(diagnosis, style = MaterialTheme.typography.bodyMedium)
            }

            if (!expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap to see the exact prompt and the raw reply",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            call.errorText?.let { Block("Error", it) }
            call.systemPrompt?.let { Block("System prompt (what instructs the model)", it) }
            call.userPrompt?.let { Block("User message (your data, as sent)", it) }
            call.responseBody?.let { Block("Raw response", it) }
        }
    }
}

@Composable
private fun Block(title: String, body: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        body,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun kindLabel(kind: String): String = when (kind) {
    "extract-message" -> "Message → task"
    "extract-transcript" -> "Transcript → tasks"
    "verify" -> "Second-opinion review"
    "asr" -> "Audio → text"
    "connection-test" -> "Connection test"
    else -> kind
}
