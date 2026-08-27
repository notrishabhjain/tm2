package com.taskmind.ui.log

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.components.EmptyState

/**
 * Spec 15 - the activity log.
 *
 * With no local machine and no adb, this screen is the debugger. Every stage
 * writes at least one line here: the pre-filter rule that fired, the evidence
 * score that failed, the funnel verdict, the dedup hit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityLogScreen(
    viewModel: ActivityLogViewModel,
    onBack: () -> Unit,
    onShareText: (String, String) -> Unit,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    val stage by viewModel.stageFilter.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.exportText { text -> onShareText("taskmind-log.txt", text) }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share the log")
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
                LogLevel.entries.forEach { option ->
                    FilterChip(
                        selected = level == option,
                        onClick = { viewModel.setLevel(option) },
                        label = { Text(option.name) },
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = stage == null, onClick = { viewModel.setStage(null) }, label = { Text("All stages") })
                STAGES.forEach { option ->
                    FilterChip(
                        selected = stage == option,
                        onClick = { viewModel.setStage(if (stage == option) null else option) },
                        label = { Text(option) },
                    )
                }
            }

            if (entries.isEmpty()) {
                EmptyState(
                    title = "Nothing logged at this level",
                    body = "Try DEBUG to see every decision, including the ones that rejected a message.",
                    icon = Icons.Outlined.Article,
                )
                return@Scaffold
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { entry ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            "${DateFormats.timestamp(entry.timestamp)}  ${entry.level.name}  ${entry.stage}",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (entry.level) {
                                LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                        entry.detail?.let { detail ->
                            Spacer(Modifier.height(2.dp))
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private val STAGES = listOf(
    Stage.CAPTURE,
    Stage.PREFILTER,
    Stage.TRANSCRIBE,
    Stage.EXTRACT,
    Stage.VERIFY,
    Stage.FUNNEL,
    Stage.CALL,
    Stage.BUDGET,
    Stage.WORKER,
    Stage.UPDATE,
    Stage.SELFTEST,
    Stage.SYSTEM,
    Stage.IMPORT,
)
