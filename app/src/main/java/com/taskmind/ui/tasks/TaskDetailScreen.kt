package com.taskmind.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.core.Priority
import com.taskmind.core.Recurrence
import com.taskmind.core.SourceType
import com.taskmind.core.TaskStatus
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.design.MetaChip
import com.taskmind.ui.design.Radius
import com.taskmind.ui.design.Space
import com.taskmind.ui.design.Touch
import com.taskmind.ui.theme.PriorityStyle

/**
 * Spec 16 - provenance is the differentiator.
 *
 * Every auto-created task shows the contact, app or call and the timestamp; the
 * evidence quote is displayed here; the engine that produced it is named; and
 * tapping through reaches the originating message text or transcript excerpt -
 * for as long as retention keeps it (spec 6.3).
 *
 * Laid out as one dense page rather than a column of large cards. The previous
 * version wrapped every group in its own raised card, which cost roughly a
 * third of the screen in padding and pushed the provenance - the reason to open
 * this screen at all - below two scrolls of chrome. Here the facts sit in tight
 * label/value rows, the controls are inline, and everything that can be
 * summarised in a chip is a chip.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var showOriginal by remember { mutableStateOf(false) }

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
                    .padding(Space.section),
            ) {
                Text(if (state.loading) "Loading..." else "That task no longer exists.")
            }
            return@Scaffold
        }

        val done = task.status == TaskStatus.COMPLETED
        val overdue = !done && DateFormats.isOverdue(task.dueAt)
        val priorityColor = PriorityStyle.color(task.priority, isSystemInDarkTheme())

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.edge)
                .padding(bottom = Space.breath),
        ) {
            // -- headline ---------------------------------------------------
            //
            // Title, then everything that can be said in one word, as chips.
            // A reader who only looks at the top of this screen should already
            // know when it is due, how urgent it is and where it came from.
            Spacer(Modifier.height(Space.snug))
            Row {
                // The same accent bar the list uses, so a task looks like
                // itself on both screens.
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .width(3.dp)
                        .height(28.dp)
                        .background(priorityColor, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(Space.step))
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            Spacer(Modifier.height(Space.snug))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Space.tight),
                verticalArrangement = Arrangement.spacedBy(Space.tight),
            ) {
                MetaChip(
                    text = if (task.dueAt == null) "No date" else DateFormats.due(task.dueAt),
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MetaChip(PriorityStyle.label(task.priority), color = priorityColor)
                if (done) MetaChip("Completed", color = MaterialTheme.colorScheme.primary)
                task.sourceLabel?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                task.tags.forEach { MetaChip("#$it") }
            }

            // -- the one action worth a button -------------------------------
            Spacer(Modifier.height(Space.step))
            FilledTonalButton(
                onClick = { if (done) viewModel.reopen() else viewModel.complete() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.snug))
                Text(if (done) "Reopen" else "Mark complete")
            }

            // Snooze reads as a modifier on the due date, so it sits with it
            // rather than in a section of its own.
            if (!done) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Space.tight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Snooze",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { viewModel.snooze(HOUR) }) { Text("+1h") }
                    TextButton(onClick = { viewModel.snooze(3 * HOUR) }) { Text("+3h") }
                    TextButton(onClick = { viewModel.snooze(24 * HOUR) }) { Text("Tomorrow") }
                }
            }

            // -- the facts ---------------------------------------------------
            Rule()
            Fact("Due", DateFormats.due(task.dueAt), if (overdue) MaterialTheme.colorScheme.error else null)
            task.reminderAt?.let { Fact("Reminder", DateFormats.full(it)) }
            Recurrence.describe(task.recurrenceRule)?.let { Fact("Repeats", it) }
            task.completedAt?.let { Fact("Completed", DateFormats.full(it)) }
            Fact("Created", DateFormats.full(task.createdAt))
            Fact("Updated", DateFormats.full(task.updatedAt))

            // -- priority ----------------------------------------------------
            Rule()
            Label("Priority")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.snug),
            ) {
                Priority.entries.forEach { option ->
                    FilterChip(
                        selected = task.priority == option,
                        onClick = { viewModel.setPriority(option) },
                        label = { Text(PriorityStyle.label(option)) },
                    )
                }
            }

            // -- sub-tasks ---------------------------------------------------
            Rule()
            Label(
                "Sub-tasks",
                trailing = if (state.subTasks.isEmpty()) {
                    null
                } else {
                    "${state.subTasks.count { it.status == TaskStatus.COMPLETED }} of ${state.subTasks.size} done"
                },
            )
            state.subTasks.forEach { sub ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Touch.min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = sub.status == TaskStatus.COMPLETED,
                        onCheckedChange = { viewModel.completeSubTask(sub) },
                    )
                    Text(
                        sub.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (sub.status == TaskStatus.COMPLETED) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.snug),
            ) {
                OutlinedTextField(
                    value = newSubTask,
                    onValueChange = { newSubTask = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Add a sub-task") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
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

            // -- notes -------------------------------------------------------
            Rule()
            Label("Notes")
            val notes = editingNotes ?: task.notes.orEmpty()
            OutlinedTextField(
                value = notes,
                onValueChange = { editingNotes = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                placeholder = { Text("Anything worth remembering") },
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            if (editingNotes != null && editingNotes != task.notes.orEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.snug)) {
                    TextButton(onClick = {
                        viewModel.save(task.copy(notes = editingNotes?.ifBlank { null }))
                        editingNotes = null
                    }) {
                        Text("Save")
                    }
                    TextButton(onClick = { editingNotes = null }) { Text("Discard") }
                }
            }

            // -- provenance ---------------------------------------------------
            if (task.sourceType != SourceType.MANUAL) {
                Rule()
                Label("Where this came from")

                task.evidence?.let { evidence ->
                    Surface(
                        shape = RoundedCornerShape(Radius.row),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(Space.step)) {
                            Text(
                                "“$evidence”",
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic,
                            )
                            Spacer(Modifier.height(Space.tight))
                            Text(
                                "The words that created this task, checked against the original " +
                                    "before it was made.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.snug))
                }

                Fact("Source", task.sourceType.name.lowercase())
                task.sourceApp?.takeIf { it.isNotBlank() }?.let { Fact("App", it) }
                task.sourceLabel?.takeIf { it.isNotBlank() }?.let { Fact("From", it) }
                task.confidence?.let { Fact("Confidence", "%.0f%%".format(it * 100)) }
                task.inferenceOrigin?.let { Fact("Engine", it) }
                state.rawCapture?.let { Fact("Captured", "${DateFormats.full(it.occurredAt)} IST") }

                val capture = state.rawCapture
                if (capture?.rawText != null) {
                    // Kept behind a tap: a transcript can be pages long, and
                    // unfolding it by default is what made this screen endless.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showOriginal = !showOriginal }
                            .heightIn(min = Touch.min),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (showOriginal) "Hide original" else "Show original",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(Space.tight))
                        Icon(
                            if (showOriginal) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (showOriginal) {
                        Text(
                            capture.rawText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = Space.step),
                        )
                    }
                } else {
                    Text(
                        "The original message or transcript has been cleared by your retention " +
                            "setting. The quote above is kept for good.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Space.snug),
                    )
                }
            }
        }
    }
}

private const val HOUR = 60 * 60 * 1000L

/**
 * One label/value line, at the height of the text and no more.
 *
 * The fixed label column is what makes a stack of these scannable: the values
 * line up, so the eye runs down one column instead of reading every row.
 */
@Composable
private fun Fact(label: String, value: String, valueColor: Color? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.tight),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A small heading, with an optional right-aligned summary of the group. */
@Composable
private fun Label(text: String, trailing: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Space.snug),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A hairline plus the breathing room around it, so groups need no cards. */
@Composable
private fun Rule() {
    Spacer(Modifier.height(Space.step))
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
    )
    Spacer(Modifier.height(Space.step))
}
