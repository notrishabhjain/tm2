package com.taskmind.ui.tasks

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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.core.Priority
import com.taskmind.core.Recurrence
import com.taskmind.core.SourceType
import com.taskmind.core.TaskStatus
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.components.KeyValueRow
import com.taskmind.ui.components.SectionCard
import com.taskmind.ui.theme.PriorityStyle

/**
 * Spec 16 - provenance is the differentiator.
 *
 * Every auto-created task shows the contact, app or call and the timestamp; the
 * evidence quote is displayed here; the engine that produced it is named; and
 * tapping through reaches the originating message text or transcript excerpt -
 * for as long as retention keeps it (spec 6.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskId: String,
    viewModel: TaskDetailViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(taskId) { viewModel.load(taskId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var newSubTask by remember { mutableStateOf("") }
    var editingNotes by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.archive(); onBack() }) {
                        Icon(Icons.Outlined.Archive, contentDescription = "Archive")
                    }
                    IconButton(onClick = { viewModel.delete(); onBack() }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
    ) { padding ->
        val task = state.task
        if (state.notFound || task == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            ) {
                Text(if (state.loading) "Loading..." else "That task no longer exists.")
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
        ) {
            SectionCard(title = task.title) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = task.status == TaskStatus.COMPLETED,
                        onCheckedChange = {
                            if (task.status == TaskStatus.COMPLETED) viewModel.reopen() else viewModel.complete()
                        },
                    )
                    Text(
                        if (task.status == TaskStatus.COMPLETED) "Completed" else "Mark complete",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Due", DateFormats.due(task.dueAt))
                Recurrence.describe(task.recurrenceRule)?.let { KeyValueRow("Repeats", it) }
                task.reminderAt?.let { KeyValueRow("Reminder", DateFormats.full(it)) }
                KeyValueRow("Created", DateFormats.full(task.createdAt))
            }

            SectionCard(title = "Priority") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Priority.entries.forEach { option ->
                        FilterChip(
                            selected = task.priority == option,
                            onClick = { viewModel.setPriority(option) },
                            label = { Text(PriorityStyle.label(option)) },
                        )
                    }
                }
            }

            SectionCard(title = "Snooze", subtitle = "Push this out without losing it.") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { viewModel.snooze(60 * 60 * 1000L) }) { Text("+1 hour") }
                    OutlinedButton(onClick = { viewModel.snooze(3 * 60 * 60 * 1000L) }) { Text("+3 hours") }
                    OutlinedButton(onClick = { viewModel.snooze(24 * 60 * 60 * 1000L) }) { Text("Tomorrow") }
                }
            }

            SectionCard(title = "Notes") {
                val notes = editingNotes ?: task.notes.orEmpty()
                OutlinedTextField(
                    value = notes,
                    onValueChange = { editingNotes = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    placeholder = { Text("Anything worth remembering") },
                )
                if (editingNotes != null && editingNotes != task.notes) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        viewModel.save(task.copy(notes = editingNotes?.ifBlank { null }))
                        editingNotes = null
                    }) {
                        Text("Save notes")
                    }
                }
            }

            SectionCard(title = "Sub-tasks", subtitle = "${state.subTasks.count { it.status == TaskStatus.COMPLETED }} of ${state.subTasks.size} done") {
                state.subTasks.forEach { sub ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = sub.status == TaskStatus.COMPLETED,
                            onCheckedChange = { viewModel.completeSubTask(sub) },
                        )
                        Text(sub.title, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newSubTask,
                        onValueChange = { newSubTask = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Add a sub-task") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (newSubTask.isNotBlank()) {
                                viewModel.addSubTask(newSubTask.trim())
                                newSubTask = ""
                            }
                        },
                        enabled = newSubTask.isNotBlank(),
                    ) {
                        Text("Add")
                    }
                }
            }

            // -- provenance ------------------------------------------------
            if (task.sourceType != SourceType.MANUAL) {
                SectionCard(
                    title = "Where this came from",
                    subtitle = task.sourceLabel ?: task.sourceApp ?: task.sourceType.name,
                ) {
                    task.evidence?.let { evidence ->
                        Text(
                            "“$evidence”",
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = FontStyle.Italic,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "These are the words that created this task. It was checked against the " +
                                "original source before the task was made.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    KeyValueRow("Source", task.sourceType.name.lowercase())
                    task.confidence?.let {
                        KeyValueRow("Confidence", "%.0f%%".format(it * 100))
                    }
                    task.inferenceOrigin?.let { KeyValueRow("Engine", it) }

                    val capture = state.rawCapture
                    Spacer(Modifier.height(12.dp))
                    if (capture?.rawText != null) {
                        Text("Original text", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(capture.rawText, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Received ${DateFormats.full(capture.occurredAt)} IST",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "The original message or transcript has been cleared by your retention " +
                                "setting. The quote above is kept for good.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
